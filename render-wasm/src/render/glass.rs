use skia_safe::{self as skia, canvas::SaveLayerRec, RuntimeEffect, runtime_effect::RuntimeShaderBuilder};

use crate::shapes::{GlassEffect, Shape, GLASS_SKSL};

use super::{RenderState, SurfaceId};

fn get_glass_effect() -> RuntimeEffect {
    RuntimeEffect::make_for_shader(GLASS_SKSL, None).expect("glass SKSL shader compile failed")
}

/// Renders the glass backdrop effect for a shape.
///
/// Must be called BEFORE the shape's transform matrix is concatenated to the canvas,
/// so that selrect coordinates (document space) match the canvas coordinate space.
/// The backdrop filter samples whatever has already been drawn to the fills surface
/// (all shapes behind this one) and applies the refraction/Fresnel/CA shader.
pub fn render_glass(
    render_state: &mut RenderState,
    shape: &Shape,
    glass: &GlassEffect,
    surface_id: SurfaceId,
) {
    if glass.hidden {
        return;
    }

    let effect = get_glass_effect();
    let mut builder = RuntimeShaderBuilder::new(effect);

    let selrect = shape.selrect;
    let scale = render_state.get_scale();
    // The fills surface size in pixels equals the filter surface size.
    // Dividing by scale converts to document-space "canvas size", matching
    // the document-space fragCoord the shader receives.
    let (fw, fh) = render_state.surfaces.filter_size();
    let res_w = fw as f32 / scale;
    let res_h = fh as f32 / scale;

    builder.set_uniform_float("resolution", &[res_w, res_h]);
    builder.set_uniform_float(
        "bounds",
        &[selrect.x(), selrect.y(), selrect.width(), selrect.height()],
    );
    builder.set_uniform_float("radius", &[glass.radius]);
    builder.set_uniform_float("refraction", &[glass.refraction]);
    builder.set_uniform_float("depth", &[glass.depth]);
    builder.set_uniform_float("dispersion", &[glass.dispersion]);
    builder.set_uniform_float("lightIntensity", &[glass.light_intensity]);
    builder.set_uniform_float("lightAngle", &[glass.light_angle]);
    // Identity mat3 (column-major, 9 floats) for static shapes
    builder.set_uniform_float("transform", &[1., 0., 0., 0., 1., 0., 0., 0., 1.]);

    let blur_filter = skia::image_filters::blur((8.0, 8.0), skia::TileMode::Clamp, None, None)
        .expect("glass blur image filter creation failed");

    // blurredImage child receives the backdrop through the blur pre-filter.
    // Pixels outside the glass shape return transparent; SrcOver compositing
    // preserves the original backdrop for those pixels.
    let Some(glass_filter) =
        skia::image_filters::runtime_shader(&builder, "blurredImage", Some(blur_filter))
    else {
        return;
    };

    // Bounds in document (local) coordinates — matches the coordinate space
    // of the fills canvas before the shape's own transform matrix is applied.
    let bounds = skia::Rect::from_xywh(selrect.x(), selrect.y(), selrect.width(), selrect.height());

    let save_layer_rec = SaveLayerRec::default()
        .bounds(&bounds)
        .backdrop(&glass_filter);

    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    canvas.save_layer(&save_layer_rec);
    canvas.restore();
}
