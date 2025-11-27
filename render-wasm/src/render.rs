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
use std::collections::HashSet;

use gpu_state::GpuState;
use options::RenderOptions;
pub use surfaces::{SurfaceId, Surfaces};

use crate::performance;
use crate::shapes::{
    all_with_ancestors, Blur, BlurType, Corners, Fill, Shadow, Shape, SolidColor, Stroke, Type,
};
use crate::state::{ShapesPoolMutRef, ShapesPoolRef};
use crate::tiles::{self, PendingTiles, TileRect};
use crate::uuid::Uuid;
use crate::view::Viewbox;
use crate::wapi;

pub use fonts::*;
pub use images::*;

// This is the extra are used for tile rendering.
const VIEWPORT_INTEREST_AREA_THRESHOLD: i32 = 1;
const MAX_BLOCKING_TIME_MS: i32 = 32;
const NODE_BATCH_THRESHOLD: i32 = 10;

type ClipStack = Vec<(Rect, Option<Corners>, Matrix)>;

pub struct NodeRenderState {
    pub id: Uuid,
    // We use this bool to keep that we've traversed all the children inside this node.
    visited_children: bool,
    // This is used to clip the content of frames.
    clip_bounds: Option<ClipStack>,
    // This is a flag to indicate that we've already drawn the mask of a masked group.
    visited_mask: bool,
    // This bool indicates that we're drawing the mask shape.
    mask: bool,
}

impl NodeRenderState {
    pub fn is_root(&self) -> bool {
        self.id.is_nil()
    }

    /// Calculates the clip bounds for child elements of a given shape.
    ///
    /// This function determines the clipping region that should be applied to child elements
    /// when rendering. It takes into account the element's selection rectangle, transform.
    ///
    /// # Parameters
    ///
    /// * `element` - The shape element for which to calculate clip bounds
    /// * `offset` - Optional offset (x, y) to adjust the bounds position. When provided,
    ///   the bounds are translated by the negative of this offset, effectively moving
    ///   the clipping region to compensate for coordinate system transformations.
    ///   This is useful for nested coordinate systems or when elements are grouped
    ///   and need relative positioning adjustments.
    fn append_clip(
        clip_stack: Option<ClipStack>,
        clip: (Rect, Option<Corners>, Matrix),
    ) -> Option<ClipStack> {
        match clip_stack {
            Some(mut stack) => {
                stack.push(clip);
                Some(stack)
            }
            None => Some(vec![clip]),
        }
    }

    pub fn get_children_clip_bounds(
        &self,
        element: &Shape,
        offset: Option<(f32, f32)>,
    ) -> Option<ClipStack> {
        if self.id.is_nil() || !element.clip() {
            return self.clip_bounds.clone();
        }

        let mut bounds = element.selrect();
        if let Some(offset) = offset {
            let x = bounds.x() - offset.0;
            let y = bounds.y() - offset.1;
            let width = bounds.width();
            let height = bounds.height();
            bounds.set_xywh(x, y, width, height);
        }
        let mut transform = element.transform;
        transform.post_translate(bounds.center());
        transform.pre_translate(-bounds.center());

        let corners = match &element.shape_type {
            Type::Rect(data) => data.corners,
            Type::Frame(data) => data.corners,
            _ => None,
        };

        Self::append_clip(self.clip_bounds.clone(), (bounds, corners, transform))
    }

    /// Calculates the clip bounds for shadow rendering of a given shape.
    ///
    /// This function determines the clipping region that should be applied when rendering a
    /// shadow for a shape element. For frames, it uses the shadow bounds to clip nested
    /// shadows. For groups, it returns the existing clip bounds since groups should not
    /// constrain nested shadows based on their selection rectangle bounds.
    ///
    /// # Parameters
    ///
    /// * `element` - The shape element for which to calculate shadow clip bounds
    /// * `shadow` - The shadow configuration containing blur, offset, and other properties
    pub fn get_nested_shadow_clip_bounds(
        &self,
        element: &Shape,
        shadow: &Shadow,
    ) -> Option<ClipStack> {
        if self.id.is_nil() {
            return self.clip_bounds.clone();
        }

        // Assert that the shape is either a Frame or Group
        assert!(
            matches!(element.shape_type, Type::Frame(_) | Type::Group(_)),
            "Shape must be a Frame or Group for nested shadow clip bounds calculation"
        );

        match &element.shape_type {
            Type::Frame(_) => {
                let bounds = element.get_selrect_shadow_bounds(shadow);
                let mut transform = element.transform;
                transform.post_translate(element.center());
                transform.pre_translate(-element.center());

                let corners = match &element.shape_type {
                    Type::Frame(data) => data.corners,
                    _ => None,
                };

                Self::append_clip(self.clip_bounds.clone(), (bounds, corners, transform))
            }
            _ => self.clip_bounds.clone(),
        }
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
    // Frames contained in groups must reset this nested_fills stack pushing a new empty vector.
    pub nested_fills: Vec<Vec<Fill>>,
    pub nested_blurs: Vec<Option<Blur>>, // FIXME: why is this an option?
    pub nested_shadows: Vec<Vec<Shadow>>,
    pub show_grid: Option<Uuid>,
    pub focus_mode: FocusMode,
    pub touched_ids: HashSet<Uuid>,
    /// Temporary flag used for off-screen passes (drop-shadow masks, filter surfaces, etc.)
    /// where we must render shapes without inheriting ancestor layer blurs. Toggle it through
    /// `with_nested_blurs_suppressed` to ensure it's always restored.
    pub ignore_nested_blurs: bool,
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
            nested_shadows: vec![],
            show_grid: None,
            focus_mode: FocusMode::new(),
            touched_ids: HashSet::default(),
            ignore_nested_blurs: false,
        }
    }

    /// Combines every visible layer blur currently active (ancestors + shape)
    /// into a single equivalent blur. Layer blur radii compound by adding their
    /// variances (σ² = radius²), so we:
    ///   1. Convert each blur radius into variance via `blur_variance`.
    ///   2. Sum all variances.
    ///   3. Convert the total variance back to a radius with `blur_from_variance`.
    ///
    /// This keeps blur math consistent everywhere we need to merge blur sources.
    fn combined_layer_blur(&self, shape_blur: Option<Blur>) -> Option<Blur> {
        let mut total = 0.;

        for nested_blur in self.nested_blurs.iter().flatten() {
            total += Self::blur_variance(Some(*nested_blur));
        }

        total += Self::blur_variance(shape_blur);

        Self::blur_from_variance(total)
    }

    /// Returns the variance (radius²) for a visible layer blur, or zero if the
    /// blur is hidden/absent. Working in variance space lets us add multiple
    /// blur radii correctly.
    fn blur_variance(blur: Option<Blur>) -> f32 {
        match blur {
            Some(blur) if !blur.hidden && blur.blur_type == BlurType::LayerBlur => {
                blur.value.powi(2)
            }
            _ => 0.,
        }
    }

    /// Builds a blur from an accumulated variance value. If no variance was
    /// contributed, we return `None`; otherwise the equivalent single radius is
    /// `sqrt(total)`.
    fn blur_from_variance(total: f32) -> Option<Blur> {
        (total > 0.).then(|| Blur::new(BlurType::LayerBlur, false, total.sqrt()))
    }

    /// Convenience helper to merge two optional layer blurs using the same
    /// variance math as `combined_layer_blur`.
    fn combine_blur_values(base: Option<Blur>, extra: Option<Blur>) -> Option<Blur> {
        let total = Self::blur_variance(base) + Self::blur_variance(extra);
        Self::blur_from_variance(total)
    }

    fn frame_clip_layer_blur(shape: &Shape) -> Option<Blur> {
        match shape.shape_type {
            Type::Frame(_) if shape.clip() => shape.blur.filter(|blur| {
                !blur.hidden && blur.blur_type == BlurType::LayerBlur && blur.value > 0.
            }),
            _ => None,
        }
    }

    /// Runs `f` with `ignore_nested_blurs` temporarily forced to `true`.
    /// Certain off-screen passes (e.g. shadow masks) must render shapes without
    /// inheriting ancestor blur. This helper guarantees the flag is restored.
    fn with_nested_blurs_suppressed<F, R>(&mut self, f: F) -> R
    where
        F: FnOnce(&mut RenderState) -> R,
    {
        let previous = self.ignore_nested_blurs;
        self.ignore_nested_blurs = true;
        let result = f(self);
        self.ignore_nested_blurs = previous;
        result
    }

    pub fn fonts(&self) -> &FontStore {
        &self.fonts
    }

    pub fn fonts_mut(&mut self) -> &mut FontStore {
        &mut self.fonts
    }

    pub fn add_image(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        image_data: &[u8],
    ) -> Result<(), String> {
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
    ) -> Result<(), String> {
        self.images
            .add_image_from_gl_texture(id, is_thumbnail, texture_id, width, height)
    }

    pub fn has_image(&self, id: &Uuid, is_thumbnail: bool) -> bool {
        self.images.contains(id, is_thumbnail)
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
    }

    pub fn apply_drawing_to_render_canvas(&mut self, shape: Option<&Shape>) {
        performance::begin_measure!("apply_drawing_to_render_canvas");

        let paint = skia::Paint::default();

        self.surfaces
            .draw_into(SurfaceId::TextDropShadows, SurfaceId::Current, Some(&paint));

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
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32;

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

    fn get_inherited_drop_shadows(&self) -> Option<Vec<skia_safe::Paint>> {
        let drop_shadows: Vec<&Shadow> = self
            .nested_shadows
            .iter()
            .flat_map(|shadows| shadows.iter())
            .filter(|shadow| !shadow.hidden() && shadow.style() == crate::shapes::ShadowStyle::Drop)
            .collect();

        if drop_shadows.is_empty() {
            return None;
        }

        Some(
            drop_shadows
                .into_iter()
                .map(|shadow| {
                    let mut paint = skia_safe::Paint::default();
                    let filter = shadow.get_drop_shadow_filter();
                    paint.set_image_filter(filter);
                    paint
                })
                .collect(),
        )
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
    ) {
        let surface_ids = fills_surface_id as u32
            | strokes_surface_id as u32
            | innershadows_surface_id as u32
            | text_drop_shadows_surface_id as u32;
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().save();
        });

        let antialias = shape.should_use_antialias(self.get_scale());

        // set clipping
        if let Some(clips) = clip_bounds.as_ref() {
            for (bounds, corners, transform) in clips.iter() {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(transform);
                });

                if let Some(corners) = corners {
                    let rrect = RRect::new_rect_radii(*bounds, corners);
                    self.surfaces.apply_mut(surface_ids, |s| {
                        s.canvas()
                            .clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
                    });
                } else {
                    self.surfaces.apply_mut(surface_ids, |s| {
                        s.canvas()
                            .clip_rect(*bounds, skia::ClipOp::Intersect, antialias);
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
                        .draw_rect(*bounds, &paint);
                }

                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas()
                        .concat(&transform.invert().unwrap_or(Matrix::default()));
                });
            }
        }

        // We don't want to change the value in the global state
        let mut shape: Cow<Shape> = Cow::Borrowed(shape);
        let frame_has_blur = Self::frame_clip_layer_blur(&shape).is_some();
        let shape_has_blur = shape.blur.is_some();

        if self.ignore_nested_blurs {
            if frame_has_blur && shape_has_blur {
                shape.to_mut().set_blur(None);
            }
        } else if !frame_has_blur {
            if let Some(blur) = self.combined_layer_blur(shape.blur) {
                shape.to_mut().set_blur(Some(blur));
            }
        } else if shape_has_blur {
            shape.to_mut().set_blur(None);
        }

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

                self.surfaces.canvas(fills_surface_id).concat(&matrix);

                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.canvas(fills_surface_id))
                } else {
                    let font_manager = skia::FontMgr::from(self.fonts().font_provider().clone());
                    let dom_result = skia::svg::Dom::from_str(&sr.content, font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.canvas(fills_surface_id));
                            shape.to_mut().set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }

            Type::Text(text_content) => {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

                let text_content = text_content.new_bounds(shape.selrect());
                let mut drop_shadows = shape.drop_shadow_paints();

                if let Some(inherited_shadows) = self.get_inherited_drop_shadows() {
                    drop_shadows.extend(inherited_shadows);
                }

                let inner_shadows = shape.inner_shadow_paints();
                let blur_filter = shape.image_filter(1.);
                let count_inner_strokes = shape.count_visible_inner_strokes();
                let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
                let mut paragraphs_with_shadows =
                    text_content.paragraph_builder_group_from_text(Some(true));
                let mut stroke_paragraphs_list = shape
                    .visible_strokes()
                    .rev()
                    .map(|stroke| {
                        text::stroke_paragraph_builder_group_from_text(
                            &text_content,
                            stroke,
                            &shape.selrect(),
                            count_inner_strokes,
                            None,
                        )
                    })
                    .collect::<Vec<_>>();

                let mut stroke_paragraphs_with_shadows_list = shape
                    .visible_strokes()
                    .rev()
                    .map(|stroke| {
                        text::stroke_paragraph_builder_group_from_text(
                            &text_content,
                            stroke,
                            &shape.selrect(),
                            count_inner_strokes,
                            Some(true),
                        )
                    })
                    .collect::<Vec<_>>();

                if let Some(parent_shadows) = parent_shadows {
                    if !shape.has_visible_strokes() {
                        for shadow in parent_shadows {
                            text::render(
                                Some(self),
                                None,
                                &shape,
                                &mut paragraphs_with_shadows,
                                text_drop_shadows_surface_id.into(),
                                Some(&shadow),
                                blur_filter.as_ref(),
                            );
                        }
                    } else {
                        shadows::render_text_shadows(
                            self,
                            &shape,
                            &mut paragraphs_with_shadows,
                            &mut stroke_paragraphs_with_shadows_list,
                            text_drop_shadows_surface_id.into(),
                            &parent_shadows,
                            &blur_filter,
                        );
                    }
                } else {
                    // 1. Text drop shadows
                    if !shape.has_visible_strokes() {
                        for shadow in &drop_shadows {
                            text::render(
                                Some(self),
                                None,
                                &shape,
                                &mut paragraphs_with_shadows,
                                text_drop_shadows_surface_id.into(),
                                Some(shadow),
                                blur_filter.as_ref(),
                            );
                        }
                    }

                    // 2. Text fills
                    text::render(
                        Some(self),
                        None,
                        &shape,
                        &mut paragraph_builders,
                        Some(fills_surface_id),
                        None,
                        blur_filter.as_ref(),
                    );

                    // 3. Stroke drop shadows
                    shadows::render_text_shadows(
                        self,
                        &shape,
                        &mut paragraphs_with_shadows,
                        &mut stroke_paragraphs_with_shadows_list,
                        text_drop_shadows_surface_id.into(),
                        &drop_shadows,
                        &blur_filter,
                    );

                    // 4. Stroke fills
                    for stroke_paragraphs in stroke_paragraphs_list.iter_mut() {
                        text::render(
                            Some(self),
                            None,
                            &shape,
                            stroke_paragraphs,
                            Some(strokes_surface_id),
                            None,
                            blur_filter.as_ref(),
                        );
                    }

                    // 5. Stroke inner shadows
                    shadows::render_text_shadows(
                        self,
                        &shape,
                        &mut paragraphs_with_shadows,
                        &mut stroke_paragraphs_with_shadows_list,
                        Some(innershadows_surface_id),
                        &inner_shadows,
                        &blur_filter,
                    );

                    // 6. Fill Inner shadows
                    if !shape.has_visible_strokes() {
                        for shadow in &inner_shadows {
                            text::render(
                                Some(self),
                                None,
                                &shape,
                                &mut paragraphs_with_shadows,
                                Some(innershadows_surface_id),
                                Some(shadow),
                                blur_filter.as_ref(),
                            );
                        }
                    }
                }
            }
            _ => {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().concat(&matrix);
                });

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
                        for fill in fills_to_render.iter() {
                            fills::render(self, shape, fill, antialias, fills_surface_id);
                        }
                    }
                } else {
                    for fill in shape.fills().rev() {
                        fills::render(self, shape, fill, antialias, fills_surface_id);
                    }
                }

                for stroke in shape.visible_strokes().rev() {
                    strokes::render(
                        self,
                        shape,
                        stroke,
                        Some(strokes_surface_id),
                        None,
                        antialias,
                    );
                    shadows::render_stroke_inner_shadows(
                        self,
                        shape,
                        stroke,
                        antialias,
                        innershadows_surface_id,
                    );
                }

                shadows::render_fill_inner_shadows(self, shape, antialias, innershadows_surface_id);
                // bools::debug_render_bool_paths(self, shape, shapes, modifiers, structure);
            }
        };

        if self.options.is_debug_visible() {
            let shape_selrect_bounds = self.get_shape_selrect_bounds(&shape);
            debug::render_debug_shape(self, Some(shape_selrect_bounds), None);
        }

        if apply_to_current_surface {
            self.apply_drawing_to_render_canvas(Some(&shape));
        }
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

    pub fn render_from_cache(&mut self, shapes: ShapesPoolRef) {
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

            if self.options.is_debug_visible() {
                debug::render(self);
            }

            ui::render(self, shapes);
            debug::render_wasm_label(self);

            self.flush_and_submit();
        }
    }

    pub fn start_render_loop(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        sync_render: bool,
    ) -> Result<(), String> {
        let scale = self.get_scale();
        self.tile_viewbox.update(self.viewbox, scale);

        self.focus_mode.reset();

        performance::begin_measure!("render");
        performance::begin_measure!("start_render_loop");

        self.reset_canvas();
        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32;
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

        // FIXME - review debug
        // debug::render_debug_tiles_for_viewbox(self);

        performance::begin_measure!("tile_cache");
        self.pending_tiles
            .update(&self.tile_viewbox, &self.surfaces);
        performance::end_measure!("tile_cache");

        self.pending_nodes.clear();
        if self.pending_nodes.capacity() < tree.len() {
            self.pending_nodes
                .reserve(tree.len() - self.pending_nodes.capacity());
        }
        // Clear nested state stacks to avoid residual fills/blurs from previous renders
        // being incorrectly applied to new frames
        self.nested_fills.clear();
        self.nested_blurs.clear();
        self.nested_shadows.clear();
        // reorder by distance to the center.
        self.current_tile = None;
        self.render_in_progress = true;
        self.apply_drawing_to_render_canvas(None);

        if sync_render {
            self.render_shape_tree_sync(base_object, tree, timestamp)?;
        } else {
            self.process_animation_frame(base_object, tree, timestamp)?;
        }

        performance::end_measure!("start_render_loop");
        Ok(())
    }

    pub fn process_animation_frame(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<(), String> {
        performance::begin_measure!("process_animation_frame");
        if self.render_in_progress {
            if tree.len() != 0 {
                self.render_shape_tree_partial(base_object, tree, timestamp, true)?;
            } else {
                println!("Empty tree");
            }
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

    pub fn render_shape_tree_sync(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<(), String> {
        if tree.len() != 0 {
            self.render_shape_tree_partial(base_object, tree, timestamp, false)?;
        } else {
            println!("Empty tree");
        }
        self.flush_and_submit();

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
            let shadows = &element.shadows;
            self.nested_fills.push(fills.to_vec());
            self.nested_shadows.push(shadows.to_vec());

            if group.masked {
                let paint = skia::Paint::default();
                let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                self.surfaces
                    .canvas(SurfaceId::Current)
                    .save_layer(&layer_rec);
            }
        }

        if let Type::Frame(_) = element.shape_type {
            self.nested_fills.push(Vec::new());
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        if let Some(frame_blur) = Self::frame_clip_layer_blur(element) {
            let scale = self.get_scale();
            let sigma = frame_blur.value * scale;
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

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
    pub fn render_shape_exit(&mut self, element: &Shape, visited_mask: bool) {
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

        match element.shape_type {
            Type::Frame(_) | Type::Group(_) => {
                self.nested_fills.pop();
                self.nested_blurs.pop();
                self.nested_shadows.pop();
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
                &element_strokes,
                None,
                SurfaceId::Fills,
                SurfaceId::Strokes,
                SurfaceId::InnerShadows,
                SurfaceId::TextDropShadows,
                true,
                None,
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
        let rect = self.get_cached_extrect(shape, tree, scale);
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
    pub fn get_current_aligned_tile_bounds(&mut self) -> Rect {
        self.get_aligned_tile_bounds(self.current_tile.unwrap())
    }

    /// Renders a drop shadow effect for the given shape.
    ///
    /// Creates a black shadow by converting the original shadow color to black,
    /// scaling the blur radius, and rendering the shape with the shadow offset applied.
    #[allow(clippy::too_many_arguments)]
    fn render_drop_black_shadow(
        &mut self,
        shape: &Shape,
        shape_bounds: &Rect,
        shadow: &Shadow,
        clip_bounds: Option<ClipStack>,
        scale: f32,
        translation: (f32, f32),
        extra_layer_blur: Option<Blur>,
    ) {
        let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);
        transformed_shadow.to_mut().offset = (0.0, 0.0);
        transformed_shadow.to_mut().color = skia::Color::BLACK;

        let mut plain_shape = Cow::Borrowed(shape);
        let combined_blur =
            Self::combine_blur_values(self.combined_layer_blur(shape.blur), extra_layer_blur);
        let blur_filter = combined_blur
            .and_then(|blur| skia::image_filters::blur((blur.value, blur.value), None, None, None));

        let mut transform_matrix = shape.transform;
        let center = shape.center();
        // Re-center the matrix so rotations/scales happen around the shape center,
        // matching how the shape itself is rendered.
        transform_matrix.post_translate(center);
        transform_matrix.pre_translate(-center);

        // Transform the local shadow offset into world coordinates so that rotations/scales
        // applied to the shape are respected when positioning the shadow.
        let mapped = transform_matrix.map_vector((shadow.offset.0, shadow.offset.1));
        let world_offset = (mapped.x, mapped.y);

        // The opacity of fills and strokes shouldn't affect the shadow,
        // so we paint everything black with the same opacity
        plain_shape.to_mut().clear_fills();
        if shape.has_fills() {
            plain_shape
                .to_mut()
                .add_fill(Fill::Solid(SolidColor(skia::Color::BLACK)));
        }

        plain_shape.to_mut().clear_strokes();
        for stroke in shape.strokes.iter() {
            plain_shape.to_mut().add_stroke(Stroke {
                fill: Fill::Solid(SolidColor(skia::Color::BLACK)),
                width: stroke.width,
                style: stroke.style,
                cap_end: stroke.cap_end,
                cap_start: stroke.cap_start,
                kind: stroke.kind,
            });
        }

        plain_shape.to_mut().clear_shadows();
        plain_shape.to_mut().blur = None;

        let Some(drop_filter) = transformed_shadow.get_drop_shadow_filter() else {
            return;
        };

        let mut bounds = drop_filter.compute_fast_bounds(shape_bounds);
        // Account for the shadow offset so the temporary surface fully contains the shifted blur.
        bounds.offset(world_offset);

        let filter_result =
            filters::render_into_filter_surface(self, bounds, |state, temp_surface| {
                {
                    let canvas = state.surfaces.canvas(temp_surface);

                    let mut shadow_paint = skia::Paint::default();
                    shadow_paint.set_image_filter(drop_filter.clone());
                    shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&shadow_paint);
                    canvas.save_layer(&layer_rec);
                }

                state.with_nested_blurs_suppressed(|state| {
                    state.render_shape(
                        &plain_shape,
                        clip_bounds,
                        temp_surface,
                        temp_surface,
                        temp_surface,
                        temp_surface,
                        false,
                        Some(shadow.offset),
                        None,
                    );
                });

                {
                    let canvas = state.surfaces.canvas(temp_surface);
                    canvas.restore();
                }
            });

        if let Some((image, filter_scale)) = filter_result {
            let drop_canvas = self.surfaces.canvas(SurfaceId::DropShadows);
            drop_canvas.save();
            drop_canvas.scale((scale, scale));
            drop_canvas.translate(translation);
            let mut drop_paint = skia::Paint::default();
            drop_paint.set_image_filter(blur_filter.clone());

            // If we scaled down in the filter surface, we need to scale back up
            if filter_scale < 1.0 {
                let scaled_width = bounds.width() * filter_scale;
                let scaled_height = bounds.height() * filter_scale;
                let src_rect = skia::Rect::from_xywh(0.0, 0.0, scaled_width, scaled_height);

                drop_canvas.save();
                drop_canvas.scale((1.0 / filter_scale, 1.0 / filter_scale));
                drop_canvas.draw_image_rect_with_sampling_options(
                    image,
                    Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
                    skia::Rect::from_xywh(
                        bounds.left * filter_scale,
                        bounds.top * filter_scale,
                        scaled_width,
                        scaled_height,
                    ),
                    self.sampling_options,
                    &drop_paint,
                );
                drop_canvas.restore();
            } else {
                let src_rect = skia::Rect::from_xywh(0.0, 0.0, bounds.width(), bounds.height());
                drop_canvas.draw_image_rect_with_sampling_options(
                    image,
                    Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
                    bounds,
                    self.sampling_options,
                    &drop_paint,
                );
            }
            drop_canvas.restore();
        }
    }

    pub fn render_shape_tree_partial_uncached(
        &mut self,
        tree: ShapesPoolRef,
        timestamp: i32,
        allow_stop: bool,
    ) -> Result<(bool, bool), String> {
        let mut iteration = 0;
        let mut is_empty = true;

        while let Some(node_render_state) = self.pending_nodes.pop() {
            let node_id = node_render_state.id;
            let visited_children = node_render_state.visited_children;
            let visited_mask = node_render_state.visited_mask;
            let mask = node_render_state.mask;
            let clip_bounds = node_render_state.clip_bounds.clone();

            is_empty = false;

            let element = tree.get(&node_id).ok_or(format!(
                "Error: Element with root_id {} not found in the tree.",
                node_render_state.id
            ))?;

            // If the shape is not in the tile set, then we add them.
            if self.tiles.get_tiles_of(node_id).is_none() {
                self.add_shape_tiles(element, tree);
            }

            if visited_children {
                self.render_shape_exit(element, visited_mask);
                continue;
            }

            if !node_render_state.is_root() {
                let transformed_element: Cow<Shape> = Cow::Borrowed(element);
                let scale = self.get_scale();
                let extrect = transformed_element.extrect(tree, scale);

                let is_visible = extrect.intersects(self.render_area)
                    && !transformed_element.hidden
                    && !transformed_element.visually_insignificant(scale, tree);

                if self.options.is_debug_visible() {
                    let shape_extrect_bounds =
                        self.get_shape_extrect_bounds(&transformed_element, tree);
                    debug::render_debug_shape(self, None, Some(shape_extrect_bounds));
                }

                if !is_visible {
                    continue;
                }
            }

            self.render_shape_enter(element, mask);

            if !node_render_state.is_root() && self.focus_mode.is_active() {
                let scale: f32 = self.get_scale();
                let translation = self
                    .surfaces
                    .get_render_context_translation(self.render_area, scale);

                // For text shapes, render drop shadow using text rendering logic
                if !matches!(element.shape_type, Type::Text(_)) {
                    // Shadow rendering technique: Two-pass approach for proper opacity handling
                    //
                    // The shadow rendering uses a two-pass technique to ensure that overlapping
                    // shadow areas maintain correct opacity without unwanted darkening:
                    //
                    // 1. First pass: Render shadow shape in pure black (alpha channel preserved)
                    //    - This creates the shadow silhouette with proper alpha gradients
                    //    - The black color acts as a mask for the final shadow color
                    //
                    // 2. Second pass: Apply actual shadow color using SrcIn blend mode
                    //    - SrcIn preserves the alpha channel from the black shadow
                    //    - Only the color channels are replaced, maintaining transparency
                    //    - This prevents overlapping shadows from accumulating opacity
                    //
                    // This approach is essential for complex shapes with transparency where
                    // multiple shadow areas might overlap, ensuring visual consistency.
                    let inherited_layer_blur = match element.shape_type {
                        Type::Frame(_) | Type::Group(_) => element.blur,
                        _ => None,
                    };

                    for shadow in element.drop_shadows_visible() {
                        let paint = skia::Paint::default();
                        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);

                        self.surfaces
                            .canvas(SurfaceId::DropShadows)
                            .save_layer(&layer_rec);

                        // First pass: Render shadow in black to establish alpha mask
                        self.render_drop_black_shadow(
                            element,
                            &element.extrect(tree, scale),
                            shadow,
                            clip_bounds.clone(),
                            scale,
                            translation,
                            None,
                        );

                        if !matches!(element.shape_type, Type::Bool(_)) {
                            // Nested shapes shadowing - apply black shadow to child shapes too
                            for shadow_shape_id in element.children.iter() {
                                let shadow_shape = tree.get(shadow_shape_id).unwrap();
                                if shadow_shape.hidden {
                                    continue;
                                }

                                let clip_bounds = node_render_state
                                    .get_nested_shadow_clip_bounds(element, shadow);

                                if !matches!(shadow_shape.shape_type, Type::Text(_)) {
                                    self.render_drop_black_shadow(
                                        shadow_shape,
                                        &shadow_shape.extrect(tree, scale),
                                        shadow,
                                        clip_bounds,
                                        scale,
                                        translation,
                                        inherited_layer_blur,
                                    );
                                } else {
                                    let paint = skia::Paint::default();
                                    let layer_rec =
                                        skia::canvas::SaveLayerRec::default().paint(&paint);

                                    self.surfaces
                                        .canvas(SurfaceId::DropShadows)
                                        .save_layer(&layer_rec);
                                    self.surfaces
                                        .canvas(SurfaceId::DropShadows)
                                        .scale((scale, scale));
                                    self.surfaces
                                        .canvas(SurfaceId::DropShadows)
                                        .translate(translation);

                                    let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);

                                    transformed_shadow.to_mut().color = skia::Color::BLACK;
                                    transformed_shadow.to_mut().blur =
                                        transformed_shadow.blur * scale;
                                    transformed_shadow.to_mut().spread =
                                        transformed_shadow.spread * scale;

                                    let mut new_shadow_paint = skia::Paint::default();
                                    new_shadow_paint.set_image_filter(
                                        transformed_shadow.get_drop_shadow_filter(),
                                    );
                                    new_shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

                                    self.with_nested_blurs_suppressed(|state| {
                                        state.render_shape(
                                            shadow_shape,
                                            clip_bounds,
                                            SurfaceId::DropShadows,
                                            SurfaceId::DropShadows,
                                            SurfaceId::DropShadows,
                                            SurfaceId::DropShadows,
                                            true,
                                            None,
                                            Some(vec![new_shadow_paint.clone()]),
                                        );
                                    });
                                    self.surfaces.canvas(SurfaceId::DropShadows).restore();
                                }
                            }
                        }

                        // Second pass: Apply actual shadow color using SrcIn blend mode
                        // This preserves the alpha channel from the black shadow while
                        // replacing only the color channels, preventing opacity accumulation
                        let mut paint = skia::Paint::default();
                        paint.set_color(shadow.color);
                        paint.set_blend_mode(skia::BlendMode::SrcIn);
                        self.surfaces
                            .canvas(SurfaceId::DropShadows)
                            .draw_paint(&paint);

                        self.surfaces.canvas(SurfaceId::DropShadows).restore();
                    }
                }

                if let Some(clips) = clip_bounds.as_ref() {
                    let antialias = element.should_use_antialias(scale);

                    self.surfaces.canvas(SurfaceId::Current).save();
                    for (bounds, corners, transform) in clips.iter() {
                        let mut total_matrix = Matrix::new_identity();
                        total_matrix.pre_scale((scale, scale), None);
                        total_matrix.pre_translate((translation.0, translation.1));
                        total_matrix.pre_concat(transform);

                        self.surfaces
                            .canvas(SurfaceId::Current)
                            .concat(&total_matrix);

                        if let Some(corners) = corners {
                            let rrect = RRect::new_rect_radii(*bounds, corners);
                            self.surfaces.canvas(SurfaceId::Current).clip_rrect(
                                rrect,
                                skia::ClipOp::Intersect,
                                antialias,
                            );
                        } else {
                            self.surfaces.canvas(SurfaceId::Current).clip_rect(
                                *bounds,
                                skia::ClipOp::Intersect,
                                antialias,
                            );
                        }

                        self.surfaces
                            .canvas(SurfaceId::Current)
                            .concat(&total_matrix.invert().unwrap_or_default());
                    }

                    self.surfaces
                        .draw_into(SurfaceId::DropShadows, SurfaceId::Current, None);

                    self.surfaces.canvas(SurfaceId::Current).restore();
                } else {
                    self.surfaces
                        .draw_into(SurfaceId::DropShadows, SurfaceId::Current, None);
                }

                self.surfaces
                    .canvas(SurfaceId::DropShadows)
                    .clear(skia::Color::TRANSPARENT);

                self.render_shape(
                    element,
                    clip_bounds.clone(),
                    SurfaceId::Fills,
                    SurfaceId::Strokes,
                    SurfaceId::InnerShadows,
                    SurfaceId::TextDropShadows,
                    true,
                    None,
                    None,
                );

                self.surfaces
                    .canvas(SurfaceId::DropShadows)
                    .clear(skia::Color::TRANSPARENT);
            } else if visited_children {
                self.apply_drawing_to_render_canvas(Some(element));
            }

            match element.shape_type {
                Type::Frame(_) if Self::frame_clip_layer_blur(element).is_some() => {
                    self.nested_blurs.push(None);
                }
                Type::Frame(_) | Type::Group(_) => {
                    self.nested_blurs.push(element.blur);
                }
                _ => {}
            }

            // Set the node as visited_children before processing children
            self.pending_nodes.push(NodeRenderState {
                id: node_id,
                visited_children: true,
                clip_bounds: clip_bounds.clone(),
                visited_mask: false,
                mask,
            });

            if element.is_recursive() {
                let children_clip_bounds =
                    node_render_state.get_children_clip_bounds(element, None);
                let mut children_ids: Vec<_> = element.children_ids_iter(false).collect();

                // Z-index ordering on Layouts
                if element.has_layout() {
                    if element.is_flex() && !element.is_flex_reverse() {
                        children_ids.reverse();
                    }

                    children_ids.sort_by(|id1, id2| {
                        let z1 = tree.get(id1).map(|s| s.z_index()).unwrap_or(0);
                        let z2 = tree.get(id2).map(|s| s.z_index()).unwrap_or(0);
                        z2.cmp(&z1)
                    });
                }

                for child_id in children_ids.iter() {
                    self.pending_nodes.push(NodeRenderState {
                        id: **child_id,
                        visited_children: false,
                        clip_bounds: children_clip_bounds.clone(),
                        visited_mask: false,
                        mask: false,
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
                    let (is_empty, early_return) =
                        self.render_shape_tree_partial_uncached(tree, timestamp, allow_stop)?;

                    if early_return {
                        return Ok(());
                    }
                    performance::end_measure!("render_shape_tree::uncached");
                    let tile_rect = self.get_current_tile_bounds();
                    if !is_empty {
                        self.apply_render_to_final_canvas(tile_rect);

                        if self.options.is_debug_visible() {
                            debug::render_workspace_current_tile(
                                self,
                                "".to_string(),
                                current_tile,
                                tile_rect,
                            );
                        }
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

            let root_ids = {
                if let Some(shape_id) = base_object {
                    vec![*shape_id]
                } else {
                    let Some(root) = tree.get(&Uuid::nil()) else {
                        return Err(String::from("Root shape not found"));
                    };
                    root.children_ids(false)
                }
            };

            // If we finish processing every node rendering is complete
            // let's check if there are more pending nodes
            if let Some(next_tile) = self.pending_tiles.pop() {
                self.update_render_context(next_tile);

                if !self.surfaces.has_cached_tile_surface(next_tile) {
                    if let Some(ids) = self.tiles.get_shapes_at(next_tile) {
                        // We only need first level shapes
                        let mut valid_ids: Vec<Uuid> = ids
                            .iter()
                            .filter(|id| root_ids.contains(id))
                            .copied()
                            .collect();

                        // These shapes for the tile should be ordered as they are in the parent node
                        valid_ids.sort_by_key(|id| {
                            root_ids.iter().position(|x| x == id).unwrap_or(usize::MAX)
                        });

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

        self.surfaces.gc();

        // Cache target surface in a texture
        self.cached_viewbox = self.viewbox;

        self.cached_target_snapshot = Some(self.surfaces.snapshot(SurfaceId::Cache));

        if self.options.is_debug_visible() {
            debug::render(self);
        }

        ui::render(self, tree);
        debug::render_wasm_label(self);

        Ok(())
    }

    /*
     * Given a shape returns the TileRect with the range of tiles that the shape is in
     */
    pub fn get_tiles_for_shape(&mut self, shape: &Shape, tree: ShapesPoolRef) -> TileRect {
        let scale = self.get_scale();
        let extrect = self.get_cached_extrect(shape, tree, scale);
        let tile_size = tiles::get_tile_size(scale);
        tiles::get_tiles_for_rect(extrect, tile_size)
    }

    /*
     * Given a shape, check the indexes and update it's location in the tile set
     * returns the tiles that have changed in the process.
     */
    pub fn update_shape_tiles(&mut self, shape: &Shape, tree: ShapesPoolRef) -> Vec<tiles::Tile> {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree);

        let old_tiles = self
            .tiles
            .get_tiles_of(shape.id)
            .map_or(Vec::new(), |tiles| tiles.iter().copied().collect());

        let new_tiles = (rsx..=rex).flat_map(|x| (rsy..=rey).map(move |y| tiles::Tile::from(x, y)));

        let mut result = HashSet::<tiles::Tile>::new();

        // First, remove the shape from all tiles where it was previously located
        for tile in old_tiles {
            self.tiles.remove_shape_at(tile, shape.id);
            result.insert(tile);
        }

        // Then, add the shape to the new tiles
        for tile in new_tiles {
            self.tiles.add_shape_at(tile, shape.id);
            result.insert(tile);
        }

        result.iter().copied().collect()
    }

    /*
     * Add the tiles forthe shape to the index.
     * returns the tiles that have been updated
     */
    pub fn add_shape_tiles(&mut self, shape: &Shape, tree: ShapesPoolRef) -> Vec<tiles::Tile> {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree);
        let tiles: Vec<_> = (rsx..=rex)
            .flat_map(|x| (rsy..=rey).map(move |y| tiles::Tile::from(x, y)))
            .collect();

        for tile in tiles.iter() {
            self.tiles.add_shape_at(*tile, shape.id);
        }
        tiles
    }

    pub fn remove_cached_tile(&mut self, tile: tiles::Tile) {
        let rect = self.get_aligned_tile_bounds(tile);
        self.surfaces
            .remove_cached_tile_surface(tile, rect, self.background_color);
    }

    pub fn rebuild_tiles_shallow(&mut self, tree: ShapesPoolRef) {
        performance::begin_measure!("rebuild_tiles_shallow");

        let mut all_tiles = HashSet::<tiles::Tile>::new();
        let mut nodes = vec![Uuid::nil()];
        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                if shape_id != Uuid::nil() {
                    all_tiles.extend(self.update_shape_tiles(shape, tree));
                } else {
                    // We only need to rebuild tiles from the first level.
                    for child_id in shape.children_ids_iter(false) {
                        nodes.push(*child_id);
                    }
                }
            }
        }

        // Update the changed tiles
        self.surfaces.remove_cached_tiles(self.background_color);
        for tile in all_tiles {
            self.remove_cached_tile(tile);
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

        // Update the changed tiles
        self.surfaces.remove_cached_tiles(self.background_color);
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

        let ids = self.touched_ids.clone();

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

        self.clean_touched();

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
    pub fn update_tiles_shapes(&mut self, shape_ids: &[Uuid], tree: ShapesPoolMutRef<'_>) {
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
    }

    /// Rebuilds tiles for shapes with modifiers and processes their ancestors
    ///
    /// This function applies transformation modifiers to shapes and updates their tiles.
    /// Additionally, it processes all ancestors of modified shapes to ensure their
    /// extended rectangles are properly recalculated and their tiles are updated.
    /// This is crucial for frames and groups that contain transformed children.
    pub fn rebuild_modifier_tiles(&mut self, tree: ShapesPoolMutRef<'_>, ids: Vec<Uuid>) {
        let ancestors = all_with_ancestors(&ids, tree, false);
        self.update_tiles_shapes(&ancestors, tree);
    }

    pub fn get_scale(&self) -> f32 {
        self.viewbox.zoom() * self.options.dpr()
    }

    pub fn get_cached_scale(&self) -> f32 {
        self.cached_viewbox.zoom() * self.options.dpr()
    }

    pub fn mark_touched(&mut self, uuid: Uuid) {
        self.touched_ids.insert(uuid);
    }

    pub fn clean_touched(&mut self) {
        self.touched_ids.clear();
    }

    pub fn get_cached_extrect(&mut self, shape: &Shape, tree: ShapesPoolRef, scale: f32) -> Rect {
        shape.extrect(tree, scale)
    }

    pub fn set_view(&mut self, zoom: f32, x: f32, y: f32) {
        self.viewbox.set_all(zoom, x, y);
    }
}
