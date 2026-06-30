use crate::error::{Error, Result};
use crate::shapes::Shape;
use crate::view::Viewbox;
use crate::{get_gpu_state, performance};

use skia_safe::{self as skia, IRect, Paint, RRect, Rect};

use super::{tiles, tiles::Tile, tiles::TileViewbox};
use crate::math::Point;

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

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
    Backbuffer = 0b100_0000_0100,
    TileAtlas = 0b100_0000_1000,
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
    tile_atlas: skia::Surface,
    tile_atlas_image: Option<skia::Image>,
    tiles: TileTextureCache,

    sampling_options: skia::SamplingOptions,
    atlas_sampling_options: skia::SamplingOptions,

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
        let backbuffer =
            gpu_state.create_surface_with_dimensions("backbuffer".to_string(), width, height)?;

        let max_texture_size = gpu_state.max_texture_size();
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
            tile_atlas,
            tile_atlas_image: None,
            tiles,
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

    pub fn draw_tile_atlas_to_backbuffer(&mut self, viewbox: &Viewbox, tile_viewbox: &TileViewbox) {
        self.tiles.update(viewbox, tile_viewbox);
        if self.tiles.needs_snapshot() || self.tile_atlas_image.is_none() {
            self.tile_atlas_image = Some(self.tile_atlas.image_snapshot());
            self.tiles.snapshot();
        }
        let Some(atlas_image) = self.tile_atlas_image.as_ref() else {
            panic!("Cannot draw tile atlas to backbuffer");
        };
        let canvas = self.backbuffer.canvas();
        canvas.draw_atlas(
            atlas_image,
            &self.tiles.transforms,
            &self.tiles.textures,
            None,
            skia::BlendMode::SrcOver,
            self.atlas_sampling_options,
            None,
            None,
        );
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

    pub fn base64_snapshot_rect(&mut self, id: SurfaceId, irect: IRect) -> Result<Option<String>> {
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

    pub fn snapshot_rect(&mut self, id: SurfaceId, irect: IRect) -> Option<skia::Image> {
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
        performance::end_measure!("apply_mut::flags");
    }

    pub fn apply_one(&mut self, surface_id: SurfaceId, mut f: impl FnMut(&mut skia::Surface)) {
        f(self.get_mut(surface_id));
    }

    pub fn get_render_context_translation(&mut self, render_area: Rect, scale: f32) -> (f32, f32) {
        (
            -render_area.left() + self.margins.width as f32 / scale,
            -render_area.top() + self.margins.height as f32 / scale,
        )
    }

    pub fn update_render_context(&mut self, render_area: Rect, scale: f32) {
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
            SurfaceId::TileAtlas => &mut self.tile_atlas,
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
            SurfaceId::TileAtlas => &self.tile_atlas,
        }
    }

    pub fn surface_size(&self, id: SurfaceId) -> (i32, i32) {
        let s = self.get(id);
        (s.width(), s.height())
    }

    pub fn copy_backbuffer_to_target(&mut self) {
        let sampling_options = self.sampling_options;
        self.backbuffer
            .draw(self.target.canvas(), (0.0, 0.0), sampling_options, None);
    }

    pub fn clear_target(&mut self, color: skia::Color) {
        self.target.canvas().clear(color);
    }

    pub fn clear_tile_atlas(&mut self) {
        self.tile_atlas.canvas().clear(skia::Color::TRANSPARENT);
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

    pub fn clear_backbuffer_rect(&mut self, rect: Rect, color: skia::Color) {
        let mut paint = Paint::default();
        paint.set_color(color);
        self.backbuffer.canvas().draw_rect(rect, &paint);
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

    pub fn draw_current_tile_into_tile_atlas(&mut self, tile: &Tile) {
        let rect = TILE_DRAWABLE_RECT;

        let tile_image_opt = self.current.image_snapshot_with_bounds(rect);
        if let Some(tile_image) = tile_image_opt {
            // Draws current tile into tile atlas
            if let Some(tile_ref) = self.tiles.add(tile) {
                self.tile_atlas.canvas().draw_image_rect(
                    &tile_image,
                    None,
                    tile_ref,
                    &skia::Paint::default(),
                );
            }
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile) {
        // Mark tile as invalid
        // Old content stays visible until new tile overwrites it atomically,
        // preventing flickering during tile re-renders.
        self.tiles.remove(tile);
    }

    /// Invalidate the tile texture cache without clearing the cache canvas.
    /// This forces all tiles to be re-rendered, but preserves the cache canvas
    /// so that `render_from_cache` can still show a scaled preview of the old
    /// content while new tiles are being rendered.
    pub fn invalidate_tile_cache(&mut self) {
        self.tiles.clear();
        self.tile_atlas_image = None;
    }

    pub fn resize_export_surface(&mut self, scale: f32, rect: Rect) {
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

            if let Some(surface) = self.shape_fills.new_surface_with_dimensions((max_w, max_h)) {
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

pub struct TileAtlasTextureProvider {
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
        Self { rects }
    }

    pub fn available(&self) -> usize {
        self.rects.len()
    }

    pub fn allocate(&mut self) -> Option<Rect> {
        self.rects.pop()
    }

    pub fn deallocate(&mut self, rect: Rect) -> bool {
        println!("Deallocating {:?}", rect);
        debug_assert!(
            !self.rects.contains(&rect),
            "Deallocating an already deallocated rect"
        );
        self.rects.push(rect);
        true
    }
}

pub struct TileTextureCache {
    tile_size: f32,
    is_updated: bool,
    provider: TileAtlasTextureProvider,
    transforms: Vec<skia::RSXform>,
    textures: Vec<Rect>,
    grid: HashMap<Tile, Rect>,
    removed: HashSet<Tile>,
}

impl TileTextureCache {
    pub fn new(texture_size: i32, capacity: usize) -> Self {
        Self {
            tile_size: tiles::TILE_SIZE,
            is_updated: false,
            provider: TileAtlasTextureProvider::new(texture_size, TILE_SIZE),
            transforms: Vec::with_capacity(capacity),
            textures: Vec::with_capacity(capacity),
            grid: HashMap::with_capacity(capacity),
            removed: HashSet::with_capacity(capacity),
        }
    }

    fn gc(&mut self) {
        // Make a real remove
        for tile in self.removed.drain() {
            if let Some(tile_ref) = self.grid.remove(&tile) {
                self.provider.deallocate(tile_ref);
            }
        }
    }

    pub fn needs_snapshot(&self) -> bool {
        self.is_updated
    }

    pub fn snapshot(&mut self) {
        self.is_updated = false;
    }

    pub fn update(&mut self, viewbox: &Viewbox, tile_viewbox: &TileViewbox) {
        if self.transforms.len() != tile_viewbox.visible_rect.len() as usize {
            self.transforms.resize(
                tile_viewbox.visible_rect.len() as usize,
                skia::RSXform::new(1.0, 0.0, Point::default()),
            );
        }

        if self.textures.len() != tile_viewbox.visible_rect.len() as usize {
            self.textures
                .resize(tile_viewbox.visible_rect.len() as usize, Rect::new_empty());
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

                if self.removed.contains(&tile) {
                    continue;
                }

                self.transforms[index].tx = x as f32 * self.tile_size - offset.x;
                self.transforms[index].ty = y as f32 * self.tile_size - offset.y;

                self.textures[index].set_ltrb(
                    tile_ref.left,
                    tile_ref.top,
                    tile_ref.right,
                    tile_ref.bottom,
                );

                index += 1;
            }
        }
    }

    pub fn has(&self, tile: Tile) -> bool {
        self.grid.contains_key(&tile) && !self.removed.contains(&tile)
    }

    pub fn add(&mut self, tile: &Tile) -> Option<Rect> {
        self.gc();

        let Some(tile_ref) = self.provider.allocate() else {
            panic!("Cannot allocate more rects");
        };

        self.grid.insert(*tile, tile_ref);

        if self.removed.contains(tile) {
            self.removed.remove(tile);
        }

        self.is_updated = true;
        Some(tile_ref)
    }

    pub fn remove(&mut self, tile: Tile) {
        self.is_updated = true;
        self.removed.insert(tile);
    }

    pub fn clear(&mut self) {
        for k in self.grid.keys() {
            self.removed.insert(*k);
        }
        self.is_updated = true;
    }
}
