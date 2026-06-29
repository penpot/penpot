use skia_safe::{self as skia, Canvas, Paint, RRect};

use crate::error::Result;
use crate::shapes::{
    merge_fills, radius_to_sigma, BlurType, Fill, Frame, Rect, Shape, Stroke, StrokeKind, Type,
};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::shape_renderer::ShapeRenderer;
use super::text;
use super::RenderResources;
use super::{get_dest_rect, get_source_rect};

// ---------------------------------------------------------------------------
// VectorRenderer — implements ShapeRenderer for canvas-based vector export
// ---------------------------------------------------------------------------

/// Canvas-based vector render backend (CPU Skia canvas, no GPU surfaces).
pub(super) struct VectorRenderer<'a> {
    canvas: &'a Canvas,
    shared: &'a mut RenderResources,
    scale: f32,
}

impl<'a> VectorRenderer<'a> {
    pub fn new(canvas: &'a Canvas, shared: &'a mut RenderResources, scale: f32) -> Self {
        Self {
            canvas,
            shared,
            scale,
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

        // Text drop shadows: one filter layer per shadow over fill + stroke
        // silhouettes (mirrors GPU `render_text_shadows`).
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
                        // Inner stroke masked by the glyph fill (outset 0 here).
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
                // Inner text stroke: clip to the glyph fill, else it bleeds out.
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
    shared: &mut RenderResources,
    canvas: &Canvas,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };

    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(group) => {
            render_group(shared, canvas, element, group.masked, tree, scale)?;
        }
        Type::Frame(_) => {
            render_frame(shared, canvas, element, tree, scale)?;
        }
        // Leaf types listed explicitly (no `_`) so a new Type must be handled.
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => {
            render_leaf(shared, canvas, element, scale)?;
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

fn render_group(
    shared: &mut RenderResources,
    canvas: &Canvas,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    // A group has no geometry of its own and does NOT propagate a transform to
    // its children: child shapes are stored in absolute coordinates and each
    // applies its own `centered_transform`. (Concatenating the group transform
    // here would double-apply it to children — visible on rotated/nested groups.)
    canvas.save();

    // Group drop shadow: subtree silhouette, below the opacity/clip layer.
    render_container_drop_shadows(shared, canvas, element, tree, scale, false)?;

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

    let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();

    if masked {
        // Mirror the GPU mask: render all children (including the mask shape)
        // as content, then re-draw the mask silhouette (the group's first child)
        // with DstIn to clip everything to it.
        let paint = Paint::default();
        canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&paint));

        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale)?;
        }

        if let Some(mask_id) = element.mask_id() {
            let mut mask_paint = Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&mask_paint));
            render_tree(shared, canvas, mask_id, tree, scale)?;
            canvas.restore(); // mask layer
        }

        canvas.restore(); // composition layer
    } else {
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale)?;
        }
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore();
    Ok(())
}

// ---------------------------------------------------------------------------
// Frames
// ---------------------------------------------------------------------------

fn render_frame(
    shared: &mut RenderResources,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    // A frame's own geometry (background, clip, strokes) is placed by its
    // `centered_transform`, but — like groups — it does NOT propagate that
    // transform to its children, which are stored in absolute coordinates. So
    // the transform is applied only around the frame's own draws; children are
    // rendered untransformed.
    let matrix = element.centered_transform();

    canvas.save();

    // Frame drop shadow: background + subtree silhouette, below the clip layer
    // so it extends outside the frame bounds.
    render_container_drop_shadows(shared, canvas, element, tree, scale, true)?;

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

    // Clip to frame bounds in the frame's own space, then undo the transform so
    // children draw at their absolute coords while staying clipped (mirrors the
    // GPU clip). Outset ~0.5px like the GPU clip to avoid an AA seam.
    if element.clip_content {
        canvas.concat(&matrix);
        clip_to_frame_content(canvas, element, scale);
        if let Some(inverse) = matrix.invert() {
            canvas.concat(&inverse);
        }
    }

    // Frame's own fills (background) + inner shadows, in the frame's space.
    if !element.fills.is_empty() {
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale);
        renderer.draw_fills(element, &element.fills)?;
        renderer.draw_fill_inner_shadows(element)?;
        canvas.restore();
    }

    // Children (absolute coords, no frame transform).
    let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(shared, canvas, child_id, tree, scale)?;
    }

    // Strokes over children (clipped frames), in the frame's space.
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale);
        renderer.draw_strokes(element, &visible_strokes)?;
        canvas.restore();
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore();
    Ok(())
}

/// Drop shadows for a container: render the subtree into a drop-shadow filter
/// layer (its alpha becomes the shadow). `draw_fills` includes the frame
/// background in the silhouette.
fn render_container_drop_shadows(
    shared: &mut RenderResources,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
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
            let mut renderer = VectorRenderer::new(canvas, shared, scale);
            renderer.draw_fills(element, &element.fills)?;
        }

        let children: Vec<Uuid> = element.children_ids_iter_forward(false).copied().collect();
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale)?;
        }

        canvas.restore();
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Leaf shapes (Rect, Circle, Path, Bool, Text, SVGRaw)
// ---------------------------------------------------------------------------

fn render_leaf(
    shared: &mut RenderResources,
    canvas: &Canvas,
    element: &Shape,
    scale: f32,
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

    let mut renderer = VectorRenderer::new(canvas, shared, scale);

    // Layer blur (non-text shapes)
    let blur_layer = if !matches!(element.shape_type, Type::Text(_)) {
        renderer.apply_blur_layer(element)
    } else {
        false
    };

    renderer.draw_drop_shadows(element)?;
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

/// Single source of truth for leaf content draw order/gating (fills, inner
/// shadows, strokes), generic over [`ShapeRenderer`]. Drop shadows and layer
/// blur are excluded — they wrap the content and are sequenced per backend.
fn render_leaf_content<R: ShapeRenderer + ?Sized>(renderer: &mut R, shape: &Shape) -> Result<()> {
    match &shape.shape_type {
        Type::Text(_) => renderer.draw_text(shape)?,
        Type::SVGRaw(_) => renderer.draw_svg(shape)?,
        // Group/Frame never reach here; listed so a new Type must be handled.
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

                // Stroke inner shadows only when there are no fills (matches GPU).
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
// Private helpers (canvas-only)
// ---------------------------------------------------------------------------

fn draw_image_fill(
    shared: &mut RenderResources,
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
    shared: &mut RenderResources,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
) -> Result<()> {
    // Image-fill strokes: the stroke masks the visible area of the image.
    if let Fill::Image(image_fill) = &stroke.fill {
        return draw_image_stroke(canvas, shared, scale, shape, stroke, image_fill);
    }

    draw_stroke_geometry(canvas, scale, shape, stroke, false);
    Ok(())
}

/// Draws a stroke's geometry by shape type, kind and dash style. Rect/Circle
/// reuse the GPU stroke fns (dash/alignment parity); Path/Bool use double-width
/// + clip/clear + caps. `opaque` forces black for an image-stroke silhouette.
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
        // Text strokes go through draw_text; groups/svg never carry strokes.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {}
    }
}

/// Draws a stroked `paint` honoring the stroke kind (inner clip / outer
/// layer+clear / center).
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

/// Image-filled stroke: draw the stroke silhouette in a layer, then paint the
/// CPU image over it with `SrcIn` so only the stroke area shows the image.
fn draw_image_stroke(
    canvas: &Canvas,
    shared: &mut RenderResources,
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

    // Opaque stroke silhouette; the SrcIn image draw below fills it.
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
        // Not plain geometry (drawn via draw_text / draw_svg / traversal).
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
        // Fallback to the bounding rect.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
    }
}
