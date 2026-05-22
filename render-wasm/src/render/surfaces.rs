use crate::error::{Error, Result};
use crate::shapes::Shape;
use crate::view::Viewbox;
use crate::{get_gpu_state, performance};

use skia_safe::{self as skia, IRect, Paint, RRect, Rect};

use super::{gpu_state::GpuState, tiles, tiles::Tile, tiles::TileRect, tiles::TileViewbox};
use crate::math::Point;

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_CACHE_CAPACITY: usize = 1024;
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

/// Atlas texture size limits (px per side).
///
/// - `DEFAULT_ATLAS_TEXTURE_SIZE` is the startup fallback used until the
///   frontend reads the real `gl.MAX_TEXTURE_SIZE` and sends it via
///   [`Surfaces::atlas.set_max_texture_size`].
/// - `atlas.max_texture_size` is a hard upper bound to clamp the runtime value
///   (defensive cap to avoid accidentally creating oversized GPU textures).
const MAX_ATLAS_TEXTURE_SIZE: i32 = 8192;

pub fn get_cache_size(viewbox: &Viewbox, interest: i32) -> skia::ISize {
    // First we retrieve the extended area of the viewport that we could render.
    let TileRect(isx, isy, iex, iey) =
        tiles::get_tiles_for_viewbox_with_interest(viewbox, interest);

    let dx = if isx.signum() != iex.signum() { 1 } else { 0 };
    let dy = if isy.signum() != iey.signum() { 1 } else { 0 };

    (
        ((iex - isx).abs() + dx) * TILE_SIZE,
        ((iey - isy).abs() + dy) * TILE_SIZE,
    )
        .into()
}

#[derive(Debug, PartialEq)]
pub enum DrawOnCache {
    Yes,
    No,
}

#[repr(u32)]
#[derive(Debug, PartialEq, Clone, Copy)]
#[allow(unused)]
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
    /// Max width/height in pixels for the atlas surface (typically browser
    /// `MAX_TEXTURE_SIZE`). Set from ClojureScript after WebGL context creation.
    pub max_texture_size: i32,
    /// Tracks the last document-space rect written to the atlas per tile.
    /// Used to clear old content without clearing the whole (potentially huge) tile rect.
    pub tile_doc_rects: HashMap<Tile, skia::Rect>,
}

impl DocAtlas {
    pub fn try_new() -> Result<Self> {
        // Keep atlas as a regular surface like the rest. Start with a tiny
        // transparent surface and grow it on demand.
        let mut surface = get_gpu_state()
            .create_surface_with_dimensions("atlas".to_string(), 1, 1)?;

        surface.canvas().clear(skia::Color::TRANSPARENT);

        Ok(Self {
            surface,
            origin: skia::Point::new(0.0, 0.0),
            size: skia::ISize::new(0, 0),
            scale: 1.0,
            doc_bounds: None,
            max_texture_size: MAX_ATLAS_TEXTURE_SIZE,
            tile_doc_rects: HashMap::default()
        })
    }

    pub fn is_empty(&self) -> bool {
        self.size.width <= 0 || self.size.height <= 0
    }

    /// Sets the maximum atlas texture dimension (one side). Should match the
    /// WebGL `MAX_TEXTURE_SIZE` reported by the browser. Values are clamped to
    /// a small minimum so the atlas logic stays well-defined.
    pub fn set_max_texture_size(&mut self, max_px: i32) {
        self.max_texture_size = max_px.clamp(TILE_SIZE, self.max_texture_size);
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

        // Add padding to reduce realloc frequency.
        let pad = tiles::TILE_SIZE;
        new_left -= pad;
        new_top -= pad;
        new_right += pad;
        new_bottom += pad;

        let doc_w = (new_right - new_left).max(1.0);
        let doc_h = (new_bottom - new_top).max(1.0);

        // Compute atlas scale needed to fit within the fixed texture cap.
        // Keep the highest possible scale (closest to 1.0) that still fits.
        let cap = self.max_texture_size.max(TILE_SIZE) as f32;
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
            let src = skia::Rect::from_xywh(
                0.0,
                0.0,
                self.size.width as f32,
                self.size.height as f32,
            );
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
        gpu_state.delete_surface(&mut self.surface);
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

    /// Returns a snapshot of the atlas together with its scale and origin, so the
    /// caller can take it **once** per `rebuild_backbuffer_crop_cache` and share it
    /// across all shapes that need the tile/atlas fallback path — avoiding an
    /// `image_snapshot` (and potential GPU flush) per shape.
    pub fn snapshot_for_drag_crop(&mut self) -> Option<(skia::Image, f32, skia::Point)> {
        if self.is_empty() {
            return None;
        }
        Some((
            self.surface.image_snapshot(),
            self.scale.max(0.01),
            self.origin,
        ))
    }

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
    // Atlas used to keep tiles.
    tile_atlas: skia::Surface,

    tiles: TileTextureCache,
    pub atlas: DocAtlas,
    sampling_options: skia::SamplingOptions,
    pub margins: skia::ISize,
    // Tracks which surfaces have content (dirty flag bitmask)
    dirty_surfaces: u32,
    extra_tile_dims: skia::ISize,
    dpr: f32,
}

#[allow(dead_code)]
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
        let cache = gpu_state.create_surface_with_dimensions("cache".to_string(), width, height)?;
        let backbuffer =
            gpu_state.create_surface_with_dimensions("backbuffer".to_string(), width, height)?;

        // TODO: Obtener este tamaño de alguna otra parte.
        let max_texture_size = 8192;
        // NOTA: Esta textura debería utilizar el máximo permitido por la GPU.
        let tile_atlas = gpu_state.create_surface_with_dimensions(
            "tile_atlas".to_string(),
            max_texture_size,
            max_texture_size,
        )?;

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

        // 512, why not?
        let tiles = TileTextureCache::new(tile_atlas.width(), 512);
        let atlas = DocAtlas::try_new()?;
        Ok(Self {
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
            tile_atlas,
            tiles,
            atlas,
            sampling_options,
            margins,
            dirty_surfaces: 0,
            extra_tile_dims,
            dpr: 1.0,
        })
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        self.dpr = dpr;
    }

    pub fn set_max_texture_size(&mut self, max_texture_size: i32) {
        self.atlas.set_max_texture_size(max_texture_size);
    }

    #[inline]
    pub fn max_texture_dimension_px(&self) -> i32 {
        self.atlas.max_texture_size
    }

    pub fn clear_tiles(&mut self) {
        self.tiles.clear();
    }

    pub fn draw_tile_atlas_to_backbuffer(&mut self, viewbox: &Viewbox, tile_viewbox: &TileViewbox) {
        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Nearest, skia::MipmapMode::None);

        self.tiles.update(viewbox, tile_viewbox);
        self.backbuffer.canvas().draw_atlas(
            &self.tile_atlas.image_snapshot(),
            &self.tiles.transforms,
            &self.tiles.textures,
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

    pub fn flush(&mut self, id: SurfaceId) {
        let gpu_state = get_gpu_state();
        let surface = self.get_mut(id);
        gpu_state.context.flush_surface(surface);
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

    /// Draws the cache surface directly to the backbuffer canvas.
    /// This avoids creating an intermediate snapshot, reducing GPU stalls.
    pub fn draw_cache_to_backbuffer(&mut self) {
        let sampling_options = self.sampling_options;
        self.cache.draw(
            self.backbuffer.canvas(),
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

    #[inline(always)]
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
            SurfaceId::Atlas => &mut self.atlas.surface,
            SurfaceId::TileAtlas => &mut self.tile_atlas,
        }
    }

    #[inline(always)]
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
            SurfaceId::Atlas => &self.atlas.surface,
            SurfaceId::TileAtlas => &self.tile_atlas,
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

    /// Seed `Backbuffer` from `Target` (last presented frame).
    pub fn seed_backbuffer_from_target(&mut self) {
        let sampling_options = self.sampling_options;
        self.target.draw(
            self.backbuffer.canvas(),
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
            (interest_area_threshold * TILE_SIZE) as f32,
            (interest_area_threshold * TILE_SIZE) as f32,
        ));
        Ok(())
    }

    pub fn resize_cache_from_viewbox(
        &mut self,
        viewbox: &Viewbox,
        cached_viewbox: &Viewbox,
        interest_area_threshold: i32,
    ) -> Result<()> {
        let viewbox_cache_size = get_cache_size(viewbox, interest_area_threshold);
        let cached_viewbox_cache_size = get_cache_size(cached_viewbox, interest_area_threshold);
        // Only resize cache if the new size is larger than the cached size
        // This avoids unnecessary surface recreations when the cache size decreases
        if viewbox_cache_size.width > cached_viewbox_cache_size.width
            || viewbox_cache_size.height > cached_viewbox_cache_size.height
        {
            return self.resize_cache(viewbox_cache_size, interest_area_threshold);
        }
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

    pub fn draw_current_tile_into_tile_atlas(
        &mut self,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        tile_rect: &skia::Rect,
        skip_cache_surface: bool,
        tile_doc_rect: skia::Rect,
    ) {
        let gpu_state = get_gpu_state();
        let rect = TILE_DRAWABLE_RECT;

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
            let _ = self.atlas.blit_tile_image_into_atlas(gpu_state, &tile_image, tile_doc_rect);
            self.atlas.tile_doc_rects.insert(*tile, tile_doc_rect);

            // Draws current tile into tile atlas
            let tile_ref = self.tiles.add(tile_viewbox, tile);
            self.tile_atlas.canvas().draw_image_rect(
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

    /// Builds a 1:1 workspace-pixel snapshot for `src_doc_bounds` / `src_irect` into
    /// `scratch`, then returns the sub-region `[0, out_w) × [0, out_h)` as an image.
    ///
    /// `scratch` must be at least `out_w × out_h` pixels — the caller is responsible
    /// for allocating (and **reusing across shapes**) a surface large enough to hold
    /// the largest window needed in one `rebuild_backbuffer_crop_cache` pass.
    ///
    /// `atlas_snap` is a pre-snapshotted view of the persistent atlas produced by
    /// [`Surfaces::atlas.snapshot_for_drag_crop`]; pass `None` when no atlas exists.
    ///
    /// For each tile cell intersecting `src_doc_bounds`: draws from
    /// [`TileTextureCache`] when present; otherwise samples the atlas.
    #[allow(clippy::too_many_arguments)]
    pub fn try_snapshot_doc_rect_from_tiles_and_atlas(
        &mut self,
        scratch: &mut skia::Surface,
        atlas_snap: Option<&(skia::Image, f32, skia::Point)>,
        src_doc_bounds: skia::Rect,
        src_irect: IRect,
        out_w: i32,
        out_h: i32,
        vb_left: f32,
        vb_top: f32,
        scale: f32,
    ) -> Option<skia::Image> {
        if out_w <= 0 || out_h <= 0 || src_doc_bounds.is_empty() {
            return None;
        }

        let canvas = scratch.canvas();
        canvas.clear(skia::Color::TRANSPARENT);

        let tile_size = tiles::get_tile_size(scale);
        let tr = tiles::get_tiles_for_rect(src_doc_bounds, tile_size);
        let ix0 = src_irect.left as f32;
        let iy0 = src_irect.top as f32;
        let paint = skia::Paint::default();

        for ty in tr.y1()..=tr.y2() {
            for tx in tr.x1()..=tr.x2() {
                let tile = Tile(tx, ty);
                let tile_doc = tiles::get_tile_rect(tile, scale);
                let mut clip_doc = tile_doc;
                if !clip_doc.intersect(src_doc_bounds) || clip_doc.is_empty() {
                    continue;
                }

                let dst = skia::Rect::from_ltrb(
                    (clip_doc.left - vb_left) * scale - ix0,
                    (clip_doc.top - vb_top) * scale - iy0,
                    (clip_doc.right - vb_left) * scale - ix0,
                    (clip_doc.bottom - vb_top) * scale - iy0,
                );

                if let Some(tile_ref) = self.tiles.get(tile) {
                    let bounds = skia::IRect::from_ltrb(
                        tile_ref.rect.left as i32,
                        tile_ref.rect.top as i32,
                        tile_ref.rect.right as i32,
                        tile_ref.rect.bottom as i32,
                    );
                    let Some(tile_image) = self.tile_atlas.image_snapshot_with_bounds(bounds)
                    else {
                        panic!("Cannot retrieve tile image");
                    };
                    let iw = tile_image.width() as f32;
                    let ih = tile_image.height() as f32;
                    let td_w = tile_doc.width().max(1e-6);
                    let td_h = tile_doc.height().max(1e-6);

                    let src = skia::Rect::from_ltrb(
                        ((clip_doc.left - tile_doc.left) / td_w) * iw,
                        ((clip_doc.top - tile_doc.top) / td_h) * ih,
                        ((clip_doc.right - tile_doc.left) / td_w) * iw,
                        ((clip_doc.bottom - tile_doc.top) / td_h) * ih,
                    );

                    canvas.draw_image_rect(
                        tile_image,
                        Some((&src, skia::canvas::SrcRectConstraint::Fast)),
                        dst,
                        &paint,
                    );
                } else {
                    let snap = atlas_snap?;
                    let (atlas, a_scale, origin) = (&snap.0, snap.1, snap.2);
                    let sx = (clip_doc.left - origin.x) * a_scale;
                    let sy = (clip_doc.top - origin.y) * a_scale;
                    let sw = clip_doc.width() * a_scale;
                    let sh = clip_doc.height() * a_scale;
                    if sw <= 0.0 || sh <= 0.0 {
                        continue;
                    }
                    let src = skia::Rect::from_xywh(sx, sy, sw, sh);
                    canvas.draw_image_rect(
                        atlas,
                        Some((&src, skia::canvas::SrcRectConstraint::Fast)),
                        dst,
                        &paint,
                    );
                }
            }
        }

        scratch.image_snapshot_with_bounds(IRect::new(0, 0, out_w, out_h))
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile) {
        let gpu_state = get_gpu_state();
        // Mark tile as invalid
        // Old content stays visible until new tile overwrites it atomically,
        // preventing flickering during tile re-renders.
        self.tiles.remove(tile);
        // Also clear the corresponding region in the persistent atlas to avoid
        // leaving stale pixels when shapes move/delete.
        let _ = self.atlas.clear_tile_in_atlas(gpu_state, tile);
    }

    pub fn get_tile_image_from_tile_atlas(&mut self, tile: Tile) -> Option<skia::Image> {
        let Some(tile_ref) = self.tiles.get(tile) else {
            panic!("Tile not found {}:{}", tile.0, tile.1);
        };

        let rect = IRect::from_ltrb(
            tile_ref.rect.left as i32,
            tile_ref.rect.top as i32,
            tile_ref.rect.right as i32,
            tile_ref.rect.bottom as i32,
        );
        self.tile_atlas.image_snapshot_with_bounds(rect)
    }

    pub fn draw_cached_tile_into_backbuffer(
        &mut self,
        tile: Tile,
        rect: skia::Rect,
        _color: skia::Color,
    ) {
        if let Some(image) = self.get_tile_image_from_tile_atlas(tile) {
            let backbuffer_canvas = self.backbuffer.canvas();

            // if color != skia::Color::TRANSPARENT {
            //     let mut paint = skia::Paint::default();
            //     paint.set_color(color);
            //     backbuffer_canvas.draw_rect(rect, &paint);
            // }

            backbuffer_canvas.draw_image_rect(&image, None, rect, &skia::Paint::default());
        }
    }

    /// Draws a cached tile texture to the Cache self.backbuffer at the given
    /// cache-aligned rect.  This keeps the Cache surface in sync with
    /// Backbuffer so that `render_from_cache` (used during pan) has the
    /// full scene including tiles served from the texture cache.
    pub fn draw_cached_tile_into_cache(
        &mut self,
        tile: Tile,
        aligned_rect: &skia::Rect,
        _color: skia::Color,
    ) {
        if let Some(image) = self.get_tile_image_from_tile_atlas(tile) {
            // let mut bg = skia::Paint::default();
            // bg.set_color(color);
            // self.cache.canvas().draw_rect(aligned_rect, &bg);
            self.cache.canvas().draw_image_rect(
                &image,
                None,
                aligned_rect,
                &skia::Paint::default(),
            );
        }
    }

    /// Draws the current tile directly to the backbuffer and cache surfaces without
    /// creating a snapshot. This avoids GPU stalls from ReadPixels but doesn't
    /// populate the tile texture cache (suitable for one-shot renders like tests).
    pub fn draw_current_tile_into_backbuffer(
        &mut self,
        tile_rect: &skia::Rect,
        _color: skia::Color,
        draw_on_cache: DrawOnCache,
    ) {
        let sampling_options = self.sampling_options;
        let src_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let src_rect_f = skia::Rect::from(src_rect);

        let backbuffer_canvas = self.backbuffer.canvas();

        // Draw background
        // let mut paint = skia::Paint::default();
        // paint.set_color(color);
        // backbuffer_canvas.draw_rect(tile_rect, &paint);

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

        // Also draw to cache for render_from_cache
        if draw_on_cache == DrawOnCache::Yes {
            self.current.draw(
                self.cache.canvas(),
                (
                    tile_rect.left - src_rect_f.left,
                    tile_rect.top - src_rect_f.top,
                ),
                sampling_options,
                None,
            );
        }
    }

    /// Full cache reset: clears both the tile texture cache and the cache canvas.
    /// Used by `rebuild_tiles` (full rebuild). For shallow rebuilds that preserve
    /// the cache canvas for scaled previews, use `invalidate_tile_cache` instead.
    pub fn remove_cached_tiles(&mut self, color: skia::Color) {
        self.tiles.clear();
        self.atlas.tile_doc_rects.clear();
        self.cache.canvas().clear(color);
    }

    /// Invalidate the tile texture cache without clearing the cache canvas.
    /// This forces all tiles to be re-rendered, but preserves the cache canvas
    /// so that `render_from_cache` can still show a scaled preview of the old
    /// content while new tiles are being rendered.
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

#[derive(Debug, Clone)]
pub struct TileAtlasTextureRef {
    pub index: usize,
    pub rect: skia::Rect,
}

impl TileAtlasTextureRef {
    pub fn new(index: usize, rect: skia::Rect) -> Self {
        Self { index, rect }
    }
}

pub struct TileAtlasTextureProvider {
    pub index: usize,
    pub length: usize,
    pub in_use: Vec<bool>,
    pub rects: Vec<Rect>,
}

impl TileAtlasTextureProvider {
    pub fn new(texture_size: i32, tile_size: i32) -> Self {
        let side = texture_size / tile_size;
        let length = side * side;
        let mut rects = Vec::with_capacity(length as usize);
        for i in 0..length {
            let left = (i % side) as f32 * tile_size as f32;
            let top = (i / side) as f32 * tile_size as f32;
            let right = left + tile_size as f32;
            let bottom = top + tile_size as f32;
            rects.push(Rect::new(left, top, right, bottom));
        }
        Self {
            index: 0,
            length: length as usize,
            in_use: vec![false; length as usize],
            rects,
        }
    }

    pub fn allocate(&mut self) -> Option<TileAtlasTextureRef> {
        let start = self.index;
        loop {
            if !self.in_use[self.index] {
                self.in_use[self.index] = true;
                return Some(TileAtlasTextureRef::new(self.index, self.rects[self.index]));
            }

            self.index = (self.index + 1) % self.length;
            if self.index == start {
                return None;
            }
        }
    }

    pub fn deallocate(&mut self, reference: TileAtlasTextureRef) -> bool {
        // In this case the user of the provider it's trying to release
        // a reference already freed.
        if !self.in_use[reference.index] {
            return false;
        }
        self.in_use[reference.index] = false;
        self.index = reference.index;
        true
    }
}

pub struct TileTextureCache {
    tile_size: f32,
    provider: TileAtlasTextureProvider,
    transforms: Vec<skia::RSXform>,
    textures: Vec<skia::Rect>,
    grid: HashMap<Tile, TileAtlasTextureRef>,
    removed: HashSet<Tile>,
}

impl TileTextureCache {
    pub fn new(texture_size: i32, capacity: usize) -> Self {
        Self {
            tile_size: tiles::TILE_SIZE,
            provider: TileAtlasTextureProvider::new(texture_size, TILE_SIZE),
            transforms: Vec::with_capacity(capacity),
            textures: Vec::with_capacity(capacity),
            grid: HashMap::with_capacity(capacity),
            removed: HashSet::with_capacity(capacity),
        }
    }

    fn gc(&mut self) {
        // Make a real remove
        for tile in self.removed.iter() {
            if let Some(tile_ref) = self.grid.remove(tile) {
                self.provider.deallocate(tile_ref);
            }
        }
    }

    fn gc_non_visible(&mut self, tile_viewbox: &TileViewbox) {
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
            if let Some(tile_ref) = self.grid.remove(tile) {
                self.provider.deallocate(tile_ref);
            }
        }
    }

    pub fn update(&mut self, viewbox: &Viewbox, tile_viewbox: &TileViewbox) {
        if self.transforms.len() != tile_viewbox.visible_rect.len() as usize {
            self.transforms.resize(
                tile_viewbox.visible_rect.len() as usize,
                skia::RSXform::new(1.0, 0.0, Point::default()),
            );
        }

        if self.textures.len() != tile_viewbox.visible_rect.len() as usize {
            self.textures.resize(
                tile_viewbox.visible_rect.len() as usize,
                skia::Rect::new_empty(),
            );
        }

        for texture in self.textures.iter_mut() {
            texture.set_empty();
        }

        let offset = viewbox.get_offset();
        let mut index = 0;
        for y in tile_viewbox.visible_rect.top()..=tile_viewbox.visible_rect.bottom() {
            for x in tile_viewbox.visible_rect.left()..=tile_viewbox.visible_rect.right() {
                let tile = Tile(x, y);

                let Some(tile_ref) = self.grid.get(&tile) else {
                    continue;
                };

                self.transforms[index].tx = x as f32 * self.tile_size - offset.x;
                self.transforms[index].ty = y as f32 * self.tile_size - offset.y;

                self.textures[index].set_ltrb(
                    tile_ref.rect.left,
                    tile_ref.rect.top,
                    tile_ref.rect.right,
                    tile_ref.rect.bottom,
                );

                index += 1;
            }
        }
    }

    pub fn has(&self, tile: Tile) -> bool {
        self.grid.contains_key(&tile) && !self.removed.contains(&tile)
    }

    pub fn add(&mut self, tile_viewbox: &TileViewbox, tile: &Tile) -> TileAtlasTextureRef {
        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            // First we try to remove the obsolete tiles.
            self.gc();
        }

        // If we still have a texture capacity problem, then
        // we try to remove all of those tiles that aren't
        // visible.
        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            self.gc_non_visible(tile_viewbox);
        }

        let Some(tile_ref) = self.provider.allocate() else {
            panic!("Tile texture allocation failed {}:{}", tile.0, tile.1);
        };

        self.grid.insert(*tile, tile_ref.clone());

        if self.removed.contains(tile) {
            self.removed.remove(tile);
        }

        tile_ref.clone()
    }

    pub fn get(&mut self, tile: Tile) -> Option<&TileAtlasTextureRef> {
        if self.removed.contains(&tile) {
            return None;
        }
        self.grid.get(&tile)
    }

    pub fn remove(&mut self, tile: Tile) {
        if let Some(tile_ref) = self.grid.get(&tile) {
            if tile_ref.index < self.textures.len() {
                self.textures[tile_ref.index].set_empty();
            }
        }
        self.removed.insert(tile);
    }

    pub fn clear(&mut self) {
        for k in self.grid.keys() {
            self.removed.insert(*k);
        }
    }
}
