use crate::math::{Matrix, Point, Rect};

use crate::shapes::{
    Corners, Fill, ImageFill, Path, Shape, Stroke, StrokeCap, StrokeKind, SvgAttrs, Type,
};
use skia_safe::{self as skia, ImageFilter, RRect};

use super::{filters, RenderState, SurfaceId};
use crate::render::filters::compose_filters;
use crate::render::{get_dest_rect, get_source_rect};

// FIXME: See if we can simplify these arguments
#[allow(clippy::too_many_arguments)]
fn draw_stroke_on_rect(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    selrect: &Rect,
    corners: &Option<Corners>,
    svg_attrs: Option<&SvgAttrs>,
    scale: f32,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    // Draw the different kind of strokes for a rect is straightforward, we just need apply a stroke to:
    // - The same rect if it's a center stroke
    // - A bigger rect if it's an outer stroke
    // - A smaller rect if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    let mut paint = stroke.to_paint(selrect, svg_attrs, scale, antialias);

    // Apply both blur and shadow filters if present, composing them if necessary.
    let filter = compose_filters(blur, shadow);
    paint.set_image_filter(filter);

    match corners {
        Some(radii) => {
            let radii = stroke.outer_corners(radii);
            let rrect = RRect::new_rect_radii(stroke_rect, &radii);
            canvas.draw_rrect(rrect, &paint);
        }
        None => {
            canvas.draw_rect(stroke_rect, &paint);
        }
    }
}

// FIXME: See if we can simplify these arguments
#[allow(clippy::too_many_arguments)]
fn draw_stroke_on_circle(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    rect: &Rect,
    selrect: &Rect,
    svg_attrs: Option<&SvgAttrs>,
    scale: f32,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    // Draw the different kind of strokes for an oval is straightforward, we just need apply a stroke to:
    // - The same oval if it's a center stroke
    // - A bigger oval if it's an outer stroke
    // - A smaller oval if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    let mut paint = stroke.to_paint(selrect, svg_attrs, scale, antialias);

    // Apply both blur and shadow filters if present, composing them if necessary.
    let filter = compose_filters(blur, shadow);
    paint.set_image_filter(filter);

    canvas.draw_oval(stroke_rect, &paint);
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
// FIXME: See if we can simplify these arguments
#[allow(clippy::too_many_arguments)]
pub fn draw_stroke_on_path(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    path: &Path,
    selrect: &Rect,
    path_transform: Option<&Matrix>,
    svg_attrs: Option<&SvgAttrs>,
    scale: f32,
    shadow: Option<&ImageFilter>,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let mut skia_path = path.to_skia_path();
    skia_path.transform(path_transform.unwrap_or(&Matrix::default()));

    let is_open = path.is_open();

    let mut paint: skia_safe::Handle<_> =
        stroke.to_stroked_paint(is_open, selrect, svg_attrs, scale, antialias);

    let filter = compose_filters(blur, shadow);
    paint.set_image_filter(filter);

    match stroke.render_kind(is_open) {
        StrokeKind::Inner => {
            draw_inner_stroke_path(canvas, &skia_path, &paint, blur, antialias);
        }
        StrokeKind::Center => {
            canvas.draw_path(&skia_path, &paint);
        }
        StrokeKind::Outer => {
            draw_outer_stroke_path(canvas, &skia_path, &paint, blur, antialias);
        }
    }

    handle_stroke_caps(
        &mut skia_path,
        stroke,
        selrect,
        canvas,
        is_open,
        svg_attrs,
        scale,
        blur,
        antialias,
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

// FIXME: See if we can simplify these arguments
#[allow(clippy::too_many_arguments)]
fn handle_stroke_caps(
    path: &mut skia::Path,
    stroke: &Stroke,
    selrect: &Rect,
    canvas: &skia::Canvas,
    is_open: bool,
    svg_attrs: Option<&SvgAttrs>,
    scale: f32,
    blur: Option<&ImageFilter>,
    antialias: bool,
) {
    let mut points = vec![Point::default(); path.count_points()];
    path.get_points(&mut points);
    // Curves can have duplicated points, so let's remove consecutive duplicated points
    points.dedup();
    let c_points = points.len();

    // Closed shapes don't have caps
    if c_points >= 2 && is_open {
        let first_point = points.first().unwrap();
        let last_point = points.last().unwrap();

        let mut paint_stroke =
            stroke.to_stroked_paint(is_open, selrect, svg_attrs, scale, antialias);

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

    let mut transformed_points = points;
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

    let mut transformed_points = points;
    matrix.map_points(&mut transformed_points, &points);

    let mut path = skia::Path::new();
    path.move_to(transformed_points[0]);
    path.line_to(transformed_points[1]);
    path.line_to(transformed_points[2]);
    path.close();

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
    let canvas = render_state.surfaces.canvas(surface_id);
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
                scale,
                None,
                None,
                antialias,
            );
        }
        Type::Circle => draw_stroke_on_circle(
            canvas,
            stroke,
            container,
            &outer_rect,
            svg_attrs,
            scale,
            None,
            None,
            antialias,
        ),

        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(p) = shape_type.path() {
                canvas.save();
                let mut path = p.to_skia_path();
                path.transform(&path_transform.unwrap());
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
                let mut paint =
                    stroke.to_stroked_paint(is_open, &outer_rect, svg_attrs, scale, antialias);
                canvas.draw_path(&path, &paint);
                if stroke.render_kind(is_open) == StrokeKind::Outer {
                    // Small extra inner stroke to overlap with the fill
                    // and avoid unnecesary artifacts.
                    paint.set_stroke_width(1. / scale);
                    canvas.draw_path(&path, &paint);
                }
                handle_stroke_caps(
                    &mut path,
                    stroke,
                    &outer_rect,
                    canvas,
                    is_open,
                    svg_attrs,
                    scale,
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
            let mut path = p.to_skia_path();
            path.transform(&path_transform.unwrap());
            let mut clear_paint = skia::Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(antialias);
            canvas.draw_path(&path, &clear_paint);
        }
    }

    // Restore canvas state
    canvas.restore();
}

#[allow(clippy::too_many_arguments)]
pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    surface_id: Option<SurfaceId>,
    shadow: Option<&ImageFilter>,
    antialias: bool,
) {
    render_internal(
        render_state,
        shape,
        stroke,
        surface_id,
        shadow,
        antialias,
        false,
    );
}

/// Internal function to render a stroke with support for offscreen blur rendering.
///
/// # Parameters
/// - `render_state`: The rendering state containing surfaces and context.
/// - `shape`: The shape to render the stroke for.
/// - `stroke`: The stroke configuration (width, fill, style, etc.).
/// - `surface_id`: Optional target surface ID. Defaults to `SurfaceId::Strokes` if `None`.
/// - `shadow`: Optional shadow filter to apply to the stroke.
/// - `antialias`: Whether to use antialiasing for rendering.
/// - `bypass_filter`:
///   - If `false`, attempts to use offscreen filter surface for blur effects.
///   - If `true`, renders directly to the target surface (used for recursive calls to avoid infinite loops when rendering into the filter surface).
///
/// # Behavior
/// When `bypass_filter` is `false` and the shape has a blur filter:
/// 1. Calculates bounds including stroke width and cap margins.
/// 2. Attempts to render into an offscreen filter surface at unscaled coordinates.
/// 3. If successful, composites the result back to the target surface and returns early.
/// 4. If the offscreen render fails or `bypass_filter` is `true`, renders directly to the target
///    surface using the appropriate drawing function for the shape type.
///
/// The recursive call with `bypass_filter=true` ensures that when rendering into the filter
/// surface, we don't attempt to create another filter surface, avoiding infinite recursion.
#[allow(clippy::too_many_arguments)]
fn render_internal(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    surface_id: Option<SurfaceId>,
    shadow: Option<&ImageFilter>,
    antialias: bool,
    bypass_filter: bool,
) {
    if !bypass_filter {
        if let Some(image_filter) = shape.image_filter(1.) {
            // We have to calculate the bounds considering the stroke and the cap margins.
            let mut content_bounds = shape.selrect;
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
                    render_internal(
                        state,
                        shape,
                        stroke,
                        Some(temp_surface),
                        shadow,
                        antialias,
                        true,
                    );
                },
            ) {
                return;
            }
        }
    }

    let scale = render_state.get_scale();
    let target_surface = surface_id.unwrap_or(SurfaceId::Strokes);
    let canvas = render_state.surfaces.canvas(target_surface);
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();
    let svg_attrs = shape.svg_attrs.as_ref();

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
                draw_stroke_on_rect(
                    canvas,
                    stroke,
                    &selrect,
                    &selrect,
                    &shape_type.corners(),
                    svg_attrs,
                    scale,
                    shadow,
                    shape.image_filter(1.).as_ref(),
                    antialias,
                );
            }
            Type::Circle => draw_stroke_on_circle(
                canvas,
                stroke,
                &selrect,
                &selrect,
                svg_attrs,
                scale,
                shadow,
                shape.image_filter(1.).as_ref(),
                antialias,
            ),
            Type::Text(_) => {}
            shape_type @ (Type::Path(_) | Type::Bool(_)) => {
                if let Some(path) = shape_type.path() {
                    draw_stroke_on_path(
                        canvas,
                        stroke,
                        path,
                        &selrect,
                        path_transform.as_ref(),
                        svg_attrs,
                        scale,
                        shadow,
                        shape.image_filter(1.).as_ref(),
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
    let scale = render_state.get_scale();
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Strokes));
    let selrect = &shape.selrect;
    let svg_attrs = shape.svg_attrs.as_ref();
    let mut paint: skia_safe::Handle<_> =
        stroke.to_text_stroked_paint(false, selrect, svg_attrs, scale, antialias);

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
