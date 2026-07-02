use skia_safe::{self as skia};

use crate::error::Result;
use crate::shapes::{Fill, Shape, StrokeKind, Type};

use super::document::SvgLayerCanvas;
use super::masks::{push_dotted_ring_alpha_mask, push_inverse_shape_mask, push_leaf_clip_path};
use crate::render::vector::{draw_shape_geometry, ExportState, VectorRenderer, VectorTarget};

/// Alpha mask of a path/bool stroked at double width (no inner clear). Combined
/// with an exterior luminance mask this yields only the outer stroke band, the
/// same composition `render_path_outer_strokes` uses for solid strokes, without
/// relying on `stroke_to_path` boolean ops (which emit even-odd self-intersections
/// in SVG for rotated paths).
fn push_path_double_stroke_alpha_mask(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    stroke: &crate::shapes::Stroke,
) -> String {
    let svg_attrs = element.svg_attrs.as_ref();
    let canvas = builder.new_fragment();
    {
        let cv: &skia::Canvas = &*canvas;
        cv.save();
        cv.concat(&element.centered_transform());
        let mut paint = stroke.to_stroked_paint(false, &element.selrect, svg_attrs, true);
        paint.set_shader(None);
        paint.set_color(skia::Color::BLACK);
        draw_shape_geometry(cv, element, &paint);
        cv.restore();
    }

    let id = builder.unique("simask");
    builder.finish_alpha_mask_fragment(&id, canvas);
    id
}

/// Re-emits the *outer* solid/gradient strokes of a closed path/bool as a
/// nested `<g>` whose content (the shape stroked at double width) is clipped to
/// the shape's *exterior* via a luminance `<mask>` (white canvas minus the
/// shape silhouette). This mirrors the GPU/PDF `save_layer` + `Clear` (DstOut of
/// the shape), which `SkSVGDevice` drops, so the shared renderer skips these on
/// SVG and lets the compositor nest them here.
///
/// Image-fill outer strokes on path/bool are handled by `render_image_strokes`
/// (same exterior-mask trick; see there for why).
pub(super) fn render_path_outer_strokes(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    _scale: f32,
) -> Result<()> {
    if !matches!(element.shape_type, Type::Path(_) | Type::Bool(_)) || element.is_open() {
        return Ok(());
    }

    let matrix = element.centered_transform();
    for stroke in element.visible_strokes() {
        if stroke.render_kind(false) != StrokeKind::Outer {
            continue;
        }
        // Image-filled outer strokes are composed by `render_image_strokes` via
        // an alpha mask of the stroke region; don't re-emit them here as a
        // solid stroke (it would show up as a second outline).
        if matches!(stroke.fill, Fill::Image(_)) {
            continue;
        }

        let mask_id = push_inverse_shape_mask(builder, element, &matrix, "smask");
        builder.open_group(&format!("mask=\"url(#{mask_id})\""));
        {
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            let svg_attrs = element.svg_attrs.as_ref();
            let paint = stroke.to_stroked_paint(false, &element.selrect, svg_attrs, true);
            draw_shape_geometry(canvas, element, &paint);
            canvas.restore();
        }
        builder.close_group();
    }
    Ok(())
}

/// Re-emits *image-filled* strokes. On the GPU/PDF path the texture is confined
/// to the stroke with a `save_layer` + `SrcIn` over the stroke silhouette, which
/// `SkSVGDevice` drops, so the shared renderer skips them on SVG and the
/// compositor draws the image under a `<mask>` built from that silhouette.
///
/// - Solid/center strokes: the silhouette is the stroke geometry, used directly
///   as the mask.
/// - Dotted inner/outer strokes: the silhouette is the boundary-centered dot
///   ring; the image is drawn under that ring mask *and* inside a second group
///   that restricts it to the shape interior (inner) or exterior (outer), since
///   the ring straddles the boundary.
///
/// When no [`ImageProvider`] is available there is nothing to draw.
pub(super) fn render_image_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    if shared.images.is_none() {
        return Ok(());
    }

    let matrix = element.centered_transform();
    for stroke in element.visible_strokes() {
        if !matches!(stroke.fill, Fill::Image(_)) {
            continue;
        }

        match stroke.clip_op() {
            // Solid / dotted-center: mask straight to the stroke silhouette.
            None => {
                if stroke.render_kind(false) == StrokeKind::Outer
                    && matches!(element.shape_type, Type::Path(_) | Type::Bool(_))
                    && !element.is_open()
                {
                    // Exterior (hide shape interior) ∩ double-width stroke silhouette.
                    let ext_id = push_inverse_shape_mask(builder, element, &matrix, "smask");
                    builder.open_group(&format!("mask=\"url(#{ext_id})\""));
                    let ring_id = push_path_double_stroke_alpha_mask(builder, element, stroke);
                    builder.open_group(&format!("mask=\"url(#{ring_id})\""));
                    draw_stroke_image(builder, shared, element, stroke, scale, &matrix)?;
                    builder.close_group(); // ring
                    builder.close_group(); // exterior
                } else if stroke.render_kind(false) == StrokeKind::Inner
                    && matches!(element.shape_type, Type::Path(_) | Type::Bool(_))
                    && !element.is_open()
                {
                    // `clip_to_shape` inside a mask fragment serializes without the
                    // canvas CTM, so restrict to the rotated interior with a native
                    // `<clipPath>` and draw the double-width stroke unclipped.
                    let clip_id = push_leaf_clip_path(builder, element, &matrix);
                    builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
                    let mask_id =
                        builder.push_stroke_alpha_mask(shared, element, stroke, scale, false)?;
                    builder.open_group(&format!("mask=\"url(#{mask_id})\""));
                    draw_stroke_image(builder, shared, element, stroke, scale, &matrix)?;
                    builder.close_group(); // stroke
                    builder.close_group(); // interior
                } else {
                    let mask_id =
                        builder.push_stroke_alpha_mask(shared, element, stroke, scale, true)?;
                    builder.open_group(&format!("mask=\"url(#{mask_id})\""));
                    draw_stroke_image(builder, shared, element, stroke, scale, &matrix)?;
                    builder.close_group();
                }
            }
            // Dotted inner/outer: restrict to interior/exterior, then to the ring.
            Some(clip_op) => {
                if clip_op == skia::ClipOp::Intersect {
                    let clip_id = push_leaf_clip_path(builder, element, &matrix);
                    builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
                } else {
                    let mask_id = push_inverse_shape_mask(builder, element, &matrix, "dmask");
                    builder.open_group(&format!("mask=\"url(#{mask_id})\""));
                }
                let ring_id = push_dotted_ring_alpha_mask(builder, element, stroke, &matrix);
                builder.open_group(&format!("mask=\"url(#{ring_id})\""));
                draw_stroke_image(builder, shared, element, stroke, scale, &matrix)?;
                builder.close_group(); // ring
                builder.close_group(); // interior/exterior restriction
            }
        }
    }
    Ok(())
}

/// Draws an image-filled stroke's texture over its destination rect into the
/// current group (the caller supplies the mask/clip that confines it).
fn draw_stroke_image(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    stroke: &crate::shapes::Stroke,
    scale: f32,
    matrix: &skia::Matrix,
) -> Result<()> {
    let mut renderer = VectorRenderer::new_svg_layer(builder, shared, scale, VectorTarget::Svg);
    {
        let canvas = renderer.skia_canvas();
        canvas.save();
        canvas.concat(matrix);
    }
    renderer.draw_stroke_image(element, stroke)?;
    {
        let canvas = renderer.skia_canvas();
        canvas.restore();
    }
    Ok(())
}

/// Re-emits the *dotted inner/outer* strokes of a rect/circle. On the GPU/PDF
/// path these stamp a ring of dots centered on the shape boundary and clip it
/// to the shape interior (inner) or exterior (outer) inside a `save_layer` that
/// `SkSVGDevice` drops, so the shared renderer skips them on SVG and the
/// compositor re-emits the same ring of dots here, clipped natively: a
/// `<g clip-path>` (inner) or a `<g mask>` with the inverse-of-shape luminance
/// mask (outer, since SVG `<clipPath>` cannot subtract).
pub(super) fn render_rect_circle_dotted_strokes(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
) -> Result<()> {
    if !matches!(element.shape_type, Type::Rect(_) | Type::Circle) {
        return Ok(());
    }

    let matrix = element.centered_transform();
    let svg_attrs = element.svg_attrs.as_ref();

    for stroke in element.visible_strokes() {
        let Some(clip_op) = stroke.clip_op() else {
            continue;
        };
        // Image-filled dotted strokes are re-emitted by `render_image_strokes`
        // (the dots must be filled with the texture, not a solid paint).
        if matches!(stroke.fill, Fill::Image(_)) {
            continue;
        }

        let paint = stroke.to_paint(&element.selrect, svg_attrs, true);

        if clip_op == skia::ClipOp::Intersect {
            let clip_id = push_leaf_clip_path(builder, element, &matrix);
            builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
        } else {
            let mask_id = push_inverse_shape_mask(builder, element, &matrix, "dmask");
            builder.open_group(&format!("mask=\"url(#{mask_id})\""));
        }

        {
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            draw_shape_geometry(canvas, element, &paint);
            canvas.restore();
        }
        builder.close_group();
    }
    Ok(())
}
