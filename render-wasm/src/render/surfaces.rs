use crate::error::{Error, Result};
use crate::shapes::Shape;
use crate::view::Viewbox;
use crate::{get_gpu_state, performance};

use skia_safe::{self as skia, IRect, Paint, RRect, Rect};

use super::{gpu_state::GpuState, tiles, tiles::Tile, tiles::TileRect, tiles::TileViewbox};
use crate::math::Point;

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_BATCH_DELETE: usize = 256;

// This is the amount of extra space we're going to give to all the surfaces to render shapes.
// If it's too big it could affect performance.
const TILE_SIZE: i32 = tiles::TILE_SIZE as i32;
const TILE_SIZE_MULTIPLIER: i32 = 2;
const TILE_MARGIN_SIZE: i32 = TILE_SIZE * TILE_SIZE_MULTIPLIER / 4;
const TILE_DRAWABLE_RECT: IRect = IRect {
    left: TILE_MARGIN_SIZE,
    top: TILE_MARGIN_SIZE,
    right: TILE_MARGIN_SIZE + TILE_SIZE,
    bottom: TILE_MARGIN_SIZE + TILE_SIZE,
};

#[repr(u32)]
#[derive(Debug, PartialEq, Clone, Copy)]
#[allow(unused)]
pub enum SurfaceId {
    Target = 0b000_0000_0001,
    Filter = 0b000_0000_0010,
    Current = 0b000_0000_1000,
    Fills = 0b000_0001_0000,
    Strokes = 0b000_0010_0000,
    DropShadows = 0b000_0100_0000,
    InnerShadows = 0b000_1000_0000,
    TextDropShadows = 0b001_0000_0000,
    Export = 0b010_0000_0000,
    UI = 0b100_0000_0000,
    Debug = 0b100_0000_0001,
    Atlas = 0b100_0000_0010,
    Backbuffer = 0b100_0000_0100,
    TileAtlas = 0b100_0000_1000,
}

pub struct DocAtlas {
    // Persistent 1:1 document-space atlas that gets incrementally updated as tiles render.
    // It grows dynamically to include any rendered document rect.
    pub surface: skia::Surface,
    pub origin: skia::Point,
    pub size: skia::ISize,
    /// Atlas pixel density relative to document pixels (1.0 == 1:1 doc px).
    /// When the atlas would exceed `max_texture_size`, this value is
    /// reduced so the atlas stays within the fixed texture cap.
    pub scale: f32,
    /// Optional document-space bounds (1 unit == 1 doc px @ 100% zoom) used to
    /// clamp atlas writes/clears so the atlas doesn't grow due to outlier tile rects.
    pub doc_bounds: Option<skia::Rect>,
    /// Tracks the last document-space rect written to the atlas per tile.
    /// Used to clear old content without clearing the whole (potentially huge) tile rect.
    pub tile_doc_rects: HashMap<Tile, skia::Rect>,
}

impl DocAtlas {
    pub fn try_new() -> Result<Self> {
        // Keep atlas as a regular surface like the rest. Start with a tiny
        // transparent surface and grow it on demand.
        let mut surface =
            get_gpu_state().create_surface_with_dimensions("atlas".to_string(), 1, 1)?;

        surface.canvas().clear(skia::Color::TRANSPARENT);

        Ok(Self {
            surface,
            origin: skia::Point::new(0.0, 0.0),
            size: skia::ISize::new(0, 0),
            scale: 1.0,
            doc_bounds: None,
            tile_doc_rects: HashMap::default(),
        })
    }

    pub fn is_empty(&self) -> bool {
        self.size.width <= 0 || self.size.height <= 0
    }

    /// Sets the document-space bounds used to clamp atlas updates.
    /// Pass `None` to disable clamping.
    pub fn set_doc_bounds(&mut self, bounds: Option<skia::Rect>) {
        self.doc_bounds = bounds.filter(|b| !b.is_empty());
    }

    fn clamp_doc_rect_to_bounds(&self, doc_rect: skia::Rect) -> skia::Rect {
        if doc_rect.is_empty() {
            return doc_rect;
        }
        if let Some(bounds) = self.doc_bounds {
            let mut r = doc_rect;
            if r.intersect(bounds) {
                r
            } else {
                skia::Rect::new_empty()
            }
        } else {
            doc_rect
        }
    }

    fn ensure_atlas_contains(
        &mut self,
        gpu_state: &mut GpuState,
        doc_rect: skia::Rect,
    ) -> Result<()> {
        if doc_rect.is_empty() {
            return Ok(());
        }

        // Current atlas bounds in document space (1 unit == 1 px).
        let current_left = self.origin.x;
        let current_top = self.origin.y;
        let scale = self.scale.max(0.01);
        let current_right = current_left + (self.size.width as f32) / scale;
        let current_bottom = current_top + (self.size.height as f32) / scale;

        let mut new_left = current_left;
        let mut new_top = current_top;
        let mut new_right = current_right;
        let mut new_bottom = current_bottom;

        // If atlas is empty/uninitialized, seed to rect (expanded to tile boundaries for fewer reallocs).
        let needs_init = self.size.width <= 0 || self.size.height <= 0;
        if needs_init {
            new_left = doc_rect.left.floor();
            new_top = doc_rect.top.floor();
            new_right = doc_rect.right.ceil();
            new_bottom = doc_rect.bottom.ceil();
        } else {
            new_left = new_left.min(doc_rect.left.floor());
            new_top = new_top.min(doc_rect.top.floor());
            new_right = new_right.max(doc_rect.right.ceil());
            new_bottom = new_bottom.max(doc_rect.bottom.ceil());
        }

        // Pad to reduce realloc frequency
        let pad = tiles::TILE_SIZE;
        if needs_init {
            new_left -= pad;
            new_top -= pad;
            new_right += pad;
            new_bottom += pad;
        } else {
            let grow_x = ((current_right - current_left) * 0.5).max(4.0 * tiles::TILE_SIZE);
            let grow_y = ((current_bottom - current_top) * 0.5).max(4.0 * tiles::TILE_SIZE);
            if new_left < current_left {
                new_left -= grow_x;
            }
            if new_right > current_right {
                new_right += grow_x;
            }
            if new_top < current_top {
                new_top -= grow_y;
            }
            if new_bottom > current_bottom {
                new_bottom += grow_y;
            }
            if let Some(bounds) = self.doc_bounds {
                new_left = new_left.max((bounds.left - pad).min(doc_rect.left.floor()));
                new_top = new_top.max((bounds.top - pad).min(doc_rect.top.floor()));
                new_right = new_right.min((bounds.right + pad).max(doc_rect.right.ceil()));
                new_bottom = new_bottom.min((bounds.bottom + pad).max(doc_rect.bottom.ceil()));
            }
            new_left = new_left.min(current_left);
            new_top = new_top.min(current_top);
            new_right = new_right.max(current_right);
            new_bottom = new_bottom.max(current_bottom);
        }

        let doc_w = (new_right - new_left).max(1.0);
        let doc_h = (new_bottom - new_top).max(1.0);

        // Compute atlas scale needed to fit within the fixed texture cap.
        // Keep the highest possible scale (closest to 1.0) that still fits.
        let cap = gpu_state.max_texture_size().max(TILE_SIZE) as f32;
        let required_scale = (cap / doc_w).min(cap / doc_h).clamp(0.01, 1.0);

        // Never upscale the atlas (it would add blur and churn).
        let new_scale = self.scale.min(required_scale).max(0.01);

        let new_w = (doc_w * new_scale).ceil().clamp(1.0, cap) as i32;
        let new_h = (doc_h * new_scale).ceil().clamp(1.0, cap) as i32;

        // Fast path: existing atlas already contains the rect.
        if !needs_init
            && doc_rect.left >= current_left
            && doc_rect.top >= current_top
            && doc_rect.right <= current_right
            && doc_rect.bottom <= current_bottom
        {
            return Ok(());
        }

        let mut new_surface =
            gpu_state.create_surface_with_dimensions("atlas".to_string(), new_w, new_h)?;
        new_surface.canvas().clear(skia::Color::TRANSPARENT);

        // Copy old atlas into the new one with offset.
        if !needs_init {
            let old_scale = self.scale.max(0.01);
            let scale_ratio = new_scale / old_scale;
            let dx = (current_left - new_left) * new_scale;
            let dy = (current_top - new_top) * new_scale;

            let image = self.surface.image_snapshot();
            let src =
                skia::Rect::from_xywh(0.0, 0.0, self.size.width as f32, self.size.height as f32);
            let dst = skia::Rect::from_xywh(
                dx,
                dy,
                (self.size.width as f32) * scale_ratio,
                (self.size.height as f32) * scale_ratio,
            );
            new_surface.canvas().draw_image_rect(
                &image,
                Some((&src, skia::canvas::SrcRectConstraint::Fast)),
                dst,
                &skia::Paint::default(),
            );
        }

        self.origin = skia::Point::new(new_left, new_top);
        self.size = skia::ISize::new(new_w, new_h);
        self.scale = new_scale;
        // Old surface is Skia-managed: dropping it on reassignment frees the
        // texture back to the resource cache.
        self.surface = new_surface;
        Ok(())
    }

    fn blit_tile_image_into_atlas(
        &mut self,
        gpu_state: &mut GpuState,
        tile_image: &skia::Image,
        tile_doc_rect: skia::Rect,
    ) -> Result<()> {
        if tile_doc_rect.is_empty() {
            return Ok(());
        }

        // Clamp to document bounds (if any) and compute a matching source-rect in tile pixels.
        let mut clipped_doc_rect = tile_doc_rect;
        if let Some(bounds) = self.doc_bounds {
            if !clipped_doc_rect.intersect(bounds) {
                return Ok(());
            }
        }
        if clipped_doc_rect.is_empty() {
            return Ok(());
        }

        self.ensure_atlas_contains(gpu_state, clipped_doc_rect)?;

        // Destination is document-space rect mapped into atlas pixel coords.
        let dst = skia::Rect::from_xywh(
            (clipped_doc_rect.left - self.origin.x) * self.scale,
            (clipped_doc_rect.top - self.origin.y) * self.scale,
            clipped_doc_rect.width() * self.scale,
            clipped_doc_rect.height() * self.scale,
        );

        // Compute source rect in tile_image pixel coordinates.
        let img_w = tile_image.width() as f32;
        let img_h = tile_image.height() as f32;
        let tw = tile_doc_rect.width().max(1.0);
        let th = tile_doc_rect.height().max(1.0);

        let sx = ((clipped_doc_rect.left - tile_doc_rect.left) / tw) * img_w;
        let sy = ((clipped_doc_rect.top - tile_doc_rect.top) / th) * img_h;
        let sw = (clipped_doc_rect.width() / tw) * img_w;
        let sh = (clipped_doc_rect.height() / th) * img_h;
        let src = skia::Rect::from_xywh(sx, sy, sw, sh);

        self.surface.canvas().draw_image_rect(
            tile_image,
            Some((&src, skia::canvas::SrcRectConstraint::Fast)),
            dst,
            &skia::Paint::default(),
        );
        Ok(())
    }

    /// Clears a doc-space rect from the atlas **without** growing it.
    ///
    /// Unlike [`clear_doc_rect_in_atlas`], this method clips `doc_rect` to the
    /// current atlas bounds and skips silently if there is no overlap. Use this
    /// when evicting stale shape content (e.g. before a drag re-render) where
    /// growing the atlas to accommodate an out-of-range rect would be wasteful.
    pub fn clear_doc_rect_in_atlas_clipped(&mut self, doc_rect: skia::Rect) {
        if self.is_empty() || doc_rect.is_empty() {
            return;
        }

        let scale = self.scale.max(0.01);
        let atlas_doc_right = self.origin.x + (self.size.width as f32) / scale;
        let atlas_doc_bottom = self.origin.y + (self.size.height as f32) / scale;

        // Intersect with current atlas bounds in doc space.
        let mut clipped = doc_rect;
        let atlas_bounds = skia::Rect::from_ltrb(
            self.origin.x,
            self.origin.y,
            atlas_doc_right,
            atlas_doc_bottom,
        );
        if !clipped.intersect(atlas_bounds) {
            return;
        }

        // Apply doc_bounds clamping.
        if let Some(bounds) = self.doc_bounds {
            if !clipped.intersect(bounds) {
                return;
            }
        }

        if clipped.is_empty() {
            return;
        }

        let dst = skia::Rect::from_xywh(
            (clipped.left - self.origin.x) * scale,
            (clipped.top - self.origin.y) * scale,
            clipped.width() * scale,
            clipped.height() * scale,
        );

        let canvas = self.surface.canvas();
        canvas.save();
        canvas.clip_rect(dst, None, true);
        canvas.clear(skia::Color::TRANSPARENT);
        canvas.restore();
    }

    pub fn clear_doc_rect_in_atlas(
        &mut self,
        gpu_state: &mut GpuState,
        doc_rect: skia::Rect,
    ) -> Result<()> {
        let doc_rect = self.clamp_doc_rect_to_bounds(doc_rect);
        if doc_rect.is_empty() {
            return Ok(());
        }

        self.ensure_atlas_contains(gpu_state, doc_rect)?;

        // Destination is document-space rect mapped into atlas pixel coords.
        let dst = skia::Rect::from_xywh(
            (doc_rect.left - self.origin.x) * self.scale,
            (doc_rect.top - self.origin.y) * self.scale,
            doc_rect.width() * self.scale,
            doc_rect.height() * self.scale,
        );

        let canvas = self.surface.canvas();
        canvas.save();
        canvas.clip_rect(dst, None, true);
        canvas.clear(skia::Color::TRANSPARENT);
        canvas.restore();
        Ok(())
    }

    /// Clears the last atlas region written by `tile` (if any).
    ///
    /// This avoids clearing the entire logical tile rect which, at very low
    /// zoom levels, can be enormous in document space and would unnecessarily
    /// grow / rescale the atlas.
    pub fn clear_tile_in_atlas(&mut self, gpu_state: &mut GpuState, tile: Tile) -> Result<()> {
        if let Some(doc_rect) = self.tile_doc_rects.remove(&tile) {
            self.clear_doc_rect_in_atlas(gpu_state, doc_rect)?;
        }
        Ok(())
    }

}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    filter: skia::Surface,
    // keeps the current render
    current: skia::Surface,
    // keeps the current shape's fills
    shape_fills: skia::Surface,
    // keeps the current shape's strokes
    shape_strokes: skia::Surface,
    // used for rendering shadows
    drop_shadows: skia::Surface,
    // used for rendering over shadows.
    inner_shadows: skia::Surface,
    // used for rendering text drop shadows
    text_drop_shadows: skia::Surface,
    // used for displaying auxiliary workspace elements
    ui: skia::Surface,
    // for drawing debug info.
    debug: skia::Surface,
    // for drawing tiles.
    export: skia::Surface,
    // Persistent viewport-sized surface used to keep the last presented frame.
    backbuffer: skia::Surface,
    // Atlas used to keep tiles.
    tiles: TileTextureCache,
    pub atlas: DocAtlas,
    sampling_options: skia::SamplingOptions,
    atlas_sampling_options: skia::SamplingOptions,
    pub margins: skia::ISize,
    // Tracks which surfaces have content (dirty flag bitmask)
    dirty_surfaces: u32,
    extra_tile_dims: skia::ISize,
    dpr: f32,
}

impl Surfaces {
    pub fn try_new(
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
        tile_dims: skia::ISize,
    ) -> Result<Self> {
        let gpu_state = get_gpu_state();

        let extra_tile_dims = skia::ISize::new(
            tile_dims.width * TILE_SIZE_MULTIPLIER,
            tile_dims.height * TILE_SIZE_MULTIPLIER,
        );
        let margins = skia::ISize::new(extra_tile_dims.width / 4, extra_tile_dims.height / 4);

        let target = gpu_state.create_target_surface(width, height)?;
        let filter = gpu_state.create_surface_with_isize("filter".to_string(), extra_tile_dims)?;
        let backbuffer =
            gpu_state.create_surface_with_dimensions("backbuffer".to_string(), width, height)?;

        let max_texture_size = gpu_state.max_texture_size();

        let current =
            gpu_state.create_surface_with_isize("current".to_string(), extra_tile_dims)?;

        let drop_shadows =
            gpu_state.create_surface_with_isize("drop_shadows".to_string(), extra_tile_dims)?;
        let inner_shadows =
            gpu_state.create_surface_with_isize("inner_shadows".to_string(), extra_tile_dims)?;
        let text_drop_shadows = gpu_state
            .create_surface_with_isize("text_drop_shadows".to_string(), extra_tile_dims)?;
        let shape_fills =
            gpu_state.create_surface_with_isize("shape_fills".to_string(), extra_tile_dims)?;
        let shape_strokes =
            gpu_state.create_surface_with_isize("shape_strokes".to_string(), extra_tile_dims)?;
        let export = gpu_state.create_surface_with_isize("export".to_string(), extra_tile_dims)?;

        let ui = gpu_state.create_surface_with_dimensions("ui".to_string(), width, height)?;
        let debug = gpu_state.create_surface_with_dimensions("debug".to_string(), width, height)?;

        let tiles = TileTextureCache::try_new(max_texture_size)?;
        let atlas = DocAtlas::try_new()?;
        Ok(Self {
            target,
            filter,
            current,
            drop_shadows,
            inner_shadows,
            text_drop_shadows,
            shape_fills,
            shape_strokes,
            ui,
            debug,
            export,
            backbuffer,
            tiles,
            atlas,
            sampling_options,
            atlas_sampling_options: skia::SamplingOptions::new(
                skia::FilterMode::Nearest,
                skia::MipmapMode::None,
            ),
            margins,
            dirty_surfaces: 0,
            extra_tile_dims,
            dpr: 1.0,
        })
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        self.dpr = dpr;
    }

    pub fn draw_tile_atlas_to_backbuffer(
        &mut self,
        viewbox: &Viewbox,
        tile_viewbox: &TileViewbox,
        background: skia::Color,
    ) {
        self.backbuffer.canvas().clear(background);
        self.draw_cached_tiles_over_backbuffer(viewbox, tile_viewbox);
    }

    /// Composite the visible cached tiles onto Backbuffer without clearing it
    /// first. Used by progressive (Partial-frame) presents, where the DocAtlas
    /// preview is drawn underneath as a backdrop for not-yet-rendered tiles.
    pub fn draw_cached_tiles_over_backbuffer(
        &mut self,
        viewbox: &Viewbox,
        tile_viewbox: &TileViewbox,
    ) {
        let (xforms, texs) = self.tiles.visible_batch(viewbox, tile_viewbox);
        if xforms.is_empty() {
            return;
        }
        let sampling_options = self.atlas_sampling_options;
        // One snapshot of the managed atlas, one batched draw from it.
        let image = self.tiles.snapshot();
        self.backbuffer.canvas().draw_atlas(
            &image,
            &xforms,
            &texs,
            None,
            skia::BlendMode::SrcOver,
            sampling_options,
            None,
            None,
        );
    }

    /// Draw the persistent atlas onto the backbuffer using the current viewbox transform.
    /// Intended for fast pan/zoom-out previews (avoids per-tile composition).
    /// Clears Backbuffer to `background` first so atlas-uncovered regions don't
    /// show stale content when the atlas only partially covers the viewport.
    pub fn draw_atlas_to_backbuffer(&mut self, viewbox: Viewbox, background: skia::Color) {
        if self.atlas.is_empty() {
            return;
        }

        let canvas = self.backbuffer.canvas();
        canvas.save();
        canvas.reset_matrix();
        let size = canvas.base_layer_size();
        canvas.clip_rect(
            skia::Rect::from_xywh(0.0, 0.0, size.width as f32, size.height as f32),
            None,
            true,
        );
        canvas.clear(background);

        let s = viewbox.get_scale();
        let scale = self.atlas.scale.max(0.01);
        canvas.translate((
            (self.atlas.origin.x + viewbox.pan.x) * s,
            (self.atlas.origin.y + viewbox.pan.y) * s,
        ));
        canvas.scale((s / scale, s / scale));

        self.atlas.surface.draw(
            canvas,
            (0.0, 0.0),
            self.sampling_options,
            Some(&skia::Paint::default()),
        );

        canvas.restore();
    }

    pub fn margins(&self) -> skia::ISize {
        self.margins
    }

    pub fn resize(&mut self, new_width: i32, new_height: i32) -> Result<()> {
        let gpu_state = get_gpu_state();

        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height)?)?;
        Ok(())
    }

    pub fn snapshot(&mut self, id: SurfaceId) -> skia::Image {
        let surface = self.get_mut(id);
        surface.image_snapshot()
    }

    pub fn filter_size(&self) -> (i32, i32) {
        (self.filter.width(), self.filter.height())
    }

    pub fn base64_snapshot(&mut self, id: SurfaceId) -> Result<String> {
        let surface = self.get_mut(id);
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .ok_or(Error::CriticalError("Failed to encode image".to_string()))?;
        Ok(general_purpose::STANDARD.encode(encoded_image.as_bytes()))
    }

    pub fn base64_snapshot_rect(
        &mut self,
        id: SurfaceId,
        irect: skia::IRect,
    ) -> Result<Option<String>> {
        let surface = self.get_mut(id);
        if let Some(image) = surface.image_snapshot_with_bounds(irect) {
            let mut context = surface.direct_context();
            let encoded_image = image
                .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
                .ok_or(Error::CriticalError("Failed to encode image".to_string()))?;
            Ok(Some(
                general_purpose::STANDARD.encode(encoded_image.as_bytes()),
            ))
        } else {
            Ok(None)
        }
    }

    /// Returns a mutable reference to the canvas and automatically marks
    /// render surfaces as dirty when accessed. This tracks which surfaces
    /// have content for optimization purposes.
    pub fn canvas_and_mark_dirty(&mut self, id: SurfaceId) -> &skia::Canvas {
        // Automatically mark render surfaces as dirty when accessed
        // This tracks which surfaces have content for optimization
        match id {
            SurfaceId::Fills
            | SurfaceId::Strokes
            | SurfaceId::InnerShadows
            | SurfaceId::TextDropShadows => {
                self.mark_dirty(id);
            }
            _ => {}
        }
        self.canvas(id)
    }

    /// Returns a mutable reference to the canvas without any side effects.
    /// Use this when you only need to read or manipulate the canvas state
    /// without marking the surface as dirty.
    pub fn canvas(&mut self, id: SurfaceId) -> &skia::Canvas {
        self.get_mut(id).canvas()
    }

    pub fn surface_clone(&self, id: SurfaceId) -> skia::Surface {
        self.get(id).clone()
    }

    /// Marks a surface as having content (dirty)
    pub fn mark_dirty(&mut self, id: SurfaceId) {
        self.dirty_surfaces |= id as u32;
    }

    /// Checks if a surface has content
    pub fn is_dirty(&self, id: SurfaceId) -> bool {
        (self.dirty_surfaces & id as u32) != 0
    }

    /// Clears the dirty flag for a surface or set of surfaces
    pub fn clear_dirty(&mut self, ids: u32) {
        self.dirty_surfaces &= !ids;
    }

    /// Clears all dirty flags
    pub fn clear_all_dirty(&mut self) {
        self.dirty_surfaces = 0;
    }

    pub fn flush_and_submit(&mut self, id: SurfaceId) {
        let gpu_state = get_gpu_state();
        let surface = self.get_mut(id);
        gpu_state.context.flush_and_submit_surface(surface, None);
    }

    pub fn draw_into(&mut self, from: SurfaceId, to: SurfaceId, paint: Option<&skia::Paint>) {
        let sampling_options = self.sampling_options;

        self.get_mut(from).clone().draw(
            self.canvas_and_mark_dirty(to),
            (0.0, 0.0),
            sampling_options,
            paint,
        );
    }

    pub fn apply_mut(&mut self, ids: u32, mut f: impl FnMut(&mut skia::Surface)) {
        performance::begin_measure!("apply_mut::flags");
        if ids & SurfaceId::Target as u32 != 0 {
            f(self.get_mut(SurfaceId::Target));
        }
        if ids & SurfaceId::Filter as u32 != 0 {
            f(self.get_mut(SurfaceId::Filter));
        }
        if ids & SurfaceId::Current as u32 != 0 {
            f(self.get_mut(SurfaceId::Current));
        }
        if ids & SurfaceId::Backbuffer as u32 != 0 {
            f(self.get_mut(SurfaceId::Backbuffer));
        }
        if ids & SurfaceId::Fills as u32 != 0 {
            f(self.get_mut(SurfaceId::Fills));
        }
        if ids & SurfaceId::Strokes as u32 != 0 {
            f(self.get_mut(SurfaceId::Strokes));
        }
        if ids & SurfaceId::InnerShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::InnerShadows));
        }
        if ids & SurfaceId::TextDropShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::TextDropShadows));
        }
        if ids & SurfaceId::DropShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::DropShadows));
        }
        if ids & SurfaceId::Debug as u32 != 0 {
            f(self.get_mut(SurfaceId::Debug));
        }
        if ids & SurfaceId::Export as u32 != 0 {
            f(self.get_mut(SurfaceId::Export));
        }
        performance::begin_measure!("apply_mut::flags");
    }

    pub fn get_render_context_translation(
        &mut self,
        render_area: skia::Rect,
        scale: f32,
    ) -> (f32, f32) {
        (
            -render_area.left() + self.margins.width as f32 / scale,
            -render_area.top() + self.margins.height as f32 / scale,
        )
    }

    pub fn update_render_context(&mut self, render_area: skia::Rect, scale: f32) {
        let translation = self.get_render_context_translation(render_area, scale);

        // When context changes (zoom/pan/tile), clear all render surfaces first
        // to remove any residual content from previous tiles, then mark as dirty
        // so they get redrawn with new transformations
        let surface_ids = SurfaceId::Fills as u32
            | SurfaceId::Strokes as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32
            | SurfaceId::DropShadows as u32;

        // Clear surfaces before updating transformations to remove residual content
        self.apply_mut(surface_ids, |s| {
            s.canvas().clear(skia::Color::TRANSPARENT);
        });

        // Mark all render surfaces as dirty so they get redrawn
        self.mark_dirty(SurfaceId::Fills);
        self.mark_dirty(SurfaceId::Strokes);
        self.mark_dirty(SurfaceId::InnerShadows);
        self.mark_dirty(SurfaceId::TextDropShadows);
        self.mark_dirty(SurfaceId::DropShadows);

        // Update transformations
        self.apply_mut(surface_ids, |s| {
            let canvas = s.canvas();
            canvas.reset_matrix();
            canvas.scale((scale, scale));
            canvas.translate(translation);
        });
    }

    #[inline(always)]
    pub fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Filter => &mut self.filter,
            SurfaceId::Backbuffer => &mut self.backbuffer,
            SurfaceId::Current => &mut self.current,
            SurfaceId::DropShadows => &mut self.drop_shadows,
            SurfaceId::InnerShadows => &mut self.inner_shadows,
            SurfaceId::TextDropShadows => &mut self.text_drop_shadows,
            SurfaceId::Fills => &mut self.shape_fills,
            SurfaceId::Strokes => &mut self.shape_strokes,
            SurfaceId::Debug => &mut self.debug,
            SurfaceId::UI => &mut self.ui,
            SurfaceId::Export => &mut self.export,
            SurfaceId::Atlas => &mut self.atlas.surface,
            SurfaceId::TileAtlas => self.tiles.surface_mut(),
        }
    }

    #[inline(always)]
    fn get(&self, id: SurfaceId) -> &skia::Surface {
        match id {
            SurfaceId::Target => &self.target,
            SurfaceId::Filter => &self.filter,
            SurfaceId::Backbuffer => &self.backbuffer,
            SurfaceId::Current => &self.current,
            SurfaceId::DropShadows => &self.drop_shadows,
            SurfaceId::InnerShadows => &self.inner_shadows,
            SurfaceId::TextDropShadows => &self.text_drop_shadows,
            SurfaceId::Fills => &self.shape_fills,
            SurfaceId::Strokes => &self.shape_strokes,
            SurfaceId::Debug => &self.debug,
            SurfaceId::UI => &self.ui,
            SurfaceId::Export => &self.export,
            SurfaceId::Atlas => &self.atlas.surface,
            SurfaceId::TileAtlas => self.tiles.surface(),
        }
    }

    pub fn surface_size(&self, id: SurfaceId) -> (i32, i32) {
        let s = self.get(id);
        (s.width(), s.height())
    }

    /// Copy the current `Backbuffer` contents into `Target`.
    /// This is a GPU→GPU copy via Skia (no ReadPixels).
    pub fn copy_backbuffer_to_target(&mut self) {
        let sampling_options = self.sampling_options;
        self.backbuffer.draw(
            self.target.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    /// Replace `Target` pixels with `Backbuffer` (Src blend).
    ///
    /// Used for viewer masked passes: transparent backbuffer regions must not
    /// preserve prior `Target` content from an earlier pass.
    pub fn copy_backbuffer_to_target_replace(&mut self) {
        let sampling_options = self.sampling_options;
        let mut paint = skia::Paint::default();
        paint.set_blend_mode(skia::BlendMode::Src);
        self.backbuffer.draw(
            self.target.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&paint),
        );
    }

    pub fn clear_target(&mut self, color: skia::Color) {
        self.target.canvas().clear(color);
    }

    pub fn clear_tile_atlas(&mut self) {
        self.tiles.reset_all();
    }

    fn reset_from_target(&mut self, target: skia::Surface) -> Result<()> {
        let dim = (target.width(), target.height());
        self.target = target;
        self.filter = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.backbuffer = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.debug = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.ui = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;

        Ok(())
    }

    pub fn draw_rect_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        let mut rect = if let Some(s) = outset.filter(|&s| s > 0.0) {
            let mut r = shape.selrect;
            r.outset((s, s));
            r
        } else {
            shape.selrect
        };
        if let Some(eps) = inset.filter(|&e| e > 0.0) {
            rect.inset((eps, eps));
        }
        if let Some(corners) = shape.shape_type.corners() {
            let corners = if let Some(eps) = inset.filter(|&e| e > 0.0) {
                let mut c = corners;
                for r in c.iter_mut() {
                    r.x = (r.x - eps).max(0.0);
                    r.y = (r.y - eps).max(0.0);
                }
                c
            } else {
                corners
            };
            let rrect = RRect::new_rect_radii(rect, &corners);
            self.canvas_and_mark_dirty(id).draw_rrect(rrect, paint);
        } else {
            self.canvas_and_mark_dirty(id).draw_rect(rect, paint);
        }
    }

    pub fn draw_circle_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        let mut rect = if let Some(s) = outset.filter(|&s| s > 0.0) {
            let mut r = shape.selrect;
            r.outset((s, s));
            r
        } else {
            shape.selrect
        };
        if let Some(eps) = inset.filter(|&e| e > 0.0) {
            rect.inset((eps, eps));
        }
        self.canvas_and_mark_dirty(id).draw_oval(rect, paint);
    }

    pub fn draw_path_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        if let Some(path) = shape.get_skia_path() {
            let canvas = self.canvas_and_mark_dirty(id);
            if let Some(s) = outset.filter(|&s| s > 0.0) {
                // Draw path as a thick stroke to get outset (expanded) silhouette
                let mut stroke_paint = paint.clone();
                stroke_paint.set_stroke_width(s * 2.0);
                canvas.draw_path(&path, &stroke_paint);
            } else if let Some(eps) = inset.filter(|&e| e > 0.0) {
                // Wrap fill + clear in a save_layer so the BlendMode::Clear
                // only erases the current shape's fill, not other shapes
                // already drawn on this surface (avoids white seams at
                // intersections of shapes with inner strokes).
                let layer_rec = skia::canvas::SaveLayerRec::default();
                canvas.save_layer(&layer_rec);
                canvas.draw_path(&path, paint);
                let mut clear_paint = skia::Paint::default();
                clear_paint.set_style(skia::PaintStyle::Stroke);
                clear_paint.set_stroke_width(eps * 2.0);
                clear_paint.set_blend_mode(skia::BlendMode::Clear);
                clear_paint.set_anti_alias(paint.is_anti_alias());
                canvas.draw_path(&path, &clear_paint);
                canvas.restore();
            } else {
                canvas.draw_path(&path, paint);
            }
        }
    }

    /// Full backbuffer clear (viewer layer passes must not reuse prior pass pixels).
    pub fn clear_backbuffer(&mut self, color: skia::Color) {
        self.backbuffer.canvas().clear(color);
    }

    pub fn reset(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Fills).restore_to_count(1);
        self.canvas(SurfaceId::InnerShadows).restore_to_count(1);
        self.canvas(SurfaceId::TextDropShadows).restore_to_count(1);
        self.canvas(SurfaceId::DropShadows).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.canvas(SurfaceId::Export).restore_to_count(1);
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::Current as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32
                | SurfaceId::DropShadows as u32
                | SurfaceId::Export as u32,
            |s| {
                s.canvas().clear(color).reset_matrix();
            },
        );

        self.canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();

        self.canvas(SurfaceId::UI)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();

        // Clear all dirty flags after reset
        self.clear_all_dirty();
    }

    /// Reset render surfaces for interactive transforms without clearing `Target`.
    /// Keeping `Target` avoids having to redraw an atlas backdrop each frame; we
    /// then overwrite only the tiles that changed.
    pub fn reset_interactive_transform(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Fills).restore_to_count(1);
        self.canvas(SurfaceId::InnerShadows).restore_to_count(1);
        self.canvas(SurfaceId::TextDropShadows).restore_to_count(1);
        self.canvas(SurfaceId::DropShadows).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.canvas(SurfaceId::Export).restore_to_count(1);

        // Clear tile-sized/intermediate surfaces; leave Target intact.
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::Current as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32
                | SurfaceId::DropShadows as u32
                | SurfaceId::Export as u32,
            |s| {
                s.canvas().clear(color).reset_matrix();
            },
        );

        // UI/debug can be redrawn; clearing them is fine.
        self.canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();

        self.canvas(SurfaceId::UI)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();

        self.clear_all_dirty();
    }

    pub fn draw_current_tile_into_tile_atlas(
        &mut self,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        tile_doc_rect: skia::Rect,
    ) {
        let gpu_state = get_gpu_state();
        let rect = TILE_DRAWABLE_RECT;

        let tile_image_opt = self.current.image_snapshot_with_bounds(rect);
        if let Some(tile_image) = tile_image_opt {
            // Incrementally update persistent 1:1 atlas in document space.
            // `tile_doc_rect` is in world/document coordinates (1 unit == 1 px at 100%).
            let _ = self
                .atlas
                .blit_tile_image_into_atlas(gpu_state, &tile_image, tile_doc_rect);
            self.atlas.tile_doc_rects.insert(*tile, tile_doc_rect);

            // Write the tile into its allocated slot on the atlas surface.
            let tile_ref = self.tiles.add(tile_viewbox, tile);
            self.tiles.surface_mut().canvas().draw_image_rect(
                &tile_image,
                None,
                tile_ref.rect,
                &skia::Paint::default(),
            );
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn cached_tiles_in_rect(&self, rect: &TileRect) -> Vec<Tile> {
        self.tiles.cached_tiles_in_rect(rect)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile) {
        let gpu_state = get_gpu_state();
        self.tiles.remove(tile);
        // Also clear the corresponding region in the persistent atlas to avoid
        // leaving stale pixels when shapes move/delete.
        let _ = self.atlas.clear_tile_in_atlas(gpu_state, tile);
    }

    pub fn invalidate_cached_tile_surface(&mut self, tile: Tile) {
        self.tiles.mark_stale(tile);
    }

    pub fn draw_cached_tile_into_backbuffer(&mut self, tile: Tile, rect: &Rect) {
        let Some(slot) = self.tiles.slot(tile) else {
            panic!("Tile not found {}:{}", tile.0, tile.1);
        };
        let src = slot.rect;
        if src.width() <= 0.0 || src.height() <= 0.0 {
            return;
        }
        // One snapshot of the managed atlas, plain image draw from it.
        let image = self.tiles.snapshot();
        self.backbuffer.canvas().draw_image_rect(
            &image,
            Some((&src, skia::canvas::SrcRectConstraint::Fast)),
            *rect,
            &skia::Paint::default(),
        );
    }

    /// Draws the current tile directly to the backbuffer without creating a
    /// snapshot. This avoids GPU stalls from ReadPixels but doesn't populate the
    /// tile texture cache (suitable for one-shot renders like tests).
    pub fn draw_current_tile_into_backbuffer(&mut self, tile_rect: &skia::Rect) {
        let sampling_options = self.sampling_options;
        let src_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let src_rect_f = skia::Rect::from(src_rect);

        let backbuffer_canvas = self.backbuffer.canvas();

        // Draw current surface directly to target (no snapshot)
        self.current.draw(
            backbuffer_canvas,
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );
    }

    /// Full tile-cache reset. Used by `rebuild_tiles` (full rebuild). For shallow
    /// rebuilds that should keep the cache for previews, use `invalidate_tile_cache`.
    pub fn remove_cached_tiles(&mut self, _color: skia::Color) {
        self.tiles.clear();
        self.atlas.tile_doc_rects.clear();
    }

    /// Invalidate the tile texture cache. This forces all tiles to be
    /// re-rendered while the DocAtlas preview keeps covering the viewport.
    pub fn invalidate_tile_cache(&mut self) {
        self.tiles.clear();
        self.atlas.tile_doc_rects.clear();
    }

    pub fn gc(&mut self) {
        self.tiles.gc();
    }

    pub fn resize_export_surface(&mut self, scale: f32, rect: skia::Rect) {
        let target_w = (scale * rect.width()).ceil() as i32;
        let target_h = (scale * rect.height()).ceil() as i32;

        let max_w = i32::max(self.extra_tile_dims.width, target_w);
        let max_h = i32::max(self.extra_tile_dims.height, target_h);

        if max_w > self.extra_tile_dims.width || max_h > self.extra_tile_dims.height {
            self.extra_tile_dims = skia::ISize::new(max_w, max_h);

            if let Some(surface) = self
                .drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
            {
                self.drop_shadows = surface;
            }

            if let Some(surface) = self
                .inner_shadows
                .new_surface_with_dimensions((max_w, max_h))
            {
                self.inner_shadows = surface;
            }

            if let Some(surface) = self
                .text_drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
            {
                self.text_drop_shadows = surface;
            }

            if let Some(surface) = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
            {
                self.shape_strokes = surface;
            }

            if let Some(surface) = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
            {
                self.shape_fills = surface;
            }
        }

        if let Some(surface) = self
            .export
            .new_surface_with_dimensions((target_w, target_h))
        {
            self.export = surface;
        }
    }
}

/// Side length of the single tile atlas texture (clamped to the GPU cap).
const ATLAS_SIZE: i32 = 4096;

#[derive(Debug, Clone)]
pub struct TileAtlasTextureRef {
    pub index: usize,
    pub rect: skia::Rect,
}

/// A single Skia-managed atlas texture holding screen-space tiles at the
/// current zoom. Because the surface is Skia-owned (budgeted), `image_snapshot`
/// takes the cheap COW-guarded path: in the render loop's `write → snapshot →
/// draw` order the snapshot is dropped before the next write, so no full-texture
/// copy ever executes. That makes the per-page freeze/recycle/evict machinery
/// (needed only to keep writes and snapshots off the same wrapped texture)
/// unnecessary — one surface plus a slot free list is enough. See
/// draw-atlas-analysis Part III.
pub struct TileTextureCache {
    tile_size: f32,
    side: usize,
    slots: usize,
    surface: skia::Surface,
    grid: HashMap<Tile, TileAtlasTextureRef>,
    free: Vec<usize>,
    next: usize,
    removed: HashSet<Tile>,
    stale: HashSet<Tile>,
}

impl TileTextureCache {
    pub fn try_new(max_texture_size: i32) -> Result<Self> {
        let atlas_size = ATLAS_SIZE.min(max_texture_size.max(TILE_SIZE));
        let side = (atlas_size / TILE_SIZE) as usize;
        let mut surface = get_gpu_state().create_surface_with_dimensions(
            "tile_atlas".to_string(),
            atlas_size,
            atlas_size,
        )?;
        surface.canvas().clear(skia::Color::TRANSPARENT);
        Ok(Self {
            tile_size: tiles::TILE_SIZE,
            side,
            slots: side * side,
            surface,
            grid: HashMap::with_capacity(256),
            free: Vec::new(),
            next: 0,
            removed: HashSet::with_capacity(256),
            stale: HashSet::with_capacity(256),
        })
    }

    fn slot_rect(&self, index: usize) -> skia::Rect {
        let ts = TILE_SIZE as f32;
        let left = (index % self.side) as f32 * ts;
        let top = (index / self.side) as f32 * ts;
        Rect::from_xywh(left, top, ts, ts)
    }

    fn gc(&mut self) {
        let removed: Vec<Tile> = self.removed.drain().collect();
        for tile in removed {
            if let Some(slot) = self.grid.remove(&tile) {
                self.free.push(slot.index);
            }
            self.stale.remove(&tile);
        }
    }

    fn gc_non_visible(&mut self, tile_viewbox: &TileViewbox) {
        let marked: Vec<_> = self
            .grid
            .keys()
            .filter(|tile| !tile_viewbox.is_in_interest_area(tile))
            .take(TEXTURES_BATCH_DELETE)
            .copied()
            .collect();

        for tile in marked.iter() {
            if let Some(slot) = self.grid.remove(tile) {
                self.free.push(slot.index);
            }
            self.removed.remove(tile);
            self.stale.remove(tile);
        }
    }

    /// Visible cached tiles as a single (xform, tex-rect) batch, ready for one
    /// `draw_atlas` call from a single snapshot of the atlas surface.
    pub fn visible_batch(
        &self,
        viewbox: &Viewbox,
        tile_viewbox: &TileViewbox,
    ) -> (Vec<skia::RSXform>, Vec<skia::Rect>) {
        let mut xforms: Vec<skia::RSXform> = vec![];
        let mut texs: Vec<skia::Rect> = vec![];
        let offset = viewbox.get_offset();
        for y in tile_viewbox.visible_rect.top()..=tile_viewbox.visible_rect.bottom() {
            for x in tile_viewbox.visible_rect.left()..=tile_viewbox.visible_rect.right() {
                let tile = Tile(x, y);
                if self.removed.contains(&tile) {
                    continue;
                }
                let Some(slot) = self.grid.get(&tile) else {
                    continue;
                };
                xforms.push(skia::RSXform::new(
                    1.0,
                    0.0,
                    Point::new(
                        x as f32 * self.tile_size - offset.x,
                        y as f32 * self.tile_size - offset.y,
                    ),
                ));
                texs.push(slot.rect);
            }
        }
        (xforms, texs)
    }

    /// A snapshot of the atlas surface, for sampling cached tiles. Cheap on the
    /// Skia-managed surface: in `write → snapshot → draw` order the returned
    /// image is dropped before the next write, so no copy executes.
    pub fn snapshot(&mut self) -> skia::Image {
        self.surface.image_snapshot()
    }

    pub fn surface(&self) -> &skia::Surface {
        &self.surface
    }

    pub fn surface_mut(&mut self) -> &mut skia::Surface {
        &mut self.surface
    }

    pub fn reset_all(&mut self) {
        self.grid.clear();
        self.removed.clear();
        self.stale.clear();
        self.free.clear();
        self.next = 0;
        self.surface.canvas().clear(skia::Color::TRANSPARENT);
    }

    pub fn has(&self, tile: Tile) -> bool {
        self.grid.contains_key(&tile)
            && !self.removed.contains(&tile)
            && !self.stale.contains(&tile)
    }

    pub fn cached_tiles_in_rect(&self, rect: &TileRect) -> Vec<Tile> {
        self.grid
            .keys()
            .filter(|tile| !self.removed.contains(tile) && rect.contains(tile))
            .copied()
            .collect()
    }

    /// Allocate a writable slot for `tile`.
    pub fn add(&mut self, tile_viewbox: &TileViewbox, tile: &Tile) -> TileAtlasTextureRef {
        if let Some(old) = self.grid.remove(tile) {
            self.free.push(old.index);
        }
        let index = self.allocate_index(tile_viewbox);
        let slot = TileAtlasTextureRef {
            index,
            rect: self.slot_rect(index),
        };
        self.grid.insert(*tile, slot.clone());
        self.removed.remove(tile);
        self.stale.remove(tile);
        slot
    }

    fn try_take_slot(&mut self) -> Option<usize> {
        self.free.pop().or_else(|| {
            (self.next < self.slots).then(|| {
                self.next += 1;
                self.next - 1
            })
        })
    }

    /// Evict one cached tile (prefer one outside the interest area) and reuse
    /// its slot. The evicted tile simply re-renders on a later frame.
    fn evict_one(&mut self, tile_viewbox: &TileViewbox) -> Option<usize> {
        let victim = self
            .grid
            .keys()
            .find(|tile| !tile_viewbox.is_in_interest_area(tile))
            .or_else(|| self.grid.keys().next())
            .copied()?;
        let slot = self.grid.remove(&victim)?;
        self.removed.remove(&victim);
        self.stale.remove(&victim);
        Some(slot.index)
    }

    fn allocate_index(&mut self, tile_viewbox: &TileViewbox) -> usize {
        if let Some(index) = self.try_take_slot() {
            return index;
        }
        self.gc();
        if let Some(index) = self.try_take_slot() {
            return index;
        }
        self.gc_non_visible(tile_viewbox);
        if let Some(index) = self.try_take_slot() {
            return index;
        }
        // Atlas full: evict a tile and reuse its slot.
        self.evict_one(tile_viewbox).unwrap_or(0)
    }

    pub fn slot(&self, tile: Tile) -> Option<TileAtlasTextureRef> {
        if self.removed.contains(&tile) {
            return None;
        }
        self.grid.get(&tile).cloned()
    }

    pub fn remove(&mut self, tile: Tile) {
        self.removed.insert(tile);
    }

    pub fn mark_stale(&mut self, tile: Tile) {
        if self.grid.contains_key(&tile) && !self.removed.contains(&tile) {
            self.stale.insert(tile);
        }
    }

    pub fn clear(&mut self) {
        for k in self.grid.keys() {
            self.removed.insert(*k);
        }
        // `removed` supersedes `stale` everywhere; drop the now-moot marks.
        self.stale.clear();
    }
}
