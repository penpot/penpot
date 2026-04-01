use crate::error::{Error, Result};
use crate::performance;
use crate::shapes::Shape;

use skia_safe::{self as skia, IRect, Paint, RRect, Rect};

use super::{gpu_state::GpuState, tiles::Tile, tiles::TileViewbox, tiles::TILE_SIZE};

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_CACHE_CAPACITY: usize = 1024;
const TEXTURES_BATCH_DELETE: usize = 256;
// This is the amount of extra space we're going to give to all the surfaces to render shapes.
// If it's too big it could affect performance.
const TILE_SIZE_MULTIPLIER: i32 = 2;

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

    tiles: TileTextureCache,
    sampling_options: skia::SamplingOptions,
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
            tiles,
            sampling_options,
            margins,
            dirty_surfaces: 0,
            extra_tile_dims,
        })
    }

    pub fn clear_tiles(&mut self) {
        self.tiles.clear();
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
            SurfaceId::Current => &mut self.current,
            SurfaceId::DropShadows => &mut self.drop_shadows,
            SurfaceId::InnerShadows => &mut self.inner_shadows,
            SurfaceId::TextDropShadows => &mut self.text_drop_shadows,
            SurfaceId::Fills => &mut self.shape_fills,
            SurfaceId::Strokes => &mut self.shape_strokes,
            SurfaceId::Debug => &mut self.debug,
            SurfaceId::UI => &mut self.ui,
            SurfaceId::Export => &mut self.export,
        }
    }

    fn get(&self, id: SurfaceId) -> &skia::Surface {
        match id {
            SurfaceId::Target => &self.target,
            SurfaceId::Filter => &self.filter,
            SurfaceId::Cache => &self.cache,
            SurfaceId::Current => &self.current,
            SurfaceId::DropShadows => &self.drop_shadows,
            SurfaceId::InnerShadows => &self.inner_shadows,
            SurfaceId::TextDropShadows => &self.text_drop_shadows,
            SurfaceId::Fills => &self.shape_fills,
            SurfaceId::Strokes => &self.shape_strokes,
            SurfaceId::Debug => &self.debug,
            SurfaceId::UI => &self.ui,
            SurfaceId::Export => &self.export,
        }
    }

    fn reset_from_target(&mut self, target: skia::Surface) -> Result<()> {
        let dim = (target.width(), target.height());
        self.target = target;
        self.filter = self
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
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.canvas(SurfaceId::Export).restore_to_count(1);
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::Current as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32
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

    pub fn cache_current_tile_texture(
        &mut self,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        tile_rect: &skia::Rect,
        scale_bits: u32,
    ) {
        let rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );

        let tile_image_opt = self.current.image_snapshot_with_bounds(rect);

        if let Some(tile_image) = tile_image_opt {
            // Draw to cache first (takes reference), then move to tile cache
            self.cache.canvas().draw_image_rect(
                &tile_image,
                None,
                tile_rect,
                &skia::Paint::default(),
            );

            self.tiles.add(tile_viewbox, tile, scale_bits, tile_image);
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile, scale_bits: u32) -> bool {
        self.tiles.has(tile, scale_bits)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile, scale_bits: u32) {
        // Mark tile as invalid
        // Old content stays visible until new tile overwrites it atomically,
        // preventing flickering during tile re-renders.
        self.tiles.remove(tile, scale_bits);
    }

    pub fn remove_cached_tile_surface_all_scales(&mut self, tile: Tile) {
        self.tiles.remove_all_scales_for_tile(tile);
    }

    pub fn draw_cached_tile_surface(
        &mut self,
        tile: Tile,
        scale_bits: u32,
        rect: skia::Rect,
        color: skia::Color,
    ) {
        if let Some(image) = self.tiles.get(tile, scale_bits) {
            let mut paint = skia::Paint::default();
            paint.set_color(color);

            self.target.canvas().draw_rect(rect, &paint);

            self.target
                .canvas()
                .draw_image_rect(image, None, rect, &skia::Paint::default());
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

    /// Full cache reset: clears both the tile texture cache and the cache canvas.
    /// Used by `rebuild_tiles` (full rebuild). For shallow rebuilds that preserve
    /// the cache canvas for scaled previews, use `invalidate_tile_cache` instead.
    pub fn remove_cached_tiles(&mut self, color: skia::Color) {
        self.tiles.clear();
        self.cache.canvas().clear(color);
    }

    /// Invalidate the tile texture cache without clearing the cache canvas.
    /// This forces all tiles to be re-rendered, but preserves the cache canvas
    /// so that `render_from_cache` can still show a scaled preview of the old
    /// content while new tiles are being rendered.
    pub fn invalidate_tile_cache(&mut self) {
        self.tiles.clear();
    }

    fn rect_intersection(a: Rect, b: Rect) -> Option<Rect> {
        let l = a.left().max(b.left());
        let t = a.top().max(b.top());
        let r = a.right().min(b.right());
        let btm = a.bottom().min(b.bottom());
        if r > l && btm > t {
            Some(Rect::from_ltrb(l, t, r, btm))
        } else {
            None
        }
    }

    /// Draw a placeholder for a missing tile using cached tiles from other zoom levels.
    pub fn draw_tile_fallback_cross_zoom(
        &mut self,
        tile_viewbox: &TileViewbox,
        rect: Rect,
        color: skia::Color,
        target_world_rect: Rect,
        target_scale: f32,
        target_scale_bits: u32,
        debug_trace: bool,
    ) -> usize {
        let Some(candidate_scale_bits) =
            self.tiles
                .best_fallback_scale_bits(target_scale, target_scale_bits)
        else {
            if debug_trace {
                println!(
                    "tile_fallback: no candidate scale (target_scale={})",
                    target_scale
                );
            }
            return 0;
        };

        let src_scale = f32::from_bits(candidate_scale_bits);
        if !src_scale.is_finite() || src_scale <= 0.0 {
            if debug_trace {
                println!(
                    "tile_fallback: invalid candidate scale (bits={}, scale={})",
                    candidate_scale_bits, src_scale
                );
            }
            return 0;
        }

        if debug_trace {
            println!(
                "tile_fallback: target_scale={} -> candidate_scale={}",
                target_scale, src_scale
            );
        }

        let tile_size_src_world = super::tiles::get_tile_size(src_scale);
        let super::tiles::TileRect(sx, sy, ex, ey) =
            super::tiles::get_tiles_for_rect(target_world_rect, tile_size_src_world);

        let mut blits: usize = 0;

        for x in sx..=ex {
            for y in sy..=ey {
                let src_tile = Tile::from(x, y);
                let Some(src_image) = self.tiles.get(src_tile, candidate_scale_bits) else {
                    continue;
                };

                let src_world_rect = super::tiles::get_tile_rect(src_tile, src_scale);
                let Some(overlap_world) = Self::rect_intersection(target_world_rect, src_world_rect)
                else {
                    continue;
                };

                // Source pixel rect within the cached tile image.
                let src_px_l = (overlap_world.left() - src_world_rect.left()) * src_scale;
                let src_px_t = (overlap_world.top() - src_world_rect.top()) * src_scale;
                let src_px_r = (overlap_world.right() - src_world_rect.left()) * src_scale;
                let src_px_b = (overlap_world.bottom() - src_world_rect.top()) * src_scale;
                let src_rect = Rect::from_ltrb(src_px_l, src_px_t, src_px_r, src_px_b);

                // Destination rect in target device space.
                let dst_px_l =
                    rect.left() + (overlap_world.left() - target_world_rect.left()) * target_scale;
                let dst_px_t =
                    rect.top() + (overlap_world.top() - target_world_rect.top()) * target_scale;
                let dst_px_r =
                    rect.left() + (overlap_world.right() - target_world_rect.left()) * target_scale;
                let dst_px_b =
                    rect.top() + (overlap_world.bottom() - target_world_rect.top()) * target_scale;
                let dst_rect = Rect::from_ltrb(dst_px_l, dst_px_t, dst_px_r, dst_px_b);
                let Some(dst_rect) = Self::rect_intersection(dst_rect, rect) else {
                    continue;
                };

                let mut paint = skia::Paint::default();
                paint.set_color(color);
                self.target.canvas().draw_rect(dst_rect, &paint);

                self.target.canvas().draw_image_rect(
                    src_image,
                    Some((&src_rect, skia::canvas::SrcRectConstraint::Fast)),
                    dst_rect,
                    &skia::Paint::default(),
                );

                blits += 1;
            }
        }

        // Opportunistic cleanup in case we kept too much cross-zoom content.
        if blits > 0 && self.tiles.grid_len() > TEXTURES_CACHE_CAPACITY {
            self.tiles.free_tiles(tile_viewbox);
        }

        if debug_trace {
            println!("tile_fallback: blits={}", blits);
        }

        blits
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
            self.drop_shadows = self
                .drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.inner_shadows = self
                .inner_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.text_drop_shadows = self
                .text_drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.text_drop_shadows = self
                .text_drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.shape_strokes = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.shape_fills = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
        }

        self.export = self
            .export
            .new_surface_with_dimensions((target_w, target_h))
            .unwrap();
    }
}

pub struct TileTextureCache {
    grid: HashMap<TileCacheKey, skia::Image>,
    removed: HashSet<TileCacheKey>,
    scales: HashSet<u32>,
}

#[derive(PartialEq, Eq, Hash, Clone, Copy, Debug)]
struct TileCacheKey {
    tile: Tile,
    scale_bits: u32,
}

impl TileTextureCache {
    pub fn new() -> Self {
        Self {
            grid: HashMap::default(),
            removed: HashSet::default(),
            scales: HashSet::default(),
        }
    }

    pub fn has(&self, tile: Tile, scale_bits: u32) -> bool {
        let key = TileCacheKey { tile, scale_bits };
        self.grid.contains_key(&key) && !self.removed.contains(&key)
    }

    fn gc(&mut self) {
        // Make a real remove
        let removed = std::mem::take(&mut self.removed);
        for key in removed.iter() {
            self.grid.remove(key);
        }
    }

    fn free_tiles(&mut self, tile_viewbox: &TileViewbox) {
        println!("free_tiles");
        let marked: Vec<_> = self
            .grid
            .iter_mut()
            .filter_map(|(key, _)| {
                // Approximate visibility check: uses tile coords only.
                if !tile_viewbox.is_visible(&key.tile) {
                    Some(*key)
                } else {
                    None
                }
            })
            .take(TEXTURES_BATCH_DELETE)
            .collect();

        for key in marked.iter() {
            self.grid.remove(key);
        }
    }

    pub fn add(
        &mut self,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        scale_bits: u32,
        image: skia::Image,
    ) {
        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            // First we try to remove the obsolete tiles
            self.gc();
        }

        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            self.free_tiles(tile_viewbox);
        }

        let key = TileCacheKey {
            tile: *tile,
            scale_bits,
        };
        self.scales.insert(scale_bits);
        self.grid.insert(key, image);
        println!("add: {:?}", key);
        self.removed.remove(&key);
    }

    pub fn get(&self, tile: Tile, scale_bits: u32) -> Option<&skia::Image> {
        let key = TileCacheKey { tile, scale_bits };
        if self.removed.contains(&key) {
            return None;
        }
        self.grid.get(&key)
    }

    pub fn remove(&mut self, tile: Tile, scale_bits: u32) {
        self.removed.insert(TileCacheKey { tile, scale_bits });
    }

    pub fn remove_all_scales_for_tile(&mut self, tile: Tile) {
        for scale_bits in self.scales.iter().copied() {
            self.removed.insert(TileCacheKey { tile, scale_bits });
        }
    }

    pub fn clear(&mut self) {
        self.removed.extend(self.grid.keys().copied());
        // After a full invalidation, we must also drop the list of known scales.
        // Otherwise `best_fallback_scale_bits` may keep suggesting a scale that has
        // no usable (non-removed) tiles, leading to confusing `blits=0` traces.
        self.scales.clear();
    }

    pub fn grid_len(&self) -> usize {
        self.grid.len()
    }

    pub fn best_fallback_scale_bits(&self, target_scale: f32, target_scale_bits: u32) -> Option<u32> {
        let mut best: Option<(f32, u32)> = None;
        for bits in self.scales.iter().copied() {
            if bits == target_scale_bits {
                continue;
            }
            let s = f32::from_bits(bits);
            if !s.is_finite() || s <= 0.0 {
                continue;
            }
            let score = (s / target_scale).ln().abs();
            match best {
                Some((best_score, _)) if best_score <= score => {}
                _ => best = Some((score, bits)),
            }
        }
        best.map(|(_, bits)| bits)
    }
}
