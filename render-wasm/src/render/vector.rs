use skia_safe::{self as skia, Canvas, Paint, RRect};

use crate::error::Result;
use crate::shapes::{
    merge_fills, radius_to_sigma, BlurType, Fill, Frame, Rect, Shape, Stroke, StrokeKind, Type,
};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::get_source_rect;
use super::shape_renderer::ShapeRenderer;
use super::text;
use super::RenderState;

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
    render_state: &'a mut RenderState,
    scale: f32,
    _target: VectorTarget,
}

impl<'a> VectorRenderer<'a> {
    pub fn new(
        canvas: &'a Canvas,
        render_state: &'a mut RenderState,
        scale: f32,
        target: VectorTarget,
    ) -> Self {
        Self {
            canvas,
            render_state,
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
                        draw_image_fill(self.render_state, self.canvas, shape, image_fill)?;
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
            draw_single_stroke(self.canvas, shape, stroke)?;
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

        text::render(
            None,
            Some(self.canvas),
            shape,
            &mut paragraph_builders,
            None,
            None,
            blur_filter.as_ref(),
            None,
            None,
        )?;

        // Strokes for text
        let count_inner_strokes = shape.count_visible_inner_strokes();
        let stroke_blur_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);

        for stroke in shape.visible_strokes().rev() {
            let (mut stroke_paragraphs, layer_opacity) =
                text::stroke_paragraph_builder_group_from_text(
                    &text_content,
                    stroke,
                    &shape.selrect(),
                    count_inner_strokes,
                    None,
                );
            text::render_with_bounds_outset(
                None,
                Some(self.canvas),
                shape,
                &mut stroke_paragraphs,
                None,
                None,
                blur_filter.as_ref(),
                stroke_blur_outset,
                None,
                layer_opacity,
            )?;
        }

        // Inner shadows for text
        let inner_shadows: Vec<_> = shape.inner_shadows_visible().collect();
        if !inner_shadows.is_empty() {
            let mut shadow_paragraphs =
                text_content.paragraph_builder_group_from_text(Some(true));
            for shadow in &inner_shadows {
                let shadow_paint = shadow.get_inner_shadow_paint(true, blur_filter.as_ref());
                text::render(
                    None,
                    Some(self.canvas),
                    shape,
                    &mut shadow_paragraphs,
                    None,
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
            let font_manager =
                skia::FontMgr::from(self.render_state.fonts().font_provider().clone());
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
    render_state: &mut RenderState,
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
            render_group(
                render_state,
                canvas,
                element,
                group.masked,
                tree,
                scale,
                target,
            )?;
        }
        Type::Frame(_) => {
            render_frame(render_state, canvas, element, tree, scale, target)?;
        }
        _ => {
            render_leaf(render_state, canvas, element, scale, target)?;
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

fn render_group(
    render_state: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    // Apply transform
    let center = element.center();
    let mut matrix = element.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);

    canvas.save();
    canvas.concat(&matrix);

    // Layer for opacity / blend mode
    let needs_layer = element.needs_layer();
    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());
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
                render_tree(render_state, canvas, child_id, tree, scale, target)?;
            }

            // Render mask with DstIn
            let mut mask_paint = Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            canvas.save_layer(&mask_rec);
            render_tree(render_state, canvas, mask_id, tree, scale, target)?;
            canvas.restore(); // mask layer

            canvas.restore(); // composition layer
        }
    } else {
        // Normal group: render children in order (forward = back-to-front for painter's algorithm)
        let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
        for child_id in &children {
            render_tree(render_state, canvas, child_id, tree, scale, target)?;
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
    render_state: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    // Apply transform
    let center = element.center();
    let mut matrix = element.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);

    canvas.save();
    canvas.concat(&matrix);

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

    // Clip to frame bounds if clip_content
    if element.clip_content {
        clip_to_shape(canvas, element, false);
    }

    // Draw the frame's own fills (background)
    if !element.fills.is_empty() {
        let mut renderer = VectorRenderer::new(canvas, render_state, scale, target);
        renderer.draw_fills(element, &element.fills)?;
    }

    // Render children (forward = back-to-front for painter's algorithm)
    let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(render_state, canvas, child_id, tree, scale, target)?;
    }

    // Strokes drawn after children for clipped frames (over children)
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let mut renderer = VectorRenderer::new(canvas, render_state, scale, target);
        renderer.draw_strokes(element, &visible_strokes)?;
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore(); // transform
    Ok(())
}

// ---------------------------------------------------------------------------
// Leaf shapes (Rect, Circle, Path, Bool, Text, SVGRaw)
// ---------------------------------------------------------------------------

fn render_leaf(
    render_state: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let needs_layer = element.needs_layer();

    // Compute transform
    let center = element.center();
    let mut matrix = element.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);

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
    let mut renderer = VectorRenderer::new(canvas, render_state, scale, target);

    // Layer blur (non-text shapes)
    let blur_layer = if !matches!(element.shape_type, Type::Text(_)) {
        renderer.apply_blur_layer(element)
    } else {
        false
    };

    // Drop shadows
    renderer.draw_drop_shadows(element)?;

    // Shape-type-specific rendering
    match &element.shape_type {
        Type::Text(_) => {
            renderer.draw_text(element)?;
        }
        Type::SVGRaw(_) => {
            renderer.draw_svg(element)?;
        }
        _ => {
            // Rect, Circle, Path, Bool
            renderer.draw_fills(element, &element.fills)?;

            // Inner shadows on fills
            renderer.draw_fill_inner_shadows(element)?;

            // Strokes
            let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
            if !visible_strokes.is_empty() {
                renderer.draw_strokes(element, &visible_strokes)?;

                // Inner shadows on strokes (only if no fills, to avoid double-drawing)
                if !element.has_fills() {
                    for stroke in &visible_strokes {
                        renderer.draw_stroke_inner_shadows(element, stroke)?;
                    }
                }
            }
        }
    }

    if blur_layer {
        renderer.restore_blur_layer();
    }

    if needs_layer {
        canvas.restore();
    }

    canvas.restore();
    Ok(())
}

// ---------------------------------------------------------------------------
// Private helpers (canvas-only, not part of the trait)
// ---------------------------------------------------------------------------

fn draw_image_fill(
    render_state: &mut RenderState,
    canvas: &Canvas,
    shape: &Shape,
    image_fill: &crate::shapes::ImageFill,
) -> Result<()> {
    // Use a CPU-backed image copy — GPU-backed images can't be drawn
    // on the PDF canvas which has no GPU context.
    let Some(image) = render_state.images.get_cpu_image(&image_fill.id()) else {
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

    canvas.draw_image_rect_with_sampling_options(
        &image,
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        render_state.sampling_options,
        &paint,
    );

    canvas.restore();
    Ok(())
}

fn draw_single_stroke(canvas: &Canvas, shape: &Shape, stroke: &Stroke) -> Result<()> {
    let is_open = shape.is_open();
    let kind = stroke.render_kind(is_open);
    let paint = stroke.to_stroked_paint(is_open, &shape.selrect, shape.svg_attrs.as_ref(), true);

    match kind {
        StrokeKind::Inner => {
            // Inner stroke: clip to shape, draw with double width
            canvas.save();
            clip_to_shape(canvas, shape, true);
            draw_shape_geometry(canvas, shape, &paint);
            canvas.restore();
        }
        StrokeKind::Outer => {
            // Outer stroke: use save_layer + clip difference to draw outside shape only
            canvas.save();
            let layer_rec = skia::canvas::SaveLayerRec::default();
            canvas.save_layer(&layer_rec);
            draw_shape_geometry(canvas, shape, &paint);
            // Clear inside the shape to keep only outer part
            let mut clear_paint = Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            let mut fill_paint = clear_paint;
            fill_paint.set_style(skia::PaintStyle::Fill);
            draw_shape_geometry(canvas, shape, &fill_paint);
            canvas.restore(); // layer
            canvas.restore();
        }
        StrokeKind::Center => {
            draw_shape_geometry(canvas, shape, &paint);
        }
    }

    Ok(())
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
                if let Some(path_transform) = shape.to_path_transform() {
                    let transformed = path.make_transform(&path_transform);
                    canvas.draw_path(&transformed, paint);
                } else {
                    canvas.draw_path(&path, paint);
                }
            }
        }
        _ => {}
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
                if let Some(path_transform) = shape.to_path_transform() {
                    canvas.clip_path(
                        &path.make_transform(&path_transform),
                        skia::ClipOp::Intersect,
                        antialias,
                    );
                } else {
                    canvas.clip_path(&path, skia::ClipOp::Intersect, antialias);
                }
            }
        }
        _ => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
    }
}
