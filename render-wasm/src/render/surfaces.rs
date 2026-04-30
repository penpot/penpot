use crate::error::{Error, Result};
use crate::performance;
use crate::shapes::Shape;
use crate::view::Viewbox;

use skia_safe::{self as skia, IRect, Paint, RRect};

use super::{gpu_state::GpuState, tiles::Tile, tiles::TileViewbox, tiles::TILE_SIZE};

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_CACHE_CAPACITY: usize = 1024;
const TEXTURES_BATCH_DELETE: usize = 256;
// This is the amount of extra space we're going to give to all the surfaces to render shapes.
// If it's too big it could affect performance.
const TILE_SIZE_MULTIPLIER: i32 = 2;

/// Atlas texture size limits (px per side).
///
/// - `DEFAULT_MAX_ATLAS_TEXTURE_SIZE` is the startup fallback used until the
///   frontend reads the real `gl.MAX_TEXTURE_SIZE` and sends it via
///   [`Surfaces::set_max_atlas_texture_size`].
/// - `MAX_ATLAS_TEXTURE_SIZE` is a hard upper bound to clamp the runtime value
///   (defensive cap to avoid accidentally creating oversized GPU textures).
const MAX_ATLAS_TEXTURE_SIZE: i32 = 4096;
const DEFAULT_MAX_ATLAS_TEXTURE_SIZE: i32 = 1024;

#[repr(u32)]
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target = 0b000_0000_0001,
    Filter = 0b000_0000_0010,
    Cache = 0b000_0000_0100,
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
}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    filter: skia::Surface,
    cache: skia::Surface,
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

    tiles: TileTextureCache,
    // Persistent 1:1 document-space atlas that gets incrementally updated as tiles render.
    // It grows dynamically to include any rendered document rect.
    atlas: skia::Surface,
    atlas_origin: skia::Point,
    atlas_size: skia::ISize,
    /// Atlas pixel density relative to document pixels (1.0 == 1:1 doc px).
    /// When the atlas would exceed `max_atlas_texture_size`, this value is
    /// reduced so the atlas stays within the fixed texture cap.
    atlas_scale: f32,
    /// Optional document-space bounds (1 unit == 1 doc px @ 100% zoom) used to
    /// clamp atlas writes/clears so the atlas doesn't grow due to outlier tile rects.
    atlas_doc_bounds: Option<skia::Rect>,
    /// Max width/height in pixels for the atlas surface (typically browser
    /// `MAX_TEXTURE_SIZE`). Set from ClojureScript after WebGL context creation.
    max_atlas_texture_size: i32,
    sampling_options: skia::SamplingOptions,
    /// Tracks the last document-space rect written to the atlas per tile.
    /// Used to clear old content without clearing the whole (potentially huge) tile rect.
    atlas_tile_doc_rects: HashMap<Tile, skia::Rect>,
    pub margins: skia::ISize,
    // Tracks which surfaces have content (dirty flag bitmask)
    dirty_surfaces: u32,

    extra_tile_dims: skia::ISize,
}

#[allow(dead_code)]
impl Surfaces {
    pub fn try_new(
        gpu_state: &mut GpuState,
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
        tile_dims: skia::ISize,
    ) -> Result<Self> {
        let extra_tile_dims = skia::ISize::new(
            tile_dims.width * TILE_SIZE_MULTIPLIER,
            tile_dims.height * TILE_SIZE_MULTIPLIER,
        );
        let margins = skia::ISize::new(extra_tile_dims.width / 4, extra_tile_dims.height / 4);

        let target = gpu_state.create_target_surface(width, height)?;
        let filter = gpu_state.create_surface_with_isize("filter".to_string(), extra_tile_dims)?;
        let cache = gpu_state.create_surface_with_dimensions("cache".to_string(), width, height)?;
        let backbuffer =
            gpu_state.create_surface_with_dimensions("backbuffer".to_string(), width, height)?;
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
        // Keep atlas as a regular surface like the rest. Start with a tiny
        // transparent surface and grow it on demand.
        let mut atlas = gpu_state.create_surface_with_dimensions("atlas".to_string(), 1, 1)?;
        atlas.canvas().clear(skia::Color::TRANSPARENT);

        let tiles = TileTextureCache::new();
        Ok(Surfaces {
            target,
            filter,
            cache,
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
            atlas_origin: skia::Point::new(0.0, 0.0),
            atlas_size: skia::ISize::new(0, 0),
            atlas_scale: 1.0,
            atlas_doc_bounds: None,
            max_atlas_texture_size: DEFAULT_MAX_ATLAS_TEXTURE_SIZE,
            sampling_options,
            atlas_tile_doc_rects: HashMap::default(),
            margins,
            dirty_surfaces: 0,
            extra_tile_dims,
        })
    }

    /// Sets the maximum atlas texture dimension (one side). Should match the
    /// WebGL `MAX_TEXTURE_SIZE` reported by the browser. Values are clamped to
    /// a small minimum so the atlas logic stays well-defined.
    pub fn set_max_atlas_texture_size(&mut self, max_px: i32) {
        self.max_atlas_texture_size = max_px.clamp(TILE_SIZE as i32, MAX_ATLAS_TEXTURE_SIZE);
    }

    /// Sets the document-space bounds used to clamp atlas updates.
    /// Pass `None` to disable clamping.
    pub fn set_atlas_doc_bounds(&mut self, bounds: Option<skia::Rect>) {
        self.atlas_doc_bounds = bounds.filter(|b| !b.is_empty());
    }

    fn clamp_doc_rect_to_bounds(&self, doc_rect: skia::Rect) -> skia::Rect {
        if doc_rect.is_empty() {
            return doc_rect;
        }
        if let Some(bounds) = self.atlas_doc_bounds {
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
        let current_left = self.atlas_origin.x;
        let current_top = self.atlas_origin.y;
        let atlas_scale = self.atlas_scale.max(0.01);
        let current_right = current_left + (self.atlas_size.width as f32) / atlas_scale;
        let current_bottom = current_top + (self.atlas_size.height as f32) / atlas_scale;

        let mut new_left = current_left;
        let mut new_top = current_top;
        let mut new_right = current_right;
        let mut new_bottom = current_bottom;

        // If atlas is empty/uninitialized, seed to rect (expanded to tile boundaries for fewer reallocs).
        let needs_init = self.atlas_size.width <= 0 || self.atlas_size.height <= 0;
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

        // Add padding to reduce realloc frequency.
        let pad = TILE_SIZE;
        new_left -= pad;
        new_top -= pad;
        new_right += pad;
        new_bottom += pad;

        let doc_w = (new_right - new_left).max(1.0);
        let doc_h = (new_bottom - new_top).max(1.0);

        // Compute atlas scale needed to fit within the fixed texture cap.
        // Keep the highest possible scale (closest to 1.0) that still fits.
        let cap = self.max_atlas_texture_size.max(TILE_SIZE as i32) as f32;
        let required_scale = (cap / doc_w).min(cap / doc_h).clamp(0.01, 1.0);

        // Never upscale the atlas (it would add blur and churn).
        let new_scale = self.atlas_scale.min(required_scale).max(0.01);

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

        let mut new_atlas =
            gpu_state.create_surface_with_dimensions("atlas".to_string(), new_w, new_h)?;
        new_atlas.canvas().clear(skia::Color::TRANSPARENT);

        // Copy old atlas into the new one with offset.
        if !needs_init {
            let old_scale = self.atlas_scale.max(0.01);
            let scale_ratio = new_scale / old_scale;
            let dx = (current_left - new_left) * new_scale;
            let dy = (current_top - new_top) * new_scale;

            let image = self.atlas.image_snapshot();
            let src = skia::Rect::from_xywh(
                0.0,
                0.0,
                self.atlas_size.width as f32,
                self.atlas_size.height as f32,
            );
            let dst = skia::Rect::from_xywh(
                dx,
                dy,
                (self.atlas_size.width as f32) * scale_ratio,
                (self.atlas_size.height as f32) * scale_ratio,
            );
            new_atlas.canvas().draw_image_rect(
                &image,
                Some((&src, skia::canvas::SrcRectConstraint::Fast)),
                dst,
                &skia::Paint::default(),
            );
        }

        self.atlas_origin = skia::Point::new(new_left, new_top);
        self.atlas_size = skia::ISize::new(new_w, new_h);
        self.atlas_scale = new_scale;
        self.atlas = new_atlas;
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
        if let Some(bounds) = self.atlas_doc_bounds {
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
            (clipped_doc_rect.left - self.atlas_origin.x) * self.atlas_scale,
            (clipped_doc_rect.top - self.atlas_origin.y) * self.atlas_scale,
            clipped_doc_rect.width() * self.atlas_scale,
            clipped_doc_rect.height() * self.atlas_scale,
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

        self.atlas.canvas().draw_image_rect(
            tile_image,
            Some((&src, skia::canvas::SrcRectConstraint::Fast)),
            dst,
            &skia::Paint::default(),
        );
        Ok(())
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
            (doc_rect.left - self.atlas_origin.x) * self.atlas_scale,
            (doc_rect.top - self.atlas_origin.y) * self.atlas_scale,
            doc_rect.width() * self.atlas_scale,
            doc_rect.height() * self.atlas_scale,
        );

        let canvas = self.atlas.canvas();
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
        if let Some(doc_rect) = self.atlas_tile_doc_rects.remove(&tile) {
            self.clear_doc_rect_in_atlas(gpu_state, doc_rect)?;
        }
        Ok(())
    }

    pub fn clear_tiles(&mut self) {
        self.tiles.clear();
    }

    pub fn has_atlas(&self) -> bool {
        self.atlas_size.width > 0 && self.atlas_size.height > 0
    }

    /// Draw the persistent atlas onto the target using the current viewbox transform.
    /// Intended for fast pan/zoom-out previews (avoids per-tile composition).
    /// Clears Target to `background` first so atlas-uncovered regions don't
    /// show stale content when the atlas only partially covers the viewport.
    pub fn draw_atlas_to_target(&mut self, viewbox: Viewbox, dpr: f32, background: skia::Color) {
        if !self.has_atlas() {
            return;
        }

        let canvas = self.target.canvas();
        canvas.save();
        canvas.reset_matrix();
        let size = canvas.base_layer_size();
        canvas.clip_rect(
            skia::Rect::from_xywh(0.0, 0.0, size.width as f32, size.height as f32),
            None,
            true,
        );
        canvas.clear(background);

        let s = viewbox.zoom * dpr;
        let atlas_scale = self.atlas_scale.max(0.01);
        canvas.translate((
            (self.atlas_origin.x + viewbox.pan_x) * s,
            (self.atlas_origin.y + viewbox.pan_y) * s,
        ));
        canvas.scale((s / atlas_scale, s / atlas_scale));

        self.atlas.draw(
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

    pub fn resize(
        &mut self,
        gpu_state: &mut GpuState,
        new_width: i32,
        new_height: i32,
    ) -> Result<()> {
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

    pub fn snapshot_rect(&mut self, id: SurfaceId, irect: skia::IRect) -> Option<skia::Image> {
        let surface = self.get_mut(id);
        surface.image_snapshot_with_bounds(irect)
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

    pub fn flush_and_submit(&mut self, gpu_state: &mut GpuState, id: SurfaceId) {
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

    /// Draws the cache surface directly to the target canvas.
    /// This avoids creating an intermediate snapshot, reducing GPU stalls.
    pub fn draw_cache_to_target(&mut self) {
        let sampling_options = self.sampling_options;
        self.cache.clone().draw(
            self.target.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    pub fn cache_dimensions(&self) -> skia::ISize {
        skia::ISize::new(self.cache.width(), self.cache.height())
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
        if ids & SurfaceId::Cache as u32 != 0 {
            f(self.get_mut(SurfaceId::Cache));
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

    #[inline]
    pub fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Filter => &mut self.filter,
            SurfaceId::Cache => &mut self.cache,
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
            SurfaceId::Atlas => &mut self.atlas,
        }
    }

    fn get(&self, id: SurfaceId) -> &skia::Surface {
        match id {
            SurfaceId::Target => &self.target,
            SurfaceId::Filter => &self.filter,
            SurfaceId::Cache => &self.cache,
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
            SurfaceId::Atlas => &self.atlas,
        }
    }

    pub fn surface_size(&self, id: SurfaceId) -> (i32, i32) {
        let s = self.get(id);
        (s.width(), s.height())
    }

    /// Copy the current `Target` contents into the persistent `Backbuffer`.
    /// This is a GPU→GPU copy via Skia (no ReadPixels).
    pub fn copy_target_to_backbuffer(&mut self) {
        let sampling_options = self.sampling_options;
        self.target.clone().draw(
            self.backbuffer.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    /// Seed `Target` from `Backbuffer` (last presented frame).
    pub fn seed_target_from_backbuffer(&mut self) {
        let sampling_options = self.sampling_options;
        self.backbuffer.clone().draw(
            self.target.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&skia::Paint::default()),
        );
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
        // The rest are tile size surfaces

        Ok(())
    }

    pub fn resize_cache(
        &mut self,
        cache_dims: skia::ISize,
        interest_area_threshold: i32,
    ) -> Result<()> {
        self.cache = self
            .target
            .new_surface_with_dimensions(cache_dims)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.cache.canvas().reset_matrix();
        self.cache.canvas().translate((
            (interest_area_threshold as f32 * TILE_SIZE),
            (interest_area_threshold as f32 * TILE_SIZE),
        ));
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

    /// Clears the whole cache surface without disturbing its configured transform.
    pub fn clear_cache(&mut self, color: skia::Color) {
        let canvas = self.cache.canvas();
        canvas.save();
        canvas.reset_matrix();
        canvas.clear(color);
        canvas.restore();
    }

    pub fn cache_current_tile_texture(
        &mut self,
        gpu_state: &mut GpuState,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        tile_rect: &skia::Rect,
        skip_cache_surface: bool,
        tile_doc_rect: skia::Rect,
    ) {
        let rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );

        let tile_image_opt = self.current.image_snapshot_with_bounds(rect);

        if let Some(tile_image) = tile_image_opt {
            if !skip_cache_surface {
                // Draw to cache surface for render_from_cache
                self.cache.canvas().draw_image_rect(
                    &tile_image,
                    None,
                    tile_rect,
                    &skia::Paint::default(),
                );
            }

            // Incrementally update persistent 1:1 atlas in document space.
            // `tile_doc_rect` is in world/document coordinates (1 unit == 1 px at 100%).
            let _ = self.blit_tile_image_into_atlas(gpu_state, &tile_image, tile_doc_rect);
            self.atlas_tile_doc_rects.insert(*tile, tile_doc_rect);
            self.tiles.add(tile_viewbox, tile, tile_image);
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn remove_cached_tile_surface(&mut self, gpu_state: &mut GpuState, tile: Tile) {
        // Mark tile as invalid
        // Old content stays visible until new tile overwrites it atomically,
        // preventing flickering during tile re-renders.
        self.tiles.remove(tile);
        // Also clear the corresponding region in the persistent atlas to avoid
        // leaving stale pixels when shapes move/delete.
        let _ = self.clear_tile_in_atlas(gpu_state, tile);
    }

    pub fn draw_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect, color: skia::Color) {
        if let Some(image) = self.tiles.get(tile) {
            let mut paint = skia::Paint::default();
            paint.set_color(color);

            self.target.canvas().draw_rect(rect, &paint);

            self.target
                .canvas()
                .draw_image_rect(&image, None, rect, &skia::Paint::default());
        }
    }

    /// Draws a cached tile texture to the Cache surface at the given
    /// cache-aligned rect.  This keeps the Cache surface in sync with
    /// Target so that `render_from_cache` (used during pan) has the
    /// full scene including tiles served from the texture cache.
    pub fn draw_cached_tile_to_cache(
        &mut self,
        tile: Tile,
        aligned_rect: &skia::Rect,
        color: skia::Color,
    ) {
        if let Some(image) = self.tiles.get(tile) {
            let mut bg = skia::Paint::default();
            bg.set_color(color);
            self.cache.canvas().draw_rect(aligned_rect, &bg);
            self.cache.canvas().draw_image_rect(
                &image,
                None,
                aligned_rect,
                &skia::Paint::default(),
            );
        }
    }

    /// Draws the current tile directly to the target and cache surfaces without
    /// creating a snapshot. This avoids GPU stalls from ReadPixels but doesn't
    /// populate the tile texture cache (suitable for one-shot renders like tests).
    pub fn draw_current_tile_direct(&mut self, tile_rect: &skia::Rect, color: skia::Color) {
        let sampling_options = self.sampling_options;
        let src_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let src_rect_f = skia::Rect::from(src_rect);

        // Draw background
        let mut paint = skia::Paint::default();
        paint.set_color(color);
        self.target.canvas().draw_rect(tile_rect, &paint);

        // Draw current surface directly to target (no snapshot)
        self.current.clone().draw(
            self.target.canvas(),
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );

        // Also draw to cache for render_from_cache
        self.current.clone().draw(
            self.cache.canvas(),
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );
    }

    /// Same as `draw_current_tile_direct` but draws only into Target.
    /// Useful during interactive transforms to reduce extra GPU work.
    pub fn draw_current_tile_direct_target_only(
        &mut self,
        tile_rect: &skia::Rect,
        color: skia::Color,
    ) {
        let sampling_options = self.sampling_options;
        let src_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let src_rect_f = skia::Rect::from(src_rect);

        let mut paint = skia::Paint::default();
        paint.set_color(color);
        self.target.canvas().draw_rect(tile_rect, &paint);

        self.current.clone().draw(
            self.target.canvas(),
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );
    }

    /// Full cache reset: clears both the tile texture cache and the cache canvas.
    /// Used by `rebuild_tiles` (full rebuild). For shallow rebuilds that preserve
    /// the cache canvas for scaled previews, use `invalidate_tile_cache` instead.
    pub fn remove_cached_tiles(&mut self, color: skia::Color) {
        self.tiles.clear();
        self.atlas_tile_doc_rects.clear();
        self.cache.canvas().clear(color);
    }

    /// Invalidate the tile texture cache without clearing the cache canvas.
    /// This forces all tiles to be re-rendered, but preserves the cache canvas
    /// so that `render_from_cache` can still show a scaled preview of the old
    /// content while new tiles are being rendered.
    pub fn invalidate_tile_cache(&mut self) {
        self.tiles.clear();
        self.atlas_tile_doc_rects.clear();
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

pub struct TileTextureCache {
    grid: HashMap<Tile, skia::Image>,
    removed: HashSet<Tile>,
}

impl TileTextureCache {
    pub fn new() -> Self {
        Self {
            grid: HashMap::default(),
            removed: HashSet::default(),
        }
    }

    pub fn has(&self, tile: Tile) -> bool {
        self.grid.contains_key(&tile) && !self.removed.contains(&tile)
    }

    fn gc(&mut self) {
        // Make a real remove
        for tile in self.removed.iter() {
            self.grid.remove(tile);
        }
    }

    fn free_tiles(&mut self, tile_viewbox: &TileViewbox) {
        let marked: Vec<_> = self
            .grid
            .iter_mut()
            .filter_map(|(tile, _)| {
                if !tile_viewbox.is_visible(tile) {
                    Some(*tile)
                } else {
                    None
                }
            })
            .take(TEXTURES_BATCH_DELETE)
            .collect();

        for tile in marked.iter() {
            self.grid.remove(tile);
        }
    }

    pub fn add(&mut self, tile_viewbox: &TileViewbox, tile: &Tile, image: skia::Image) {
        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            // First we try to remove the obsolete tiles
            self.gc();
        }

        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            self.free_tiles(tile_viewbox);
        }

        self.grid.insert(*tile, image);

        if self.removed.contains(tile) {
            self.removed.remove(tile);
        }
    }

    pub fn get(&mut self, tile: Tile) -> Option<&mut skia::Image> {
        if self.removed.contains(&tile) {
            return None;
        }
        self.grid.get_mut(&tile)
    }

    pub fn remove(&mut self, tile: Tile) {
        self.removed.insert(tile);
    }

    pub fn clear(&mut self) {
        for k in self.grid.keys() {
            self.removed.insert(*k);
        }
    }
}
