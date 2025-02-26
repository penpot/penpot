use std::collections::HashMap;

use crate::math::{Matrix, Point, Rect};

use crate::shapes::{Corners, Fill, ImageFill, Path, Shape, Stroke, StrokeCap, StrokeKind, Type};
use skia_safe::{self as skia, RRect};

use super::{RenderState, SurfaceId};

fn draw_stroke_on_rect(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    selrect: &Rect,
    corners: &Option<Corners>,
    svg_attrs: &HashMap<String, String>,
    scale: f32,
) {
    // Draw the different kind of strokes for a rect is straightforward, we just need apply a stroke to:
    // - The same rect if it's a center stroke
    // - A bigger rect if it's an outer stroke
    // - A smaller rect if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    let paint = stroke.to_paint(selrect, svg_attrs, scale);

    match corners {
        Some(radii) => {
            let radii = stroke.outer_corners(radii);
            let rrect = RRect::new_rect_radii(stroke_rect, &radii);
            canvas.draw_rrect(rrect, &paint);
        }
        None => {
            canvas.draw_rect(&stroke_rect, &paint);
        }
    }
}

fn draw_stroke_on_circle(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    selrect: &Rect,
    svg_attrs: &HashMap<String, String>,
    scale: f32,
) {
    // Draw the different kind of strokes for an oval is straightforward, we just need apply a stroke to:
    // - The same oval if it's a center stroke
    // - A bigger oval if it's an outer stroke
    // - A smaller oval if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    canvas.draw_oval(&stroke_rect, &stroke.to_paint(selrect, svg_attrs, scale));
}

fn draw_stroke_on_path(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    path: &Path,
    selrect: &Rect,
    path_transform: Option<&Matrix>,
    svg_attrs: &HashMap<String, String>,
    scale: f32,
) {
    let mut skia_path = path.to_skia_path();
    skia_path.transform(path_transform.unwrap());

    let is_open = path.is_open();
    let paint_stroke = stroke.to_stroked_paint(is_open, selrect, svg_attrs, scale);
    // Draw the different kind of strokes for a path requires different strategies:
    match stroke.render_kind(is_open) {
        // For inner stroke we draw a center stroke (with double width) and clip to the original path (that way the extra outer stroke is removed)
        StrokeKind::InnerStroke => {
            canvas.clip_path(&skia_path, skia::ClipOp::Intersect, true);
            canvas.draw_path(&skia_path, &paint_stroke);
        }
        // For center stroke we don't need to do anything extra
        StrokeKind::CenterStroke => {
            canvas.draw_path(&skia_path, &paint_stroke);
        }
        // For outer stroke we draw a center stroke (with double width) and use another path with blend mode clear to remove the inner stroke added
        StrokeKind::OuterStroke => {
            let mut paint = skia::Paint::default();
            paint.set_blend_mode(skia::BlendMode::SrcOver);
            paint.set_anti_alias(true);
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            canvas.save_layer(&layer_rec);

            canvas.draw_path(&skia_path, &paint_stroke);

            let mut clear_paint = skia::Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            canvas.draw_path(&skia_path, &clear_paint);

            canvas.restore();
        }
    }

    handle_stroke_caps(
        &mut skia_path,
        stroke,
        &selrect,
        canvas,
        is_open,
        svg_attrs,
        scale,
    );
}

fn handle_stroke_cap(
    canvas: &skia::Canvas,
    cap: StrokeCap,
    width: f32,
    paint: &mut skia::Paint,
    p1: &Point,
    p2: &Point,
) {
    paint.set_style(skia::PaintStyle::Fill);
    paint.set_blend_mode(skia::BlendMode::Src);
    match cap {
        StrokeCap::None => {}
        StrokeCap::Line => {
            // We also draw this square cap to fill the gap between the path and the arrow
            draw_square_cap(canvas, &paint, p1, p2, width, 0.);
            paint.set_style(skia::PaintStyle::Stroke);
            draw_arrow_cap(canvas, &paint, p1, p2, width * 4.);
        }
        StrokeCap::Triangle => {
            draw_triangle_cap(canvas, &paint, p1, p2, width * 4.);
        }
        StrokeCap::Rectangle => {
            draw_square_cap(canvas, &paint, p1, p2, width * 4., 0.);
        }
        StrokeCap::Circle => {
            canvas.draw_circle((p1.x, p1.y), width * 2., &paint);
        }
        StrokeCap::Diamond => {
            draw_square_cap(canvas, &paint, p1, p2, width * 4., 45.);
        }
        StrokeCap::Round => {
            canvas.draw_circle((p1.x, p1.y), width / 2.0, &paint);
        }
        StrokeCap::Square => {
            draw_square_cap(canvas, &paint, p1, p2, width, 0.);
        }
    }
}

fn handle_stroke_caps(
    path: &mut skia::Path,
    stroke: &Stroke,
    selrect: &Rect,
    canvas: &skia::Canvas,
    is_open: bool,
    svg_attrs: &HashMap<String, String>,
    dpr_scale: f32,
) {
    let points_count = path.count_points();
    let mut points = vec![Point::default(); points_count];
    let c_points = path.get_points(&mut points);

    // Closed shapes don't have caps
    if c_points >= 2 && is_open {
        let first_point = points.first().unwrap();
        let last_point = points.last().unwrap();

        let mut paint_stroke = stroke.to_stroked_paint(is_open, selrect, svg_attrs, dpr_scale);

        handle_stroke_cap(
            canvas,
            stroke.cap_start,
            stroke.width,
            &mut paint_stroke,
            first_point,
            &points[1],
        );
        handle_stroke_cap(
            canvas,
            stroke.cap_end,
            stroke.width,
            &mut paint_stroke,
            last_point,
            &points[points_count - 2],
        );
    }
}

fn draw_square_cap(
    canvas: &skia::Canvas,
    paint: &skia::Paint,
    center: &Point,
    direction: &Point,
    size: f32,
    extra_rotation: f32,
) {
    let dx = direction.x - center.x;
    let dy = direction.y - center.y;
    let angle = dy.atan2(dx);

    let mut matrix = Matrix::new_identity();
    matrix.pre_rotate(
        angle.to_degrees() + extra_rotation,
        Point::new(center.x, center.y),
    );

    let half_size = size / 2.0;
    let rect = Rect::from_xywh(center.x - half_size, center.y - half_size, size, size);

    let points = [
        Point::new(rect.left(), rect.top()),
        Point::new(rect.right(), rect.top()),
        Point::new(rect.right(), rect.bottom()),
        Point::new(rect.left(), rect.bottom()),
    ];

    let mut transformed_points = points.clone();
    matrix.map_points(&mut transformed_points, &points);

    let mut path = skia::Path::new();
    path.move_to(Point::new(center.x, center.y));
    path.move_to(transformed_points[0]);
    path.line_to(transformed_points[1]);
    path.line_to(transformed_points[2]);
    path.line_to(transformed_points[3]);
    path.close();
    canvas.draw_path(&path, paint);
}

fn draw_arrow_cap(
    canvas: &skia::Canvas,
    paint: &skia::Paint,
    center: &Point,
    direction: &Point,
    size: f32,
) {
    let dx = direction.x - center.x;
    let dy = direction.y - center.y;
    let angle = dy.atan2(dx);

    let mut matrix = Matrix::new_identity();
    matrix.pre_rotate(angle.to_degrees() - 90., Point::new(center.x, center.y));

    let half_height = size / 2.;
    let points = [
        Point::new(center.x, center.y - half_height),
        Point::new(center.x - size, center.y + half_height),
        Point::new(center.x + size, center.y + half_height),
    ];

    let mut transformed_points = points.clone();
    matrix.map_points(&mut transformed_points, &points);

    let mut path = skia::Path::new();
    path.move_to(transformed_points[1]);
    path.line_to(transformed_points[0]);
    path.line_to(transformed_points[2]);
    path.move_to(Point::new(center.x, center.y));
    path.line_to(transformed_points[0]);

    canvas.draw_path(&path, paint);
}

fn draw_triangle_cap(
    canvas: &skia::Canvas,
    paint: &skia::Paint,
    center: &Point,
    direction: &Point,
    size: f32,
) {
    let dx = direction.x - center.x;
    let dy = direction.y - center.y;
    let angle = dy.atan2(dx);

    let mut matrix = Matrix::new_identity();
    matrix.pre_rotate(angle.to_degrees() - 90., Point::new(center.x, center.y));

    let half_height = size / 2.;
    let points = [
        Point::new(center.x, center.y - half_height),
        Point::new(center.x - size, center.y + half_height),
        Point::new(center.x + size, center.y + half_height),
    ];

    let mut transformed_points = points.clone();
    matrix.map_points(&mut transformed_points, &points);

    let mut path = skia::Path::new();
    path.move_to(transformed_points[0]);
    path.line_to(transformed_points[1]);
    path.line_to(transformed_points[2]);
    path.close();

    canvas.draw_path(&path, paint);
}

fn calculate_scaled_rect(size: (i32, i32), container: &Rect, delta: f32) -> Rect {
    let (width, height) = (size.0 as f32, size.1 as f32);
    let image_aspect_ratio = width / height;

    // Container size
    let container_width = container.width();
    let container_height = container.height();
    let container_aspect_ratio = container_width / container_height;

    let scale = if image_aspect_ratio > container_aspect_ratio {
        container_height / height
    } else {
        container_width / width
    };

    let scaled_width = width * scale;
    let scaled_height = height * scale;

    Rect::from_xywh(
        container.left - delta - (scaled_width - container_width) / 2.0,
        container.top - delta - (scaled_height - container_height) / 2.0,
        scaled_width + (2. * delta) + (scaled_width - container_width),
        scaled_height + (2. * delta) + (scaled_width - container_width),
    )
}

fn draw_image_stroke_in_container(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    image_fill: &ImageFill,
) {
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image_fill.size();
    let canvas = render_state.surfaces.canvas(SurfaceId::Fills);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();
    let svg_attrs = &shape.svg_attrs;
    let dpr_scale = render_state.viewbox.zoom * render_state.options.dpr();

    // Save canvas and layer state
    let mut pb = skia::Paint::default();
    pb.set_blend_mode(skia::BlendMode::SrcOver);
    pb.set_anti_alias(true);
    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&pb);
    canvas.save_layer(&layer_rec);

    // Draw the stroke based on the shape type, we are using this stroke as
    // a "selector" of the area of the image we want to show.
    let outer_rect = stroke.outer_rect(container);

    match &shape.shape_type {
        shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
            draw_stroke_on_rect(
                canvas,
                stroke,
                container,
                &outer_rect,
                &shape_type.corners(),
                svg_attrs,
                dpr_scale,
            );
        }
        Type::Circle => {
            draw_stroke_on_circle(canvas, stroke, container, &outer_rect, svg_attrs, dpr_scale)
        }

        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(p) = shape_type.path() {
                canvas.save();
                let mut path = p.to_skia_path();
                path.transform(&path_transform.unwrap());
                let stroke_kind = stroke.render_kind(p.is_open());
                match stroke_kind {
                    StrokeKind::InnerStroke => {
                        canvas.clip_path(&path, skia::ClipOp::Intersect, true);
                    }
                    StrokeKind::CenterStroke => {}
                    StrokeKind::OuterStroke => {
                        canvas.clip_path(&path, skia::ClipOp::Difference, true);
                    }
                }
                let is_open = p.is_open();
                let mut paint = stroke.to_stroked_paint(is_open, &outer_rect, svg_attrs, dpr_scale);
                canvas.draw_path(&path, &paint);
                canvas.restore();
                if stroke.render_kind(is_open) == StrokeKind::OuterStroke {
                    // Small extra inner stroke to overlap with the fill and avoid unnecesary artifacts
                    paint.set_stroke_width(1. / dpr_scale);
                    canvas.draw_path(&path, &paint);
                }
                handle_stroke_caps(
                    &mut path,
                    stroke,
                    &outer_rect,
                    canvas,
                    is_open,
                    svg_attrs,
                    dpr_scale,
                );
            }
        }

        _ => unreachable!("This shape should not have strokes"),
    }

    // Draw the image. We are using now the SrcIn blend mode, so the rendered piece of image will the area of the stroke over the image.
    let mut image_paint = skia::Paint::default();
    image_paint.set_blend_mode(skia::BlendMode::SrcIn);
    image_paint.set_anti_alias(true);

    // Compute scaled rect and clip to it
    let dest_rect = calculate_scaled_rect(size, container, stroke.delta());
    canvas.clip_rect(dest_rect, skia::ClipOp::Intersect, true);
    canvas.draw_image_rect(image.unwrap(), None, dest_rect, &image_paint);

    // Clear outer stroke for paths if necessary. When adding an outer stroke we need to empty the stroke added too in the inner area.
    if let Type::Path(p) = &shape.shape_type {
        if stroke.render_kind(p.is_open()) == StrokeKind::OuterStroke {
            let mut path = p.to_skia_path();
            path.transform(&path_transform.unwrap());
            let mut clear_paint = skia::Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            canvas.draw_path(&path, &clear_paint);
        }
    }

    // Restore canvas state
    canvas.restore();
}

/**
 * This SHOULD be the only public function in this module.
 */
pub fn render(render_state: &mut RenderState, shape: &Shape, stroke: &Stroke) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Strokes);
    let dpr_scale = render_state.viewbox.zoom * render_state.options.dpr();
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();
    let svg_attrs = &shape.svg_attrs;

    if let Fill::Image(image_fill) = &stroke.fill {
        draw_image_stroke_in_container(render_state, shape, stroke, image_fill);
    } else {
        match &shape.shape_type {
            shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
                draw_stroke_on_rect(
                    canvas,
                    stroke,
                    &selrect,
                    &selrect,
                    &shape_type.corners(),
                    svg_attrs,
                    dpr_scale,
                );
            }
            Type::Circle => {
                draw_stroke_on_circle(canvas, stroke, &selrect, &selrect, &svg_attrs, dpr_scale)
            }
            shape_type @ (Type::Path(_) | Type::Bool(_)) => {
                if let Some(path) = shape_type.path() {
                    let svg_attrs = &shape.svg_attrs;
                    draw_stroke_on_path(
                        canvas,
                        stroke,
                        path,
                        &selrect,
                        path_transform.as_ref(),
                        svg_attrs,
                        dpr_scale,
                    );
                }
            }
            _ => unreachable!("This shape should not have strokes"),
        }
    }
}
