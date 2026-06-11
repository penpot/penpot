use skia_safe::{self as skia, RRect};
use super::{RenderState, SurfaceId};
use crate::shapes::{radius_to_sigma, Shape, Type};

pub fn render_background_blur(
    render_state: &mut RenderState,
    shape: &Shape,
    target_surface: SurfaceId,
) {
    if render_state.options.is_fast_mode() {
        return;
    }
    if matches!(shape.shape_type, Type::Text(_)) || matches!(shape.shape_type, Type::SVGRaw(_)) {
        return;
    }
    let blur = match shape
        .blur
        .filter(|b| !b.hidden && b.blur_type == crate::shapes::BlurType::BackgroundBlur)
    {
        Some(blur) => blur,
        None => return,
    };

    let scale = render_state.get_scale();
    let scaled_sigma = radius_to_sigma(blur.value * scale);
    let sigma = if render_state.export_context.is_some() {
        scaled_sigma
    } else {
        let margin = render_state.surfaces.margins().width as f32;
        let max_sigma = margin / 3.0;
        scaled_sigma.min(max_sigma)
    };

    let blur_filter =
        match skia::image_filters::blur((sigma, sigma), skia::TileMode::Clamp, None, None) {
            Some(filter) => filter,
            None => return,
        };

    let target_surface_snapshot = render_state.surfaces.snapshot(target_surface);
    let translation = render_state
        .surfaces
        .get_render_context_translation(render_state.render_area, scale);

    let center = shape.center();
    let mut matrix = shape.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);

    let canvas = render_state.surfaces.canvas(target_surface);
    canvas.save();

    canvas.scale((scale, scale));
    canvas.translate(translation);
    canvas.concat(&matrix);

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

    canvas.reset_matrix();

    let mut paint = skia::Paint::default();
    paint.set_image_filter(blur_filter);
    paint.set_blend_mode(skia::BlendMode::Src);
    canvas.draw_image(&target_surface_snapshot, (0, 0), Some(&paint));

    canvas.restore();
}
