use crate::math::{Matrix, Point, Rect};

use crate::shapes::{
    merge_fills, Corners, Fill, ImageFill, Path, Shape, Stroke, StrokeCap, StrokeKind, Type,
};
use skia_safe::{self as skia, ImageFilter, RRect};

use super::{filters, RenderState, SurfaceId};
use crate::render::filters::compose_filters;
use crate::render::{get_dest_rect, get_source_rect};

#[allow(clippy::too_many_arguments)]
fn draw_stroke_on_rect(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    corners: &Option<Corners>,
    paint: &skia::Paint,
    scale: f32,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let stroke_rect = stroke.aligned_rect(rect, scale);
    let mut paint = paint.clone();

    // Apply both blur and shadow filters if present, composing them if necessary.
    let filter = compose_filters(blur, shadow);
    paint.set_image_filter(filter);

    // By default just draw the rect. Only dotted inner/outer strokes need
    // clipping to prevent the dotted pattern from appearing in wrong areas.
    let draw_stroke = || match corners {
        Some(radii) => {
            let radii = stroke.outer_corners(radii);
            let rrect = RRect::new_rect_radii(stroke_rect, &radii);
            canvas.draw_rrect(rrect, &paint);
        }
        None => {
            canvas.draw_rect(stroke_rect, &paint);
        }
    };

    // By default just draw the rect. Only dotted inner/outer strokes need
    // clipping to prevent the dotted pattern from appearing in wrong areas.
    if let Some(clip_op) = stroke.clip_op() {
        // Use a neutral layer (no extra paint) so opacity and filters
        // come solely from the stroke paint. This avoids applying
        // stroke alpha twice for dotted inner/outer strokes.
        let layer_rec = skia::canvas::SaveLayerRec::default();
        canvas.save_layer(&layer_rec);
        match corners {
            Some(radii) => {
                let rrect = RRect::new_rect_radii(*rect, radii);
                canvas.clip_rrect(rrect, clip_op, antialias);
            }
            None => {
                canvas.clip_rect(*rect, clip_op, antialias);
            }
        }
        draw_stroke();
        canvas.restore();
    } else {
        draw_stroke();
    }
}

#[allow(clippy::too_many_arguments)]
fn draw_stroke_on_circle(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    paint: &skia::Paint,
    scale: f32,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let stroke_rect = stroke.aligned_rect(rect, scale);
    let mut paint = paint.clone();

    // Apply both blur and shadow filters if present, composing them if necessary.
    let filter = compose_filters(blur, shadow);
    paint.set_image_filter(filter);

    // By default just draw the circle. Only dotted inner/outer strokes need
    // clipping to prevent the dotted pattern from appearing in wrong areas.
    if let Some(clip_op) = stroke.clip_op() {
        // Use a neutral layer (no extra paint) so opacity and filters
        // come solely from the stroke paint. This avoids applying
        // stroke alpha twice for dotted inner/outer strokes.
        let layer_rec = skia::canvas::SaveLayerRec::default();
        canvas.save_layer(&layer_rec);
        let clip_path = {
            let mut pb = skia::PathBuilder::new();
            pb.add_oval(rect, None, None);
            pb.detach()
        };
        canvas.clip_path(&clip_path, clip_op, antialias);
        canvas.draw_oval(stroke_rect, &paint);
        canvas.restore();
    } else {
        canvas.draw_oval(stroke_rect, &paint);
    }
}

fn draw_outer_stroke_path(
    canvas: &skia::Canvas,
    path: &skia::Path,
    paint: &skia::Paint,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let mut outer_paint = skia::Paint::default();
    outer_paint.set_blend_mode(skia::BlendMode::SrcOver);
    outer_paint.set_anti_alias(antialias);

    if let Some(filter) = blur {
        outer_paint.set_image_filter(filter.clone());
    }

    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&outer_paint);
    canvas.save_layer(&layer_rec);
    canvas.draw_path(path, paint);

    let mut clear_paint = skia::Paint::default();
    clear_paint.set_blend_mode(skia::BlendMode::Clear);
    clear_paint.set_anti_alias(antialias);
    canvas.draw_path(path, &clear_paint);

    canvas.restore();
}

// For inner stroke we draw a center stroke (with double width) and clip to the original path (that way the extra outer stroke is removed)
fn draw_inner_stroke_path(
    canvas: &skia::Canvas,
    path: &skia::Path,
    paint: &skia::Paint,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let mut inner_paint = skia::Paint::default();
    inner_paint.set_anti_alias(antialias);
    if let Some(filter) = blur {
        inner_paint.set_image_filter(filter.clone());
    }

    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&inner_paint);
    canvas.save_layer(&layer_rec);
    canvas.clip_path(path, skia::ClipOp::Intersect, antialias);
    canvas.draw_path(path, paint);
    canvas.restore();
}

// For outer stroke we draw a center stroke (with double width) and use another path with blend mode clear to remove the inner stroke added
#[allow(clippy::too_many_arguments)]
fn draw_stroke_on_path(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    path: &Path,
    paint: &skia::Paint,
    path_transform: Option<&Matrix>,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let is_open = path.is_open();

    let mut draw_paint = paint.clone();
    let filter = compose_filters(blur, shadow);
    draw_paint.set_image_filter(filter);

    // Move path_transform from the path geometry to the canvas so the
    // stroke width is not distorted by non-uniform shape scaling.
    // The path coordinates are already in world space, so we draw the
    // raw path on a canvas where the shape transform has been undone:
    //   canvas * path_transform = View × parents (no shape scale/rotation)
    // This matches the SVG renderer, which bakes the transform into path
    // coordinates and never sets a transform attribute on the element.
    let save_count = canvas.save();
    if let Some(pt) = path_transform {
        canvas.concat(pt);
    }
    let skia_path = path.to_skia_path();

    match stroke.render_kind(is_open) {
        StrokeKind::Inner => {
            draw_inner_stroke_path(canvas, &skia_path, &draw_paint, blur, antialias);
        }
        StrokeKind::Center => {
            canvas.draw_path(&skia_path, &draw_paint);
        }
        StrokeKind::Outer => {
            draw_outer_stroke_path(canvas, &skia_path, &draw_paint, blur, antialias);
        }
    }

    handle_stroke_caps(&skia_path, stroke, canvas, is_open, paint, blur, antialias);

    canvas.restore_to_count(save_count);
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
    match cap {
        StrokeCap::LineArrow => {
            // We also draw this square cap to fill the gap between the path and the arrow
            draw_square_cap(canvas, paint, p1, p2, width, 0.);
            paint.set_style(skia::PaintStyle::Stroke);
            draw_arrow_cap(canvas, paint, p1, p2, width * 4.);
        }
        StrokeCap::TriangleArrow => {
            draw_triangle_cap(canvas, paint, p1, p2, width * 4.);
        }
        StrokeCap::SquareMarker => {
            draw_square_cap(canvas, paint, p1, p2, width * 4., 0.);
        }
        StrokeCap::CircleMarker => {
            canvas.draw_circle((p1.x, p1.y), width * 2., paint);
        }
        StrokeCap::DiamondMarker => {
            draw_square_cap(canvas, paint, p1, p2, width * 4., 45.);
        }
        StrokeCap::Round => {
            canvas.draw_circle((p1.x, p1.y), width / 2.0, paint);
        }
        StrokeCap::Square => {
            draw_square_cap(canvas, paint, p1, p2, width, 0.);
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn handle_stroke_caps(
    path: &skia::Path,
    stroke: &Stroke,
    canvas: &skia::Canvas,
    is_open: bool,
    paint: &skia::Paint,
    blur: Option<&ImageFilter>,
    _antialias: bool,
) {
    let mut points = path.points().to_vec();
    // Curves can have duplicated points, so let's remove consecutive duplicated points
    points.dedup();
    let c_points = points.len();

    // Closed shapes don't have caps
    if c_points >= 2 && is_open {
        let first_point = points.first().unwrap();
        let last_point = points.last().unwrap();

        let mut paint_stroke = paint.clone();

        if let Some(filter) = blur {
            paint_stroke.set_image_filter(filter.clone());
        }

        if let Some(cap) = stroke.cap_start {
            handle_stroke_cap(
                canvas,
                cap,
                stroke.width,
                &mut paint_stroke,
                first_point,
                &points[1],
            );
        }

        if let Some(cap) = stroke.cap_end {
            handle_stroke_cap(
                canvas,
                cap,
                stroke.width,
                &mut paint_stroke,
                last_point,
                &points[c_points - 2],
            );
        }
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

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);

    let path = {
        let mut pb = skia::PathBuilder::new();
        pb.move_to(Point::new(center.x, center.y));
        pb.move_to(transformed_points[0]);
        pb.line_to(transformed_points[1]);
        pb.line_to(transformed_points[2]);
        pb.line_to(transformed_points[3]);
        pb.close();
        pb.detach()
    };
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

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);

    let path = {
        let mut pb = skia::PathBuilder::new();
        pb.move_to(transformed_points[1]);
        pb.line_to(transformed_points[0]);
        pb.line_to(transformed_points[2]);
        pb.move_to(Point::new(center.x, center.y));
        pb.line_to(transformed_points[0]);
        pb.detach()
    };
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

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);

    let path = {
        let mut pb = skia::PathBuilder::new();
        pb.move_to(transformed_points[0]);
        pb.line_to(transformed_points[1]);
        pb.line_to(transformed_points[2]);
        pb.close();
        pb.detach()
    };
    canvas.draw_path(&path, paint);
}

fn draw_image_stroke_in_container(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    image_fill: &ImageFill,
    antialias: bool,
    surface_id: SurfaceId,
) {
    let scale = render_state.get_scale();
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image.unwrap().dimensions();
    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();
    let svg_attrs = shape.svg_attrs.as_ref();

    // Save canvas and layer state
    let mut pb = skia::Paint::default();
    pb.set_blend_mode(skia::BlendMode::SrcOver);
    pb.set_anti_alias(antialias);
    if let Some(filter) = shape.image_filter(1.) {
        pb.set_image_filter(filter);
    }

    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&pb);
    canvas.save_layer(&layer_rec);

    // Draw the stroke based on the shape type, we are using this stroke as
    // a "selector" of the area of the image we want to show.
    let outer_rect = stroke.aligned_rect(container, scale);

    match &shape.shape_type {
        shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
            let paint = stroke.to_paint(&outer_rect, svg_attrs, antialias);
            draw_stroke_on_rect(
                canvas,
                stroke,
                container,
                &shape_type.corners(),
                &paint,
                scale,
                None,
                None,
                antialias,
            );
        }
        Type::Circle => {
            let paint = stroke.to_paint(&outer_rect, svg_attrs, antialias);
            draw_stroke_on_circle(
                canvas, stroke, container, &paint, scale, None, None, antialias,
            );
        }

        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(p) = shape_type.path() {
                canvas.save();
                let path = p.to_skia_path().make_transform(&path_transform.unwrap());
                let stroke_kind = stroke.render_kind(p.is_open());
                match stroke_kind {
                    StrokeKind::Inner => {
                        canvas.clip_path(&path, skia::ClipOp::Intersect, antialias);
                    }
                    StrokeKind::Center => {}
                    StrokeKind::Outer => {
                        canvas.clip_path(&path, skia::ClipOp::Difference, antialias);
                    }
                }
                let is_open = p.is_open();
                let paint = stroke.to_stroked_paint(is_open, &outer_rect, svg_attrs, antialias);
                canvas.draw_path(&path, &paint);
                if stroke.render_kind(is_open) == StrokeKind::Outer {
                    // Small extra inner stroke to overlap with the fill
                    // and avoid unnecesary artifacts.
                    let mut thin_paint = paint.clone();
                    thin_paint.set_stroke_width(1. / scale);
                    canvas.draw_path(&path, &thin_paint);
                }
                handle_stroke_caps(
                    &path,
                    stroke,
                    canvas,
                    is_open,
                    &paint,
                    shape.image_filter(1.).as_ref(),
                    antialias,
                );
                canvas.restore();
            }
        }

        _ => unreachable!("This shape should not have strokes"),
    }

    // Draw the image. We are using now the SrcIn blend mode,
    // so the rendered piece of image will the area of the
    // stroke over the image.
    let mut image_paint = skia::Paint::default();
    image_paint.set_blend_mode(skia::BlendMode::SrcIn);
    image_paint.set_anti_alias(antialias);
    if let Some(filter) = shape.image_filter(1.) {
        image_paint.set_image_filter(filter);
    }

    let src_rect = get_source_rect(size, container, image_fill);
    let dest_rect = get_dest_rect(container, stroke.delta());

    canvas.clip_rect(dest_rect, skia::ClipOp::Intersect, antialias);
    canvas.draw_image_rect_with_sampling_options(
        image.unwrap(),
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        render_state.sampling_options,
        &image_paint,
    );

    // Clear outer stroke for paths if necessary. When adding an outer stroke we need to empty the stroke added too in the inner area.
    if let Type::Path(p) = &shape.shape_type {
        if stroke.render_kind(p.is_open()) == StrokeKind::Outer {
            let path = p.to_skia_path().make_transform(&path_transform.unwrap());
            let mut clear_paint = skia::Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(antialias);
            canvas.draw_path(&path, &clear_paint);
        }
    }

    // Restore canvas state
    canvas.restore();
}

/// Renders all strokes for a shape. Merges strokes that share the same
/// geometry (kind, width, style, caps) into a single draw call to avoid
/// anti-aliasing edge bleed between them.
pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    strokes: &[&Stroke],
    surface_id: Option<SurfaceId>,
    antialias: bool,
    outset: Option<f32>,
) {
    if strokes.is_empty() {
        return;
    }

    let has_image_fills = strokes.iter().any(|s| matches!(s.fill, Fill::Image(_)));
    let can_merge = !has_image_fills && strokes.len() > 1 && strokes_share_geometry(strokes);

    if !can_merge {
        // When blur is active, render all strokes into a single offscreen surface
        // and apply blur once to the composite. This prevents blur from making
        // edges semi-transparent and revealing strokes underneath.
        if let Some(image_filter) = shape.image_filter(1.) {
            let mut content_bounds = shape.selrect;
            // Expand for outset if provided
            if let Some(s) = outset.filter(|&s| s > 0.0) {
                content_bounds.outset((s, s));
            }
            let max_margin = strokes
                .iter()
                .map(|s| s.bounds_width(shape.is_open()))
                .fold(0.0f32, f32::max);
            if max_margin > 0.0 {
                content_bounds.inset((-max_margin, -max_margin));
            }
            let max_cap = strokes
                .iter()
                .map(|s| s.cap_bounds_margin())
                .fold(0.0f32, f32::max);
            if max_cap > 0.0 {
                content_bounds.inset((-max_cap, -max_cap));
            }
            let bounds = image_filter.compute_fast_bounds(content_bounds);
            let target = surface_id.unwrap_or(SurfaceId::Strokes);
            if filters::render_with_filter_surface(
                render_state,
                bounds,
                target,
                |state, temp_surface| {
                    // Use save_layer with the blur filter so it applies once
                    // to the composite of all strokes, not per-stroke.
                    let canvas = state.surfaces.canvas(temp_surface);
                    let mut blur_paint = skia::Paint::default();
                    blur_paint.set_image_filter(image_filter.clone());
                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&blur_paint);
                    canvas.save_layer(&layer_rec);

                    for stroke in strokes.iter().rev() {
                        // bypass_filter=true prevents each stroke from creating
                        // its own filter surface. The blur on the paint inside
                        // draw functions is harmless — it composes with the
                        // layer's filter but the layer filter is the dominant one.
                        render_single_internal(
                            state,
                            shape,
                            stroke,
                            Some(temp_surface),
                            None,
                            antialias,
                            true,
                            true,
                            outset,
                        );
                    }

                    state.surfaces.canvas(temp_surface).restore();
                },
            ) {
                return;
            }
        }

        // No blur or filter surface unavailable — draw strokes individually.
        for stroke in strokes.iter().rev() {
            render_single(
                render_state,
                shape,
                stroke,
                surface_id,
                None,
                antialias,
                outset,
            );
        }
        return;
    }

    render_merged(
        render_state,
        shape,
        strokes,
        surface_id,
        antialias,
        false,
        outset,
    );
}

fn strokes_share_geometry(strokes: &[&Stroke]) -> bool {
    strokes.windows(2).all(|pair| {
        pair[0].kind == pair[1].kind
            && pair[0].width == pair[1].width
            && pair[0].style == pair[1].style
            && pair[0].cap_start == pair[1].cap_start
            && pair[0].cap_end == pair[1].cap_end
    })
}

fn render_merged(
    render_state: &mut RenderState,
    shape: &Shape,
    strokes: &[&Stroke],
    surface_id: Option<SurfaceId>,
    antialias: bool,
    bypass_filter: bool,
    outset: Option<f32>,
) {
    let representative = *strokes
        .last()
        .expect("render_merged expects at least one stroke");

    let blur_filter = if bypass_filter {
        None
    } else {
        shape.image_filter(1.)
    };

    // Handle blur filter
    if !bypass_filter {
        if let Some(image_filter) = blur_filter.clone() {
            let mut content_bounds = shape.selrect;
            // Expand for outset if provided
            if let Some(s) = outset.filter(|&s| s > 0.0) {
                content_bounds.outset((s, s));
            }
            let stroke_margin = representative.bounds_width(shape.is_open());
            if stroke_margin > 0.0 {
                content_bounds.inset((-stroke_margin, -stroke_margin));
            }
            let cap_margin = representative.cap_bounds_margin();
            if cap_margin > 0.0 {
                content_bounds.inset((-cap_margin, -cap_margin));
            }
            let bounds = image_filter.compute_fast_bounds(content_bounds);
            let target = surface_id.unwrap_or(SurfaceId::Strokes);
            if filters::render_with_filter_surface(
                render_state,
                bounds,
                target,
                |state, temp_surface| {
                    let blur_filter = image_filter.clone();

                    state.surfaces.apply_mut(temp_surface as u32, |surface| {
                        let canvas = surface.canvas();
                        let mut blur_paint = skia::Paint::default();
                        blur_paint.set_image_filter(blur_filter.clone());
                        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&blur_paint);
                        canvas.save_layer(&layer_rec);
                    });

                    render_merged(
                        state,
                        shape,
                        strokes,
                        Some(temp_surface),
                        antialias,
                        true,
                        outset,
                    );

                    state.surfaces.apply_mut(temp_surface as u32, |surface| {
                        surface.canvas().restore();
                    });
                },
            ) {
                return;
            }
        }
    }

    // `merge_fills` puts fills[0] on top (each new fill goes under the accumulated shader
    // via SrcOver), matching the non-merged path where strokes[0] is drawn last (on top).
    let fills: Vec<Fill> = strokes.iter().map(|s| s.fill.clone()).collect();

    // Expand selrect if outset is provided
    let selrect = if let Some(s) = outset.filter(|&s| s > 0.0) {
        let mut r = shape.selrect;
        r.outset((s, s));
        r
    } else {
        shape.selrect
    };

    let merged = merge_fills(&fills, selrect);
    let scale = render_state.get_scale();
    let target_surface = surface_id.unwrap_or(SurfaceId::Strokes);
    let canvas = render_state.surfaces.canvas_and_mark_dirty(target_surface);
    let svg_attrs = shape.svg_attrs.as_ref();
    let path_transform = shape.to_path_transform();

    match &shape.shape_type {
        shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
            let mut paint = representative.to_paint(&selrect, svg_attrs, antialias);
            paint.set_shader(merged.shader());
            draw_stroke_on_rect(
                canvas,
                representative,
                &selrect,
                &shape_type.corners(),
                &paint,
                scale,
                None,
                blur_filter.as_ref(),
                antialias,
            );
        }
        Type::Circle => {
            let mut paint = representative.to_paint(&selrect, svg_attrs, antialias);
            paint.set_shader(merged.shader());
            draw_stroke_on_circle(
                canvas,
                representative,
                &selrect,
                &paint,
                scale,
                None,
                blur_filter.as_ref(),
                antialias,
            );
        }
        Type::Text(_) => {}
        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(path) = shape_type.path() {
                let is_open = path.is_open();
                let mut paint =
                    representative.to_stroked_paint(is_open, &selrect, svg_attrs, antialias);
                paint.set_shader(merged.shader());
                draw_stroke_on_path(
                    canvas,
                    representative,
                    path,
                    &paint,
                    path_transform.as_ref(),
                    None,
                    blur_filter.as_ref(),
                    antialias,
                );
            }
        }
        _ => unreachable!("This shape should not have strokes"),
    }
}

/// Renders a single stroke. Used by the shadow module which needs per-stroke
/// shadow filters.
#[allow(clippy::too_many_arguments)]
pub fn render_single(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    surface_id: Option<SurfaceId>,
    shadow: Option<&ImageFilter>,
    antialias: bool,
    outset: Option<f32>,
) {
    render_single_internal(
        render_state,
        shape,
        stroke,
        surface_id,
        shadow,
        antialias,
        false,
        false,
        outset,
    );
}

#[allow(clippy::too_many_arguments)]
fn render_single_internal(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    surface_id: Option<SurfaceId>,
    shadow: Option<&ImageFilter>,
    antialias: bool,
    bypass_filter: bool,
    skip_blur: bool,
    outset: Option<f32>,
) {
    if !bypass_filter {
        if let Some(image_filter) = shape.image_filter(1.) {
            let mut content_bounds = shape.selrect;
            // Expand for outset if provided
            if let Some(s) = outset.filter(|&s| s > 0.0) {
                content_bounds.outset((s, s));
            }
            let stroke_margin = stroke.bounds_width(shape.is_open());
            if stroke_margin > 0.0 {
                content_bounds.inset((-stroke_margin, -stroke_margin));
            }
            let cap_margin = stroke.cap_bounds_margin();
            if cap_margin > 0.0 {
                content_bounds.inset((-cap_margin, -cap_margin));
            }
            let bounds = image_filter.compute_fast_bounds(content_bounds);

            let target = surface_id.unwrap_or(SurfaceId::Strokes);
            if filters::render_with_filter_surface(
                render_state,
                bounds,
                target,
                |state, temp_surface| {
                    render_single_internal(
                        state,
                        shape,
                        stroke,
                        Some(temp_surface),
                        shadow,
                        antialias,
                        true,
                        true,
                        outset,
                    );
                },
            ) {
                return;
            }
        }
    }

    let scale = render_state.get_scale();
    let target_surface = surface_id.unwrap_or(SurfaceId::Strokes);
    let canvas = render_state.surfaces.canvas_and_mark_dirty(target_surface);
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();
    let svg_attrs = shape.svg_attrs.as_ref();

    let blur = if skip_blur {
        None
    } else {
        shape.image_filter(1.)
    };

    if !matches!(shape.shape_type, Type::Text(_))
        && shadow.is_none()
        && matches!(stroke.fill, Fill::Image(_))
    {
        if let Fill::Image(image_fill) = &stroke.fill {
            draw_image_stroke_in_container(
                render_state,
                shape,
                stroke,
                image_fill,
                antialias,
                target_surface,
            );
        }
    } else {
        match &shape.shape_type {
            shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
                let paint = stroke.to_paint(&selrect, svg_attrs, antialias);
                draw_stroke_on_rect(
                    canvas,
                    stroke,
                    &selrect,
                    &shape_type.corners(),
                    &paint,
                    scale,
                    shadow,
                    blur.as_ref(),
                    antialias,
                );
            }
            Type::Circle => {
                let paint = stroke.to_paint(&selrect, svg_attrs, antialias);
                draw_stroke_on_circle(
                    canvas,
                    stroke,
                    &selrect,
                    &paint,
                    scale,
                    shadow,
                    blur.as_ref(),
                    antialias,
                );
            }
            Type::Text(_) => {}
            shape_type @ (Type::Path(_) | Type::Bool(_)) => {
                if let Some(path) = shape_type.path() {
                    let is_open = path.is_open();
                    let mut paint =
                        stroke.to_stroked_paint(is_open, &selrect, svg_attrs, antialias);
                    // Apply outset by increasing stroke width
                    if let Some(s) = outset.filter(|&s| s > 0.0) {
                        let current_width = paint.stroke_width();
                        // Path stroke kinds are built differently:
                        // - Center uses the stroke width directly.
                        // - Inner/Outer use a doubled width plus clipping/clearing logic.
                        // Compensate outset so visual growth is comparable across kinds.
                        let outset_growth = match stroke.render_kind(is_open) {
                            StrokeKind::Center => s * 2.0,
                            StrokeKind::Inner | StrokeKind::Outer => s * 4.0,
                        };
                        paint.set_stroke_width(current_width + outset_growth);
                    }
                    draw_stroke_on_path(
                        canvas,
                        stroke,
                        path,
                        &paint,
                        path_transform.as_ref(),
                        shadow,
                        blur.as_ref(),
                        antialias,
                    );
                }
            }
            _ => unreachable!("This shape should not have strokes"),
        }
    }
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_paths(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    paths: &Vec<(skia::Path, skia::Paint)>,
    surface_id: Option<SurfaceId>,
    shadow: Option<&ImageFilter>,
    antialias: bool,
) {
    let canvas = render_state
        .surfaces
        .canvas_and_mark_dirty(surface_id.unwrap_or(SurfaceId::Strokes));
    let selrect = &shape.selrect;
    let svg_attrs = shape.svg_attrs.as_ref();
    let mut paint: skia_safe::Handle<_> =
        stroke.to_text_stroked_paint(false, selrect, svg_attrs, antialias);

    if let Some(filter) = shadow {
        paint.set_image_filter(filter.clone());
    }

    match stroke.render_kind(false) {
        StrokeKind::Inner => {
            for (path, _) in paths {
                draw_inner_stroke_path(canvas, path, &paint, None, antialias);
            }
        }
        StrokeKind::Center => {
            for (path, _) in paths {
                canvas.draw_path(path, &paint);
            }
        }
        StrokeKind::Outer => {
            for (path, _) in paths {
                draw_outer_stroke_path(canvas, path, &paint, None, antialias);
            }
        }
    }
}
