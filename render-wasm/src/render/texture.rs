use skia_safe::{self as skia};

use super::{RenderState, SurfaceId};
use crate::shapes::{Shape, TextureEffect, Type};

/// Renders the texture effect for a shape onto `surface_id`.
///
/// The effect generates a Perlin turbulence noise pattern with the given
/// `noise_size` (converted to a base frequency) and optionally blurs it
/// before compositing it over the shape using the `Multiply` blend mode.
/// When `clip_to_shape` is set the noise is clipped to the shape's visual
/// outline; otherwise it covers the full selection rectangle.
pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    texture: &TextureEffect,
    antialias: bool,
    surface_id: SurfaceId,
) {
    // Convert noise_size to base_frequency: larger grain → lower frequency.
    let base_freq = if texture.noise_size > 0.0 {
        1.0 / texture.noise_size
    } else {
        0.65
    };

    let noise_shader =
        skia::shaders::turbulence((base_freq, base_freq), 4, 0.0, None);

    let Some(noise_shader) = noise_shader else {
        return;
    };

    let mut paint = skia::Paint::default();
    paint.set_anti_alias(antialias);
    paint.set_blend_mode(skia::BlendMode::Multiply);

    if texture.radius > 0.0 {
        let sigma = crate::shapes::radius_to_sigma(texture.radius);
        if let Some(blur_filter) =
            skia::image_filters::blur((sigma, sigma), None, None, None)
        {
            let blurred = skia::image_filters::shader(noise_shader.clone(), None);
            if let Some(blurred) = blurred {
                let composed = skia::ImageFilter::compose(&blur_filter, &blurred);
                if let Some(f) = composed {
                    paint.set_image_filter(f);
                } else {
                    paint.set_shader(noise_shader);
                }
            } else {
                paint.set_shader(noise_shader);
            }
        } else {
            paint.set_shader(noise_shader);
        }
    } else {
        paint.set_shader(noise_shader);
    }

    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    canvas.save();

    if texture.clip_to_shape {
        apply_shape_clip(canvas, shape, antialias);
    }

    canvas.draw_rect(shape.selrect, &paint);
    canvas.restore();
}

/// Clips the canvas to the visual outline of the shape.
fn apply_shape_clip(canvas: &skia::Canvas, shape: &Shape, antialias: bool) {
    match &shape.shape_type {
        Type::Rect(r) => {
            if let Some(corners) = r.corners {
                let rrect = skia::RRect::new_rect_radii(shape.selrect, &corners);
                canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
            } else {
                canvas.clip_rect(shape.selrect, skia::ClipOp::Intersect, antialias);
            }
        }
        Type::Frame(f) => {
            if let Some(corners) = f.corners {
                let rrect = skia::RRect::new_rect_radii(shape.selrect, &corners);
                canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
            } else {
                canvas.clip_rect(shape.selrect, skia::ClipOp::Intersect, antialias);
            }
        }
        Type::Circle => {
            let mut pb = skia::PathBuilder::new();
            pb.add_oval(shape.selrect, None, None);
            canvas.clip_path(&pb.detach(), skia::ClipOp::Intersect, antialias);
        }
        Type::Path(_) | Type::Bool(_) => {
            if let Some(path) = shape.shape_type.path() {
                let path_transform = shape.to_path_transform();
                if let Some(pt) = path_transform {
                    canvas.clip_path(
                        &path.to_skia_path().make_transform(&pt),
                        skia::ClipOp::Intersect,
                        antialias,
                    );
                }
            }
        }
        _ => {
            canvas.clip_rect(shape.selrect, skia::ClipOp::Intersect, antialias);
        }
    }
}
