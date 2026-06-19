mod background_blur;
mod debug;
pub mod drop_shadow;
pub mod enter_exit;
pub mod export;
mod fills;
pub mod filters;
pub mod focus_mode;
mod fonts;
pub mod gpu_state;
pub mod grid_layout;
mod images;
pub mod layer_blur;
mod options;
pub mod pdf;
pub mod rulers;
mod shadows;
pub mod shape_renderer;
mod strokes;
mod surfaces;
pub mod text;
pub mod text_editor;
mod ui;
mod vector;
pub mod view_mode;
pub mod walk;

use skia_safe::{self as skia, Matrix, RRect, Rect};
use std::borrow::Cow;
use std::collections::{HashSet};

use options::RenderOptions;
pub use surfaces::{SurfaceId, Surfaces};

pub use focus_mode::FocusMode;
pub(crate) use walk::{get_simplified_children, sort_z_index};
pub use walk::{ClipStack, NodeRenderState, RenderStats};

use crate::error::{Error, Result};
use crate::shapes::{
    all_with_ancestors, Blur, BlurType, Fill, Layout, Shadow, Shape, Stroke, StrokeKind,
    TextContent, Type,
};
use crate::state::{RulerState, ShapesPoolMutRef, ShapesPoolRef};
use crate::tiles::{self, PendingTiles, TileRect};
use crate::uuid::Uuid;
use crate::view::Viewbox;
use crate::wapi;
use crate::{get_gpu_state, performance};

pub use fonts::*;
pub use images::*;

#[repr(u8)]
pub enum FrameType {
    None = 0,
    Partial = 1,
    Full = 2,
}

#[allow(dead_code)]
#[repr(u8)]
pub enum RenderFlag {
    None = 0,
    Partial = 1,
    Full = 2,
}

pub(crate) struct RenderState {
    pub options: RenderOptions,
    stats: RenderStats,
    pub surfaces: Surfaces,
    pub fonts: FontStore,
    pub viewbox: Viewbox,
    pub cached_viewbox: Viewbox,
    pub images: ImageStore,
    pub background_color: skia::Color,
    // Stack of nodes pending to be rendered.
    pending_nodes: Vec<NodeRenderState>,
    pub current_tile: Option<tiles::Tile>,
    pub sampling_options: skia::SamplingOptions,
    pub render_area: Rect,
    // render_area expanded by surface margins — used for visibility checks so that
    // shapes in the margin zone are rendered (needed for background blur sampling).
    pub render_area_with_margins: Rect,
    pub tile_viewbox: tiles::TileViewbox,
    pub tiles: tiles::TileHashMap,
    pub pending_tiles: PendingTiles,
    // nested_fills maintains a stack of group  fills that apply to nested shapes
    // without their own fill definitions. This is necessary because in SVG, a group's `fill`
    // can affect its child elements if they don't specify one themselves. If the planned
    // migration to remove group-level fills is completed, this code should be removed.
    // Frames contained in groups must reset this nested_fills stack pushing a new empty vector.
    pub nested_fills: Vec<Vec<Fill>>,
    pub nested_blurs: Vec<Option<Blur>>, // FIXME: why is this an option?
    pub cached_layer_blur: Option<Option<Blur>>,
    pub nested_shadows: Vec<Vec<Shadow>>,
    pub show_grid: Option<Uuid>,
    pub empty_grid_frame_ids: HashSet<Uuid>,
    pub rulers: RulerState,
    pub focus_mode: FocusMode,
    /// Viewer-only whitelist for fixed-scroll layer passes.
    pub include_filter: Option<HashSet<Uuid>>,
    /// Frame id passed as `base_object` for viewer renders; always traversed.
    pub viewer_render_root: Option<Uuid>,
    pub viewer_visible_set: Option<HashSet<Uuid>>,
    pub touched_ids: HashSet<Uuid>,
    /// Temporary flag used for off-screen passes (drop-shadow masks, filter surfaces, etc.)
    /// where we must render shapes without inheriting ancestor layer blurs. Toggle it through
    /// `with_nested_blurs_suppressed` to ensure it's always restored.
    pub ignore_nested_blurs: bool,
    /// Preview render mode - when true, uses simplified rendering for progressive loading
    pub preview_mode: bool,
    pub export_context: Option<(Rect, f32)>,
    /// True if the current tile had shapes assigned to it when we
    /// started rendering it. Lets us distinguish a genuinely empty
    /// tile (skip composite, just clear) from a tile whose walker
    /// finished its work in a previous PAF and is now being resumed
    /// (must composite to present the work). Reset when current_tile
    /// changes.
    pub current_tile_had_shapes: bool,
    /// When true, the next `start_render_loop` keeps the last presented `Target`
    /// pixels instead of clearing the canvas. Set after incremental shape updates
    /// (e.g. adding a rect) so the workspace stays visible while only affected
    /// tiles are re-rendered asynchronously.
    pub preserve_target_during_render: bool,
}

impl RenderState {
    pub fn try_new(width: i32, height: i32) -> Result<RenderState> {
        // This needs to be done once per WebGL context.
        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);

        let fonts = FontStore::try_new()?;
        let surfaces = Surfaces::try_new(
            (width, height),
            sampling_options,
            tiles::get_tile_dimensions(),
        )?;

        // This is used multiple times everywhere so instead of creating new instances every
        // time we reuse this one.

        let viewbox = Viewbox::new(width as f32, height as f32);
        let tiles = tiles::TileHashMap::new();
        let options = RenderOptions::default();

        Ok(Self {
            options,
            stats: RenderStats::new(),
            surfaces,
            fonts,
            viewbox,
            cached_viewbox: Viewbox::new(0., 0.),
            images: ImageStore::new(),
            background_color: skia::Color::TRANSPARENT,
            pending_nodes: vec![],
            current_tile: None,
            sampling_options,
            render_area: Rect::new_empty(),
            render_area_with_margins: Rect::new_empty(),
            tiles,
            tile_viewbox: tiles::TileViewbox::new_with_interest(
                &viewbox,
                options.dpr_viewport_interest_area_threshold,
            ),
            pending_tiles: PendingTiles::new(),
            nested_fills: vec![],
            nested_blurs: vec![],
            cached_layer_blur: None,
            nested_shadows: vec![],
            show_grid: None,
            empty_grid_frame_ids: HashSet::default(),
            rulers: RulerState::default(),
            focus_mode: FocusMode::new(),
            include_filter: None,
            viewer_render_root: None,
            viewer_visible_set: None,
            touched_ids: HashSet::default(),
            ignore_nested_blurs: false,
            preview_mode: false,
            export_context: None,
            current_tile_had_shapes: false,
            preserve_target_during_render: false,
            // backbuffer_crop_cache: HashMap::default(),
        })
    }

    /// Combines every visible layer blur currently active (ancestors + shape)
    /// into a single equivalent blur. Layer blur radii compound by adding their
    /// variances (σ² = radius²), so we:
    ///   1. Convert each blur radius into variance via `blur_variance`.
    ///   2. Sum all variances.
    ///   3. Convert the total variance back to a radius with `blur_from_variance`.
    ///
    /// This keeps blur math consistent everywhere we need to merge blur sources.
    fn combined_layer_blur(&mut self, shape_blur: Option<Blur>) -> Option<Blur> {
        layer_blur::combined_layer_blur(&self.nested_blurs, &mut self.cached_layer_blur, shape_blur)
    }

    fn frame_clip_layer_blur(shape: &Shape) -> Option<Blur> {
        layer_blur::frame_clip_layer_blur(shape)
    }

    /// Renders background blur effect directly to the given target surface.
    /// Must be called BEFORE any save_layer for the shape's own opacity/blend,
    /// so that the backdrop blur is independent of the shape's visual properties.
    fn render_background_blur(&mut self, shape: &Shape, target_surface: SurfaceId) {
        background_blur::render_background_blur(self, shape, target_surface)
    }

    /// Runs `f` with `ignore_nested_blurs` temporarily forced to `true`.
    /// Certain off-screen passes (e.g. shadow masks) must render shapes without
    /// inheriting ancestor blur. This helper guarantees the flag is restored.
    fn with_nested_blurs_suppressed<F, R>(&mut self, f: F) -> Result<R>
    where
        F: FnOnce(&mut RenderState) -> Result<R>,
    {
        let previous = self.ignore_nested_blurs;
        self.ignore_nested_blurs = true;
        let result = f(self)?;
        self.ignore_nested_blurs = previous;
        Ok(result)
    }

    pub fn fonts(&self) -> &FontStore {
        &self.fonts
    }

    pub fn fonts_mut(&mut self) -> &mut FontStore {
        &mut self.fonts
    }

    pub fn add_image(&mut self, id: Uuid, is_thumbnail: bool, image_data: &[u8]) -> Result<()> {
        self.images.add(id, is_thumbnail, image_data)
    }

    /// Adds an image from an existing WebGL texture, avoiding re-decoding
    pub fn add_image_from_gl_texture(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        texture_id: u32,
        width: i32,
        height: i32,
    ) -> Result<()> {
        self.images
            .add_image_from_gl_texture(id, is_thumbnail, texture_id, width, height)
    }

    pub fn has_image(&self, id: &Uuid, is_thumbnail: bool) -> bool {
        self.images.contains(id, is_thumbnail)
    }

    pub fn set_debug_flags(&mut self, debug: u32) {
        self.options.flags = debug;
    }

    pub fn set_dpr(&mut self, dpr: f32) -> Result<()> {
        // Only when this function returns true (it means the value
        // was properly changed) the rest of the functions is called.
        if self.options.set_dpr(dpr) {
            self.tile_viewbox
                .set_interest(self.options.dpr_viewport_interest_area_threshold);
            self.resize(
                self.viewbox.width().floor() as i32,
                self.viewbox.height().floor() as i32,
            )?;
            self.fonts.set_scale_debug_font(dpr);
            self.viewbox.set_dpr(dpr);
            self.surfaces.set_dpr(dpr);
        }
        Ok(())
    }

    pub fn set_antialias_threshold(&mut self, value: f32) {
        self.options.set_antialias_threshold(value);
    }

    pub fn set_viewport_interest_area_threshold(&mut self, value: i32) {
        // Only when this function returns true (it means the value
        // was changed properly) the tile_viewbox.set_interest is called.
        if self.options.set_viewport_interest_area_threshold(value) {
            // The TileViewbox stores its own copy of `interest` (set at
            // construction). Without propagating, options change wouldn't
            // affect pending_tiles generation.
            self.tile_viewbox
                .set_interest(self.options.dpr_viewport_interest_area_threshold);
        }
    }

    pub fn set_node_batch_threshold(&mut self, value: i32) {
        self.options.set_node_batch_threshold(value);
    }

    pub fn set_max_blocking_time_ms(&mut self, value: i32) {
        self.options.set_max_blocking_time_ms(value);
    }

    pub fn set_blur_downscale_threshold(&mut self, value: f32) {
        self.options.set_blur_downscale_threshold(value);
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.background_color = color;
    }

    pub fn set_preview_mode(&mut self, enabled: bool) {
        self.preview_mode = enabled;
    }

    pub fn resize(&mut self, width: i32, height: i32) -> Result<()> {
        let dpr_width = (width as f32 * self.options.dpr).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr).floor() as i32;
        self.surfaces.resize(dpr_width, dpr_height)?;
        self.viewbox.set_wh(width as f32, height as f32);
        self.tile_viewbox.update(&self.viewbox);

        Ok(())
    }

    pub fn flush_and_submit(&mut self) {
        self.surfaces.flush_and_submit(SurfaceId::Target);
    }

    /// Copy the clean (no UI overlay) Backbuffer to Target, draw UI/debug overlays
    /// on top of Target, then present. Backbuffer is left clean so it can be reused
    /// as-is across interactive-transform frames without stale overlay pixels.
    pub fn present_frame(&mut self, tree: ShapesPoolRef) {
        // Viewer masked passes render a partial scene onto a transparent backbuffer.
        // SrcOver would keep pass-1 pixels wherever the backbuffer stays transparent.
        if self.viewer_masked_pass() {
            self.surfaces.clear_target(skia::Color::TRANSPARENT);
            self.surfaces.copy_backbuffer_to_target_replace();
        } else {
            self.surfaces
                .copy_backbuffer_to_target(self.background_color);
        }

        if self.options.is_debug_visible() {
            debug::render(self);
        }
        if !self.preview_mode {
            ui::render(self, tree);
        }
        debug::render_wasm_label(self);
        self.surfaces.flush_and_submit(SurfaceId::Target);
    }

    /// Renders only the canvas background and UI surface (rulers/frame), without
    /// rebuilding or drawing any shape tiles. Used to show the viewport frame
    /// immediately before shape tiles are built (e.g., right after a DPR change).
    pub fn render_ui_only(&mut self, tree: ShapesPoolRef) {
        self.surfaces
            .canvas(SurfaceId::Target)
            .clear(self.background_color);
        ui::render(self, tree);
        self.flush_and_submit();
    }

    /// Blurs the Backbuffer into Target and draws the rulers sharp on top, for
    /// capturing an already-blurred page-transition snapshot. `blur_radius` is in
    /// CSS pixels, scaled by DPR to match the device-resolution capture.
    pub fn render_blurred_snapshot(&mut self, tree: ShapesPoolRef, blur_radius: f32) {
        let sigma = (blur_radius * self.options.dpr).max(0.0);
        self.surfaces
            .canvas(SurfaceId::Target)
            .clear(self.background_color);

        let mut paint = skia::Paint::default();
        if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
            paint.set_image_filter(filter);
        }
        self.surfaces
            .draw_into(SurfaceId::Backbuffer, SurfaceId::Target, Some(&paint));
        ui::render(self, tree);
        self.surfaces.flush_and_submit(SurfaceId::Target);
    }

    pub fn reset_canvas(&mut self) {
        self.surfaces.reset(self.background_color);
        self.surfaces.clear_backbuffer(self.background_color);
        self.surfaces.clear_target(self.background_color);
    }

    /// Drop cached tile textures before a one-shot `render_sync_shape` render.
    pub fn prepare_sync_shape_render(&mut self) {
        self.surfaces.clear_tile_atlas();
        self.surfaces.invalidate_tile_cache();
    }

    /// NOTE:
    /// This is currently not being used, but it's set there for testing purposes on
    /// upcoming tasks
    pub fn render_loading_overlay(&mut self) {
        let canvas = self.surfaces.canvas(SurfaceId::Backbuffer);
        let skia::ISize { width, height } = canvas.base_layer_size();

        canvas.save();

        // Full-screen background rect
        let rect = skia::Rect::from_wh(width as f32, height as f32);
        let mut bg_paint = skia::Paint::default();
        bg_paint.set_color(self.background_color);
        bg_paint.set_style(skia::PaintStyle::Fill);
        canvas.draw_rect(rect, &bg_paint);

        // Centered "Loading…" text
        let mut text_paint = skia::Paint::default();
        text_paint.set_color(skia::Color::GRAY);
        text_paint.set_anti_alias(true);

        let font = self.fonts.debug_font();
        // FIXME
        let text = "Loading…";
        let (text_width, _) = font.measure_str(text, None);
        let metrics = font.metrics();
        let text_height = metrics.1.cap_height;
        let x = (width as f32 - text_width) / 2.0;
        let y = (height as f32 + text_height) / 2.0;
        canvas.draw_str(text, skia::Point::new(x, y), font, &text_paint);

        canvas.restore();
        self.flush_and_submit();
    }

    /// This function draws the "surface stack" into the specified "target" surface.
    pub fn draw_shape_surface_stack_into(&mut self, shape: Option<&Shape>, target: SurfaceId) {
        performance::begin_measure!("apply_drawing_to_render_canvas");

        let paint = skia::Paint::default();

        // Only draw surfaces that have content (dirty flag optimization)
        if self.surfaces.is_dirty(SurfaceId::TextDropShadows) {
            self.surfaces
                .draw_into(SurfaceId::TextDropShadows, target, Some(&paint));
        }

        if self.surfaces.is_dirty(SurfaceId::Fills) {
            self.surfaces
                .draw_into(SurfaceId::Fills, target, Some(&paint));
        }

        let mut render_overlay_below_strokes = false;
        if let Some(shape) = shape {
            render_overlay_below_strokes = shape.has_fills();
        }

        if render_overlay_below_strokes && self.surfaces.is_dirty(SurfaceId::InnerShadows) {
            self.surfaces
                .draw_into(SurfaceId::InnerShadows, target, Some(&paint));
        }

        if self.surfaces.is_dirty(SurfaceId::Strokes) {
            self.surfaces
                .draw_into(SurfaceId::Strokes, target, Some(&paint));
        }

        if !render_overlay_below_strokes && self.surfaces.is_dirty(SurfaceId::InnerShadows) {
            self.surfaces
                .draw_into(SurfaceId::InnerShadows, target, Some(&paint));
        }

        if self.surfaces.is_dirty(SurfaceId::DropShadows) {
            self.surfaces
                .draw_into(SurfaceId::DropShadows, target, Some(&paint));
        }

        // Build mask of dirty surfaces that need clearing
        let mut dirty_surfaces_to_clear = 0u32;
        if self.surfaces.is_dirty(SurfaceId::Strokes) {
            dirty_surfaces_to_clear |= SurfaceId::Strokes as u32;
        }
        if self.surfaces.is_dirty(SurfaceId::Fills) {
            dirty_surfaces_to_clear |= SurfaceId::Fills as u32;
        }
        if self.surfaces.is_dirty(SurfaceId::InnerShadows) {
            dirty_surfaces_to_clear |= SurfaceId::InnerShadows as u32;
        }
        if self.surfaces.is_dirty(SurfaceId::TextDropShadows) {
            dirty_surfaces_to_clear |= SurfaceId::TextDropShadows as u32;
        }
        if self.surfaces.is_dirty(SurfaceId::DropShadows) {
            dirty_surfaces_to_clear |= SurfaceId::DropShadows as u32;
        }

        if dirty_surfaces_to_clear != 0 {
            self.surfaces.apply_mut(dirty_surfaces_to_clear, |s| {
                s.canvas().clear(skia::Color::TRANSPARENT);
            });
            // Clear dirty flags for surfaces we just cleared
            self.surfaces.clear_dirty(dirty_surfaces_to_clear);
        }
    }

    pub fn clear_focus_mode(&mut self) {
        self.focus_mode.clear();
    }

    pub fn set_focus_mode(&mut self, shapes: Vec<Uuid>) {
        self.focus_mode.set_shapes(shapes);
    }

    pub fn clear_include_filter(&mut self) {
        self.include_filter = None;
    }

    pub fn set_include_filter(&mut self, shapes: Vec<Uuid>) {
        self.include_filter = Some(shapes.into_iter().collect());
    }

    fn viewer_masked_pass(&self) -> bool {
        view_mode::viewer_masked_pass(&self.include_filter)
    }

    fn get_inherited_drop_shadows(&self) -> Option<Vec<skia_safe::Paint>> {
        let drop_shadows: Vec<skia_safe::Paint> = self
            .nested_shadows
            .iter()
            .flat_map(|shadows| shadows.iter())
            .filter(|shadow| !shadow.hidden() && shadow.style() == crate::shapes::ShadowStyle::Drop)
            .map(|shadow| {
                let mut paint = skia_safe::Paint::default();
                let filter = shadow.get_drop_shadow_filter();
                paint.set_image_filter(filter);
                paint
            })
            .collect();

        if drop_shadows.is_empty() {
            None
        } else {
            Some(drop_shadows)
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn render_shape(
        &mut self,
        shape: &Shape,
        clip_bounds: Option<ClipStack>,
        fills_surface_id: SurfaceId,
        strokes_surface_id: SurfaceId,
        innershadows_surface_id: SurfaceId,
        text_drop_shadows_surface_id: SurfaceId,
        apply_to_current_surface: bool,
        offset: Option<(f32, f32)>,
        parent_shadows: Option<Vec<skia_safe::Paint>>,
        outset: Option<f32>,
        target_surface: SurfaceId,
    ) -> Result<()> {
        #[cfg(feature = "stats")]
        self.stats.count(shape.id);

        let surface_ids = fills_surface_id as u32
            | strokes_surface_id as u32
            | innershadows_surface_id as u32
            | text_drop_shadows_surface_id as u32;

        // Only save canvas state if we have clipping or transforms
        // For simple shapes without clipping, skip expensive save/restore
        let needs_save =
            clip_bounds.is_some() || offset.is_some() || !shape.transform.is_identity();

        if needs_save {
            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas().save();
            });
        }
        // let fast_mode = self.options.is_fast_mode();
        // Skip anti-aliasing entirely during fast_mode (interactive
        // gestures + pan/zoom). AA edge sampling is per-pixel and adds
        // up across many shapes; reverts to full quality on commit.
        let antialias = true;
            // && shape.should_use_antialias(self.get_scale_fast(), self.options.antialias_threshold);
        let skip_effects = false;

        let has_nested_fills = self
            .nested_fills
            .last()
            .is_some_and(|fills| !fills.is_empty());

        let has_inherited_blur = !self.ignore_nested_blurs
            && self.nested_blurs.iter().flatten().any(|blur| {
                !blur.hidden && blur.blur_type == BlurType::LayerBlur && blur.value > 0.0
            });

        let can_render_directly = apply_to_current_surface
            && clip_bounds.is_none()
            && offset.is_none()
            && parent_shadows.is_none()
            && !shape.needs_layer()
            && shape.blur.is_none()
            && shape.background_blur.is_none()
            && !has_inherited_blur
            && shape.shadows.is_empty()
            && shape.transform.is_identity()
            && matches!(
                shape.shape_type,
                Type::Rect(_) | Type::Circle | Type::Path(_) | Type::Bool(_)
            )
            && !(shape.fills.is_empty() && has_nested_fills)
            && !shape
                .svg_attrs
                .as_ref()
                .is_some_and(|attrs| attrs.fill_none)
            && target_surface != SurfaceId::Export;

        if can_render_directly {
            let scale = self.get_scale_fast();
            let translation = self
                .surfaces
                .get_render_context_translation(self.render_area, scale);

            self.surfaces.apply_mut(target_surface as u32, |s| {
                let canvas = s.canvas();
                canvas.save();
                canvas.scale((scale, scale));
                canvas.translate(translation);
            });

            fills::render(self, shape, &shape.fills, antialias, target_surface, None)?;
            // Pass strokes in natural order; stroke merging handles top-most ordering internally.
            let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
            strokes::render(
                self,
                shape,
                &visible_strokes,
                Some(target_surface),
                antialias,
                outset,
            )?;

            self.surfaces.apply_mut(target_surface as u32, |s| {
                s.canvas().restore();
            });

            if self.options.is_debug_visible() {
                let shape_selrect_bounds = self.get_shape_selrect_bounds(shape);
                debug::render_debug_shape(self, Some(shape_selrect_bounds), None);
            }

            if needs_save {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().restore();
                });
            }
            return Ok(());
        }

        // set clipping
        if let Some(clips) = clip_bounds.as_ref() {
            let scale = self.get_scale_fast();
            for (mut bounds, corners, transform, _inverse_transform) in clips.iter() {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(transform);
                });

                // Outset clip by ~0.5 to include edge pixels that
                // aliased clip misclassifies as outside (causing artifacts).
                let outset = 0.5 / scale;
                bounds.outset((outset, outset));

                // Hard clip edge (antialias = false) to avoid alpha seam when clipping
                // semi-transparent content larger than the frame.
                if let Some(corners) = corners {
                    let rrect = RRect::new_rect_radii(bounds, corners);
                    self.surfaces.apply_mut(surface_ids, |s| {
                        s.canvas().clip_rrect(rrect, skia::ClipOp::Intersect, false);
                    });
                } else {
                    self.surfaces.apply_mut(surface_ids, |s| {
                        s.canvas().clip_rect(bounds, skia::ClipOp::Intersect, false);
                    });
                }

                // This renders a red line around clipped
                // shapes (frames).
                if self.options.is_debug_visible() {
                    let mut paint = skia::Paint::default();
                    paint.set_style(skia::PaintStyle::Stroke);
                    paint.set_color(skia::Color::from_argb(255, 255, 0, 0));
                    paint.set_stroke_width(4.);
                    self.surfaces
                        .canvas(fills_surface_id)
                        .draw_rect(bounds, &paint);
                }

                // Uncomment to debug the render_position_data
                // if let Type::Text(text_content) = &shape.shape_type {
                //     text::render_position_data(self, fills_surface_id, &shape, text_content);
                // }

                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas()
                        .concat(&transform.invert().unwrap_or(Matrix::default()));
                });
            }
        }

        // We don't want to change the value in the global state
        let mut shape: Cow<Shape> = Cow::Borrowed(shape);
        let shape = shape.to_mut();

        // Background blur is stored separately (shape.background_blur) and is
        // rendered before the save_layer in render_background_blur(), so here
        // shape.blur only ever holds a layer blur.

        let frame_has_blur = Self::frame_clip_layer_blur(shape).is_some();
        let shape_has_blur = shape.blur.is_some();

        if self.ignore_nested_blurs {
            if frame_has_blur && shape_has_blur {
                shape.set_blur(None);
            }
        } else if !frame_has_blur {
            if let Some(blur) = self.combined_layer_blur(shape.blur) {
                shape.set_blur(Some(blur));
            }
        } else if shape_has_blur {
            shape.set_blur(None);
        }
        if skip_effects {
            shape.set_blur(None);
        }

        // For non-text, non-SVG shapes in the normal rendering path, apply blur
        // via a single save_layer on each render surface
        // Clip correctness is preserved
        let blur_sigma_for_layers: Option<f32> = if !skip_effects
            && apply_to_current_surface
            && fills_surface_id == SurfaceId::Fills
            && !matches!(shape.shape_type, Type::Text(_))
            && !matches!(shape.shape_type, Type::SVGRaw(_))
        {
            if let Some(blur) = shape.blur.filter(|b| !b.hidden) {
                shape.set_blur(None);
                Some(blur.sigma())
            } else {
                None
            }
        } else {
            None
        };

        let center = shape.center();
        let mut matrix = shape.transform;
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        // Apply the additional transformation matrix if exists
        if let Some(offset) = offset {
            matrix.pre_translate(offset);
        }

        match &shape.shape_type {
            Type::SVGRaw(sr) => {
                if let Some(svg_transform) = shape.svg_transform() {
                    matrix.pre_concat(&svg_transform);
                }

                self.surfaces
                    .canvas_and_mark_dirty(fills_surface_id)
                    .concat(&matrix);

                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.canvas_and_mark_dirty(fills_surface_id));
                } else {
                    let font_manager = skia::FontMgr::from(self.fonts().font_provider().clone());
                    let dom_result = skia::svg::Dom::from_str(&sr.content, font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.canvas_and_mark_dirty(fills_surface_id));
                            shape.set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }

            Type::Text(stored_text_content) => {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

                // Skip the paragraph-cloning `new_bounds` when shape size is unchanged.
                let selrect = shape.selrect();
                let stored_bounds = stored_text_content.bounds();
                let bounds_match = (stored_bounds.width() - selrect.width()).abs() < 0.01
                    && (stored_bounds.height() - selrect.height()).abs() < 0.01;
                let rebound_text_content = if bounds_match {
                    None
                } else {
                    Some(stored_text_content.new_bounds(selrect))
                };
                let text_content: &TextContent =
                    rebound_text_content.as_ref().unwrap_or(stored_text_content);
                let count_inner_strokes = shape.count_visible_inner_strokes();
                // Erode the main text fill by 1px when there are inner strokes, to avoid a visible seam at the glyph edge.
                let text_fill_inset =
                    (count_inner_strokes > 0).then(|| 1.0 / self.get_scale_fast());
                let text_stroke_blur_outset =
                    Stroke::max_bounds_width(shape.visible_strokes(), false);
                let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
                let stroke_kinds: Vec<StrokeKind> =
                    shape.visible_strokes().rev().map(|s| s.kind).collect();
                let (mut stroke_paragraphs_list, stroke_opacities): (Vec<_>, Vec<_>) = shape
                    .visible_strokes()
                    .rev()
                    .map(|stroke| {
                        text::stroke_paragraph_builder_group_from_text(
                            text_content,
                            stroke,
                            &shape.selrect(),
                            None,
                        )
                    })
                    .unzip();
                if skip_effects {
                    // Fast path: render fills and strokes only (skip shadows/blur).
                    text::render(
                        Some(self),
                        None,
                        shape,
                        &mut paragraph_builders,
                        Some(fills_surface_id),
                        None,
                        None,
                        text_fill_inset,
                        None,
                    )?;

                    for (i, (stroke_paragraphs, layer_opacity)) in stroke_paragraphs_list
                        .iter_mut()
                        .zip(stroke_opacities.iter())
                        .enumerate()
                    {
                        if stroke_kinds[i] == StrokeKind::Inner {
                            let mut mask_builders = text_content.paragraph_builder_group_opaque();
                            let mut fill_builders =
                                text_content.paragraph_builder_group_from_text(None);
                            text::render_inner_stroke(
                                Some(self),
                                None,
                                shape,
                                &mut mask_builders,
                                stroke_paragraphs,
                                &mut fill_builders,
                                Some(strokes_surface_id),
                                None,
                                text_stroke_blur_outset,
                                *layer_opacity,
                            )?;
                        } else {
                            text::render_with_bounds_outset(
                                Some(self),
                                None,
                                shape,
                                stroke_paragraphs,
                                Some(strokes_surface_id),
                                None,
                                None,
                                text_stroke_blur_outset,
                                None,
                                *layer_opacity,
                            )?;
                        }
                    }
                } else {
                    let mut drop_shadows = shape.drop_shadow_paints();

                    if let Some(inherited_shadows) = self.get_inherited_drop_shadows() {
                        drop_shadows.extend(inherited_shadows);
                    }

                    let inner_shadows = shape.inner_shadow_paints();
                    let blur_filter = shape.image_filter(1.);
                    let mut paragraphs_with_shadows =
                        text_content.paragraph_builder_group_from_text(Some(true));
                    let (mut stroke_paragraphs_with_shadows_list, _shadow_opacities): (
                        Vec<_>,
                        Vec<_>,
                    ) = shape
                        .visible_strokes()
                        .rev()
                        .map(|stroke| {
                            text::stroke_paragraph_builder_group_from_text(
                                text_content,
                                stroke,
                                &shape.selrect(),
                                Some(true),
                            )
                        })
                        .unzip();

                    if let Some(parent_shadows) = parent_shadows {
                        if !shape.has_visible_strokes() {
                            for shadow in parent_shadows {
                                text::render(
                                    Some(self),
                                    None,
                                    shape,
                                    &mut paragraphs_with_shadows,
                                    text_drop_shadows_surface_id.into(),
                                    Some(&shadow),
                                    blur_filter.as_ref(),
                                    None,
                                    None,
                                )?;
                            }
                        } else {
                            shadows::render_text_shadows(
                                self,
                                shape,
                                &mut paragraphs_with_shadows,
                                &mut stroke_paragraphs_with_shadows_list,
                                text_drop_shadows_surface_id.into(),
                                &parent_shadows,
                                &blur_filter,
                                &stroke_kinds,
                                text_content,
                            )?;
                        }
                    } else {
                        // 1. Text drop shadows
                        if !shape.has_visible_strokes() {
                            for shadow in &drop_shadows {
                                text::render(
                                    Some(self),
                                    None,
                                    shape,
                                    &mut paragraphs_with_shadows,
                                    text_drop_shadows_surface_id.into(),
                                    Some(shadow),
                                    blur_filter.as_ref(),
                                    None,
                                    None,
                                )?;
                            }
                        }

                        // 2. Text fills
                        text::render(
                            Some(self),
                            None,
                            shape,
                            &mut paragraph_builders,
                            Some(fills_surface_id),
                            None,
                            blur_filter.as_ref(),
                            text_fill_inset,
                            None,
                        )?;

                        // 3. Stroke drop shadows
                        shadows::render_text_shadows(
                            self,
                            shape,
                            &mut paragraphs_with_shadows,
                            &mut stroke_paragraphs_with_shadows_list,
                            text_drop_shadows_surface_id.into(),
                            &drop_shadows,
                            &blur_filter,
                            &stroke_kinds,
                            text_content,
                        )?;

                        // 4. Stroke fills
                        for (i, (stroke_paragraphs, layer_opacity)) in stroke_paragraphs_list
                            .iter_mut()
                            .zip(stroke_opacities.iter())
                            .enumerate()
                        {
                            if stroke_kinds[i] == StrokeKind::Inner {
                                let mut mask_builders =
                                    text_content.paragraph_builder_group_opaque();
                                let mut fill_builders =
                                    text_content.paragraph_builder_group_from_text(None);
                                text::render_inner_stroke(
                                    Some(self),
                                    None,
                                    shape,
                                    &mut mask_builders,
                                    stroke_paragraphs,
                                    &mut fill_builders,
                                    Some(strokes_surface_id),
                                    blur_filter.as_ref(),
                                    text_stroke_blur_outset,
                                    *layer_opacity,
                                )?;
                            } else {
                                text::render_with_bounds_outset(
                                    Some(self),
                                    None,
                                    shape,
                                    stroke_paragraphs,
                                    Some(strokes_surface_id),
                                    None,
                                    blur_filter.as_ref(),
                                    text_stroke_blur_outset,
                                    None,
                                    *layer_opacity,
                                )?;
                            }
                        }

                        // 5. Stroke inner shadows
                        shadows::render_text_shadows(
                            self,
                            shape,
                            &mut paragraphs_with_shadows,
                            &mut stroke_paragraphs_with_shadows_list,
                            Some(innershadows_surface_id),
                            &inner_shadows,
                            &blur_filter,
                            &stroke_kinds,
                            text_content,
                        )?;

                        // 6. Fill Inner shadows
                        if !shape.has_visible_strokes() {
                            for shadow in &inner_shadows {
                                text::render(
                                    Some(self),
                                    None,
                                    shape,
                                    &mut paragraphs_with_shadows,
                                    Some(innershadows_surface_id),
                                    Some(shadow),
                                    blur_filter.as_ref(),
                                    None,
                                    None,
                                )?;
                            }
                        }
                    }
                }
            }
            _ => {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

                // Wrap ALL fill/stroke/shadow rendering so a single GPU blur pass calls
                let blur_filter_for_layers: Option<skia::ImageFilter> = blur_sigma_for_layers
                    .and_then(|sigma| skia::image_filters::blur((sigma, sigma), None, None, None));
                if let Some(ref filter) = blur_filter_for_layers {
                    let mut layer_paint = skia::Paint::default();
                    layer_paint.set_image_filter(filter.clone());
                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&layer_paint);
                    self.surfaces
                        .canvas(fills_surface_id)
                        .save_layer(&layer_rec);
                    self.surfaces
                        .canvas(strokes_surface_id)
                        .save_layer(&layer_rec);
                    self.surfaces
                        .canvas(innershadows_surface_id)
                        .save_layer(&layer_rec);
                }

                let shape = &shape;

                if shape.fills.is_empty()
                    && !matches!(shape.shape_type, Type::Group(_))
                    && !matches!(shape.shape_type, Type::Frame(_))
                    && !shape
                        .svg_attrs
                        .as_ref()
                        .is_some_and(|attrs| attrs.fill_none)
                {
                    if let Some(fills_to_render) = self.nested_fills.last() {
                        let fills_to_render = fills_to_render.clone();
                        fills::render(
                            self,
                            shape,
                            &fills_to_render,
                            antialias,
                            fills_surface_id,
                            outset,
                        )?;
                    }
                } else {
                    fills::render(
                        self,
                        shape,
                        &shape.fills,
                        antialias,
                        fills_surface_id,
                        outset,
                    )?;
                }

                // Skip stroke rendering for clipped frames - they are drawn in render_shape_exit
                // over the children. Drawing twice would cause incorrect opacity blending.
                let skip_strokes = matches!(shape.shape_type, Type::Frame(_)) && shape.clip_content;
                if !skip_strokes {
                    // Pass strokes in natural order; stroke merging handles top-most ordering internally.
                    let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
                    strokes::render(
                        self,
                        shape,
                        &visible_strokes,
                        Some(strokes_surface_id),
                        antialias,
                        outset,
                    )?;
                    if !skip_effects {
                        for stroke in &visible_strokes {
                            shadows::render_stroke_inner_shadows(
                                self,
                                shape,
                                stroke,
                                antialias,
                                innershadows_surface_id,
                            )?;
                        }
                    }
                }

                if !skip_effects {
                    shadows::render_fill_inner_shadows(
                        self,
                        shape,
                        antialias,
                        innershadows_surface_id,
                    );
                }

                if blur_filter_for_layers.is_some() {
                    self.surfaces.canvas(innershadows_surface_id).restore();
                    self.surfaces.canvas(strokes_surface_id).restore();
                    self.surfaces.canvas(fills_surface_id).restore();
                }
            }
        };

        if self.options.is_debug_visible() {
            let shape_selrect_bounds = self.get_shape_selrect_bounds(shape);
            debug::render_debug_shape(self, Some(shape_selrect_bounds), None);
        }

        if apply_to_current_surface {
            self.draw_shape_surface_stack_into(Some(shape), target_surface);
        }

        // Only restore if we saved (optimization for simple shapes)
        if needs_save {
            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas().restore();
            });
        }
        Ok(())
    }

    pub fn update_render_context(&mut self, tile: tiles::Tile) {
        self.current_tile = Some(tile);
        let scale = self.get_scale();
        self.render_area = tiles::get_tile_rect(tile, scale);
        let margins = self.surfaces.margins();
        let margin_w = margins.width as f32 / scale;
        let margin_h = margins.height as f32 / scale;
        self.render_area_with_margins = skia::Rect::from_ltrb(
            self.render_area.left - margin_w,
            self.render_area.top - margin_h,
            self.render_area.right + margin_w,
            self.render_area.bottom + margin_h,
        );
        self.surfaces.update_render_context(self.render_area, scale);
    }

    // fn rebuild_backbuffer_crop_cache(&mut self, tree: ShapesPoolRef) {
    //     drag_crop::rebuild_backbuffer_crop_cache(self, tree)
    // }

    // pub fn render_from_cache(&mut self, shapes: ShapesPoolRef) {
    //     cache::render_from_cache(self, shapes)
    // }

    /// Render a preview of the shapes during loading.
    /// This rebuilds tiles for touched shapes and renders synchronously.
    pub fn render_preview(&mut self, tree: ShapesPoolRef, timestamp: i32) -> Result<()> {
        let _start = performance::begin_timed_log!("render_preview");
        performance::begin_measure!("render_preview");

        // Enable fast_mode during preview to skip expensive effects (blur, shadows).
        // Restore the previous state afterward so the final render is full quality.
        // let current_fast_mode = self.options.is_fast_mode();
        // self.options.set_fast_mode(true);

        // Skip tile rebuilding during preview - we'll do it at the end
        // Just rebuild tiles for touched shapes and render synchronously
        self.rebuild_touched_tiles(tree);

        // Use the sync render path
        self.start_render_loop(None, tree, timestamp, true)?;

        // self.options.set_fast_mode(current_fast_mode);

        performance::end_measure!("render_preview");
        performance::end_timed_log!("render_preview", _start);

        Ok(())
    }

    /// Clears all the necessary vecs and hashmaps.
    /// Also garbage collects surfaces.
    fn clear(&mut self, tree: ShapesPoolRef) {
        #[cfg(feature = "stats")]
        self.stats.clear();

        self.surfaces.gc();

        self.pending_nodes.clear();
        self.pending_nodes.reserve(tree.len());

        // Clear nested state stacks to avoid residual fills/blurs from previous renders
        // being incorrectly applied to new frames
        self.nested_fills.clear();
        self.nested_blurs.clear();
        self.cached_layer_blur = None;
        self.nested_shadows.clear();

        // reorder by distance to the center.
        self.current_tile = None;

        self.empty_grid_frame_ids.clear();
        if self.show_grid.is_some() {
            for shape in tree.iter() {
                if shape.id.is_nil() || !shape.children.is_empty() {
                    continue;
                }
                if let Type::Frame(frame) = &shape.shape_type {
                    if matches!(frame.layout, Some(Layout::GridLayout(_, _))) && !shape.deleted() {
                        self.empty_grid_frame_ids.insert(shape.id);
                    }
                }
            }
        }
    }

    pub fn start_render_loop(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        sync_render: bool,
    ) -> Result<FrameType> {
        self.clear(tree);
        view_mode::precompute_viewer_visible_set(self, tree);

        let _start = performance::begin_timed_log!("start_render_loop");
        let scale = self.get_scale();

        self.tile_viewbox.update(&self.viewbox);
        self.focus_mode.reset();

        // render_state.tile_viewbox.update(&render_state.viewbox);

        // render_state.rebuild_tile_index(&state.shapes);
        // if render_state.zoom_changed() {
        //     render_state.surfaces.invalidate_tile_cache();
        // }

        performance::begin_measure!("render");
        performance::begin_measure!("start_render_loop");

        let preserve_target = self.preserve_target_during_render;
        self.preserve_target_during_render = false;

        // if self.options.is_interactive_transform() {
        //     // Keep `Target` as the previous frame and overwrite only the tiles
        //     // that changed. This avoids clearing + redrawing an atlas backdrop
        //     // every rAF during drag (a common source of GPU work/stalls).
        //     self.surfaces
        //         .reset_interactive_transform(self.background_color);
        //     if !self.interactive_target_seeded {
        //         // Seed from the last presented frame; this is stable even when
        //         // fast_mode skips cache updates and regardless of atlas coverage.
        //         self.interactive_target_seeded = true;
        //     }
        // } else if preserve_target || self.zoom_changed() {
        //     // Shape updates or zoom-end: keep the last presented frame on screen
        //     // while tiles are re-rendered asynchronously. During zoom the
        //     // preview from render_from_cache stays visible until the full-
        //     // quality pass completes.
        //     self.surfaces
        //         .reset_interactive_transform(self.background_color);
        //     self.surfaces.seed_backbuffer_from_target();
        //     self.interactive_target_seeded = false;
        // } else {
            // self.reset_canvas();
            // self.interactive_target_seeded = false;
            // // Paint rulers/frame now so they survive the progressive frames
            // // instead of blanking until the first full `present_frame`.
            // // Skip on sync renders (thumbnails/exports)
            // if !sync_render {
            //     ui::render(self, tree);
            //     self.flush_and_submit();
            // }
        // }

        // Viewer fixed-scroll passes reuse the same WASM context; `reset` does not
        // clear Backbuffer, so pass 2 would otherwise keep pass-1 pixels in regions
        // that render no shapes for the current mask. Target is cleared in present_frame.
        if self.viewer_masked_pass() {
            view_mode::reset_viewer_masked_surfaces(self);
        }

        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32;

        // NOTE: Why we're scaling in here these surfaces?
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().scale((scale, scale));
        });

        // self.surfaces.resize_cache_from_viewbox(
        //     &self.viewbox,
        //     &self.cached_viewbox,
        //     self.options.dpr_viewport_interest_area_threshold,
        // )?;

        // FIXME - review debug
        // debug::render_debug_tiles_for_viewbox(self);

        let _tile_start = performance::begin_timed_log!("tile_cache_update");

        performance::begin_measure!("tile_cache");
        let only_visible = self.options.is_interactive_transform();
        self.pending_tiles
            .update(&self.tile_viewbox, &self.surfaces, only_visible);
        performance::end_measure!("tile_cache");

        performance::end_timed_log!("tile_cache_update", _tile_start);

        // self.draw_shape_surface_stack_into(None, SurfaceId::Current);

        #[allow(unused)]
        let mut frame_type = FrameType::None;
        if sync_render {
            frame_type = self.render_shape_tree_sync(base_object, tree, timestamp)?;
        } else {
            // Keep progressive yielding, except for a localized shape edit on a
            // stable viewbox (e.g. recoloring) which renders in one frame.
            let allow_stop =
                !preserve_target || self.zoom_changed() || self.options.is_interactive_transform();
            frame_type = self.continue_render_loop(base_object, tree, timestamp, allow_stop)?;

            // This is an option to debug frames.
            if self.options.capture_frames > 0 {
                self.options.capture_frames -= 1;
            }
        }

        performance::end_measure!("start_render_loop");
        performance::end_timed_log!("start_render_loop", _start);
        Ok(frame_type)
    }

    // fn compute_document_bounds(
    //     &mut self,
    //     base_object: Option<&Uuid>,
    //     tree: ShapesPoolRef,
    // ) -> Option<skia::Rect> {
    //     let ids: Vec<Uuid> = if let Some(id) = base_object {
    //         vec![*id]
    //     } else {
    //         let root = tree.get(&Uuid::nil())?;
    //         root.children_ids(false)
    //     };

    //     let mut acc: Option<skia::Rect> = None;
    //     for id in ids.iter() {
    //         let Some(shape) = tree.get(id) else {
    //             continue;
    //         };
    //         let r = shape.extrect(tree, 1.0);
    //         if r.is_empty() {
    //             continue;
    //         }
    //         acc = Some(if let Some(mut a) = acc {
    //             a.join(r);
    //             a
    //         } else {
    //             r
    //         });
    //     }
    //     acc
    // }

    pub fn continue_render_loop(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        allow_stop: bool,
    ) -> Result<FrameType> {
        performance::begin_measure!("continue_render_loop");
        let frame_type =
            self.render_shape_tree_partial(base_object, tree, timestamp, allow_stop)?;

        // `draw_atlas` needs a snapshot of the tile atlas. Partial frames are not
        // presented (only flushed), so defer composition to the final frame and
        // avoid re-snapshotting up to 4096² on every rAF during async tile work.
        // if !self.options.is_interactive_transform() && matches!(frame_type, FrameType::Full) {
            self.surfaces.draw_tile_atlas_to_backbuffer(
                &self.viewbox,
                &self.tile_viewbox,
                self.background_color,
            );
        // }

        match frame_type {
            FrameType::None => {
                panic!("FrameType::None");
            }
            FrameType::Partial => {
                self.present_frame(tree);
            }
            FrameType::Full => {
                self.present_frame(tree);
                wapi::notify_tiles_render_complete!();
                performance::end_measure!("render");
            }
        }
        performance::end_measure!("continue_render_loop");
        Ok(frame_type)
    }

    pub fn render_shape_tree_sync(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<FrameType> {
        self.render_shape_tree_partial(base_object, tree, timestamp, false)?;

        // Same composition as `continue_render_loop` for full frames: snapshot only the
        // drawable tile rect into the atlas (no blur-margin overlap), then blit once.
        if !self.viewer_masked_pass() {
            self.surfaces.draw_tile_atlas_to_backbuffer(
                &self.viewbox,
                &self.tile_viewbox,
                self.background_color,
            );
        }

        let saved_preview_mode = self.preview_mode;
        self.preview_mode = true;
        self.present_frame(tree);
        self.preview_mode = saved_preview_mode;
        Ok(FrameType::Full)
    }

    pub fn render_shape_pixels(
        &mut self,
        id: &Uuid,
        tree: ShapesPoolRef,
        scale: f32,
        timestamp: i32,
    ) -> Result<(Vec<u8>, i32, i32)> {
        export::render_shape_pixels(self, id, tree, scale, timestamp)
    }

    #[inline]
    pub fn should_stop_rendering(&self, iteration: i32, timestamp: i32) -> bool {
        if iteration % self.options.node_batch_threshold != 0 {
            return false;
        }
        if performance::get_time() - timestamp <= self.options.max_blocking_time_ms {
            return false;
        }

        // During interactive shape transforms we must complete every
        // visible tile in a single rAF so the user never sees tiles
        // popping in sequentially. Only yield once all visible work is
        // done and we are processing the interest-area pre-render.
        if self.options.is_interactive_transform() {
            if let Some(tile) = self.current_tile {
                if self.tile_viewbox.is_visible(&tile) {
                    return false;
                }
            }
        }

        true
    }

    #[inline]
    fn clip_target_surface_to_stack(
        &mut self,
        clips: &ClipStack,
        target_surface: SurfaceId,
        scale: f32,
        antialias: bool,
    ) {
        let translation = self
            .surfaces
            .get_render_context_translation(self.render_area, scale);

        for (bounds, corners, transform, inverse_transform) in clips.iter() {
            let mut total_matrix = Matrix::new_identity();
            if target_surface == SurfaceId::Export {
                let Some((export_rect, export_scale)) = self.export_context else {
                    continue;
                };
                total_matrix.pre_scale((export_scale, export_scale), None);
                total_matrix.pre_translate((-export_rect.x(), -export_rect.y()));
            } else {
                total_matrix.pre_scale((scale, scale), None);
                total_matrix.pre_translate((translation.0, translation.1));
            }
            total_matrix.pre_concat(transform);

            let canvas = self.surfaces.canvas(target_surface);
            canvas.concat(&total_matrix);
            if let Some(corners) = corners {
                let rrect = RRect::new_rect_radii(*bounds, corners);
                canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
            } else {
                canvas.clip_rect(*bounds, skia::ClipOp::Intersect, antialias);
            }
            self.surfaces
                .canvas(target_surface)
                .concat(inverse_transform);
        }
    }

    pub fn render_shape_enter(
        &mut self,
        element: &Shape,
        mask: bool,
        clip_bounds: Option<&ClipStack>,
        target_surface: SurfaceId,
    ) {
        enter_exit::render_shape_enter(self, element, mask, clip_bounds, target_surface)
    }

    #[inline]
    pub fn render_shape_exit(
        &mut self,
        element: &Shape,
        visited_mask: bool,
        clip_bounds: Option<ClipStack>,
        target_surface: SurfaceId,
    ) -> Result<()> {
        enter_exit::render_shape_exit(self, element, visited_mask, clip_bounds, target_surface)
    }

    pub fn get_rect_bounds(&mut self, rect: skia::Rect) -> Rect {
        let scale = self.get_scale();
        let offset_x = self.viewbox.area.left * scale;
        let offset_y = self.viewbox.area.top * scale;
        Rect::from_xywh(
            (rect.left * scale) - offset_x,
            (rect.top * scale) - offset_y,
            rect.width() * scale,
            rect.height() * scale,
        )
    }

    pub fn get_shape_selrect_bounds(&mut self, shape: &Shape) -> Rect {
        let rect = shape.selrect();
        self.get_rect_bounds(rect)
    }

    pub fn get_shape_extrect_bounds(&mut self, shape: &Shape, tree: ShapesPoolRef) -> Rect {
        let scale = self.get_scale();
        let rect = shape.extrect(tree, scale);
        self.get_rect_bounds(rect)
    }

    pub fn get_aligned_tile_bounds(&mut self, tile: tiles::Tile) -> Rect {
        let scale = self.get_scale();
        let start_tile_x =
            (self.viewbox.area.left * scale / tiles::TILE_SIZE).floor() * tiles::TILE_SIZE;
        let start_tile_y =
            (self.viewbox.area.top * scale / tiles::TILE_SIZE).floor() * tiles::TILE_SIZE;
        Rect::from_xywh(
            (tile.x() as f32 * tiles::TILE_SIZE) - start_tile_x,
            (tile.y() as f32 * tiles::TILE_SIZE) - start_tile_y,
            tiles::TILE_SIZE,
            tiles::TILE_SIZE,
        )
    }

    // Returns the bounds of the current tile relative to the viewbox,
    // aligned to the nearest tile grid origin.
    //
    // Unlike `get_current_tile_bounds`, which calculates bounds using the exact
    // scaled offset of the viewbox, this method snaps the origin to the nearest
    // lower multiple of `TILE_SIZE`. This ensures the tile bounds are aligned
    // with the global tile grid, which is useful for rendering tiles in a
    /// consistent and predictable layout.
    pub fn get_current_aligned_tile_bounds(&mut self) -> Result<Rect> {
        Ok(self.get_aligned_tile_bounds(
            self.current_tile
                .ok_or(Error::CriticalError("Current tile not found".to_string()))?,
        ))
    }

    /// Renders element drop shadows to DropShadows surface and composites to Current.
    /// Used for both normal shadow rendering and pre-layer rendering (frame_clip_layer_blur).
    #[allow(clippy::too_many_arguments)]
    fn render_element_drop_shadows_and_composite(
        &mut self,
        element: &Shape,
        tree: ShapesPoolRef,
        extrect: &mut Option<Rect>,
        clip_bounds: Option<ClipStack>,
        scale: f32,
        node_render_state: &NodeRenderState,
        target_surface: SurfaceId,
    ) -> Result<()> {
        drop_shadow::render_element_drop_shadows_and_composite(
            self,
            element,
            tree,
            extrect,
            clip_bounds,
            scale,
            node_render_state,
            target_surface,
        )
    }

    pub fn render_shape_tree_partial_uncached(
        &mut self,
        tree: ShapesPoolRef,
        timestamp: i32,
        allow_stop: bool,
        export: bool,
    ) -> Result<(bool, bool)> {
        let mut iteration = 0;
        let mut is_empty = true;

        let mut target_surface = SurfaceId::Current;
        if export {
            target_surface = SurfaceId::Export;
        }

        while let Some(node_render_state) = self.pending_nodes.pop() {
            let node_id = node_render_state.id;
            let visited_children = node_render_state.visited_children;
            let visited_mask = node_render_state.visited_mask;
            let mask = node_render_state.mask;
            let clip_bounds = node_render_state.clip_bounds.clone();

            is_empty = false;

            let Some(element) = tree.get(&node_id) else {
                // The shape isn't available yet (likely still streaming in from WASM).
                // Skip it for this pass; a subsequent render will pick it up once present.
                continue;
            };
            let scale = self.get_scale_fast();
            let mut extrect: Option<Rect> = None;

            // If the shape is not in the tile set, then we add them.
            if self.tiles.get_tiles_of(node_id).is_none() {
                self.add_shape_tiles(element, tree);
            }

            if visited_children {
                if !node_render_state.flattened {
                    self.render_shape_exit(element, visited_mask, clip_bounds, target_surface)?;
                }
                continue;
            }

            if !node_render_state.is_root() {
                let transformed_element: Cow<Shape> = Cow::Borrowed(element);

                // Aggressive early exit: check hidden first (fastest check)
                if transformed_element.hidden {
                    continue;
                }

                if !view_mode::shape_visible_for_viewer_layer(
                    &self.viewer_render_root,
                    &self.viewer_visible_set,
                    &node_id,
                ) {
                    continue;
                }

                // Ancestors needed to reach whitelisted descendants: traverse only.
                if self.include_filter.is_some()
                    && view_mode::shape_visible_for_viewer_layer(
                        &self.viewer_render_root,
                        &self.viewer_visible_set,
                        &node_id,
                    )
                    && !view_mode::shape_should_paint_for_viewer_layer(
                        &self.include_filter,
                        &node_id,
                    )
                {
                    if element.is_recursive() {
                        let children_ids: Vec<_> =
                            element.children_ids_iter(false).copied().collect();
                        let children_ids = sort_z_index(tree, element, children_ids);
                        for child_id in children_ids.iter() {
                            self.pending_nodes.push(NodeRenderState {
                                id: *child_id,
                                visited_children: false,
                                clip_bounds: clip_bounds.clone(),
                                visited_mask: false,
                                mask: false,
                                flattened: false,
                            });
                        }
                    }
                    continue;
                }

                // For frames and groups, we must use extrect because they can have nested content
                // that extends beyond their selrect. Using selrect for early exit would incorrectly
                // skip frames/groups that have nested content in the current tile.
                let is_container = matches!(
                    transformed_element.shape_type,
                    crate::shapes::Type::Frame(_) | crate::shapes::Type::Group(_)
                );

                let has_effects = transformed_element.has_effects_that_extend_bounds();

                let is_visible = export
                    || mask
                    || if is_container || has_effects {
                        let element_extrect =
                            extrect.get_or_insert_with(|| transformed_element.extrect(tree, scale));
                        element_extrect.intersects(self.render_area_with_margins)
                            && !transformed_element.visually_insignificant(scale, tree)
                    } else {
                        let selrect = transformed_element.selrect();
                        selrect.intersects(self.render_area_with_margins)
                            && !transformed_element.visually_insignificant(scale, tree)
                    };

                if self.options.is_debug_visible() {
                    let shape_extrect_bounds = self.get_shape_extrect_bounds(element, tree);
                    debug::render_debug_shape(self, None, Some(shape_extrect_bounds));
                }

                if !is_visible {
                    continue;
                }
            }

            let can_flatten = element.can_flatten() && !self.focus_mode.should_focus(&element.id);

            // Skip render_shape_enter/exit for flattened containers
            // If a container was flattened, it doesn't affect children visually, so we skip
            // the expensive enter/exit operations and process children directly
            if !can_flatten {
                // Enter focus early so shadow_before_layer can run (it needs focus_mode.is_active())
                self.focus_mode.enter(&element.id);

                // For frames with layer blur, render shadow BEFORE the layer so it doesn't get
                // the layer blur (which would make it more diffused than without clipping)
                let shadow_before_layer = !node_render_state.is_root()
                    && self.focus_mode.is_active()
                    && !matches!(element.shape_type, Type::Text(_))
                    && Self::frame_clip_layer_blur(element).is_some()
                    && element.drop_shadows_visible().next().is_some();

                if shadow_before_layer {
                    self.render_element_drop_shadows_and_composite(
                        element,
                        tree,
                        &mut extrect,
                        clip_bounds.clone(),
                        scale,
                        &node_render_state,
                        target_surface,
                    )?;
                }

                // Render background blur BEFORE save_layer so it modifies
                // the backdrop independently of the shape's opacity.
                if !node_render_state.is_root() && self.focus_mode.is_active() {
                    self.render_background_blur(element, target_surface);
                }

                self.render_shape_enter(element, mask, clip_bounds.as_ref(), target_surface);
            }

            if !node_render_state.is_root() && self.focus_mode.is_active() {
                // Skip expensive drop shadow rendering in fast mode (during pan/zoom).
                // let skip_shadows = self.options.is_fast_mode();
                let skip_shadows = false;
                // Skip shadow block when already rendered before the layer (frame_clip_layer_blur)
                let shadows_already_rendered = Self::frame_clip_layer_blur(element).is_some();

                // For text shapes, render drop shadow using text rendering logic
                if !skip_shadows
                    && !shadows_already_rendered
                    && !matches!(element.shape_type, Type::Text(_))
                {
                    self.render_element_drop_shadows_and_composite(
                        element,
                        tree,
                        &mut extrect,
                        clip_bounds.clone(),
                        scale,
                        &node_render_state,
                        target_surface,
                    )?;
                } else {
                    // This is necessary or the later flush_and_submit will be very slow
                    self.surfaces
                        .draw_into(SurfaceId::DropShadows, target_surface, None);
                }

                // For frames without clip_content, inner strokes must render after children in
                // render_shape_exit so children don't paint over them. Strip them here.
                let element_for_inline: Cow<Shape> = if matches!(element.shape_type, Type::Frame(_))
                    && !element.clip_content
                    && element.has_inner_stroke()
                {
                    let is_open = element.is_open();
                    let mut modified = element.clone();
                    modified
                        .strokes
                        .retain(|s| s.render_kind(is_open) != StrokeKind::Inner);
                    Cow::Owned(modified)
                } else {
                    Cow::Borrowed(element)
                };

                self.render_shape(
                    &element_for_inline,
                    clip_bounds.clone(),
                    SurfaceId::Fills,
                    SurfaceId::Strokes,
                    SurfaceId::InnerShadows,
                    SurfaceId::TextDropShadows,
                    true,
                    None,
                    None,
                    None,
                    target_surface,
                )?;

                self.surfaces
                    .canvas(SurfaceId::DropShadows)
                    .clear(skia::Color::TRANSPARENT);
            } else if visited_children {
                self.draw_shape_surface_stack_into(Some(element), target_surface);
            }

            // Skip nested state updates for flattened containers
            // Flattened containers don't affect children, so we don't need to track their state
            if !can_flatten {
                match element.shape_type {
                    Type::Frame(_) if Self::frame_clip_layer_blur(element).is_some() => {
                        self.nested_blurs.push(None);
                        self.cached_layer_blur = None;
                    }
                    Type::Group(_) if element.masked_group_layer_blur().is_some() => {
                        self.nested_blurs.push(None);
                        self.cached_layer_blur = None;
                    }
                    Type::Frame(_) | Type::Group(_) => {
                        self.nested_blurs.push(element.blur);
                        self.cached_layer_blur = None;
                    }
                    _ => {}
                }
            }

            // Set the node as visited_children before processing children
            self.pending_nodes.push(NodeRenderState {
                id: node_id,
                visited_children: true,
                clip_bounds: clip_bounds.clone(),
                visited_mask: false,
                mask,
                flattened: can_flatten,
            });

            if element.is_recursive() {
                // Shrink the child clip by ~1 device px when the frame has an inner stroke, same
                // epsilon as `fills::render` inset, so clipped overflow does not sit under the
                // stroke band drawn later in `render_shape_exit`.
                let clip_inset_for_children = (matches!(element.shape_type, Type::Frame(_))
                    && element.clip()
                    && element.has_inner_stroke())
                .then_some(1.0 / scale);
                let children_clip_bounds = node_render_state.get_children_clip_bounds(
                    element,
                    None,
                    clip_inset_for_children,
                );

                let mut children_ids: Vec<_> = Vec::new();
                if can_flatten {
                    get_simplified_children(tree, element, &mut children_ids);
                } else {
                    children_ids = element.children_ids_iter(false).copied().collect();
                }

                let children_ids = sort_z_index(tree, element, children_ids);

                for child_id in children_ids.iter() {
                    self.pending_nodes.push(NodeRenderState {
                        id: *child_id,
                        visited_children: false,
                        clip_bounds: children_clip_bounds.clone(),
                        visited_mask: false,
                        mask: false,
                        flattened: false,
                    });
                }
            }

            // We try to avoid doing too many calls to get_time
            if allow_stop && self.should_stop_rendering(iteration, timestamp) {
                return Ok((is_empty, true));
            }
            iteration += 1;
        }

        Ok((is_empty, false))
    }

    pub fn render_shape_tree_partial(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        allow_stop: bool,
    ) -> Result<FrameType> {
        let mut should_stop = false;
        self.viewer_render_root = base_object.copied();
        let root_ids = {
            if let Some(shape_id) = base_object {
                vec![*shape_id]
            } else {
                let Some(root) = tree.get(&Uuid::nil()) else {
                    return Err(Error::CriticalError("Root shape not found".to_string()));
                };
                root.children_ids(false)
            }
        };

        while !should_stop {
            if let Some(current_tile) = self.current_tile {
                // NOTE: For now we don't need to cover the case where the tile
                // is not cached because everything will be handled from draw_atlas.
                // Viewer masked passes (include_filter) must not reuse cached tiles from
                // a previous pass; otherwise pass-1 pixels can leak into pass 2.
                if self.viewer_masked_pass() || !self.surfaces.has_cached_tile_surface(current_tile)
                {
                    performance::begin_measure!("render_shape_tree::uncached");
                    let (is_empty, early_return) = self
                        .render_shape_tree_partial_uncached(tree, timestamp, allow_stop, false)?;

                    #[cfg(target_arch = "wasm32")]
                    if self.options.capture_frames > 0 {
                        debug::console_debug_surface(self, SurfaceId::Backbuffer);
                    }

                    if early_return {
                        self.viewer_render_root = None;
                        return Ok(FrameType::Partial);
                    }
                    performance::end_measure!("render_shape_tree::uncached");

                    // Composite if the walker did work in this PAF (`!is_empty`) OR
                    // the tile has unfinished work from a previous PAF
                    // (`current_tile_had_shapes` was set when we populated pending_nodes
                    // for this tile).
                    if !is_empty || self.current_tile_had_shapes {
                        let tile_rect = self.get_current_aligned_tile_bounds()?;

                        let current_tile = *self
                            .current_tile
                            .as_ref()
                            .ok_or(Error::CriticalError("Current tile not found".to_string()))?;

                        self.surfaces.draw_current_tile_into_tile_atlas(
                            &self.tile_viewbox,
                            &current_tile,
                        );

                        if self.options.is_debug_visible() {
                            debug::render_workspace_current_tile(
                                self,
                                "".to_string(),
                                current_tile,
                                tile_rect,
                            );
                        }
                    }
                } else if self.tiles.is_empty_at(current_tile) {
                    self.surfaces.remove_cached_tile_surface(current_tile);
                }
            }

            self.surfaces
                .canvas(SurfaceId::Current)
                .clear(self.background_color);

            // If we finish processing every node rendering is complete
            // let's check if there are more pending nodes
            if let Some(next_tile) = self.pending_tiles.pop() {
                self.update_render_context(next_tile);
                // Reset for the new tile. We'll flip it to true if the
                // tile has shapes, so a later "is_empty=true" reflects
                // a resumed-from-yield case rather than a genuinely
                // empty tile.
                self.current_tile_had_shapes = false;

                let viewer_masked_pass = self.viewer_masked_pass();

                let Some(ids) = self.tiles.get_shapes_at(next_tile) else {
                    // If the tile is empty we do not need to render it.
                    continue;
                };

                // Never skip based on cached surfaces during viewer masked passes.
                if !viewer_masked_pass && self.surfaces.has_cached_tile_surface(next_tile) {
                    // If the tile is cached, then we do not need to
                    // render it.
                    continue;
                }

                // Check if any shape on this tile has a background blur.
                // If so, we need ALL root shapes rendered (not just those
                // assigned to this tile) because the blur snapshots Current
                // which must contain the shapes behind it.
                let tile_has_bg_blur = ids.iter().any(|id| {
                    tree.get(id)
                        .is_some_and(|s| s.visible_background_blur().is_some())
                });

                // We only need first level shapes, in the same order as the parent node.
                //
                // During interactive transforms we may invalidate only the modified shapes
                // (to avoid massive ancestor eviction). However, we still composite full
                // tiles (we clear the tile rect before drawing Current), so we must render
                // all root shapes that can contribute to this tile; otherwise, unchanged
                // siblings inside the same tile would disappear.
                let mut valid_ids = Vec::with_capacity(ids.len());
                if self.options.is_interactive_transform()
                || tile_has_bg_blur {
                    valid_ids.extend(root_ids.iter().copied());
                } else {
                    for root_id in root_ids.iter() {
                        if ids.contains(root_id) {
                            valid_ids.push(*root_id);
                        }
                    }
                }

                if !valid_ids.is_empty() {
                    self.current_tile_had_shapes = true;
                }

                self.pending_nodes
                    .extend(valid_ids.into_iter().map(|id| NodeRenderState {
                        id,
                        visited_children: false,
                        clip_bounds: None,
                        visited_mask: false,
                        mask: false,
                        flattened: false,
                    }));
            } else {
                // If there are no more pending tiles, stop.
                should_stop = true;
            }
        }

        self.viewer_render_root = None;

        // Mark cache as valid for render_from_cache.
        // Only update for full-quality renders (non-fast mode).
        // An async render can complete while fast mode is active
        // (e.g. interest-area tiles finish during a pan gesture).
        // Those tiles lack effects (shadows, blur).  Updating
        // cached_viewbox here would make zoom_changed() return false,
        // so set_view_end would skip tile invalidation and the next
        // full render would reuse the low-quality tiles.
        self.cached_viewbox = self.viewbox;

        Ok(FrameType::Full)
    }

    /*
     * Given a shape returns the TileRect with the range of tiles that the shape is in.
     * This is always limited to the interest area to optimize performance and prevent
     * processing unnecessary tiles outside the viewport. The interest area already
     * includes a margin (VIEWPORT_INTEREST_AREA_THRESHOLD) calculated via
     * get_tiles_for_viewbox_with_interest, ensuring smooth pan/zoom interactions.
     *
     * When the viewport changes (pan/zoom), the interest area is updated and shapes
     * are dynamically added to the tile index via the fallback mechanism in
     * render_shape_tree_partial_uncached, ensuring all shapes render correctly.
     */
    pub fn get_tiles_for_shape(&mut self, shape: &Shape, tree: ShapesPoolRef) -> TileRect {
        let scale = self.get_scale();
        let extrect = shape.extrect(tree, scale);
        let tile_size = tiles::get_tile_size(scale);
        let shape_tiles = tiles::get_tiles_for_rect(extrect, tile_size);
        let interest_rect = &self.tile_viewbox.interest_rect;
        // Calculate the intersection of shape_tiles with interest_rect
        // This returns only the tiles that are both in the shape and in the interest area
        let intersection_x1 = shape_tiles.x1().max(interest_rect.x1());
        let intersection_y1 = shape_tiles.y1().max(interest_rect.y1());
        let intersection_x2 = shape_tiles.x2().min(interest_rect.x2());
        let intersection_y2 = shape_tiles.y2().min(interest_rect.y2());

        // Return the intersection if valid (there is overlap), otherwise return empty rect
        if intersection_x1 <= intersection_x2 && intersection_y1 <= intersection_y2 {
            // Valid intersection: return the tiles that are in both shape_tiles and interest_rect
            TileRect(
                intersection_x1,
                intersection_y1,
                intersection_x2,
                intersection_y2,
            )
        } else {
            // No intersection: shape is completely outside interest area
            // The shape will be added dynamically via add_shape_tiles when it enters
            // the interest area during pan/zoom operations
            TileRect(0, 0, -1, -1)
        }
    }

    /*
     * Given a shape, check the indexes and update it's location in the tile set
     * returns the tiles that have changed in the process.
     */
    pub fn update_shape_tiles(
        &mut self,
        shape: &Shape,
        tree: ShapesPoolRef,
    ) -> HashSet<tiles::Tile> {
        let tile_rect = self.get_tiles_for_shape(shape, tree);

        // Collect old tiles to avoid borrow conflict with remove_shape_at
        let old_tiles: Vec<_> = self
            .tiles
            .get_tiles_of(shape.id)
            .map_or(Vec::new(), |t| t.iter().copied().collect());

        let mut result = HashSet::<tiles::Tile>::with_capacity(old_tiles.len());

        // First, remove the shape from all tiles where it was previously located
        for tile in old_tiles {
            self.tiles.remove_shape_at(tile, shape.id);
            result.insert(tile);
        }

        // Then, add the shape to the new tiles
        for tile in tile_rect.iter(true) {
            self.tiles.add_shape_at(tile, shape.id);
            result.insert(tile);
        }

        result
    }

    /*
     * Incremental version of update_shape_tiles for pan/zoom operations.
     * Updates the tile index and returns ONLY tiles that need cache invalidation.
     *
     * During pan operations, shapes don't move in world coordinates. The interest
     * area (viewport) moves, which changes which tiles we track in the index, but
     * tiles that were already cached don't need re-rendering just because the
     * viewport moved.
     *
     * This function:
     * 1. Updates the tile index (adds/removes shapes from tiles based on interest area)
     * 2. Returns empty vec for cache invalidation (pan doesn't change tile content)
     *
     * Tile cache invalidation only happens when shapes actually move or change,
     * which is handled by rebuild_touched_tiles, not during pan/zoom.
     */
    pub fn update_shape_tiles_incremental(
        &mut self,
        shape: &Shape,
        tree: ShapesPoolRef,
    ) -> Vec<tiles::Tile> {
        let tile_rect = self.get_tiles_for_shape(shape, tree);
        let old_tiles: HashSet<tiles::Tile> = self
            .tiles
            .get_tiles_of(shape.id)
            .map_or(HashSet::new(), |tiles| tiles.iter().copied().collect());

        let new_tiles: HashSet<tiles::Tile> = tile_rect.iter(true).collect();

        // Tiles where shape is being removed from index (left interest area)
        let removed: Vec<_> = old_tiles.difference(&new_tiles).copied().collect();
        // Tiles where shape is being added to index (entered interest area)
        let added: Vec<_> = new_tiles.difference(&old_tiles).copied().collect();

        // Update the index: remove from old tiles
        for tile in &removed {
            self.tiles.remove_shape_at(*tile, shape.id);
        }

        // Update the index: add to new tiles
        for tile in &added {
            self.tiles.add_shape_at(*tile, shape.id);
        }

        // Don't invalidate cache for pan/zoom - the tile content hasn't changed,
        // only the interest area moved. Tiles that were cached are still valid.
        // New tiles that entered the interest area will be rendered fresh since
        // they weren't in the cache anyway.
        Vec::new()
    }

    /*
     * Add the tiles for the shape to the index.
     * returns the tiles that have been updated
     */
    pub fn add_shape_tiles(&mut self, shape: &Shape, tree: ShapesPoolRef) -> Vec<tiles::Tile> {
        performance::begin_measure!("add_shape_tiles");
        let tiles: Vec<tiles::Tile> = self.get_tiles_for_shape(shape, tree).iter(true).collect();
        for tile in tiles.iter() {
            self.tiles.add_shape_at(*tile, shape.id);
        }
        performance::end_measure!("add_shape_tiles");
        tiles
    }

    pub fn remove_cached_tile(&mut self, tile: tiles::Tile) {
        self.surfaces.remove_cached_tile_surface(tile);
    }

    /// Rebuild the tile index (shape→tile mapping) for all top-level shapes.
    /// This does NOT invalidate the tile texture cache — cached tile images
    /// survive so that fast-mode renders during pan still show shadows/blur.
    pub fn rebuild_tile_index(&mut self, tree: ShapesPoolRef) {
        let zoom_changed = self.zoom_changed();
        performance::begin_measure!("rebuild_tile_index");
        let mut nodes = Vec::<Uuid>::with_capacity(64);
        nodes.push(Uuid::nil());
        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                if shape_id != Uuid::nil() {
                    if zoom_changed {
                        let _ = self.update_shape_tiles(shape, tree);
                    } else {
                        let _ = self.update_shape_tiles_incremental(shape, tree);
                    }
                } else {
                    // We only need to rebuild tiles from the first level.
                    for child_id in shape.children_ids_iter(false) {
                        nodes.push(*child_id);
                    }
                }
            }
        }
        performance::end_measure!("rebuild_tile_index");
    }

    pub fn rebuild_tiles_shallow(&mut self, tree: ShapesPoolRef) {
        performance::begin_measure!("rebuild_tiles_shallow");

        self.rebuild_tile_index(tree);

        // Zoom changes world tile size: a partial cache update would mix scales in the
        // mosaic and glitch. Same zoom as last finished render (typical pan): drop only
        // tile textures and keep the cache canvas for render_from_cache.
        if !self.zoom_changed() {
            self.surfaces.invalidate_tile_cache();
        }

        performance::end_measure!("rebuild_tiles_shallow");
    }

    pub fn rebuild_tiles_from(&mut self, tree: ShapesPoolRef, base_id: Option<&Uuid>) {
        performance::begin_measure!("rebuild_tiles");

        self.tiles.invalidate();

        let mut all_tiles = HashSet::<tiles::Tile>::new();
        let mut nodes = {
            if let Some(base_id) = base_id {
                vec![*base_id]
            } else {
                vec![Uuid::nil()]
            }
        };

        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                if shape_id != Uuid::nil() {
                    // We have invalidated the tiles so we only need to add the shape
                    all_tiles.extend(self.add_shape_tiles(shape, tree));
                }

                for child_id in shape.children_ids_iter(false) {
                    nodes.push(*child_id);
                }
            }
        }

        // Invalidate changed tiles - old content stays visible until new tiles render
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        performance::end_measure!("rebuild_tiles");
    }

    /*
     * Rebuild the tiles for the shapes that have been modified from the
     * last time this was executed.
     */
    pub fn rebuild_touched_tiles(&mut self, tree: ShapesPoolRef) {
        performance::begin_measure!("rebuild_touched_tiles");

        let mut all_tiles = HashSet::<tiles::Tile>::new();

        let ids = std::mem::take(&mut self.touched_ids);
        self.preserve_target_during_render = !ids.is_empty();

        for shape_id in ids.iter() {
            if let Some(shape) = tree.get(shape_id) {
                if shape_id != &Uuid::nil() {
                    all_tiles.extend(self.update_shape_tiles(shape, tree));
                }
            }
        }

        // Update the changed tiles
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }

        performance::end_measure!("rebuild_touched_tiles");
    }

    /// Invalidates extended rectangles and updates tiles for a set of shapes
    ///
    /// This function takes a set of shape IDs and for each one:
    /// 1. Invalidates the extrect cache
    /// 2. Updates the tiles to ensure proper rendering
    ///
    /// This is useful when you have a pre-computed set of shape IDs that need to be refreshed,
    /// regardless of their relationship to other shapes (e.g., ancestors, descendants, or any other collection).
    pub fn update_tiles_shapes(
        &mut self,
        shape_ids: &[Uuid],
        tree: ShapesPoolMutRef<'_>,
    ) -> Result<()> {
        performance::begin_measure!("invalidate_and_update_tiles");
        let mut all_tiles = HashSet::<tiles::Tile>::new();
        for shape_id in shape_ids {
            if let Some(shape) = tree.get(shape_id) {
                all_tiles.extend(self.update_shape_tiles(shape, tree));
            }
        }
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        performance::end_measure!("invalidate_and_update_tiles");
        Ok(())
    }

    /// Rebuilds tiles for shapes with modifiers and processes their ancestors
    ///
    /// This function applies transformation modifiers to shapes and updates their tiles.
    /// Additionally, it processes all ancestors of modified shapes to ensure their
    /// extended rectangles are properly recalculated and their tiles are updated.
    /// This is crucial for frames and groups that contain transformed children.
    pub fn rebuild_modifier_tiles(
        &mut self,
        tree: ShapesPoolMutRef<'_>,
        ids: &[Uuid],
    ) -> Result<()> {
        // During interactive transform, skip ancestor invalidation: walking up to the
        // parent frame evicts every tile the frame covers, including dense tiles with
        // many siblings. Ancestor extrect caches are already invalidated by
        // `ShapesPool::set_modifiers`; the tile index is reconciled post-gesture by
        // the committing code path (rebuild_touched_tiles).
        if self.options.is_interactive_transform() {
            self.update_tiles_shapes(ids, tree)?;
        } else {
            let ancestors = all_with_ancestors(ids, tree, false);
            self.update_tiles_shapes(&ancestors, tree)?;
        }
        Ok(())
    }

    pub fn get_scale(&self) -> f32 {
        // During export, use the export scale instead of the workspace zoom.
        if let Some((_, export_scale)) = self.export_context {
            return export_scale;
        }
        self.viewbox.get_scale()
    }

    /// Hot-path variant that skips the export_context check.
    /// Use in render_shape / walk loops where export is never active.
    #[inline]
    pub fn get_scale_fast(&self) -> f32 {
        self.viewbox.get_scale()
    }

    pub fn zoom_changed(&self) -> bool {
        (self.viewbox.zoom - self.cached_viewbox.zoom).abs() > f32::EPSILON
    }

    pub fn mark_touched(&mut self, uuid: Uuid) {
        self.touched_ids.insert(uuid);
    }

    #[allow(dead_code)]
    pub fn clean_touched(&mut self) {
        self.touched_ids.clear();
    }

    pub fn set_view(&mut self, zoom: f32, x: f32, y: f32) {
        self.viewbox.set_all(zoom, x, y);
    }

    pub fn print_stats(&self) {
        self.stats.print();
    }

    pub fn prepare_context_loss_cleanup(&mut self) {
        // Drop cached GPU-backed snapshots before dropping the render state.
        // self.backbuffer_crop_cache.clear();
        self.surfaces.invalidate_tile_cache();
        // Mark context as abandoned so resource destructors avoid issuing
        // GL commands when the browser has already lost/restored the context.
        get_gpu_state().context.abandon();
    }

    pub fn free_gpu_resources(&mut self) {
        get_gpu_state().context.free_gpu_resources();
    }
}
