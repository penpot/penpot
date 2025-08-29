mod blend;
mod debug;
mod fills;
pub mod filters;
mod fonts;
mod gpu_state;
pub mod grid_layout;
mod images;
mod options;
mod shadows;
mod strokes;
mod surfaces;
mod text;
mod ui;

use skia_safe::{self as skia, Matrix, RRect, Rect};
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};

use gpu_state::GpuState;
use options::RenderOptions;
pub use surfaces::{SurfaceId, Surfaces};

use crate::performance;
use crate::shapes::{Blur, BlurType, Corners, Fill, Shape, StructureEntry, Type};
use crate::state::ShapesPool;
use crate::tiles::{self, PendingTiles, TileRect};
use crate::uuid::Uuid;
use crate::view::Viewbox;
use crate::wapi;

use crate::math;
use crate::math::bools;

pub use blend::BlendMode;
pub use fonts::*;
pub use images::*;

// This is the extra are used for tile rendering.
const VIEWPORT_INTEREST_AREA_THRESHOLD: i32 = 1;
const MAX_BLOCKING_TIME_MS: i32 = 32;
const NODE_BATCH_THRESHOLD: i32 = 10;

pub struct NodeRenderState {
    pub id: Uuid,
    // We use this bool to keep that we've traversed all the children inside this node.
    visited_children: bool,
    // This is used to clip the content of frames.
    clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    // This is a flag to indicate that we've already drawn the mask of a masked group.
    visited_mask: bool,
    // This bool indicates that we're drawing the mask shape.
    mask: bool,
}

impl NodeRenderState {
    pub fn is_root(&self) -> bool {
        self.id.is_nil()
    }

    pub fn get_children_clip_bounds(
        &self,
        element: &Shape,
        modifiers: Option<&Matrix>,
    ) -> Option<(Rect, Option<Corners>, Matrix)> {
        if self.id.is_nil() || !element.clip() {
            return self.clip_bounds;
        }

        let bounds = element.selrect();
        let mut transform = element.transform;
        transform.post_translate(bounds.center());
        transform.pre_translate(-bounds.center());

        if let Some(modifier) = modifiers {
            transform.post_concat(modifier);
        }

        let corners = match &element.shape_type {
            Type::Rect(data) => data.corners,
            Type::Frame(data) => data.corners,
            _ => None,
        };

        Some((bounds, corners, transform))
    }
}

/// Represents the "focus mode" state used during rendering.
///
/// Focus mode allows selectively highlighting or isolating specific shapes (UUIDs)
/// during the render pass. It maintains a list of shapes to focus and tracks
/// whether the current rendering context is inside a focused element.
///
/// # Focus Propagation
/// If a shape is in focus, all its nested content
/// is also considered to be in focus for the duration of the render traversal. Focus
/// state propagates *downward* through the tree while rendering.
///
/// # Usage
/// - `set_shapes(...)` to activate focus mode for specific elements and their anidated content.
/// - `clear()` to disable focus mode.
/// - `reset()` should be called at the beginning of the render loop.
/// - `enter(...)` / `exit(...)` should be called when entering and leaving shape
///   render contexts.
/// - `is_active()` returns whether the current shape is being rendered in focus.
pub struct FocusMode {
    shapes: Vec<Uuid>,
    active: bool,
}

impl FocusMode {
    pub fn new() -> Self {
        FocusMode {
            shapes: Vec::new(),
            active: false,
        }
    }

    pub fn clear(&mut self) {
        self.shapes.clear();
        self.active = false;
    }

    pub fn set_shapes(&mut self, shapes: Vec<Uuid>) {
        self.shapes = shapes;
    }

    /// Returns `true` if the given shape ID should be focused.
    /// If the `shapes` list is empty, focus applies to all shapes.
    pub fn should_focus(&self, id: &Uuid) -> bool {
        self.shapes.is_empty() || self.shapes.contains(id)
    }

    pub fn enter(&mut self, id: &Uuid) {
        if !self.active && self.should_focus(id) {
            self.active = true;
        }
    }

    pub fn exit(&mut self, id: &Uuid) {
        if self.active && self.should_focus(id) {
            self.active = false;
        }
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn reset(&mut self) {
        self.active = false;
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub options: RenderOptions,
    pub surfaces: Surfaces,
    pub fonts: FontStore,
    pub viewbox: Viewbox,
    pub cached_viewbox: Viewbox,
    pub cached_target_snapshot: Option<skia::Image>,
    pub images: ImageStore,
    pub background_color: skia::Color,
    // Identifier of the current requestAnimationFrame call, if any.
    pub render_request_id: Option<i32>,
    // Indicates whether the rendering process has pending frames.
    pub render_in_progress: bool,
    // Stack of nodes pending to be rendered.
    pending_nodes: Vec<NodeRenderState>,
    pub current_tile: Option<tiles::Tile>,
    pub sampling_options: skia::SamplingOptions,
    pub render_area: Rect,
    pub tile_viewbox: tiles::TileViewbox,
    pub tiles: tiles::TileHashMap,
    pub pending_tiles: PendingTiles,
    // nested_fills maintains a stack of group  fills that apply to nested shapes
    // without their own fill definitions. This is necessary because in SVG, a group's `fill`
    // can affect its child elements if they don't specify one themselves. If the planned
    // migration to remove group-level fills is completed, this code should be removed.
    pub nested_fills: Vec<Vec<Fill>>,
    pub nested_blurs: Vec<Option<Blur>>,
    pub show_grid: Option<Uuid>,
    pub focus_mode: FocusMode,
}

pub fn get_cache_size(viewbox: Viewbox, scale: f32) -> skia::ISize {
    // First we retrieve the extended area of the viewport that we could render.
    let TileRect(isx, isy, iex, iey) = tiles::get_tiles_for_viewbox_with_interest(
        viewbox,
        VIEWPORT_INTEREST_AREA_THRESHOLD,
        scale,
    );

    let dx = if isx.signum() != iex.signum() { 1 } else { 0 };
    let dy = if isy.signum() != iey.signum() { 1 } else { 0 };

    let tile_size = tiles::TILE_SIZE;
    (
        ((iex - isx).abs() + dx) * tile_size as i32,
        ((iey - isy).abs() + dy) * tile_size as i32,
    )
        .into()
}

fn is_modified_child(
    shape: &Shape,
    shapes: &ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
) -> bool {
    if modifiers.is_empty() {
        return false;
    }

    let ids = shape.all_children(shapes, true, false);
    let default = &Matrix::default();
    let parent_modifier = modifiers.get(&shape.id).unwrap_or(default);

    // Returns true if the transform of any child is different to the parent's
    ids.iter().any(|id| {
        !math::is_close_matrix(
            parent_modifier,
            modifiers.get(id).unwrap_or(&Matrix::default()),
        )
    })
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);

        let fonts = FontStore::new();
        let surfaces = Surfaces::new(
            &mut gpu_state,
            (width, height),
            sampling_options,
            tiles::get_tile_dimensions(),
        );

        // This is used multiple times everywhere so instead of creating new instances every
        // time we reuse this one.

        let viewbox = Viewbox::new(width as f32, height as f32);
        let tiles = tiles::TileHashMap::new();

        RenderState {
            gpu_state: gpu_state.clone(),
            options: RenderOptions::default(),
            surfaces,
            fonts,
            viewbox,
            cached_viewbox: Viewbox::new(0., 0.),
            cached_target_snapshot: None,
            images: ImageStore::new(gpu_state.context.clone()),
            background_color: skia::Color::TRANSPARENT,
            render_request_id: None,
            render_in_progress: false,
            pending_nodes: vec![],
            current_tile: None,
            sampling_options,
            render_area: Rect::new_empty(),
            tiles,
            tile_viewbox: tiles::TileViewbox::new_with_interest(
                viewbox,
                VIEWPORT_INTEREST_AREA_THRESHOLD,
                1.0,
            ),
            pending_tiles: PendingTiles::new_empty(),
            nested_fills: vec![],
            nested_blurs: vec![],
            show_grid: None,
            focus_mode: FocusMode::new(),
        }
    }

    pub fn fonts(&self) -> &FontStore {
        &self.fonts
    }

    pub fn fonts_mut(&mut self) -> &mut FontStore {
        &mut self.fonts
    }

    pub fn add_image(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        self.images.add(id, image_data)
    }

    pub fn has_image(&self, id: &Uuid) -> bool {
        self.images.contains(id)
    }

    pub fn set_debug_flags(&mut self, debug: u32) {
        self.options.flags = debug;
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        if Some(dpr) != self.options.dpr {
            self.options.dpr = Some(dpr);
            self.resize(
                self.viewbox.width.floor() as i32,
                self.viewbox.height.floor() as i32,
            );
            self.fonts.set_scale_debug_font(dpr);
        }
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.background_color = color;
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;
        self.surfaces
            .resize(&mut self.gpu_state, dpr_width, dpr_height);
        self.viewbox.set_wh(width as f32, height as f32);
        self.tile_viewbox.update(self.viewbox, self.get_scale());
    }

    pub fn flush_and_submit(&mut self) {
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Target);
    }

    pub fn reset_canvas(&mut self) {
        self.surfaces.reset(self.background_color);
    }

    #[allow(dead_code)]
    pub fn get_canvas_at(&mut self, surface_id: SurfaceId) -> &skia::Canvas {
        self.surfaces.canvas(surface_id)
    }

    #[allow(dead_code)]
    pub fn restore_canvas(&mut self, surface_id: SurfaceId) {
        self.surfaces.canvas(surface_id).restore();
    }

    pub fn apply_render_to_final_canvas(&mut self, rect: skia::Rect) {
        let tile_rect = self.get_current_aligned_tile_bounds();
        self.surfaces.cache_current_tile_texture(
            &self.tile_viewbox,
            &self.current_tile.unwrap(),
            &tile_rect,
        );

        self.surfaces.draw_cached_tile_surface(
            self.current_tile.unwrap(),
            rect,
            self.background_color,
        );

        if self.options.is_debug_visible() {
            debug::render_workspace_current_tile(
                self,
                "".to_string(),
                self.current_tile.unwrap(),
                rect,
            );
        }
    }

    pub fn apply_drawing_to_render_canvas(&mut self, shape: Option<&Shape>) {
        performance::begin_measure!("apply_drawing_to_render_canvas");

        let paint = skia::Paint::default();

        self.surfaces
            .draw_into(SurfaceId::DropShadows, SurfaceId::Current, Some(&paint));

        self.surfaces
            .draw_into(SurfaceId::Fills, SurfaceId::Current, Some(&paint));

        let mut render_overlay_below_strokes = false;
        if let Some(shape) = shape {
            render_overlay_below_strokes = shape.has_fills();
        }

        if render_overlay_below_strokes {
            self.surfaces
                .draw_into(SurfaceId::InnerShadows, SurfaceId::Current, Some(&paint));
        }

        self.surfaces
            .draw_into(SurfaceId::Strokes, SurfaceId::Current, Some(&paint));

        if !render_overlay_below_strokes {
            self.surfaces
                .draw_into(SurfaceId::InnerShadows, SurfaceId::Current, Some(&paint));
        }

        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::DropShadows as u32
            | SurfaceId::InnerShadows as u32;

        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().clear(skia::Color::TRANSPARENT);
        });
    }

    pub fn clear_focus_mode(&mut self) {
        self.focus_mode.clear();
    }

    pub fn set_focus_mode(&mut self, shapes: Vec<Uuid>) {
        self.focus_mode.set_shapes(shapes);
    }

    pub fn render_shape(
        &mut self,
        shapes: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        shape: &Shape,
        scale_content: Option<&f32>,
        clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    ) {
        let shape = if let Some(scale_content) = scale_content {
            &shape.scale_content(*scale_content)
        } else {
            shape
        };

        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::DropShadows as u32
            | SurfaceId::InnerShadows as u32;
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().save();
        });

        let antialias = shape.should_use_antialias(self.get_scale());

        // set clipping
        if let Some((bounds, corners, transform)) = clip_bounds {
            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas().concat(&transform);
            });

            if let Some(corners) = corners {
                let rrect = RRect::new_rect_radii(bounds, &corners);
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas()
                        .clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
                });
            } else {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas()
                        .clip_rect(bounds, skia::ClipOp::Intersect, antialias);
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
                    .canvas(SurfaceId::Fills)
                    .draw_rect(bounds, &paint);
            }

            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas()
                    .concat(&transform.invert().unwrap_or(Matrix::default()));
            });
        }

        // We don't want to change the value in the global state
        let mut shape: Cow<Shape> = Cow::Borrowed(shape);

        if let Some(shape_modifiers) = modifiers.get(&shape.id) {
            shape.to_mut().apply_transform(shape_modifiers);
        }

        let mut nested_blur_value = 0.;
        for nested_blur in self.nested_blurs.iter().flatten() {
            if !nested_blur.hidden && nested_blur.blur_type == BlurType::Layer {
                nested_blur_value += nested_blur.value.powf(2.);
            }
        }

        if !shape.blur.hidden && shape.blur.blur_type == BlurType::Layer {
            nested_blur_value += shape.blur.value.powf(2.);
        }

        if nested_blur_value > 0. {
            shape
                .to_mut()
                .set_blur(BlurType::Layer as u8, false, nested_blur_value.sqrt());
        }

        let center = shape.center();
        let mut matrix = shape.transform;
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        match &shape.shape_type {
            Type::SVGRaw(sr) => {
                if let Some(shape_modifiers) = modifiers.get(&shape.id) {
                    self.surfaces
                        .canvas(SurfaceId::Fills)
                        .concat(shape_modifiers);
                }
                self.surfaces.canvas(SurfaceId::Fills).concat(&matrix);
                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.canvas(SurfaceId::Fills))
                } else {
                    let font_manager = skia::FontMgr::from(self.fonts().font_provider().clone());
                    let dom_result = skia::svg::Dom::from_str(&sr.content, font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.canvas(SurfaceId::Fills));
                            shape.to_mut().set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }

            Type::Text(text_content) => {
                let surface_ids = SurfaceId::Strokes as u32
                    | SurfaceId::Fills as u32
                    | SurfaceId::DropShadows as u32
                    | SurfaceId::InnerShadows as u32;
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

                let text_content = text_content.new_bounds(shape.selrect());
                let mut paragraphs = text_content.to_paragraphs(
                    shape.image_filter(1.).as_ref(),
                    shape.mask_filter(1.).as_ref(),
                );

                if !shape.has_visible_strokes() {
                    shadows::render_text_drop_shadows(self, &shape, &mut paragraphs, antialias);
                }

                let count_inner_strokes = shape.count_visible_inner_strokes();
                text::render(self, &shape, &mut paragraphs, None, None);

                for stroke in shape.visible_strokes().rev() {
                    let mut stroke_paragraphs = text_content.to_stroke_paragraphs(
                        stroke,
                        &shape.selrect(),
                        shape.image_filter(1.).as_ref(),
                        shape.mask_filter(1.).as_ref(),
                        count_inner_strokes,
                    );
                    shadows::render_text_drop_shadows(
                        self,
                        &shape,
                        &mut stroke_paragraphs,
                        antialias,
                    );
                    strokes::render(
                        self,
                        &shape,
                        stroke,
                        None,
                        None,
                        Some(&mut stroke_paragraphs),
                        antialias,
                        None,
                    );
                    shadows::render_text_inner_shadows(
                        self,
                        &shape,
                        &mut stroke_paragraphs,
                        antialias,
                    );
                }

                shadows::render_text_inner_shadows(self, &shape, &mut paragraphs, antialias);
            }
            _ => {
                let surface_ids = SurfaceId::Strokes as u32
                    | SurfaceId::Fills as u32
                    | SurfaceId::DropShadows as u32
                    | SurfaceId::InnerShadows as u32;
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

                let shape = if let Type::Bool(_) = &shape.shape_type {
                    // If any child transform doesn't match the parent transform means
                    // that the children is transformed and we need to recalculate the
                    // boolean
                    if is_modified_child(&shape, shapes, modifiers) {
                        &bools::update_bool_to_path(&shape, shapes, modifiers, structure)
                    } else {
                        &shape
                    }
                } else {
                    &shape
                };

                let has_fill_none = matches!(
                    shape.svg_attrs.get("fill").map(String::as_str),
                    Some("none")
                );

                if shape.fills.is_empty()
                    && !matches!(shape.shape_type, Type::Group(_))
                    && !has_fill_none
                {
                    if let Some(fills_to_render) = self.nested_fills.last() {
                        let fills_to_render = fills_to_render.clone();
                        for fill in fills_to_render.iter() {
                            fills::render(self, shape, fill, antialias);
                        }
                    }
                } else {
                    for fill in shape.fills().rev() {
                        fills::render(self, shape, fill, antialias);
                    }
                }

                for stroke in shape.visible_strokes().rev() {
                    shadows::render_stroke_drop_shadows(self, shape, stroke, antialias);
                    //In clipped content strokes are drawn over the contained elements in a subsequent step
                    if !shape.clip() {
                        strokes::render(self, shape, stroke, None, None, None, antialias, None);
                    }
                    shadows::render_stroke_inner_shadows(self, shape, stroke, antialias);
                }

                shadows::render_fill_inner_shadows(self, shape, antialias);
                shadows::render_fill_drop_shadows(self, shape, antialias);
                // bools::debug_render_bool_paths(self, shape, shapes, modifiers, structure);
            }
        };
        self.apply_drawing_to_render_canvas(Some(&shape));
        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::DropShadows as u32
            | SurfaceId::InnerShadows as u32;
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().restore();
        });
    }

    pub fn update_render_context(&mut self, tile: tiles::Tile) {
        self.current_tile = Some(tile);
        self.render_area = tiles::get_tile_rect(tile, self.get_scale());
        self.surfaces
            .update_render_context(self.render_area, self.get_scale());
    }

    pub fn cancel_animation_frame(&mut self) {
        if self.render_in_progress {
            if let Some(frame_id) = self.render_request_id {
                wapi::cancel_animation_frame!(frame_id);
            }
        }
    }

    pub fn render_from_cache(
        &mut self,
        shapes: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
    ) {
        let scale = self.get_cached_scale();
        if let Some(snapshot) = &self.cached_target_snapshot {
            let canvas = self.surfaces.canvas(SurfaceId::Target);
            canvas.save();

            // Scale and translate the target according to the cached data
            let navigate_zoom = self.viewbox.zoom / self.cached_viewbox.zoom;

            canvas.scale((navigate_zoom, navigate_zoom));

            let TileRect(start_tile_x, start_tile_y, _, _) =
                tiles::get_tiles_for_viewbox_with_interest(
                    self.cached_viewbox,
                    VIEWPORT_INTEREST_AREA_THRESHOLD,
                    scale,
                );
            let offset_x = self.viewbox.area.left * self.cached_viewbox.zoom * self.options.dpr();
            let offset_y = self.viewbox.area.top * self.cached_viewbox.zoom * self.options.dpr();

            canvas.translate((
                (start_tile_x as f32 * tiles::TILE_SIZE) - offset_x,
                (start_tile_y as f32 * tiles::TILE_SIZE) - offset_y,
            ));

            canvas.clear(self.background_color);
            canvas.draw_image(snapshot, (0, 0), Some(&skia::Paint::default()));
            canvas.restore();

            ui::render(self, shapes, modifiers, structure);
            debug::render_wasm_label(self);

            self.flush_and_submit();
        }
    }

    pub fn start_render_loop(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        scale_content: &HashMap<Uuid, f32>,
        timestamp: i32,
    ) -> Result<(), String> {
        let scale = self.get_scale();
        self.tile_viewbox.update(self.viewbox, scale);

        self.focus_mode.reset();

        performance::begin_measure!("render");
        performance::begin_measure!("start_render_loop");

        self.reset_canvas();
        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::DropShadows as u32
            | SurfaceId::InnerShadows as u32;
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().scale((scale, scale));
        });

        let viewbox_cache_size = get_cache_size(self.viewbox, scale);
        let cached_viewbox_cache_size = get_cache_size(self.cached_viewbox, scale);
        if viewbox_cache_size != cached_viewbox_cache_size {
            self.surfaces.resize_cache(
                &mut self.gpu_state,
                viewbox_cache_size,
                VIEWPORT_INTEREST_AREA_THRESHOLD,
            );
        }

        debug::render_debug_tiles_for_viewbox(self);

        performance::begin_measure!("tile_cache");
        self.pending_tiles.update(&self.tile_viewbox);
        performance::end_measure!("tile_cache");

        self.pending_nodes.clear();
        if self.pending_nodes.capacity() < tree.len() {
            self.pending_nodes
                .reserve(tree.len() - self.pending_nodes.capacity());
        }
        // reorder by distance to the center.
        self.current_tile = None;
        self.render_in_progress = true;
        self.apply_drawing_to_render_canvas(None);
        self.process_animation_frame(tree, modifiers, structure, scale_content, timestamp)?;
        performance::end_measure!("start_render_loop");
        Ok(())
    }

    pub fn process_animation_frame(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        scale_content: &HashMap<Uuid, f32>,
        timestamp: i32,
    ) -> Result<(), String> {
        performance::begin_measure!("process_animation_frame");
        if self.render_in_progress {
            self.render_shape_tree_partial(tree, modifiers, structure, scale_content, timestamp)?;
            self.flush_and_submit();

            if self.render_in_progress {
                self.cancel_animation_frame();
                self.render_request_id = Some(wapi::request_animation_frame!());
            } else {
                performance::end_measure!("render");
            }
        }
        performance::end_measure!("process_animation_frame");
        Ok(())
    }

    #[inline]
    pub fn should_stop_rendering(&self, iteration: i32, timestamp: i32) -> bool {
        iteration % NODE_BATCH_THRESHOLD == 0
            && performance::get_time() - timestamp > MAX_BLOCKING_TIME_MS
    }

    #[inline]
    pub fn render_shape_enter(&mut self, element: &Shape, mask: bool) {
        // Masked groups needs two rendering passes, the first one rendering
        // the content and the second one rendering the mask so we need to do
        // an extra save_layer to keep all the masked group separate from
        // other already drawn elements.
        if let Type::Group(group) = element.shape_type {
            let fills = &element.fills;
            self.nested_fills.push(fills.to_vec());
            if group.masked {
                let paint = skia::Paint::default();
                let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                self.surfaces
                    .canvas(SurfaceId::Current)
                    .save_layer(&layer_rec);
            }
        }

        match element.shape_type {
            Type::Frame(_) | Type::Group(_) => {
                self.nested_blurs.push(Some(element.blur));
            }
            _ => {}
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        // When we're rendering the mask shape we need to set a special blend mode
        // called 'destination-in' that keeps the drawn content within the mask.
        // @see https://skia.org/docs/user/api/skblendmode_overview/
        if mask {
            let mut mask_paint = skia::Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            self.surfaces
                .canvas(SurfaceId::Current)
                .save_layer(&mask_rec);
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        self.surfaces
            .canvas(SurfaceId::Current)
            .save_layer(&layer_rec);

        self.focus_mode.enter(&element.id);
    }

    #[inline]
    pub fn render_shape_exit(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        element: &Shape,
        visited_mask: bool,
        scale_content: Option<&f32>,
    ) {
        if visited_mask {
            // Because masked groups needs two rendering passes (first drawing
            // the content and then drawing the mask), we need to do an
            // extra restore.
            if let Type::Group(group) = element.shape_type {
                if group.masked {
                    self.surfaces.canvas(SurfaceId::Current).restore();
                }
            }
        } else {
            // !visited_mask
            if let Type::Group(group) = element.shape_type {
                // When we're dealing with masked groups we need to
                // do a separate extra step to draw the mask (the last
                // element of a masked group) and blend (using
                // the blend mode 'destination-in') the content
                // of the group and the mask.
                if group.masked {
                    self.pending_nodes.push(NodeRenderState {
                        id: element.id,
                        visited_children: true,
                        clip_bounds: None,
                        visited_mask: true,
                        mask: false,
                    });
                    if let Some(&mask_id) = element.mask_id() {
                        self.pending_nodes.push(NodeRenderState {
                            id: mask_id,
                            visited_children: false,
                            clip_bounds: None,
                            visited_mask: false,
                            mask: true,
                        });
                    }
                }
            }
        }
        if let Type::Group(_) = element.shape_type {
            self.nested_fills.pop();
        }

        match element.shape_type {
            Type::Frame(_) | Type::Group(_) => {
                self.nested_blurs.pop();
            }
            _ => {}
        }

        //In clipped content strokes are drawn over the contained elements
        if element.clip() {
            let mut element_strokes: Cow<Shape> = Cow::Borrowed(element);
            element_strokes.to_mut().clear_fills();
            element_strokes.to_mut().clear_shadows();
            element_strokes.to_mut().clip_content = false;
            self.render_shape(
                tree,
                modifiers,
                structure,
                &element_strokes,
                scale_content,
                None,
            );
        }

        self.surfaces.canvas(SurfaceId::Current).restore();
        self.focus_mode.exit(&element.id);
    }

    pub fn get_current_tile_bounds(&mut self) -> Rect {
        let tiles::Tile(tile_x, tile_y) = self.current_tile.unwrap();
        let scale = self.get_scale();
        let offset_x = self.viewbox.area.left * scale;
        let offset_y = self.viewbox.area.top * scale;
        Rect::from_xywh(
            (tile_x as f32 * tiles::TILE_SIZE) - offset_x,
            (tile_y as f32 * tiles::TILE_SIZE) - offset_y,
            tiles::TILE_SIZE,
            tiles::TILE_SIZE,
        )
    }

    pub fn get_aligned_tile_bounds(&mut self, tile: tiles::Tile) -> Rect {
        let scale = self.get_scale();
        let start_tile_x =
            (self.viewbox.area.left * scale / tiles::TILE_SIZE).floor() * tiles::TILE_SIZE;
        let start_tile_y =
            (self.viewbox.area.top * scale / tiles::TILE_SIZE).floor() * tiles::TILE_SIZE;
        Rect::from_xywh(
            (tile.0 as f32 * tiles::TILE_SIZE) - start_tile_x,
            (tile.1 as f32 * tiles::TILE_SIZE) - start_tile_y,
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
    pub fn get_current_aligned_tile_bounds(&mut self) -> Rect {
        self.get_aligned_tile_bounds(self.current_tile.unwrap())
    }

    pub fn render_shape_tree_partial_uncached(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        scale_content: &HashMap<Uuid, f32>,
        timestamp: i32,
    ) -> Result<(bool, bool), String> {
        let mut iteration = 0;
        let mut is_empty = true;
        while let Some(node_render_state) = self.pending_nodes.pop() {
            let NodeRenderState {
                id: node_id,
                visited_children,
                clip_bounds,
                visited_mask,
                mask,
            } = node_render_state;

            is_empty = false;
            let element = tree.get(&node_id).ok_or(
                "Error: Element with root_id {node_render_state.id} not found in the tree."
                    .to_string(),
            )?;

            // If the shape is not in the tile set, then we update
            // it.
            if self.tiles.get_tiles_of(node_id).is_none() {
                self.update_tile_for(element, tree, modifiers);
            }

            if visited_children {
                self.render_shape_exit(
                    tree,
                    modifiers,
                    structure,
                    element,
                    visited_mask,
                    scale_content.get(&element.id),
                );
                continue;
            }

            if !node_render_state.is_root() {
                let mut transformed_element: Cow<Shape> = Cow::Borrowed(element);

                if let Some(modifier) = modifiers.get(&node_id) {
                    transformed_element.to_mut().apply_transform(modifier);
                }

                let is_visible = transformed_element
                    .extrect(tree, modifiers)
                    .intersects(self.render_area)
                    && !transformed_element.hidden
                    && !transformed_element.visually_insignificant(
                        self.get_scale(),
                        tree,
                        modifiers,
                    );

                if self.options.is_debug_visible() {
                    debug::render_debug_shape(
                        self,
                        &transformed_element,
                        is_visible,
                        tree,
                        modifiers,
                    );
                }

                if !is_visible {
                    continue;
                }
            }

            self.render_shape_enter(element, mask);
            if !node_render_state.is_root() && self.focus_mode.is_active() {
                self.render_shape(
                    tree,
                    modifiers,
                    structure,
                    element,
                    scale_content.get(&element.id),
                    clip_bounds,
                );
            } else if visited_children {
                self.apply_drawing_to_render_canvas(Some(element));
            }

            // Set the node as visited_children before processing children
            self.pending_nodes.push(NodeRenderState {
                id: node_id,
                visited_children: true,
                clip_bounds,
                visited_mask: false,
                mask,
            });

            if element.is_recursive() {
                let children_clip_bounds =
                    node_render_state.get_children_clip_bounds(element, modifiers.get(&element.id));

                let mut children_ids =
                    element.modified_children_ids(structure.get(&element.id), false);

                // Z-index ordering on Layouts
                if element.has_layout() {
                    children_ids.sort_by(|id1, id2| {
                        let z1 = tree.get(id1).map_or_else(|| 0, |s| s.z_index());
                        let z2 = tree.get(id2).map_or_else(|| 0, |s| s.z_index());
                        z1.cmp(&z2)
                    });
                }

                for child_id in children_ids.iter() {
                    self.pending_nodes.push(NodeRenderState {
                        id: *child_id,
                        visited_children: false,
                        clip_bounds: children_clip_bounds,
                        visited_mask: false,
                        mask: false,
                    });
                }
            }

            // We try to avoid doing too many calls to get_time
            if self.should_stop_rendering(iteration, timestamp) {
                return Ok((is_empty, true));
            }
            iteration += 1;
        }
        Ok((is_empty, false))
    }

    pub fn render_shape_tree_partial(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
        scale_content: &HashMap<Uuid, f32>,
        timestamp: i32,
    ) -> Result<(), String> {
        let mut should_stop = false;
        while !should_stop {
            if let Some(current_tile) = self.current_tile {
                if self.surfaces.has_cached_tile_surface(current_tile) {
                    performance::begin_measure!("render_shape_tree::cached");
                    let tile_rect = self.get_current_tile_bounds();
                    self.surfaces.draw_cached_tile_surface(
                        current_tile,
                        tile_rect,
                        self.background_color,
                    );
                    performance::end_measure!("render_shape_tree::cached");

                    if self.options.is_debug_visible() {
                        debug::render_workspace_current_tile(
                            self,
                            "Cached".to_string(),
                            current_tile,
                            tile_rect,
                        );
                    }
                } else {
                    performance::begin_measure!("render_shape_tree::uncached");
                    let (is_empty, early_return) = self.render_shape_tree_partial_uncached(
                        tree,
                        modifiers,
                        structure,
                        scale_content,
                        timestamp,
                    )?;
                    if early_return {
                        return Ok(());
                    }
                    performance::end_measure!("render_shape_tree::uncached");
                    let tile_rect = self.get_current_tile_bounds();
                    if !is_empty {
                        self.apply_render_to_final_canvas(tile_rect);
                    } else {
                        self.surfaces.apply_mut(SurfaceId::Target as u32, |s| {
                            let mut paint = skia::Paint::default();
                            paint.set_color(self.background_color);
                            s.canvas().draw_rect(tile_rect, &paint);
                        });
                    }
                }
            }

            self.surfaces
                .canvas(SurfaceId::Current)
                .clear(self.background_color);

            let Some(root) = tree.get(&Uuid::nil()) else {
                return Err(String::from("Root shape not found"));
            };
            let root_ids = root.modified_children_ids(structure.get(&root.id), false);

            // If we finish processing every node rendering is complete
            // let's check if there are more pending nodes
            if let Some(next_tile) = self.pending_tiles.pop() {
                self.update_render_context(next_tile);

                if !self.surfaces.has_cached_tile_surface(next_tile) {
                    if let Some(ids) = self.tiles.get_shapes_at(next_tile) {
                        // We only need first level shapes
                        let mut valid_ids: Vec<Uuid> = ids
                            .iter()
                            .filter_map(|id| root_ids.get(id).map(|_| *id))
                            .collect();

                        // These shapes for the tile should be ordered as they are in the parent node
                        valid_ids.sort_by_key(|id| root_ids.get_index_of(id));

                        self.pending_nodes.extend(valid_ids.into_iter().map(|id| {
                            NodeRenderState {
                                id,
                                visited_children: false,
                                clip_bounds: None,
                                visited_mask: false,
                                mask: false,
                            }
                        }));
                    }
                }
            } else {
                should_stop = true;
            }
        }
        self.render_in_progress = false;

        // Cache target surface in a texture
        self.cached_viewbox = self.viewbox;
        self.cached_target_snapshot = Some(self.surfaces.snapshot(SurfaceId::Cache));

        if self.options.is_debug_visible() {
            debug::render(self);
        }

        ui::render(self, tree, modifiers, structure);
        debug::render_wasm_label(self);

        Ok(())
    }

    pub fn get_tiles_for_shape(
        &mut self,
        shape: &Shape,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
    ) -> TileRect {
        let tile_size = tiles::get_tile_size(self.get_scale());
        tiles::get_tiles_for_rect(shape.extrect(tree, modifiers), tile_size)
    }

    pub fn update_tile_for(
        &mut self,
        shape: &Shape,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
    ) {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree, modifiers);
        let old_tiles: HashSet<tiles::Tile> = self
            .tiles
            .get_tiles_of(shape.id)
            .map_or(HashSet::new(), |tiles| tiles.iter().cloned().collect());
        let new_tiles: HashSet<tiles::Tile> = (rsx..=rex)
            .flat_map(|x| (rsy..=rey).map(move |y| tiles::Tile(x, y)))
            .collect();

        // First, remove the shape from all tiles where it was previously located
        for tile in old_tiles {
            self.remove_cached_tile_shape(tile, shape.id);
        }

        // Then, add the shape to the new tiles
        for tile in new_tiles {
            self.tiles.add_shape_at(tile, shape.id);
        }
    }

    pub fn remove_cached_tile_shape(&mut self, tile: tiles::Tile, id: Uuid) {
        let rect = self.get_aligned_tile_bounds(tile);
        self.surfaces
            .remove_cached_tile_surface(tile, rect, self.background_color);
        self.tiles.remove_shape_at(tile, id);
    }

    pub fn rebuild_tiles_shallow(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
    ) {
        performance::begin_measure!("rebuild_tiles_shallow");
        self.tiles.invalidate();
        self.surfaces.remove_cached_tiles(self.background_color);
        let mut nodes = vec![Uuid::nil()];
        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                let mut shape: Cow<Shape> = Cow::Borrowed(shape);
                if shape_id != Uuid::nil() {
                    if let Some(modifier) = modifiers.get(&shape_id) {
                        shape.to_mut().apply_transform(modifier);
                    }
                    self.update_tile_for(&shape, tree, modifiers);
                } else {
                    // We only need to rebuild tiles from the first level.
                    let children = shape.modified_children_ids(structure.get(&shape.id), false);
                    for child_id in children.iter() {
                        nodes.push(*child_id);
                    }
                }
            }
        }
        performance::end_measure!("rebuild_tiles_shallow");
    }

    pub fn rebuild_tiles(
        &mut self,
        tree: &ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
        structure: &HashMap<Uuid, Vec<StructureEntry>>,
    ) {
        performance::begin_measure!("rebuild_tiles");
        self.tiles.invalidate();
        self.surfaces.remove_cached_tiles(self.background_color);
        let mut nodes = vec![Uuid::nil()];
        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                let mut shape: Cow<Shape> = Cow::Borrowed(shape);
                if shape_id != Uuid::nil() {
                    if let Some(modifier) = modifiers.get(&shape_id) {
                        shape.to_mut().apply_transform(modifier);
                    }
                    self.update_tile_for(&shape, tree, modifiers);
                }

                let children = shape.modified_children_ids(structure.get(&shape.id), false);
                for child_id in children.iter() {
                    nodes.push(*child_id);
                }
            }
        }
        performance::end_measure!("rebuild_tiles");
    }

    /// Processes all ancestors of a shape, invalidating their extended rectangles and updating their tiles
    ///
    /// When a shape changes, all its ancestors need to have their extended rectangles recalculated
    /// because they may contain the changed shape. This function:
    /// 1. Invalidates the extrect cache for each ancestor
    /// 2. Updates the tiles for each ancestor to ensure proper rendering
    pub fn process_shape_ancestors(
        &mut self,
        shape: &Shape,
        tree: &mut ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
    ) {
        for ancestor in shape.all_ancestors(tree, false) {
            if let Some(ancestor) = tree.get_mut(&ancestor) {
                ancestor.invalidate_extrect();
            }
            if let Some(ancestor) = tree.get(&ancestor) {
                if !ancestor.id.is_nil() {
                    self.update_tile_for(ancestor, tree, modifiers);
                }
            }
        }
    }

    /// Rebuilds tiles for shapes with modifiers and processes their ancestors
    ///
    /// This function applies transformation modifiers to shapes and updates their tiles.
    /// Additionally, it processes all ancestors of modified shapes to ensure their
    /// extended rectangles are properly recalculated and their tiles are updated.
    /// This is crucial for frames and groups that contain transformed children.
    pub fn rebuild_modifier_tiles(
        &mut self,
        tree: &mut ShapesPool,
        modifiers: &HashMap<Uuid, Matrix>,
    ) {
        for (uuid, matrix) in modifiers {
            let mut shape = {
                let Some(shape) = tree.get(uuid) else {
                    panic!("Invalid current shape")
                };
                shape.clone()
            };

            shape.apply_transform(matrix);
            self.update_tile_for(&shape, tree, modifiers);
            self.process_shape_ancestors(&shape, tree, modifiers);
        }
    }

    pub fn get_scale(&self) -> f32 {
        self.viewbox.zoom() * self.options.dpr()
    }

    pub fn get_cached_scale(&self) -> f32 {
        self.cached_viewbox.zoom() * self.options.dpr()
    }
}
