mod debug;
mod fills;
pub mod filters;
mod fonts;
pub mod gpu_state;
pub mod grid_layout;
mod images;
mod options;
pub mod pdf;
mod shadows;
pub mod shape_renderer;
mod strokes;
mod surfaces;
pub mod text;
pub mod text_editor;
mod ui;
mod vector;

use skia_safe::{self as skia, Matrix, RRect, Rect};
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};

use options::RenderOptions;
pub use surfaces::{SurfaceId, Surfaces};

use crate::error::{Error, Result};
use crate::math;
use crate::shapes::{
    all_with_ancestors, radius_to_sigma, Blur, BlurType, Corners, Fill, Shadow, Shape, SolidColor,
    Stroke, StrokeKind, TextContent, Type,
};
use crate::state::{RulerState, ShapesPoolMutRef, ShapesPoolRef};
use crate::tiles::{self, PendingTiles, TileRect};
use crate::uuid::Uuid;
use crate::view::Viewbox;
use crate::wapi;
use crate::{get_gpu_state, performance};

pub use fonts::*;
pub use images::*;

type ClipStack = Vec<(Rect, Option<Corners>, Matrix)>;

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
    /// Rebuilds the tile index without leaving fast mode.
    SyncTiles = 4,
}

#[derive(Debug)]
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
    // True when this container was flattened (enter/exit skipped).
    flattened: bool,
}

/// Get simplified children of a container, flattening nested flattened containers
fn get_simplified_children<'a>(tree: ShapesPoolRef<'a>, shape: &'a Shape) -> Vec<Uuid> {
    let mut result = Vec::new();

    for child_id in shape.children_ids_iter(false) {
        if let Some(child) = tree.get(child_id) {
            if child.can_flatten() {
                // Child is flattened: recursively get its simplified children
                result.extend(get_simplified_children(tree, child));
            } else {
                // Child is not flattened: add it directly
                result.push(*child_id);
            }
        }
    }

    result
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
        clip_inset: Option<f32>,
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

        if let Some(clip_inset) = clip_inset.filter(|&e| e > 0.0) {
            bounds.inset((clip_inset, clip_inset));
        }

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
                let mut bounds = element.get_selrect_shadow_bounds(shadow);
                let blur_inset = (shadow.blur * 2.).max(0.0);
                if blur_inset > 0.0 {
                    let max_inset_x = (bounds.width() * 0.5).max(0.0);
                    let max_inset_y = (bounds.height() * 0.5).max(0.0);
                    // Clamp the inset so we never shrink more than half of the width/height;
                    // otherwise the rect could end up inverted on small frames.
                    let inset_x = blur_inset.min(max_inset_x);
                    let inset_y = blur_inset.min(max_inset_y);
                    if inset_x > 0.0 || inset_y > 0.0 {
                        bounds.inset((inset_x, inset_y));
                    }
                }

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
#[derive(Clone)]
pub struct FocusMode {
    shapes: Vec<Uuid>,
    depth: u32,
}

impl FocusMode {
    pub fn new() -> Self {
        FocusMode {
            shapes: Vec::new(),
            depth: 0,
        }
    }

    pub fn clear(&mut self) {
        self.shapes.clear();
        self.depth = 0;
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
        if self.should_focus(id) {
            self.depth += 1;
        }
    }

    pub fn exit(&mut self, id: &Uuid) {
        if self.should_focus(id) && self.depth > 0 {
            self.depth -= 1;
        }
    }

    pub fn is_active(&self) -> bool {
        self.depth > 0
    }

    pub fn reset(&mut self) {
        self.depth = 0;
    }
}

/*
 * Sort by z_index descending (higher z renders on top).
 * The sort is stable so if the values are equal the index for the children
 * has preference.
 * When changing this method check the benchmark
 */
fn sort_z_index(tree: ShapesPoolRef, element: &Shape, children_ids: Vec<Uuid>) -> Vec<Uuid> {
    if element.has_layout() {
        let mut ids = children_ids;

        if element.is_flex() && !element.is_flex_reverse() {
            ids.reverse();
        }
        ids.sort_by(|id1, id2| {
            let z1 = tree.get(id1).map(|s| s.z_index()).unwrap_or(0);
            let z2 = tree.get(id2).map(|s| s.z_index()).unwrap_or(0);
            z2.cmp(&z1)
        });
        ids
    } else {
        children_ids
    }
}

struct RenderStats {
    pub counts: HashMap<Uuid, i32>,
}

#[allow(dead_code)]
impl RenderStats {
    pub fn new() -> Self {
        Self {
            counts: HashMap::new(),
        }
    }

    fn count(&mut self, id: Uuid) -> i32 {
        let counter = self.counts.entry(id).or_insert(0);
        *counter += 1;
        *counter
    }

    fn clear(&mut self) {
        self.counts.clear();
    }

    #[allow(dead_code)]
    fn get(&self, id: &Uuid) -> Option<&i32> {
        self.counts.get(id)
    }

    fn print(&self) {
        let mut sum: i32 = 0;
        for (&id, &count) in self.counts.iter() {
            println!("{}: {}", id, count);
            sum += count;
        }
        println!("{}: {}", self.counts.len(), sum);
    }
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
    pub nested_shadows: Vec<Vec<Shadow>>,
    pub show_grid: Option<Uuid>,
    pub rulers: RulerState,
    pub focus_mode: FocusMode,
    /// Viewer-only whitelist for fixed-scroll layer passes.
    pub include_filter: Option<HashSet<Uuid>>,
    /// Frame id passed as `base_object` for viewer renders; always traversed.
    pub viewer_render_root: Option<Uuid>,
    pub touched_ids: HashSet<Uuid>,
    /// Temporary flag used for off-screen passes (drop-shadow masks, filter surfaces, etc.)
    /// where we must render shapes without inheriting ancestor layer blurs. Toggle it through
    /// `with_nested_blurs_suppressed` to ensure it's always restored.
    pub ignore_nested_blurs: bool,
    /// Preview render mode - when true, uses simplified rendering for progressive loading
    pub preview_mode: bool,
    pub export_context: Option<(Rect, f32)>,
    /// Cleared at the beginning of a render pass; set to true after we clear Cache the first
    /// time we are about to blit a tile into Cache for this pass.
    pub cache_cleared_this_render: bool,
    /// True if the current tile had shapes assigned to it when we
    /// started rendering it. Lets us distinguish a genuinely empty
    /// tile (skip composite, just clear) from a tile whose walker
    /// finished its work in a previous PAF and is now being resumed
    /// (must composite to present the work). Reset when current_tile
    /// changes.
    pub current_tile_had_shapes: bool,
    /// During interactive transforms we keep `Target` between rAFs. Seed the
    /// interactive backdrop exactly once per gesture (first rAF) so we don't
    /// repeatedly overwrite tiles that have already been updated.
    pub interactive_target_seeded: bool,
    /// When true, the next `start_render_loop` keeps the last presented `Target`
    /// pixels instead of clearing the canvas. Set after incremental shape updates
    /// (e.g. adding a rect) so the workspace stays visible while only affected
    /// tiles are re-rendered asynchronously.
    pub preserve_target_during_render: bool,
    /// GPU crops from `Backbuffer` or tile atlas keyed by shape id. Filled on full-frame completion; during
    /// drag, entries for the moved top-level selection are ensured here
    pub backbuffer_crop_cache: HashMap<Uuid, InteractiveDragCrop>,
}

pub struct InteractiveDragCrop {
    pub src_doc_bounds: Rect,
    pub src_selrect: Rect,
    /// Viewbox origin (doc-space) at capture time.
    pub capture_vb_left: f32,
    pub capture_vb_top: f32,
    /// Backbuffer pixel origin used for `snapshot_rect` (so we can do 1:1 blits).
    pub capture_src_left: i32,
    pub capture_src_top: i32,
    pub image: skia::Image,
}

/// Chooses a window inside the full workspace-pixel crop `[0, out_w) × [0, out_h)` with each side
/// at most `max_side_px` (**without scaling**): centered on the projection of
/// `viewport_doc ∩ src_doc_bounds`, or on the full crop if that intersection is empty.
/// `max_side_px` should match [`GpuState::max_texture_size`] (same budget as the atlas).
#[allow(clippy::too_many_arguments)]
fn drag_crop_snapshot_window_px(
    max_side_px: i32,
    out_w: i32,
    out_h: i32,
    viewport_doc: Rect,
    vb_left: f32,
    vb_top: f32,
    scale: f32,
    src_left_px: i32,
    src_top_px: i32,
    src_doc_bounds: Rect,
) -> (i32, i32, i32, i32) {
    let cap = max_side_px.max(1);
    if out_w <= cap && out_h <= cap {
        return (0, 0, out_w, out_h);
    }
    let win_w = out_w.min(cap);
    let win_h = out_h.min(cap);

    let mut vis = viewport_doc;
    let has_vis = vis.intersect(src_doc_bounds);
    let (cx, cy) = if !has_vis || vis.is_empty() {
        (out_w as f32 * 0.5, out_h as f32 * 0.5)
    } else {
        let lx0 = (vis.left - vb_left) * scale - src_left_px as f32;
        let ly0 = (vis.top - vb_top) * scale - src_top_px as f32;
        let lx1 = (vis.right - vb_left) * scale - src_left_px as f32;
        let ly1 = (vis.bottom - vb_top) * scale - src_top_px as f32;
        ((lx0 + lx1) * 0.5, (ly0 + ly1) * 0.5)
    };

    let mut ox = (cx - win_w as f32 * 0.5).round() as i32;
    let mut oy = (cy - win_h as f32 * 0.5).round() as i32;
    ox = ox.clamp(0, out_w - win_w);
    oy = oy.clamp(0, out_h - win_h);
    (ox, oy, win_w, win_h)
}

impl RenderState {
    /// Decide whether a top-level node can be served from `backbuffer_crop_cache` during an
    /// interactive transform (drag/resize/rotate).
    ///
    /// We only reuse cached pixels when it is safe and visually correct:
    /// - **Top-level only**: cache entries are built for direct children of the root.
    /// - **Moved node**: only allow cache reuse for *pure translations* (no scale/rotate/skew),
    ///   because other transforms would require resampling and can diverge from the live render.
    /// - **Other cached nodes**: if the moving bounds overlap this cached crop, invalidate it so
    ///   we don't show stale content while something moves over/inside it.
    fn should_use_cached_top_level_during_interactive(
        &mut self,
        node_id: Uuid,
        tree: ShapesPoolRef,
        moved_ids: &[Uuid],
        moved_bounds: Option<Rect>,
    ) -> bool {
        if !self.backbuffer_crop_cache.contains_key(&node_id) {
            return false;
        }
        let Some(raw) = tree.get_raw(&node_id) else {
            return false;
        };
        if raw.parent_id != Some(Uuid::nil()) {
            return false;
        }

        // If this top-level shape itself is being moved, always allow using its cached pixels.
        // BUT only for pure translations. For non-translation transforms (scale/rotate/skew),
        // cached pixels won't match the live result (and may require resampling), so render live.
        if moved_ids.contains(&node_id) {
            let Some(m) = tree.get_modifier(&node_id) else {
                return false;
            };
            // Only allow using the cached pixels for pure translations.
            // For non-translation transforms (scale/rotate/skew), cached pixels won't match.
            // If the transform is the identity means a reflow, we need to redraw as well.
            if math::identitish(m) || !math::is_move_only_matrix(m) {
                return false;
            }

            if !self.backbuffer_crop_cache.contains_key(&node_id) {
                return false;
            }

            // Additionally require this node to be safe to serve from a rectangular backbuffer
            // crop while moving; otherwise it must be rendered live (e.g. text, overflow frames).
            return tree
                .get(&node_id)
                .is_some_and(|s| s.is_safe_for_drag_crop_cache(tree));
        }

        // If the moving content overlaps this cached crop, do not use the cached pixels
        // for this frame. We intentionally keep the cache entry: overlap is typically
        // transient during drag, and once the moving content leaves the area the crop
        // becomes valid again (stationary shape unchanged).
        if let Some(moved) = moved_bounds {
            let intersects = self
                .backbuffer_crop_cache
                .get(&node_id)
                .is_some_and(|crop| moved.intersects(crop.src_doc_bounds));

            if intersects {
                return false;
            }
        }
        true
    }

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
            nested_shadows: vec![],
            show_grid: None,
            rulers: RulerState::default(),
            focus_mode: FocusMode::new(),
            include_filter: None,
            viewer_render_root: None,
            touched_ids: HashSet::default(),
            ignore_nested_blurs: false,
            preview_mode: false,
            export_context: None,
            cache_cleared_this_render: false,
            current_tile_had_shapes: false,
            interactive_target_seeded: false,
            preserve_target_during_render: false,
            backbuffer_crop_cache: HashMap::default(),
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
        shape.frame_clip_layer_blur()
    }

    /// Renders background blur effect directly to the given target surface.
    /// Must be called BEFORE any save_layer for the shape's own opacity/blend,
    /// so that the backdrop blur is independent of the shape's visual properties.
    fn render_background_blur(&mut self, shape: &Shape, target_surface: SurfaceId) {
        if self.options.is_fast_mode() {
            return;
        }
        if matches!(shape.shape_type, Type::Text(_)) || matches!(shape.shape_type, Type::SVGRaw(_))
        {
            return;
        }
        let blur = match shape.visible_background_blur() {
            Some(blur) => blur,
            None => return,
        };

        let scale = self.get_scale();
        let scaled_sigma = radius_to_sigma(blur.value * scale);
        // Cap sigma so the blur kernel (≈3σ) stays within the tile margin.
        // This prevents visible seams at tile boundaries when zoomed in.
        // During export there's no tiling, so skip the cap.
        let sigma = if self.export_context.is_some() {
            scaled_sigma
        } else {
            let margin = self.surfaces.margins().width as f32;
            let max_sigma = margin / 3.0;
            scaled_sigma.min(max_sigma)
        };

        let blur_filter =
            match skia::image_filters::blur((sigma, sigma), skia::TileMode::Clamp, None, None) {
                Some(filter) => filter,
                None => return,
            };

        let translation = self
            .surfaces
            .get_render_context_translation(self.render_area, scale);

        let center = shape.center();
        let mut matrix = shape.transform;
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        let canvas = self.surfaces.canvas(target_surface);
        canvas.save();

        // Current/Export have no render context transform (identity canvas).
        // Apply scale + translate + shape transform so the clip maps
        // from shape-local coords to device pixels correctly.
        canvas.scale((scale, scale));
        canvas.translate(translation);
        canvas.concat(&matrix);

        // Clip to shape's path based on shape type
        match &shape.shape_type {
            Type::Rect(data) if data.corners.is_some() => {
                let rrect = RRect::new_rect_radii(shape.selrect, data.corners.as_ref().unwrap());
                canvas.clip_rrect(rrect, skia::ClipOp::Intersect, true);
            }
            Type::Frame(data) if data.corners.is_some() => {
                let rrect = RRect::new_rect_radii(shape.selrect, data.corners.as_ref().unwrap());
                canvas.clip_rrect(rrect, skia::ClipOp::Intersect, true);
            }
            Type::Rect(_) | Type::Frame(_) => {
                canvas.clip_rect(shape.selrect, skia::ClipOp::Intersect, true);
            }
            Type::Circle => {
                let mut pb = skia::PathBuilder::new();
                pb.add_oval(shape.selrect, None, None);
                canvas.clip_path(&pb.detach(), skia::ClipOp::Intersect, true);
            }
            _ => {
                if let Some(path) = shape.get_skia_path() {
                    canvas.clip_path(&path, skia::ClipOp::Intersect, true);
                } else {
                    canvas.clip_rect(shape.selrect, skia::ClipOp::Intersect, true);
                }
            }
        }

        // Reset matrix so the blur is applied in device space (sigma is already
        // scaled by the zoom). Clips survive reset_matrix (stored in device coords).
        canvas.reset_matrix();

        // Apply the blur as a backdrop filter on a save_layer. A backdrop filter
        // samples the *current device* contents (respecting the active clip),
        // which includes whatever has been drawn so far — including any in-flight
        // ancestor save_layer, such as a parent frame with opacity < 100% or a
        // non-default blend mode. This way the background blur reflects the actual
        // pixels behind the shape regardless of the layer stack. Src blend makes
        // the layer replace the clipped region with the blurred backdrop instead
        // of compositing over it (which would double-render the backdrop).
        let mut paint = skia::Paint::default();
        paint.set_blend_mode(skia::BlendMode::Src);
        let layer_rec = skia::canvas::SaveLayerRec::default()
            .backdrop(&blur_filter)
            .backdrop_tile_mode(skia::TileMode::Clamp)
            .paint(&paint);
        canvas.save_layer(&layer_rec);

        // Two restores are required, balancing two separate pushes:
        // 1. this restore pops the save_layer above; it is the step that composites
        //    the blurred-backdrop layer back onto the canvas (with the Src paint),
        //    so it is what actually produces the blurred output.
        // 2. the final restore pops the canvas.save() above, removing the shape clip
        //    and the scale/translate/transform so they don't leak into later drawing.
        canvas.restore();
        canvas.restore();
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

    pub fn flush(&mut self) {
        self.surfaces.flush(SurfaceId::Backbuffer);
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

    pub fn apply_render_to_final_canvas(&mut self) -> Result<()> {
        // During interactive transforms we render tiles directly into Target; updating the cache
        // (snapshot -> atlas blit -> tiles.add) can force GPU stalls. Defer cache rebuild until
        // the interaction ends.
        if self.options.is_interactive_transform() {
            let tile_rect = self.get_current_aligned_tile_bounds()?;
            self.surfaces.draw_current_tile_into_backbuffer(
                &tile_rect,
                self.background_color,
                surfaces::DrawOnCache::No,
            );
            return Ok(());
        }

        // Viewer masked passes render a partial scene. Reusing the tile texture cache would
        // SrcOver-blend onto textures from the previous pass and leak pixels into the blob.
        if self.viewer_masked_pass() {
            // Use viewbox-aligned bounds (not grid-snapped) to match interactive-transform
            // compositing and avoid a visible offset vs the DOM canvas.
            let tile_rect = self.get_current_tile_bounds()?;
            self.surfaces.draw_current_tile_into_backbuffer(
                &tile_rect,
                self.background_color,
                surfaces::DrawOnCache::No,
            );
            return Ok(());
        }

        let fast_mode = self.options.is_fast_mode();
        // Decide *now* (at the first real cache blit) whether we need to clear Cache.
        // This avoids clearing Cache on renders that don't actually paint tiles (e.g. hover/UI),
        // while still preventing stale pixels from surviving across full-quality renders.
        if !fast_mode && !self.cache_cleared_this_render {
            self.surfaces.clear_cache(self.background_color);
            self.cache_cleared_this_render = true;
        }
        // In fast mode the viewport is moving (pan/zoom) so Cache surface
        // positions would be wrong — only save to the tile HashMap.
        let tile_rect = self.get_current_aligned_tile_bounds()?;

        let current_tile = *self
            .current_tile
            .as_ref()
            .ok_or(Error::CriticalError("Current tile not found".to_string()))?;

        self.surfaces.draw_current_tile_into_tile_atlas(
            &self.tile_viewbox,
            &current_tile,
            &tile_rect,
            fast_mode,
            self.render_area,
        );

        Ok(())
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
        self.include_filter.is_some()
    }

    /// True when the shape or any descendant is whitelisted.
    pub fn shape_visible_in_include_filter(&self, shape_id: &Uuid, tree: ShapesPoolRef) -> bool {
        let Some(ref include) = self.include_filter else {
            return true;
        };
        if include.contains(shape_id) {
            return true;
        }
        let Some(shape) = tree.get(shape_id) else {
            return false;
        };
        shape
            .children_ids_iter(false)
            .any(|child_id| self.shape_visible_in_include_filter(child_id, tree))
    }

    /// When an include whitelist is active, only those ids are painted.
    fn shape_should_paint_for_viewer_layer(&self, shape_id: &Uuid) -> bool {
        match &self.include_filter {
            Some(include) => include.contains(shape_id),
            None => true,
        }
    }

    /// Viewer layer mask: traverse whitelisted subtrees; paint only listed ids.
    pub fn shape_visible_for_viewer_layer(&self, shape_id: &Uuid, tree: ShapesPoolRef) -> bool {
        if self.viewer_render_root.as_ref() == Some(shape_id) {
            return true;
        }
        self.shape_visible_in_include_filter(shape_id, tree)
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
        let fast_mode = self.options.is_fast_mode();
        // Skip anti-aliasing entirely during fast_mode (interactive
        // gestures + pan/zoom). AA edge sampling is per-pixel and adds
        // up across many shapes; reverts to full quality on commit.
        let antialias = !fast_mode
            && shape.should_use_antialias(self.get_scale(), self.options.antialias_threshold);
        let skip_effects = fast_mode;

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
            let scale = self.get_scale();
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
            let scale = self.get_scale();
            for (mut bounds, corners, transform) in clips.iter() {
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

        // Background blur is stored separately (shape.background_blur) and is
        // rendered before the save_layer in render_background_blur(), so here
        // shape.blur only ever holds a layer blur.

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
        if skip_effects {
            shape.to_mut().set_blur(None);
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
                shape.to_mut().set_blur(None);
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
                            shape.to_mut().set_svg(dom);
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
                let text_fill_inset = (count_inner_strokes > 0).then(|| 1.0 / self.get_scale());
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
                        &shape,
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
                                &shape,
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
                                &shape,
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
                                    &shape,
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
                                &shape,
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
                                    &shape,
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
                            &shape,
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
                            &shape,
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
                                    &shape,
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
                                    &shape,
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
                            &shape,
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
                                    &shape,
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
            let shape_selrect_bounds = self.get_shape_selrect_bounds(&shape);
            debug::render_debug_shape(self, Some(shape_selrect_bounds), None);
        }

        if apply_to_current_surface {
            self.draw_shape_surface_stack_into(Some(&shape), target_surface);
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

    fn rebuild_backbuffer_crop_cache(&mut self, tree: ShapesPoolRef) {
        self.backbuffer_crop_cache.clear();

        // Collect candidate shapes that are "recortable" and visible in the current viewport.

        // This is intentionally conservative; we only cache shapes that do not overlap with
        // ANY other candidate to guarantee the pixels under their bounds belong exclusively
        // to that shape in Backbuffer.
        let viewport = self.viewbox.area;
        let scale = self.get_scale();
        let mut candidates: Vec<(Uuid, Rect, Rect)> = Vec::new(); // (id, doc_bounds, selrect)

        let root_ids: Vec<Uuid> = match tree.get(&Uuid::nil()) {
            Some(root) => root.children_ids(false),
            None => Vec::new(),
        };

        for shape_id in root_ids {
            let Some(shape) = tree.get(&shape_id) else {
                continue;
            };
            if shape.hidden {
                continue;
            }

            let doc_bounds = self.get_cached_extrect(shape, tree, 1.0);
            if !doc_bounds.intersects(viewport) {
                continue;
            }

            // Also require selrect to be visible; used for drag delta placement.
            let selrect = shape.selrect();
            if !selrect.intersects(viewport) {
                continue;
            }

            candidates.push((shape.id, doc_bounds, selrect));
        }

        // Filter out any candidate that overlaps with any other candidate.
        // Sort by left edge so the inner loop can break early once no further
        // x-overlap is possible, reducing comparisons from O(N²) to O(N log N)
        // in typical layouts where shapes are spread out.
        candidates.sort_unstable_by(|a, b| {
            a.1.left
                .partial_cmp(&b.1.left)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        let n = candidates.len();
        let mut is_overlapping = vec![false; n];
        for i in 0..n {
            for j in (i + 1)..n {
                if candidates[j].1.left >= candidates[i].1.right {
                    break; // sorted: no further x-overlap possible for i
                }
                if is_overlapping[i] && is_overlapping[j] {
                    continue; // both already excluded, skip check
                }
                if candidates[i].1.intersects(candidates[j].1) {
                    is_overlapping[i] = true;
                    is_overlapping[j] = true;
                }
            }
        }
        let non_overlapping: Vec<(Uuid, Rect, Rect)> = candidates
            .iter()
            .zip(is_overlapping.iter())
            .filter_map(|((id, bounds, selrect), ov)| {
                if !ov {
                    Some((*id, *bounds, *selrect))
                } else {
                    None
                }
            })
            .collect();

        let vb_left = self.viewbox.area.left;
        let vb_top = self.viewbox.area.top;
        let (bb_w, bb_h) = self.surfaces.surface_size(SurfaceId::Backbuffer);
        let max_snap_px = get_gpu_state().max_texture_size();

        // Snapshot the atlas once for the whole pass so that all shapes sharing
        // the tile/atlas fallback path reuse the same GPU image rather than each
        // triggering a separate `image_snapshot` flush.
        let atlas_snap = self.surfaces.atlas.snapshot_for_drag_crop();

        // Scratch surface reused across all shapes that need the tile/atlas
        // fallback — avoids one WebGL texture allocation per shape.
        // Created lazily on first use and grown if a later shape needs more space.
        let mut scratch_surface: Option<skia::Surface> = None;

        for (id, doc_bounds, selrect) in non_overlapping {
            let left = ((doc_bounds.left - vb_left) * scale).floor() as i32;
            let top = ((doc_bounds.top - vb_top) * scale).floor() as i32;
            let right = ((doc_bounds.right - vb_left) * scale).ceil() as i32;
            let bottom = ((doc_bounds.bottom - vb_top) * scale).ceil() as i32;
            if right <= left || bottom <= top {
                continue;
            }
            let src_irect = skia::IRect::new(left, top, right, bottom);

            let src_doc_bounds = Rect::new(
                src_irect.left as f32 / scale + vb_left,
                src_irect.top as f32 / scale + vb_top,
                src_irect.right as f32 / scale + vb_left,
                src_irect.bottom as f32 / scale + vb_top,
            );

            let full_w = src_irect.width();
            let full_h = src_irect.height();
            let (win_ox, win_oy, win_w, win_h) = drag_crop_snapshot_window_px(
                max_snap_px,
                full_w,
                full_h,
                viewport,
                vb_left,
                vb_top,
                scale,
                src_irect.left,
                src_irect.top,
                src_doc_bounds,
            );
            let window_irect = skia::IRect::new(
                src_irect.left + win_ox,
                src_irect.top + win_oy,
                src_irect.left + win_ox + win_w,
                src_irect.top + win_oy + win_h,
            );

            let src_doc_window = Rect::new(
                window_irect.left as f32 / scale + vb_left,
                window_irect.top as f32 / scale + vb_top,
                window_irect.right as f32 / scale + vb_left,
                window_irect.bottom as f32 / scale + vb_top,
            );

            let in_backbuffer = window_irect.left >= 0
                && window_irect.top >= 0
                && window_irect.right <= bb_w
                && window_irect.bottom <= bb_h;

            let backbuffer_snap = if in_backbuffer {
                self.surfaces
                    .snapshot_rect(SurfaceId::Backbuffer, window_irect)
            } else {
                None
            };

            let image = if let Some(img) = backbuffer_snap {
                img
            } else {
                // Ensure the scratch surface is large enough for this window.
                // Grow (reallocate) only when necessary so that the common case
                // of similarly-sized shapes pays zero extra allocation cost.
                let needs_alloc = scratch_surface
                    .as_ref()
                    .is_none_or(|s| s.width() < win_w || s.height() < win_h);
                if needs_alloc {
                    scratch_surface = get_gpu_state()
                        .create_surface_with_isize(
                            "drag_crop_scratch".to_string(),
                            skia::ISize::new(win_w, win_h),
                        )
                        .ok();
                }
                let Some(scratch) = scratch_surface.as_mut() else {
                    continue;
                };
                let Some(img) = self.surfaces.try_snapshot_doc_rect_from_tiles_and_atlas(
                    scratch,
                    atlas_snap.as_ref(),
                    src_doc_window,
                    window_irect,
                    win_w,
                    win_h,
                    vb_left,
                    vb_top,
                    scale,
                ) else {
                    continue;
                };
                img
            };

            self.backbuffer_crop_cache.insert(
                id,
                InteractiveDragCrop {
                    src_doc_bounds: src_doc_window,
                    src_selrect: selrect,
                    capture_vb_left: vb_left,
                    capture_vb_top: vb_top,
                    capture_src_left: window_irect.left,
                    capture_src_top: window_irect.top,
                    image,
                },
            );
        }
    }

    pub fn render_from_cache(&mut self, shapes: ShapesPoolRef) {
        let _start = performance::begin_timed_log!("render_from_cache");
        performance::begin_measure!("render_from_cache");
        self.surfaces.draw_combined_atlas_to_backbuffer(
            &self.viewbox,
            &self.tile_viewbox,
            self.background_color,
        );
        self.present_frame(shapes);

        performance::end_measure!("render_from_cache");
        performance::end_timed_log!("render_from_cache", _start);
    }

    /// Render a preview of the shapes during loading.
    /// This rebuilds tiles for touched shapes and renders synchronously.
    pub fn render_preview(&mut self, tree: ShapesPoolRef, timestamp: i32) -> Result<()> {
        let _start = performance::begin_timed_log!("render_preview");
        performance::begin_measure!("render_preview");

        // Enable fast_mode during preview to skip expensive effects (blur, shadows).
        // Restore the previous state afterward so the final render is full quality.
        let current_fast_mode = self.options.is_fast_mode();
        self.options.set_fast_mode(true);

        // Skip tile rebuilding during preview - we'll do it at the end
        // Just rebuild tiles for touched shapes and render synchronously
        self.rebuild_touched_tiles(tree);

        // Use the sync render path
        self.start_render_loop(None, tree, timestamp, true)?;

        self.options.set_fast_mode(current_fast_mode);

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
    }

    pub fn start_render_loop(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        sync_render: bool,
    ) -> Result<FrameType> {
        self.clear(tree);

        let _start = performance::begin_timed_log!("start_render_loop");
        let scale = self.get_scale();

        self.tile_viewbox.update(&self.viewbox);
        self.focus_mode.reset();

        performance::begin_measure!("render");
        performance::begin_measure!("start_render_loop");

        // Compute and set document-space bounds (1 unit == 1 doc px @ 100% zoom)
        // to clamp atlas updates. This prevents zoom-out tiles from forcing atlas
        // growth far beyond real content.
        let doc_bounds = self.compute_document_bounds(base_object, tree);
        self.surfaces.atlas.set_doc_bounds(doc_bounds);

        self.cache_cleared_this_render = false;
        let preserve_target = self.preserve_target_during_render;
        self.preserve_target_during_render = false;

        if preserve_target && self.options.is_fast_mode() {
            self.rebuild_tile_index(tree);
        }

        if self.options.is_interactive_transform() {
            // Keep `Target` as the previous frame and overwrite only the tiles
            // that changed. This avoids clearing + redrawing an atlas backdrop
            // every rAF during drag (a common source of GPU work/stalls).
            self.surfaces
                .reset_interactive_transform(self.background_color);
            if !self.interactive_target_seeded {
                // Seed from the last presented frame; this is stable even when
                // fast_mode skips cache updates and regardless of atlas coverage.
                self.interactive_target_seeded = true;
            }
        } else if preserve_target || self.zoom_changed() {
            // Shape updates or zoom-end: keep the last presented frame on screen
            // while tiles are re-rendered asynchronously. During zoom the
            // preview from render_from_cache stays visible until the full-
            // quality pass completes.
            self.surfaces
                .reset_interactive_transform(self.background_color);
            self.interactive_target_seeded = false;
        } else {
            self.reset_canvas();
            self.interactive_target_seeded = false;
            // Paint rulers/frame now so they survive the progressive frames
            // instead of blanking until the first full `present_frame`.
            // Skip on sync renders (thumbnails/exports)
            if !sync_render {
                ui::render(self, tree);
                self.flush_and_submit();
            }
        }

        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32;

        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().scale((scale, scale));
        });

        self.surfaces.resize_cache_from_viewbox(
            &self.viewbox,
            &self.cached_viewbox,
            self.options.dpr_viewport_interest_area_threshold,
        )?;

        // FIXME - review debug
        // debug::render_debug_tiles_for_viewbox(self);

        let _tile_start = performance::begin_timed_log!("tile_cache_update");

        performance::begin_measure!("tile_cache");
        let only_visible = self.options.is_interactive_transform();
        self.pending_tiles
            .update(&self.tile_viewbox, &self.surfaces, only_visible);
        performance::end_measure!("tile_cache");

        performance::end_timed_log!("tile_cache_update", _tile_start);

        self.draw_shape_surface_stack_into(None, SurfaceId::Current);

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

            // Update cached_viewbox after visible tiles render
            // synchronously so that render_from_cache uses the correct
            // zoom ratio even if interest-area tiles are still rendering
            // asynchronously.  Without this, panning right after a zoom
            // would keep scaling the Cache surface by the old zoom ratio
            // (pixelated/wrong-scale tiles) because the async render
            // never completes — each pan frame cancels it.
            if self.cache_cleared_this_render {
                self.cached_viewbox = self.viewbox;
            }
        }

        performance::end_measure!("start_render_loop");
        performance::end_timed_log!("start_render_loop", _start);
        Ok(frame_type)
    }

    fn compute_document_bounds(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
    ) -> Option<skia::Rect> {
        let ids: Vec<Uuid> = if let Some(id) = base_object {
            vec![*id]
        } else {
            let root = tree.get(&Uuid::nil())?;
            root.children_ids(false)
        };

        let mut acc: Option<skia::Rect> = None;
        for id in ids.iter() {
            let Some(shape) = tree.get(id) else {
                continue;
            };
            let r = self.get_cached_extrect(shape, tree, 1.0);
            if r.is_empty() {
                continue;
            }
            acc = Some(if let Some(mut a) = acc {
                a.join(r);
                a
            } else {
                r
            });
        }
        acc
    }

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
        if !self.options.is_interactive_transform() && matches!(frame_type, FrameType::Full) {
            self.surfaces.draw_tile_atlas_to_backbuffer(
                &self.viewbox,
                &self.tile_viewbox,
                self.background_color,
            );
        }

        match frame_type {
            FrameType::None => {
                panic!("FrameType::None");
            }
            FrameType::Partial => {
                // Partial frame: just flush GPU work. The display shows the last
                // fully submitted frame; no need to copy or draw UI overlays here.
                self.flush();
            }
            FrameType::Full => {
                // A full-quality frame is now complete. Rebuild the per-shape crop
                // cache from the clean Backbuffer (no UI overlay yet) so that
                // interactive drag backgrounds don't include the grid overlay.
                if !self.options.is_fast_mode() && !self.options.is_interactive_transform() {
                    self.rebuild_backbuffer_crop_cache(tree);
                }
                // present_frame: copy clean Backbuffer → Target, draw UI/debug
                // overlays on Target only, then flush. Backbuffer stays overlay-free.
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
        let target_surface = SurfaceId::Export;

        // `render_shape_pixels` is used by the workspace to render thumbnails using the
        // same WASM renderer instance. It must not leak any state into the main
        // viewport renderer (tile cache, atlas, focus mode, render context, etc.).
        //
        // In particular, `update_render_context` clears and reconfigures multiple
        // render surfaces, and `render_area` drives atlas blits. If we don't restore
        // them, the workspace can temporarily show missing tiles until the next
        // interaction (e.g. zoom) forces a full context rebuild.
        let saved_focus_mode = self.focus_mode.clone();
        let saved_export_context = self.export_context;
        let saved_render_area = self.render_area;
        let saved_render_area_with_margins = self.render_area_with_margins;
        let saved_current_tile = self.current_tile;
        let saved_pending_nodes = std::mem::take(&mut self.pending_nodes);
        let saved_nested_fills = std::mem::take(&mut self.nested_fills);
        let saved_nested_blurs = std::mem::take(&mut self.nested_blurs);
        let saved_nested_shadows = std::mem::take(&mut self.nested_shadows);
        let saved_ignore_nested_blurs = self.ignore_nested_blurs;
        let saved_preview_mode = self.preview_mode;

        // Reset focus mode so all shapes in the export tree are rendered.
        // Without this, leftover focus_mode state from the workspace could
        // cause shapes (and their background blur) to be skipped.
        self.focus_mode.clear();

        self.surfaces
            .canvas(target_surface)
            .clear(skia::Color::TRANSPARENT);

        if tree.len() != 0 {
            let Some(shape) = tree.get(id) else {
                // FIXME
                return Ok((Vec::new(), 0, 0));
            };
            let mut extrect = shape.extrect(tree, scale);
            self.export_context = Some((extrect, scale));
            let margins = self.surfaces.margins;
            extrect.offset((margins.width as f32 / scale, margins.height as f32 / scale));

            self.surfaces.resize_export_surface(scale, extrect);
            self.render_area = extrect;
            self.render_area_with_margins = extrect;
            self.surfaces.update_render_context(extrect, scale);

            self.pending_nodes.push(NodeRenderState {
                id: *id,
                visited_children: false,
                clip_bounds: None,
                visited_mask: false,
                mask: false,
                flattened: false,
            });
            self.render_shape_tree_partial_uncached(tree, timestamp, false, true)?;
        }

        // Clear export context so get_scale() returns to workspace zoom.
        self.export_context = None;

        self.surfaces.flush_and_submit(target_surface);

        let image = self.surfaces.snapshot(target_surface);
        let data = image
            .encode(
                Some(&mut get_gpu_state().context),
                skia::EncodedImageFormat::PNG,
                100,
            )
            .expect("PNG encode failed");
        let skia::ISize { width, height } = image.dimensions();

        // Restore the workspace render state.
        self.focus_mode = saved_focus_mode;
        self.export_context = saved_export_context;
        self.render_area = saved_render_area;
        self.render_area_with_margins = saved_render_area_with_margins;
        self.current_tile = saved_current_tile;
        self.pending_nodes = saved_pending_nodes;
        self.nested_fills = saved_nested_fills;
        self.nested_blurs = saved_nested_blurs;
        self.nested_shadows = saved_nested_shadows;
        self.ignore_nested_blurs = saved_ignore_nested_blurs;
        self.preview_mode = saved_preview_mode;

        // Restore render-surface transforms for the workspace context.
        // If we have a current tile, restore its tile render context; otherwise
        // fall back to restoring the previous render_area (may be empty).
        let workspace_scale = self.get_scale();
        if let Some(tile) = self.current_tile {
            self.update_render_context(tile);
        } else if !self.render_area.is_empty() {
            self.surfaces
                .update_render_context(self.render_area, workspace_scale);
        }

        Ok((data.as_bytes().to_vec(), width, height))
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

        for (bounds, corners, transform) in clips.iter() {
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
                .concat(&total_matrix.invert().unwrap_or_default());
        }
    }

    pub fn render_shape_enter(
        &mut self,
        element: &Shape,
        mask: bool,
        clip_bounds: Option<&ClipStack>,
        target_surface: SurfaceId,
    ) {
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
                // A masked group's blur is applied as a single layer blur over
                // the whole masked result.
                let mask_group_blur = element.masked_group_layer_blur().is_some();
                if mask_group_blur {
                    self.surfaces.canvas(target_surface).save();
                    if let Some(clips) = clip_bounds {
                        let scale = self.get_scale();
                        let antialias = !self.options.is_fast_mode()
                            && element
                                .should_use_antialias(scale, self.options.antialias_threshold);
                        self.clip_target_surface_to_stack(clips, target_surface, scale, antialias);
                    }
                }

                let mut paint = skia::Paint::default();
                if !self.options.is_fast_mode() {
                    if let Some(blur) = element.masked_group_layer_blur() {
                        let scale = self.get_scale();
                        let sigma = radius_to_sigma(blur.value * scale);
                        if let Some(filter) =
                            skia::image_filters::blur((sigma, sigma), None, None, None)
                        {
                            paint.set_image_filter(filter);
                        }
                    }
                }

                let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                self.surfaces.canvas(target_surface).save_layer(&layer_rec);
            }
        }

        if let Type::Frame(_) = element.shape_type {
            self.nested_fills.push(Vec::new());
        }

        // When we're rendering the mask shape we need to set a special blend mode
        // called 'destination-in' that keeps the drawn content within the mask.
        // @see https://skia.org/docs/user/api/skblendmode_overview/
        if mask {
            let mut mask_paint = skia::Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            self.surfaces.canvas(target_surface).save_layer(&mask_rec);
        }

        // Only create save_layer if actually needed
        // For simple shapes with default opacity and blend mode, skip expensive save_layer
        // Groups with masks need a layer to properly handle the mask rendering
        let needs_layer = element.needs_layer();

        if needs_layer {
            let mut paint = skia::Paint::default();
            paint.set_blend_mode(element.blend_mode().into());
            paint.set_alpha_f(element.opacity());

            // Skip frame-level blur in fast mode (pan/zoom).
            if !self.options.is_fast_mode() {
                if let Some(frame_blur) = Self::frame_clip_layer_blur(element) {
                    let scale = self.get_scale();
                    let sigma = radius_to_sigma(frame_blur.value * scale);
                    if let Some(filter) =
                        skia::image_filters::blur((sigma, sigma), None, None, None)
                    {
                        paint.set_image_filter(filter);
                    }
                }
            }

            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            self.surfaces.canvas(target_surface).save_layer(&layer_rec);
        }
    }

    #[inline]
    pub fn render_shape_exit(
        &mut self,
        element: &Shape,
        visited_mask: bool,
        clip_bounds: Option<ClipStack>,
        target_surface: SurfaceId,
    ) -> Result<()> {
        if visited_mask {
            // Because masked groups needs two rendering passes (first drawing
            // the content and then drawing the mask), we need to do an
            // extra restore.
            if let Type::Group(group) = element.shape_type {
                if group.masked {
                    self.surfaces.canvas(target_surface).restore();
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
                        flattened: false,
                    });
                    if let Some(&mask_id) = element.mask_id() {
                        self.pending_nodes.push(NodeRenderState {
                            id: mask_id,
                            visited_children: false,
                            clip_bounds: None,
                            visited_mask: false,
                            mask: true,
                            flattened: false,
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

        // Strokes are drawn over children for clipped frames (all strokes), and for non-clipped
        // frames with inner strokes (inner strokes only — non-inner were rendered before children).
        // Skip when focus mode excludes this subtree (focus_mode.exit runs after this, so
        // is_active() still reflects this element's focus state here).
        let needs_exit_strokes = self.focus_mode.is_active()
            && (element.clip()
                || (matches!(element.shape_type, Type::Frame(_)) && element.has_inner_stroke()));

        if needs_exit_strokes {
            let mut element_strokes: Cow<Shape> = Cow::Borrowed(element);
            element_strokes.to_mut().clear_fills();
            element_strokes.to_mut().clear_shadows();
            element_strokes.to_mut().clip_content = false;

            // For non-clipped frames, non-inner strokes were already rendered inline.
            if !element.clip() {
                let is_open = element.is_open();
                element_strokes
                    .to_mut()
                    .strokes
                    .retain(|s| s.render_kind(is_open) == StrokeKind::Inner);
            }

            // Frame blur is applied at the save_layer level - avoid double blur on the stroke paint
            if Self::frame_clip_layer_blur(element).is_some() {
                element_strokes.to_mut().set_blur(None);
            }
            self.render_shape(
                &element_strokes,
                clip_bounds,
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
        }

        // Only restore if we created a layer (optimization for simple shapes)
        // Groups with masks need restore to properly handle the mask rendering
        let needs_layer = element.needs_layer();

        if needs_layer {
            self.surfaces.canvas(target_surface).restore();
        }

        if visited_mask && element.masked_group_layer_blur().is_some() {
            self.surfaces.canvas(target_surface).restore();
        }

        self.focus_mode.exit(&element.id);
        Ok(())
    }

    pub fn get_current_tile_bounds(&mut self) -> Result<Rect> {
        let tile = self
            .current_tile
            .ok_or(Error::CriticalError("Current tile not found".to_string()))?;
        let offset = self.viewbox.get_offset();
        Ok(tile.get_rect_with_offset(&offset))
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
    pub fn get_current_aligned_tile_bounds(&mut self) -> Result<Rect> {
        Ok(self.get_aligned_tile_bounds(
            self.current_tile
                .ok_or(Error::CriticalError("Current tile not found".to_string()))?,
        ))
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
        extra_layer_blur: Option<Blur>,
        target_surface: SurfaceId,
    ) -> Result<()> {
        let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);
        transformed_shadow.to_mut().offset = (0.0, 0.0);
        transformed_shadow.to_mut().color = skia::Color::BLACK;

        let mut plain_shape = Cow::Borrowed(shape);
        let combined_blur =
            Self::combine_blur_values(self.combined_layer_blur(shape.blur), extra_layer_blur);
        let blur_filter = combined_blur.and_then(|blur| {
            let sigma = blur.sigma();
            skia::image_filters::blur((sigma, sigma), None, None, None)
        });

        let use_low_zoom_path = scale <= 1.0 && combined_blur.is_none();
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
        // so we paint everything black with the same opacity.
        let plain_shape_mut = plain_shape.to_mut();
        plain_shape_mut.clear_fills();
        if shape.has_fills() {
            plain_shape_mut.add_fill(Fill::Solid(SolidColor(skia::Color::BLACK)));
        }

        // Reuse existing strokes and only override their fill color.
        for stroke in plain_shape_mut.strokes.iter_mut() {
            stroke.fill = Fill::Solid(SolidColor(skia::Color::BLACK));
        }

        plain_shape_mut.clear_shadows();
        plain_shape_mut.blur = None;
        plain_shape_mut.background_blur = None;

        // Shadow rendering uses a single render_shape call with no render_shape_exit,
        // so strokes must be drawn here. Disable clip_content to avoid skip_strokes
        // (which defers strokes to render_shape_exit for clipped frames).
        plain_shape_mut.clip_content = false;

        let Some(drop_filter) = transformed_shadow.get_drop_shadow_filter() else {
            return Ok(());
        };

        let mut bounds = drop_filter.compute_fast_bounds(shape_bounds);
        // Account for the shadow offset so the temporary surface fully contains the shifted blur.
        bounds.offset(world_offset);
        // Early cull if the shadow bounds are outside the render area.
        if !bounds.intersects(self.render_area_with_margins) && target_surface != SurfaceId::Export
        {
            return Ok(());
        }

        // blur=0 at high zoom: draw directly on DropShadows with geometric spread (no filter).
        if scale > 1.0 && shadow.blur <= 0.0 {
            let drop_canvas = self.surfaces.canvas(SurfaceId::DropShadows);
            drop_canvas.save();
            //drop_canvas.scale((scale, scale));
            //drop_canvas.translate(translation);

            self.with_nested_blurs_suppressed(|state| {
                state.render_shape(
                    &plain_shape,
                    clip_bounds,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    false,
                    Some(shadow.offset),
                    None,
                    Some(shadow.spread),
                    target_surface,
                )
            })?;

            self.surfaces.canvas(SurfaceId::DropShadows).restore();
            return Ok(());
        }

        // Create filter with blur only (no offset, no spread - handled geometrically)
        let blur_only_filter = if transformed_shadow.blur > 0.0 {
            let sigma = radius_to_sigma(transformed_shadow.blur);
            Some(skia::image_filters::blur((sigma, sigma), None, None, None))
        } else {
            None
        };

        let mut shadow_paint = skia::Paint::default();
        if let Some(blur_filter) = blur_only_filter {
            shadow_paint.set_image_filter(blur_filter);
        }
        shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&shadow_paint);

        // Low zoom path: use blur filter but apply offset and spread geometrically
        if use_low_zoom_path {
            let drop_canvas = self.surfaces.canvas(SurfaceId::DropShadows);
            drop_canvas.save_layer(&layer_rec);
            //drop_canvas.scale((scale, scale));
            //drop_canvas.translate(translation);

            self.with_nested_blurs_suppressed(|state| {
                state.render_shape(
                    &plain_shape,
                    clip_bounds,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    SurfaceId::DropShadows,
                    false,
                    Some(shadow.offset), // Offset is geometric
                    None,
                    Some(shadow.spread),
                    target_surface,
                )
            })?;

            self.surfaces.canvas(SurfaceId::DropShadows).restore();
            return Ok(());
        }

        // Adaptive downscale for large blur values (lossless GPU optimization).
        // Bounds above were computed from the original sigma so filter surface coverage is correct.
        // Maximum downscale is 1/BLUR_DOWNSCALE_THRESHOLD (i.e. 8x): beyond that the
        // filter surface becomes too small and quality degrades noticeably.
        let blur_downscale_threshold: f32 = self.options.blur_downscale_threshold;
        let min_blur_downscale: f32 = 1.0 / blur_downscale_threshold;
        let blur_downscale = if shadow.blur > blur_downscale_threshold {
            (blur_downscale_threshold / shadow.blur).max(min_blur_downscale)
        } else {
            1.0
        };

        // High zoom with blur: use render_into_filter_surface to ensure blur has enough space
        // Apply spread geometrically to avoid dilate filter rounding issues
        let filter_result = filters::render_into_filter_surface(
            self,
            bounds,
            blur_downscale,
            |state, temp_surface| {
                let canvas = state.surfaces.canvas(temp_surface);
                canvas.save_layer(&layer_rec);

                state.with_nested_blurs_suppressed(|state| {
                    // Apply offset and spread geometrically
                    state.render_shape(
                        &plain_shape,
                        clip_bounds,
                        temp_surface,
                        temp_surface,
                        temp_surface,
                        temp_surface,
                        false,
                        Some(shadow.offset), // Offset is geometric
                        None,
                        Some(shadow.spread),
                        target_surface,
                    )
                })?;

                state.surfaces.canvas(temp_surface).restore();
                Ok(())
            },
        )?;

        if let Some((mut surface, filter_scale)) = filter_result {
            let drop_canvas = self.surfaces.canvas(SurfaceId::DropShadows);
            drop_canvas.save();
            //drop_canvas.scale((scale, scale));
            //drop_canvas.translate(translation);
            let mut drop_paint = skia::Paint::default();
            drop_paint.set_image_filter(blur_filter.clone());

            // If we scaled down in the filter surface, we need to scale back up
            if filter_scale < 1.0 {
                drop_canvas.save();
                drop_canvas.scale((1.0 / filter_scale, 1.0 / filter_scale));
                drop_canvas.translate((bounds.left * filter_scale, bounds.top * filter_scale));
                surface.draw(
                    drop_canvas,
                    (0.0, 0.0),
                    self.sampling_options,
                    Some(&drop_paint),
                );
                drop_canvas.restore();
            } else {
                drop_canvas.save();
                drop_canvas.translate((bounds.left, bounds.top));
                surface.draw(
                    drop_canvas,
                    (0.0, 0.0),
                    self.sampling_options,
                    Some(&drop_paint),
                );
                drop_canvas.restore();
            }
            drop_canvas.restore();
        }

        Ok(())
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
        let element_extrect = extrect.get_or_insert_with(|| element.extrect(tree, scale));
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

            self.render_drop_black_shadow(
                element,
                element_extrect,
                shadow,
                clip_bounds.clone(),
                scale,
                None,
                target_surface,
            )?;

            if !matches!(element.shape_type, Type::Bool(_)) {
                let shadow_children = if element.is_recursive() {
                    get_simplified_children(tree, element)
                } else {
                    Vec::new()
                };

                for shadow_shape_id in shadow_children.iter() {
                    let Some(shadow_shape) = tree.get(shadow_shape_id) else {
                        continue;
                    };
                    if shadow_shape.hidden {
                        continue;
                    }

                    let nested_clip_bounds =
                        node_render_state.get_nested_shadow_clip_bounds(element, shadow);

                    if !matches!(shadow_shape.shape_type, Type::Text(_)) {
                        self.render_drop_black_shadow(
                            shadow_shape,
                            &shadow_shape.extrect(tree, scale),
                            shadow,
                            nested_clip_bounds,
                            scale,
                            inherited_layer_blur,
                            target_surface,
                        )?;
                    } else {
                        let paint = skia::Paint::default();
                        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                        self.surfaces
                            .canvas(SurfaceId::DropShadows)
                            .save_layer(&layer_rec);

                        let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);
                        transformed_shadow.to_mut().color = skia::Color::BLACK;
                        transformed_shadow.to_mut().blur = transformed_shadow.blur;
                        transformed_shadow.to_mut().spread = transformed_shadow.spread;

                        let mut new_shadow_paint = skia::Paint::default();
                        new_shadow_paint
                            .set_image_filter(transformed_shadow.get_drop_shadow_filter());
                        new_shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

                        self.with_nested_blurs_suppressed(|state| {
                            state.render_shape(
                                shadow_shape,
                                nested_clip_bounds,
                                SurfaceId::DropShadows,
                                SurfaceId::DropShadows,
                                SurfaceId::DropShadows,
                                SurfaceId::DropShadows,
                                true,
                                None,
                                Some(vec![new_shadow_paint.clone()]),
                                None,
                                target_surface,
                            )
                        })?;
                        self.surfaces.canvas(SurfaceId::DropShadows).restore();
                    }
                }
            }

            let mut paint = skia::Paint::default();
            paint.set_color(shadow.color);
            paint.set_blend_mode(skia::BlendMode::SrcIn);
            self.surfaces
                .canvas(SurfaceId::DropShadows)
                .draw_paint(&paint);

            self.surfaces.canvas(SurfaceId::DropShadows).restore();
        }

        if let Some(clips) = clip_bounds.as_ref() {
            let antialias = !self.options.is_fast_mode()
                && element.should_use_antialias(scale, self.options.antialias_threshold);
            self.surfaces.canvas(target_surface).save();
            self.clip_target_surface_to_stack(clips, target_surface, scale, antialias);
            self.surfaces
                .draw_into(SurfaceId::DropShadows, target_surface, None);
            self.surfaces.canvas(target_surface).restore();
        } else {
            self.surfaces
                .draw_into(SurfaceId::DropShadows, target_surface, None);
        }

        self.surfaces
            .canvas(SurfaceId::DropShadows)
            .clear(skia::Color::TRANSPARENT);
        Ok(())
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

        // During interactive transforms we compute the union of the current bounds of all
        // modified shapes (doc-space @ 100% zoom, scale=1.0). This is used as a cheap overlap
        // guard to decide when cached top-level crops are unsafe to reuse (something is moving
        // over/inside them), without doing expensive ancestor walks per node.
        //
        // `modifier_ids` is pre-computed once here and reused throughout the loop to avoid
        // repeated allocations (formerly O(N_shapes) HashMap builds) per node.
        let modifier_ids = tree.modifier_ids();
        let moved_bounds = if self.options.is_interactive_transform() && !modifier_ids.is_empty() {
            let mut acc: Option<Rect> = None;
            for id in modifier_ids.iter() {
                // Current (post-modifier) bounds
                if let Some(s) = tree.get(id) {
                    let r = self.get_cached_extrect(s, tree, 1.0);
                    acc = Some(match acc {
                        None => r,
                        Some(mut prev) => {
                            prev.join(r);
                            prev
                        }
                    });
                }

                // Pre-modifier bounds: important so cached top-level crops that still contain the
                // shape at its original position are considered "unsafe" even after the shape
                // has moved away (e.g. dragging a child out of a clipped frame).
                if let Some(raw) = tree.get_raw(id) {
                    let r0 = self.get_cached_extrect(raw, tree, 1.0);
                    acc = Some(match acc {
                        None => r0,
                        Some(mut prev) => {
                            prev.join(r0);
                            prev
                        }
                    });
                }
            }
            acc
        } else {
            None
        };

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
            let scale = self.get_scale();
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

                if !self.shape_visible_for_viewer_layer(&node_id, tree) {
                    continue;
                }

                // Ancestors needed to reach whitelisted descendants: traverse only.
                if self.include_filter.is_some()
                    && self.shape_visible_for_viewer_layer(&node_id, tree)
                    && !self.shape_should_paint_for_viewer_layer(&node_id)
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

            // Interactive drag cache: if this node is cacheable during interactive transform,
            // draw it directly from Backbuffer crop on the current tile surface and skip
            // traversing/rendering the subtree.
            if self.options.is_interactive_transform() {
                let use_cached = self.should_use_cached_top_level_during_interactive(
                    node_id,
                    tree,
                    modifier_ids,
                    moved_bounds,
                );

                if use_cached {
                    if let Some(crop) = self.backbuffer_crop_cache.get(&node_id) {
                        let crop_image = &crop.image;
                        let crop_src_selrect = crop.src_selrect;

                        let cur_selrect = tree.get(&node_id).map(|s| s.selrect());
                        let (dx, dy) = match cur_selrect {
                            Some(cur) => (
                                cur.left - crop_src_selrect.left,
                                cur.top - crop_src_selrect.top,
                            ),
                            None => (0.0, 0.0),
                        };
                        let scale = self.get_scale();
                        let translation = self
                            .surfaces
                            .get_render_context_translation(self.render_area, scale);

                        let canvas = self.surfaces.canvas(target_surface);
                        canvas.save();
                        canvas.reset_matrix();
                        // If the crop includes shadows/blur (extrect pixels outside the fill/stroke
                        // silhouette), do NOT apply the silhouette clip or we'd cut those pixels.
                        let should_clip_crop = element.shadows.is_empty() && element.blur.is_none();
                        if should_clip_crop {
                            if let Some(clip_path) = element.drag_crop_clip_path() {
                                let mut doc_to_tile = Matrix::new_identity();
                                // Map document-space coordinates into tile pixels.
                                // Rendering surfaces apply: scale(scale) then translate(translation) in doc units.
                                // Equivalent point mapping: (doc + translation) * scale.
                                doc_to_tile.post_translate((translation.0, translation.1));
                                doc_to_tile.post_scale((scale, scale), None);
                                let clip_path = clip_path.make_transform(&doc_to_tile);
                                canvas.clip_path(&clip_path, skia::ClipOp::Intersect, true);
                            }
                        }
                        let doc_left =
                            crop.capture_vb_left + (crop.capture_src_left as f32 / scale) + dx;
                        let doc_top =
                            crop.capture_vb_top + (crop.capture_src_top as f32 / scale) + dy;

                        let x = (doc_left + translation.0) * scale;
                        let y = (doc_top + translation.1) * scale;
                        let bw = crop_image.width() as f32;
                        let bh = crop_image.height() as f32;
                        let dst = skia::Rect::from_xywh(x, y, bw, bh);
                        canvas.draw_image_rect(crop_image, None, dst, &skia::Paint::default());

                        canvas.restore();
                    }
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
                    && !self.options.is_fast_mode()
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
                let skip_shadows = self.options.is_fast_mode();

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
                    }
                    Type::Group(_) if element.masked_group_layer_blur().is_some() => {
                        self.nested_blurs.push(None);
                    }
                    Type::Frame(_) | Type::Group(_) => {
                        self.nested_blurs.push(element.blur);
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

                let children_ids: Vec<_> = if can_flatten {
                    // Container was flattened: get simplified children (which skip this level)
                    get_simplified_children(tree, element)
                } else {
                    // Container not flattened: use original children
                    element.children_ids_iter(false).copied().collect()
                };

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

                    let tile_rect = self.get_current_tile_bounds()?;
                    // Composite if the walker did work in this PAF (`!is_empty`) OR
                    // the tile has unfinished work from a previous PAF
                    // (`current_tile_had_shapes` was set when we populated pending_nodes
                    // for this tile).
                    if !is_empty || self.current_tile_had_shapes {
                        if self.options.is_interactive_transform() {
                            // During drag, avoid snapshot-based caching. Draw Current directly
                            // into Target (and Cache) to reduce stalls.
                            self.surfaces.draw_current_tile_into_backbuffer(
                                &tile_rect,
                                self.background_color,
                                surfaces::DrawOnCache::Yes,
                            );
                        } else {
                            self.apply_render_to_final_canvas()?;
                        }

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
                if self.options.is_interactive_transform() || tile_has_bg_blur {
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
        if !self.options.is_fast_mode() {
            self.cached_viewbox = self.viewbox;
        }

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
        let extrect = self.get_cached_extrect(shape, tree, scale);
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

        // When the shape has an active modifier (i.e. is being moved/resized),
        // clear its OLD doc-space extent from the atlas using the raw
        // (pre-modifier) shape.  The per-tile clearing done later via
        // `clear_tile_in_atlas` only covers tiles tracked in `atlas.tile_doc_rects`
        // at the current zoom level. However, the atlas may also contain stale
        // pixels from previous zoom levels (tiles are larger / smaller in doc
        // space at different zoom scales) that were never re-tracked after a zoom
        // change.  Clearing the full raw extrect here removes all such residual
        // content without growing the atlas.
        //
        // We intentionally skip this when there is NO modifier so that plain
        // zoom / pan tile-index rebuilds do NOT invalidate valid atlas content.
        if tree.get_modifier(&shape.id).is_some() {
            if let Some(raw_shape) = tree.get_raw(&shape.id) {
                let old_extrect = raw_shape.extrect(tree, 1.0);
                self.surfaces
                    .atlas
                    .clear_doc_rect_in_atlas_clipped(old_extrect);
            }
        }

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
        if self.zoom_changed() {
            self.surfaces.remove_cached_tiles(self.background_color);
        } else {
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

        let ids = std::mem::take(&mut self.touched_ids);
        // Pan release sets `preserve_target` in `set_view_end`; don't reset it
        // here when no shapes changed, or the next render clears the canvas.
        if !ids.is_empty() {
            self.preserve_target_during_render = true;
        }

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

    pub fn get_cached_extrect(&mut self, shape: &Shape, tree: ShapesPoolRef, scale: f32) -> Rect {
        shape.extrect(tree, scale)
    }

    pub fn set_view(&mut self, zoom: f32, x: f32, y: f32) {
        self.viewbox.set_all(zoom, x, y);
    }

    pub fn print_stats(&self) {
        self.stats.print();
    }

    pub fn prepare_context_loss_cleanup(&mut self) {
        // Drop cached GPU-backed snapshots before dropping the render state.
        self.backbuffer_crop_cache.clear();
        self.surfaces.invalidate_tile_cache();
        // Mark context as abandoned so resource destructors avoid issuing
        // GL commands when the browser has already lost/restored the context.
        get_gpu_state().context.abandon();
    }

    pub fn free_gpu_resources(&mut self) {
        get_gpu_state().context.free_gpu_resources();
    }
}
