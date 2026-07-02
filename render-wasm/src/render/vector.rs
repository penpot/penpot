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

/// Vector export backend selector.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum VectorTarget {
    Pdf,
    Svg,
}

// ---------------------------------------------------------------------------
// VectorRenderer — implements ShapeRenderer for canvas-based vector export
// ---------------------------------------------------------------------------

/// Canvas-based vector render backend (CPU Skia canvas, no GPU surfaces).
pub(super) struct VectorRenderer<'a> {
    canvas: &'a Canvas,
    shared: &'a mut RenderState,
    scale: f32,
    target: VectorTarget,
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
            target,
        }
    }

    fn is_svg(&self) -> bool {
        matches!(self.target, VectorTarget::Svg)
    }

    /// Whether a blur `image_filter` may be attached to a paint. On the SVG
    /// compositor, layer blur is emitted as a `<g filter="feGaussianBlur">`
    /// wrapping the shape, and `SkSVGDevice` both ignores paint image filters
    /// and drops the implicit layer they trigger (the shape would vanish), so we
    /// must not set one there.
    fn paint_image_filter(&self, shape: &Shape) -> Option<skia::ImageFilter> {
        if self.is_svg() {
            None
        } else {
            shape.image_filter(1.)
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
            let is_svg = self.is_svg();
            for fill in fills.iter().rev() {
                match fill {
                    Fill::Image(image_fill) => {
                        draw_image_fill(self.shared, self.canvas, shape, image_fill, is_svg)?;
                    }
                    _ => {
                        let mut paint = fill.to_paint(&shape.selrect, true);
                        if let Some(filter) = self.paint_image_filter(shape) {
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

        if let Some(filter) = self.paint_image_filter(shape) {
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
        // On the SVG compositor, layer blur is emitted as a `<g filter>` wrapping
        // the whole leaf, so text must not also carry its own blur (which would
        // double-blur and, worse, be dropped by `SkSVGDevice`).
        let blur_filter = self.paint_image_filter(shape);

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
// Child paint order
// ---------------------------------------------------------------------------

/// Children of `element` in the exact paint order (bottom-to-top) the GPU
/// renderer uses, so the vector export matches on-canvas z-order.
///
/// The GPU pushes `sort_z_index(children_ids_iter(false))` onto a LIFO stack and
/// therefore paints nodes in the reverse of that list. Crucially, `sort_z_index`
/// reorders children by their layout `z-index` for layout containers (flex/grid),
/// which is what places absolutely-positioned items (e.g. a background gradient
/// with a lower z-index) behind the flow content. Iterating children in plain
/// stored order ignored this and drew such items on top. We replicate the GPU
/// ordering here.
pub(super) fn children_paint_order(tree: ShapesPoolRef, element: &Shape) -> Vec<Uuid> {
    let ids: Vec<Uuid> = element.children_ids_iter(false).copied().collect();
    let mut ids = super::sort_z_index(tree, element, ids);
    ids.reverse();
    ids
}

/// Single source of truth for leaf content draw order/gating (fills, inner
/// shadows, strokes), generic over [`ShapeRenderer`]. Drop shadows and layer
/// blur are excluded — they wrap the content and are sequenced per backend.
pub(super) fn render_leaf_content<R: ShapeRenderer + ?Sized>(
    renderer: &mut R,
    shape: &Shape,
) -> Result<()> {
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
    shared: &mut RenderState,
    canvas: &Canvas,
    shape: &Shape,
    image_fill: &crate::shapes::ImageFill,
    is_svg: bool,
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
    if !is_svg {
        if let Some(filter) = shape.image_filter(1.) {
            paint.set_image_filter(filter);
        }
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
pub(super) fn draw_shape_geometry(canvas: &Canvas, shape: &Shape, paint: &Paint) {
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
pub(super) fn clip_to_frame_content(canvas: &Canvas, shape: &Shape, scale: f32) {
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

