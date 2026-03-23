use skia_safe::{self as skia, Canvas, Paint, RRect};

use crate::error::Result;
use crate::shapes::{
    merge_fills, radius_to_sigma, BlurType, Fill, Frame, Rect, Shape, Stroke, StrokeKind, Type,
};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::shape_renderer::ShapeRenderer;
use super::text;
use super::RenderState;
use super::{get_dest_rect, get_source_rect};

// ---------------------------------------------------------------------------
// VectorTarget — vector export backend selector
// ---------------------------------------------------------------------------

/// Selects backend-specific behaviour in [`VectorRenderer`].
///
/// Currently only PDF is supported. The enum exists so that adding
/// future vector targets (e.g. SVG) only requires a new variant and
/// target-specific branches in `VectorRenderer`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum VectorTarget {
    Pdf,
}

// ---------------------------------------------------------------------------
// VectorRenderer — implements ShapeRenderer for canvas-based vector export
// ---------------------------------------------------------------------------

/// Canvas-based vector render backend (used by both PDF and SVG export).
/// Draws directly to a Skia canvas (CPU-only, no GPU surfaces).
/// Implements [`ShapeRenderer`] so that adding a new trait method produces
/// a compile error until the vector export path handles it.
pub(super) struct VectorRenderer<'a> {
    canvas: &'a Canvas,
    shared: &'a mut RenderState,
    scale: f32,
    _target: VectorTarget,
}

impl<'a> VectorRenderer<'a> {
    pub fn new(
        canvas: &'a Canvas,
        shared: &'a mut RenderState,
        scale: f32,
        target: VectorTarget,
    ) -> Self {
        Self {
            canvas,
            shared,
            scale,
            _target: target,
        }
    }
}

impl ShapeRenderer for VectorRenderer<'_> {
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()> {
        if fills.is_empty() {
            return Ok(());
        }

        // Handle image fills individually
        let has_image_fills = fills.iter().any(|f| matches!(f, Fill::Image(_)));
        if has_image_fills {
            for fill in fills.iter().rev() {
                match fill {
                    Fill::Image(image_fill) => {
                        draw_image_fill(self.shared, self.canvas, shape, image_fill)?;
                    }
                    _ => {
                        let mut paint = fill.to_paint(&shape.selrect, true);
                        if let Some(filter) = shape.image_filter(1.) {
                            paint.set_image_filter(filter);
                        }
                        draw_shape_geometry(self.canvas, shape, &paint);
                    }
                }
            }
            return Ok(());
        }

        let mut paint = merge_fills(fills, shape.selrect);
        paint.set_anti_alias(true);

        if let Some(filter) = shape.image_filter(1.) {
            paint.set_image_filter(filter);
        }

        draw_shape_geometry(self.canvas, shape, &paint);
        Ok(())
    }

    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()> {
        for stroke in strokes.iter().rev() {
            draw_single_stroke(self.canvas, self.shared, self.scale, shape, stroke)?;
        }
        Ok(())
    }

    fn draw_drop_shadows(&mut self, shape: &Shape) -> Result<()> {
        for shadow in shape.drop_shadows_visible() {
            if let Some(filter) = shadow.get_drop_shadow_filter() {
                let mut paint = Paint::default();
                paint.set_image_filter(filter);
                let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                self.canvas.save_layer(&layer_rec);
                let mut fill_paint = Paint::default();
                fill_paint.set_anti_alias(true);
                fill_paint.set_color(skia::Color::BLACK);
                draw_shape_geometry(self.canvas, shape, &fill_paint);
                self.canvas.restore();
            }
        }
        Ok(())
    }

    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()> {
        if !shape.has_fills() {
            return Ok(());
        }
        for shadow in shape.inner_shadows_visible() {
            let paint = shadow.get_inner_shadow_paint(true, shape.image_filter(1.).as_ref());
            self.canvas
                .save_layer(&skia::canvas::SaveLayerRec::default().paint(&paint));
            let mut fill_paint = Paint::default();
            fill_paint.set_anti_alias(true);
            fill_paint.set_color(skia::Color::BLACK);
            draw_shape_geometry(self.canvas, shape, &fill_paint);
            self.canvas.restore();
        }
        Ok(())
    }

    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()> {
        let is_open = shape.is_open();
        for shadow in shape.inner_shadows_visible() {
            if let Some(filter) = shadow.get_inner_shadow_filter() {
                let mut paint = stroke.to_stroked_paint(
                    is_open,
                    &shape.selrect,
                    shape.svg_attrs.as_ref(),
                    true,
                );
                paint.set_image_filter(filter);
                draw_shape_geometry(self.canvas, shape, &paint);
            }
        }
        Ok(())
    }

    fn draw_text(&mut self, shape: &Shape) -> Result<()> {
        let Type::Text(text_content) = &shape.shape_type else {
            return Ok(());
        };

        let text_content = text_content.new_bounds(shape.selrect());
        let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
        let blur_filter = shape.image_filter(1.);

        // Drop shadows for text (fill + per-stroke silhouettes). Each shadow is
        // a single layer carrying the drop-shadow filter, mirroring GPU
        // `shadows::render_text_shadows`, so overlapping fill/stroke shadows
        // don't double-darken.
        let drop_shadows = shape.drop_shadow_paints();
        if !drop_shadows.is_empty() {
            let shadow_stroke_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);
            let mut shadow_paragraphs = text_content.paragraph_builder_group_from_text(Some(true));
            let mut stroke_shadow_groups: Vec<(StrokeKind, _)> = shape
                .visible_strokes()
                .rev()
                .map(|stroke| {
                    (
                        stroke.render_kind(false),
                        text::stroke_paragraph_builder_group_from_text(
                            &text_content,
                            stroke,
                            &shape.selrect(),
                            Some(true),
                        )
                        .0,
                    )
                })
                .collect();

            for shadow_paint in &drop_shadows {
                self.canvas
                    .save_layer(&skia::canvas::SaveLayerRec::default().paint(shadow_paint));

                text::render_overlay_emoji(
                    self.canvas,
                    shape,
                    &mut shadow_paragraphs,
                    None,
                    blur_filter.as_ref(),
                    None,
                    None,
                )?;

                for (kind, stroke_paragraphs) in &mut stroke_shadow_groups {
                    if *kind == StrokeKind::Inner {
                        // Inner strokes are masked by the glyph fill; mirror GPU
                        // `render_inner_stroke` (outset 0 inside the shadow layer).
                        let mut mask_builders = text_content.paragraph_builder_group_opaque();
                        let mut fill_builders =
                            text_content.paragraph_builder_group_from_text(Some(true));
                        text::render_inner_stroke(
                            None,
                            Some(self.canvas),
                            shape,
                            &mut mask_builders,
                            stroke_paragraphs,
                            &mut fill_builders,
                            None,
                            blur_filter.as_ref(),
                            0.0,
                            None,
                        )?;
                    } else {
                        text::render_with_bounds_outset_overlay_emoji(
                            self.canvas,
                            shape,
                            stroke_paragraphs,
                            None,
                            blur_filter.as_ref(),
                            shadow_stroke_outset,
                            None,
                            None,
                        )?;
                    }
                }

                self.canvas.restore();
            }
        }

        text::render_overlay_emoji(
            self.canvas,
            shape,
            &mut paragraph_builders,
            None,
            blur_filter.as_ref(),
            None,
            None,
        )?;

        // Strokes for text
        let stroke_blur_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);

        for stroke in shape.visible_strokes().rev() {
            let (mut stroke_paragraphs, layer_opacity) =
                text::stroke_paragraph_builder_group_from_text(
                    &text_content,
                    stroke,
                    &shape.selrect(),
                    None,
                );
            if stroke.render_kind(false) == StrokeKind::Inner {
                // Inner text stroke: clip the stroke to the glyph fill via
                // `render_inner_stroke` (matches GPU), otherwise it bleeds
                // outside the glyphs.
                let mut mask_builders = text_content.paragraph_builder_group_opaque();
                let mut fill_builders = text_content.paragraph_builder_group_from_text(None);
                text::render_inner_stroke(
                    None,
                    Some(self.canvas),
                    shape,
                    &mut mask_builders,
                    &mut stroke_paragraphs,
                    &mut fill_builders,
                    None,
                    blur_filter.as_ref(),
                    stroke_blur_outset,
                    layer_opacity,
                )?;
            } else {
                text::render_with_bounds_outset_overlay_emoji(
                    self.canvas,
                    shape,
                    &mut stroke_paragraphs,
                    None,
                    blur_filter.as_ref(),
                    stroke_blur_outset,
                    None,
                    layer_opacity,
                )?;
            }
        }

        // Inner shadows for text
        let inner_shadows: Vec<_> = shape.inner_shadows_visible().collect();
        if !inner_shadows.is_empty() {
            let mut shadow_paragraphs = text_content.paragraph_builder_group_from_text(Some(true));
            for shadow in &inner_shadows {
                let shadow_paint = shadow.get_inner_shadow_paint(true, blur_filter.as_ref());
                text::render_overlay_emoji(
                    self.canvas,
                    shape,
                    &mut shadow_paragraphs,
                    Some(&shadow_paint),
                    blur_filter.as_ref(),
                    None,
                    None,
                )?;
            }
        }

        Ok(())
    }

    fn draw_svg(&mut self, shape: &Shape) -> Result<()> {
        let Type::SVGRaw(sr) = &shape.shape_type else {
            return Ok(());
        };

        if let Some(svg_transform) = shape.svg_transform() {
            self.canvas.concat(&svg_transform);
        }
        if let Some(svg) = shape.svg.as_ref() {
            svg.render(self.canvas);
        } else {
            let font_manager = skia::FontMgr::from(self.shared.fonts.font_provider().clone());
            if let Ok(dom) = skia::svg::Dom::from_str(&sr.content, font_manager) {
                dom.render(self.canvas);
            }
        }

        Ok(())
    }

    fn apply_blur_layer(&mut self, shape: &Shape) -> bool {
        let blur = match shape.blur {
            Some(b) if !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0 => b,
            _ => return false,
        };

        let sigma = radius_to_sigma(blur.value * self.scale);
        if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
            let mut paint = Paint::default();
            paint.set_image_filter(filter);
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            self.canvas.save_layer(&layer_rec);
            true
        } else {
            false
        }
    }

    fn restore_blur_layer(&mut self) {
        self.canvas.restore();
    }
}

// ---------------------------------------------------------------------------
// Tree traversal
// ---------------------------------------------------------------------------

/// Depth-first render of the shape tree rooted at `id`.
pub(super) fn render_tree(
    shared: &mut RenderState,
    canvas: &Canvas,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };

    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(group) => {
            render_group(shared, canvas, element, group.masked, tree, scale, target)?;
        }
        Type::Frame(_) => {
            render_frame(shared, canvas, element, tree, scale, target)?;
        }
        // All leaf shape types. Explicit (no `_`) so a new Type variant forces
        // a routing decision here instead of silently falling through to leaf.
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => {
            render_leaf(shared, canvas, element, scale, target)?;
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

fn render_group(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let matrix = element.centered_transform();

    canvas.save();
    canvas.concat(&matrix);

    // Drop shadows for the group as a whole (silhouette of the subtree).
    // Drawn before the opacity/clip layer so the shadow sits below the
    // content and isn't clipped away.
    render_container_drop_shadows(shared, canvas, element, tree, scale, target, false)?;

    // Layer for opacity / blend mode (and group-level layer blur)
    let needs_layer = element.needs_layer();
    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        if let Some(blur) = element
            .blur
            .filter(|b| !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0)
        {
            let sigma = radius_to_sigma(blur.value * scale);
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    if masked {
        // Masked group: render all children except the last, then apply the
        // mask child with DstIn blend.
        let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
        if let Some((mask_id, content_ids)) = children.split_last() {
            // Save a layer for the mask composition
            let paint = Paint::default();
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            canvas.save_layer(&layer_rec);

            // Render content children
            for child_id in content_ids {
                render_tree(shared, canvas, child_id, tree, scale, target)?;
            }

            // Render mask with DstIn
            let mut mask_paint = Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            canvas.save_layer(&mask_rec);
            render_tree(shared, canvas, mask_id, tree, scale, target)?;
            canvas.restore(); // mask layer

            canvas.restore(); // composition layer
        }
    } else {
        // Normal group: render children in order (forward = back-to-front for painter's algorithm)
        let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale, target)?;
        }
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore(); // transform
    Ok(())
}

// ---------------------------------------------------------------------------
// Frames
// ---------------------------------------------------------------------------

fn render_frame(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let matrix = element.centered_transform();

    canvas.save();
    canvas.concat(&matrix);

    // Drop shadows for the frame (silhouette of background + subtree). Drawn
    // before the opacity/clip layer so the shadow sits below the content and
    // extends outside the (clipped) frame bounds.
    render_container_drop_shadows(shared, canvas, element, tree, scale, target, true)?;

    let needs_layer = element.needs_layer();

    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        // Frame-level layer blur
        if let Some(blur) = element
            .blur
            .filter(|b| !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0)
        {
            let sigma = radius_to_sigma(blur.value * scale);
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    // Clip to frame bounds if clip_content. Outset by ~0.5px (device) like the
    // GPU clip so edge pixels aliased as "outside" aren't shaved off, leaving
    // an AA seam between frame content and its border.
    if element.clip_content {
        clip_to_frame_content(canvas, element, scale);
    }

    // Draw the frame's own fills (background) plus any inner shadows on them
    if !element.fills.is_empty() {
        let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
        renderer.draw_fills(element, &element.fills)?;
        renderer.draw_fill_inner_shadows(element)?;
    }

    // Render children (forward = back-to-front for painter's algorithm)
    let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(shared, canvas, child_id, tree, scale, target)?;
    }

    // Strokes drawn after children for clipped frames (over children)
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
        renderer.draw_strokes(element, &visible_strokes)?;
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore(); // transform
    Ok(())
}

/// Renders drop shadows for a container (group/frame) as a silhouette of its
/// whole subtree, mirroring the GPU path which renders every descendant into
/// the shadow. For each visible drop shadow the subtree is rendered into a
/// layer carrying the drop-shadow image filter (which turns the layer's alpha
/// into a colored, offset, blurred shadow); the real content is drawn
/// afterwards by the caller, on top. When `draw_fills` is set the container's
/// own fills (frame background) are included in the silhouette.
fn render_container_drop_shadows(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
    draw_fills: bool,
) -> Result<()> {
    for shadow in element.drop_shadows_visible() {
        let Some(filter) = shadow.get_drop_shadow_filter() else {
            continue;
        };
        let mut paint = Paint::default();
        paint.set_image_filter(filter);
        canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&paint));

        if draw_fills && !element.fills.is_empty() {
            let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
            renderer.draw_fills(element, &element.fills)?;
        }

        let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale, target)?;
        }

        canvas.restore();
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Leaf shapes (Rect, Circle, Path, Bool, Text, SVGRaw)
// ---------------------------------------------------------------------------

fn render_leaf(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let needs_layer = element.needs_layer();

    let matrix = element.centered_transform();

    canvas.save();
    canvas.concat(&matrix);

    // Layer for opacity/blend
    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());
        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    // Use VectorRenderer for all drawing operations via the ShapeRenderer trait.
    let mut renderer = VectorRenderer::new(canvas, shared, scale, target);

    // Layer blur (non-text shapes)
    let blur_layer = if !matches!(element.shape_type, Type::Text(_)) {
        renderer.apply_blur_layer(element)
    } else {
        false
    };

    // Drop shadows
    renderer.draw_drop_shadows(element)?;

    // Shape content (fills, inner shadows, strokes) in the canonical order.
    render_leaf_content(&mut renderer, element)?;

    if blur_layer {
        renderer.restore_blur_layer();
    }

    if needs_layer {
        canvas.restore();
    }

    canvas.restore();
    Ok(())
}

/// Backend-agnostic draw order + gating for a leaf shape's content (fills,
/// fill inner shadows, strokes, stroke inner shadows).
///
/// This is the single source of truth for *content ordering*, expressed purely
/// in terms of [`ShapeRenderer`] so the GPU backend can share it verbatim once
/// it implements the trait — making this the one place where order/gating drift
/// between live and vector render could occur, and a compile error the moment a
/// new content capability is added to the trait without being ordered here.
///
/// Note: drop shadows and layer blur are intentionally NOT here — they wrap the
/// content and are sequenced differently per backend (GPU composites drop
/// shadows from a separate surface at the tree level; vector draws them inline).
fn render_leaf_content<R: ShapeRenderer + ?Sized>(renderer: &mut R, shape: &Shape) -> Result<()> {
    match &shape.shape_type {
        Type::Text(_) => renderer.draw_text(shape)?,
        Type::SVGRaw(_) => renderer.draw_svg(shape)?,
        // Group/Frame never reach a leaf content render; listed so a new Type
        // variant forces a decision here.
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Group(_)
        | Type::Frame(_) => {
            renderer.draw_fills(shape, &shape.fills)?;
            renderer.draw_fill_inner_shadows(shape)?;

            let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
            if !visible_strokes.is_empty() {
                renderer.draw_strokes(shape, &visible_strokes)?;

                // Stroke inner shadows only when there are no fills, to avoid
                // double-drawing (matches GPU `render_stroke_inner_shadows`).
                if !shape.has_fills() {
                    for stroke in &visible_strokes {
                        renderer.draw_stroke_inner_shadows(shape, stroke)?;
                    }
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Private helpers (canvas-only, not part of the trait)
// ---------------------------------------------------------------------------

fn draw_image_fill(
    shared: &mut RenderState,
    canvas: &Canvas,
    shape: &Shape,
    image_fill: &crate::shapes::ImageFill,
) -> Result<()> {
    // Use a CPU-backed image copy — GPU-backed images can't be drawn
    // on the PDF canvas which has no GPU context.
    let Some(image) = shared.images.get_cpu_image(&image_fill.id()) else {
        return Ok(());
    };

    let size = image.dimensions();
    let container = &shape.selrect;

    let src_rect = get_source_rect(size, container, image_fill);
    let dest_rect = container;

    canvas.save();

    // Clip to shape
    clip_to_shape(canvas, shape, true);

    let mut paint = Paint::default();
    paint.set_anti_alias(true);
    if let Some(filter) = shape.image_filter(1.) {
        paint.set_image_filter(filter);
    }

    canvas.draw_image_rect_with_sampling_options(
        &image,
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        shared.sampling_options,
        &paint,
    );

    canvas.restore();
    Ok(())
}

fn draw_single_stroke(
    canvas: &Canvas,
    shared: &mut RenderState,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
) -> Result<()> {
    // Image-fill strokes: the stroke masks the visible area of the image
    // (matches GPU `draw_image_stroke_in_container`).
    if let Fill::Image(image_fill) = &stroke.fill {
        return draw_image_stroke(canvas, shared, scale, shape, stroke, image_fill);
    }

    draw_stroke_geometry(canvas, scale, shape, stroke, false);
    Ok(())
}

/// Draws a stroke's geometry honoring shape type, kind (inner/outer/center),
/// and dash/dot style.
///
/// - Rect/Frame/Circle reuse the GPU stroke functions (`aligned_rect` +
///   `clip_op` + `outer_corners`) so dotted/dashed and inner/outer alignment
///   match the live render exactly.
/// - Path/Bool use the double-width + clip/clear technique plus explicit caps
///   for open paths.
///
/// Blur/shadow are handled by the per-shape layer in `render_leaf`, so filters
/// are passed as `None` here. When `opaque` is set the paint color/shader are
/// overridden to opaque black — used to build an image-stroke silhouette before
/// an `SrcIn` image draw.
fn draw_stroke_geometry(canvas: &Canvas, scale: f32, shape: &Shape, stroke: &Stroke, opaque: bool) {
    let svg_attrs = shape.svg_attrs.as_ref();
    let is_open = shape.is_open();

    match &shape.shape_type {
        shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
            let corners = shape_type.corners();
            let mut paint = stroke.to_paint(&shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            super::strokes::draw_stroke_on_rect(
                canvas,
                stroke,
                &shape.selrect,
                &corners,
                &paint,
                scale,
                None,
                None,
                true,
            );
        }
        Type::Circle => {
            let mut paint = stroke.to_paint(&shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            super::strokes::draw_stroke_on_circle(
                canvas,
                stroke,
                &shape.selrect,
                &paint,
                scale,
                None,
                None,
                true,
            );
        }
        Type::Path(_) | Type::Bool(_) => {
            let mut paint = stroke.to_stroked_paint(is_open, &shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            draw_stroke_kind_aware(canvas, shape, stroke, &paint);

            if is_open {
                if let Some(cap_path) = transformed_skia_path(shape) {
                    super::strokes::handle_stroke_caps(
                        &cap_path, stroke, canvas, is_open, &paint, None, true,
                    );
                }
            }
        }
        // Text strokes are drawn in draw_text; groups/svg never carry strokes.
        // Explicit so a new Type variant forces a decision here.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {}
    }
}

/// Draws the shape's stroke geometry honoring the stroke kind (inner/outer/
/// center) using the same double-width + clip/clear technique as
/// `draw_single_stroke`. `paint` must already be a stroked paint.
fn draw_stroke_kind_aware(canvas: &Canvas, shape: &Shape, stroke: &Stroke, paint: &Paint) {
    match stroke.render_kind(shape.is_open()) {
        StrokeKind::Inner => {
            canvas.save();
            clip_to_shape(canvas, shape, true);
            draw_shape_geometry(canvas, shape, paint);
            canvas.restore();
        }
        StrokeKind::Outer => {
            canvas.save();
            canvas.save_layer(&skia::canvas::SaveLayerRec::default());
            draw_shape_geometry(canvas, shape, paint);
            let mut clear_paint = Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            clear_paint.set_style(skia::PaintStyle::Fill);
            draw_shape_geometry(canvas, shape, &clear_paint);
            canvas.restore(); // layer
            canvas.restore();
        }
        StrokeKind::Center => {
            draw_shape_geometry(canvas, shape, paint);
        }
    }
}

/// Renders a stroke whose fill is an image: draw the stroke silhouette as an
/// opaque mask inside a layer, then paint the (CPU) image over it with `SrcIn`
/// so only the stroke area shows the image.
fn draw_image_stroke(
    canvas: &Canvas,
    shared: &mut RenderState,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
    image_fill: &crate::shapes::ImageFill,
) -> Result<()> {
    let Some(image) = shared.images.get_cpu_image(&image_fill.id()) else {
        return Ok(());
    };
    let size = image.dimensions();
    let container = shape.selrect;

    canvas.save();
    canvas.save_layer(&skia::canvas::SaveLayerRec::default());

    // Opaque silhouette of the stroke (kind/dash-aware, including caps); the
    // SrcIn image draw below replaces the covered pixels with the image.
    draw_stroke_geometry(canvas, scale, shape, stroke, true);

    let mut image_paint = Paint::default();
    image_paint.set_blend_mode(skia::BlendMode::SrcIn);
    image_paint.set_anti_alias(true);
    if let Some(filter) = shape.image_filter(1.) {
        image_paint.set_image_filter(filter);
    }

    let src_rect = get_source_rect(size, &container, image_fill);
    let dest_rect = get_dest_rect(&container, stroke.delta());
    canvas.draw_image_rect_with_sampling_options(
        &image,
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        shared.sampling_options,
        &image_paint,
    );

    canvas.restore(); // layer
    canvas.restore();
    Ok(())
}

fn transformed_skia_path(shape: &Shape) -> Option<skia::Path> {
    if !matches!(shape.shape_type, Type::Path(_) | Type::Bool(_)) {
        return None;
    }
    shape.get_skia_path()
}

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

/// Draws the shape's geometry (rect/rrect/oval/path) with the given paint.
fn draw_shape_geometry(canvas: &Canvas, shape: &Shape, paint: &Paint) {
    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            if let Some(corners) = shape.shape_type.corners() {
                let rrect = RRect::new_rect_radii(shape.selrect, &corners);
                canvas.draw_rrect(rrect, paint);
            } else {
                canvas.draw_rect(shape.selrect, paint);
            }
        }
        Type::Circle => {
            canvas.draw_oval(shape.selrect, paint);
        }
        Type::Path(_) | Type::Bool(_) => {
            if let Some(path) = shape.get_skia_path() {
                canvas.draw_path(&path, paint);
            }
        }
        // Not drawn as plain geometry; handled by draw_text / draw_svg /
        // group traversal. Listed explicitly so a new Type variant forces a
        // decision here instead of silently no-op'ing in PDF export.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {}
    }
}

/// Clips the canvas to a frame's content bounds, outset by ~0.5 device px so
/// the hard (non-AA) clip edge doesn't shave off edge pixels and leave a seam.
fn clip_to_frame_content(canvas: &Canvas, shape: &Shape, scale: f32) {
    let outset = 0.5 / scale.max(1e-6);
    let mut rect = shape.selrect;
    rect.outset((outset, outset));
    match shape.shape_type.corners() {
        Some(corners) => {
            let rrect = RRect::new_rect_radii(rect, &corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, false);
        }
        None => {
            canvas.clip_rect(rect, skia::ClipOp::Intersect, false);
        }
    }
}

/// Clips the canvas to the shape's geometry.
fn clip_to_shape(canvas: &Canvas, shape: &Shape, antialias: bool) {
    let container = &shape.selrect;
    match &shape.shape_type {
        Type::Rect(Rect {
            corners: Some(corners),
        })
        | Type::Frame(Frame {
            corners: Some(corners),
            ..
        }) => {
            let rrect = RRect::new_rect_radii(*container, corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
        }
        Type::Rect(_) | Type::Frame(_) => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
        Type::Circle => {
            let mut pb = skia::PathBuilder::new();
            pb.add_oval(*container, None, None);
            canvas.clip_path(&pb.detach(), skia::ClipOp::Intersect, antialias);
        }
        Type::Path(_) | Type::Bool(_) => {
            if let Some(path) = shape.get_skia_path() {
                canvas.clip_path(&path, skia::ClipOp::Intersect, antialias);
            }
        }
        // Fallback to the bounding rect. Listed explicitly so a new Type
        // variant forces a decision here instead of defaulting silently.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
    }
}
