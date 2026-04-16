use std::cell::OnceCell;

use skia_safe::{self as skia, RRect, RuntimeEffect};

use crate::shapes::{GlassEffect, Shape, Type, GLASS_DISPLACEMENT_SKSL, GLASS_REFRACTION_SKSL, GLASS_SKSL};

use super::gpu_state::GpuState;
use super::{RenderState, SurfaceId};

thread_local! {
    static DISPLACEMENT_EFFECT: OnceCell<RuntimeEffect> = const { OnceCell::new() };
    static REFRACTION_EFFECT: OnceCell<RuntimeEffect> = const { OnceCell::new() };
    static COMPOSITE_EFFECT: OnceCell<RuntimeEffect> = const { OnceCell::new() };
}

fn compile(src: &str) -> RuntimeEffect {
    RuntimeEffect::make_for_shader(src, None).expect("SkSL compile failed")
}

fn write_f32(data: &mut [u8], offset: usize, val: f32) {
    data[offset..offset + 4].copy_from_slice(&val.to_ne_bytes());
}

fn write_i32(data: &mut [u8], offset: usize, val: i32) {
    data[offset..offset + 4].copy_from_slice(&val.to_ne_bytes());
}

/// Clip canvas to the shape's actual geometry.
fn clip_to_shape(canvas: &skia::Canvas, shape: &Shape) {
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
}

/// Create a blurred version of an image as a shader, using Skia's built-in blur.
/// Uses a GPU surface to keep all operations on the GPU (raster surfaces cannot
/// read back GPU-backed textures in the WASM/WebGL context).
fn make_blurred_shader(
    gpu_state: &mut GpuState,
    image: &skia::Image,
    sigma: f32,
    sampling: skia::SamplingOptions,
) -> Option<skia::Shader> {
    let blur_filter =
        skia::image_filters::blur((sigma, sigma), skia::TileMode::Clamp, None, None)?;

    // Use a budgeted GPU surface — Skia manages the texture lifecycle so it
    // gets freed when no longer referenced, preventing GPU memory leaks.
    let mut temp_surface = gpu_state
        .create_budgeted_surface(image.width(), image.height())
        .ok()?;

    {
        let temp_canvas = temp_surface.canvas();
        let mut paint = skia::Paint::default();
        paint.set_image_filter(blur_filter);
        temp_canvas.draw_image(image, (0.0, 0.0), Some(&paint));
    }

    let blurred_image = temp_surface.image_snapshot();

    blurred_image.to_shader(
        (skia::TileMode::Clamp, skia::TileMode::Clamp),
        sampling,
        None,
    )
}

/// Pass 1: Render the displacement field to a temporary F16 GPU surface and return
/// it as a child shader for subsequent passes.
///
/// The displacement shader computes SDF, surface profiles, Snell's Law
/// refraction, specular highlights, and outputs raw float values:
///   half4(dx, dy, specular, mask)
fn render_displacement_pass(
    gpu_state: &mut GpuState,
    iw: i32,
    ih: i32,
    box_center_dev: skia::Point,
    box_half_w_dev: f32,
    box_half_h_dev: f32,
    corner_radius_dev: f32,
    glass: &GlassEffect,
    scale: f32,
) -> Option<skia::Shader> {
    DISPLACEMENT_EFFECT.with(|cell| {
        let effect = cell.get_or_init(|| compile(GLASS_DISPLACEMENT_SKSL));

        // Fill geometry/physics uniforms
        let uniform_size = effect.uniform_size();
        let mut data = vec![0u8; uniform_size];

        for u in effect.uniforms().iter() {
            let name: &str = &u.name();
            let off = u.offset();
            match name {
                "u_resolution" => {
                    write_f32(&mut data, off, iw as f32);
                    write_f32(&mut data, off + 4, ih as f32);
                }
                "u_mouse" => {
                    write_f32(&mut data, off, box_center_dev.x);
                    write_f32(&mut data, off + 4, box_center_dev.y);
                    write_f32(&mut data, off + 8, 0.0);
                    write_f32(&mut data, off + 12, 0.0);
                }
                "u_surfaceType" => {
                    write_i32(&mut data, off, glass.surface_type);
                }
                "u_bezelWidth" => {
                    write_f32(&mut data, off, glass.bezel_width * scale);
                }
                "u_glassThickness" => {
                    write_f32(&mut data, off, glass.glass_thickness);
                }
                "u_refractiveIndex" => {
                    write_f32(&mut data, off, glass.refractive_index);
                }
                "u_specularAngle" => {
                    write_f32(&mut data, off, glass.specular_angle);
                }
                "u_glassSize" => {
                    write_f32(&mut data, off, box_half_w_dev);
                    write_f32(&mut data, off + 4, box_half_h_dev);
                }
                "u_cornerRadius" => {
                    write_f32(&mut data, off, corner_radius_dev);
                }
                "u_splay" => {
                    write_f32(&mut data, off, glass.splay);
                }
                "u_tiltAngle" => {
                    write_f32(&mut data, off, glass.tilt_angle);
                }
                "u_edgeBoost" => {
                    write_f32(&mut data, off, glass.edge_boost);
                }
                "u_zoom" => {
                    write_f32(&mut data, off, glass.zoom);
                }
                "u_scale" => {
                    write_f32(&mut data, off, scale);
                }
                _ => {}
            }
        }

        // No child shaders — pure computation
        let disp_shader = effect.make_shader(skia::Data::new_copy(&data), &[], None)?;

        // Render to a temporary F16 GPU surface for full-precision displacement values
        let mut temp_surface = gpu_state
            .create_budgeted_surface_f16(iw, ih)
            .ok()?;

        {
            let canvas = temp_surface.canvas();
            let mut paint = skia::Paint::default();
            paint.set_shader(disp_shader);
            paint.set_blend_mode(skia::BlendMode::Src);
            canvas.draw_paint(&paint);
        }

        let disp_image = temp_surface.image_snapshot();

        // Linear sampling is fine with F16 raw values
        disp_image.to_shader(
            (skia::TileMode::Clamp, skia::TileMode::Clamp),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::None),
            None,
        )
    })
}

/// Pass 2: Apply refraction and chromatic aberration to the unblurred backdrop.
/// Renders to a GPU surface and returns the refracted image.
fn render_refraction_pass(
    gpu_state: &mut GpuState,
    iw: i32,
    ih: i32,
    backdrop_shader: skia::Shader,
    displacement_shader: skia::Shader,
    glass: &GlassEffect,
    scale: f32,
) -> Option<skia::Image> {
    REFRACTION_EFFECT.with(|cell| {
        let effect = cell.get_or_init(|| compile(GLASS_REFRACTION_SKSL));

        let uniform_size = effect.uniform_size();
        let mut data = vec![0u8; uniform_size];

        for u in effect.uniforms().iter() {
            let name: &str = &u.name();
            let off = u.offset();
            match name {
                "u_resolution" => {
                    write_f32(&mut data, off, iw as f32);
                    write_f32(&mut data, off + 4, ih as f32);
                }
                "u_chromaticAberration" => {
                    write_f32(&mut data, off, glass.chromatic_aberration * scale);
                }
                "u_scale" => {
                    write_f32(&mut data, off, scale);
                }
                _ => {}
            }
        }

        // Child shaders: backdrop (unblurred) + displacement
        let children = vec![
            skia::runtime_effect::ChildPtr::Shader(backdrop_shader),
            skia::runtime_effect::ChildPtr::Shader(displacement_shader),
        ];

        let refract_shader = effect.make_shader(skia::Data::new_copy(&data), &children, None)?;

        // Render to GPU surface
        let mut temp_surface = gpu_state
            .create_budgeted_surface(iw, ih)
            .ok()?;

        {
            let canvas = temp_surface.canvas();
            let mut paint = skia::Paint::default();
            paint.set_shader(refract_shader);
            paint.set_blend_mode(skia::BlendMode::Src);
            canvas.draw_paint(&paint);
        }

        Some(temp_surface.image_snapshot())
    })
}

/// Renders the glass effect using a multi-pass pipeline:
///
/// **Pass 1 — Displacement field:** Computes SDF, surface profiles, Snell's Law
/// refraction displacement, specular highlights, and mask. Stored as raw F16
/// values: half4(dx, dy, specular, mask).
///
/// **Pass 2 — Refraction:** Applies displacement and chromatic aberration to
/// the unblurred backdrop, producing a refracted image.
///
/// **Blur:** Applied via Skia's ImageFilter to the refracted image (not the
/// raw backdrop), so refraction distortion edges get softened.
///
/// **Pass 3 — Composite:** Reads the blurred refracted image and displacement
/// field, then applies frost scatter, glass tint, prismatic specular highlights,
/// and composites with the original backdrop using the mask.
pub fn render_glass(
    render_state: &mut RenderState,
    shape: &Shape,
    glass: &GlassEffect,
    surface_id: SurfaceId,
) {
    if glass.hidden {
        return;
    }

    let selrect = shape.selrect;
    if selrect.width() <= 0.0 || selrect.height() <= 0.0 {
        return;
    }

    let scale = render_state.get_scale();

    let sampling = skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::None);

    // ── Compute the render context transform ────────────────────────────
    let translation = render_state
        .surfaces
        .get_render_context_translation(render_state.render_area, scale);

    let center = shape.center();
    let mut local_matrix = shape.transform;
    local_matrix.post_translate(center);
    local_matrix.pre_translate(-center);

    // ── Compute box parameters in device-pixel space ─────────────────────
    let mut ctm = skia::Matrix::new_identity();
    ctm.pre_scale((scale, scale), None);
    ctm.pre_translate(translation);
    ctm.pre_concat(&local_matrix);

    let box_center_dev = ctm.map_point((center.x, center.y));
    let box_half_w_dev = selrect.width() * 0.5 * scale;
    let box_half_h_dev = selrect.height() * 0.5 * scale;

    // Single corner radius in device pixels (use first corner)
    let corner_radius_dev: f32 = match &shape.shape_type {
        Type::Rect(data) => data
            .corners
            .as_ref()
            .map(|c| c[0].x * scale)
            .unwrap_or(0.0),
        Type::Frame(data) => data
            .corners
            .as_ref()
            .map(|c| c[0].x * scale)
            .unwrap_or(0.0),
        _ => 0.0,
    };

    // ── Snapshot backdrop ───────────────────────────────────────────────
    let backdrop = render_state.surfaces.snapshot(surface_id);
    let iw = backdrop.width();
    let ih = backdrop.height();

    // Original (unblurred) source shader — always needed for passthrough
    let original_shader = match backdrop.to_shader(
        (skia::TileMode::Clamp, skia::TileMode::Clamp),
        sampling,
        None,
    ) {
        Some(s) => s,
        None => return,
    };

    // ── Pass 1: Displacement field (F16 surface) ──────────────────────
    let displacement_shader = match render_displacement_pass(
        &mut render_state.gpu_state,
        iw,
        ih,
        box_center_dev,
        box_half_w_dev,
        box_half_h_dev,
        corner_radius_dev,
        glass,
        scale,
    ) {
        Some(s) => s,
        None => return,
    };

    // ── Pass 2: Refraction + Chromatic Aberration ─────────────────────
    let refracted_image = match render_refraction_pass(
        &mut render_state.gpu_state,
        iw,
        ih,
        original_shader.clone(),
        displacement_shader.clone(),
        glass,
        scale,
    ) {
        Some(img) => img,
        None => return,
    };

    // ── Blur the refracted image (applied AFTER refraction) ───────────
    // total_blur_sigma() already returns sigma — no radius_to_sigma conversion.
    let total_sigma = glass.total_blur_sigma() * scale;
    let blurred_shader = if total_sigma > 0.5 {
        make_blurred_shader(&mut render_state.gpu_state, &refracted_image, total_sigma, sampling)
            .unwrap_or_else(|| {
                refracted_image
                    .to_shader(
                        (skia::TileMode::Clamp, skia::TileMode::Clamp),
                        sampling,
                        None,
                    )
                    .unwrap_or_else(|| original_shader.clone())
            })
    } else {
        match refracted_image.to_shader(
            (skia::TileMode::Clamp, skia::TileMode::Clamp),
            sampling,
            None,
        ) {
            Some(s) => s,
            None => return,
        }
    };

    // ── Pass 3: Composite (frost + specular + mask blend) ─────────────
    let glass_shader = COMPOSITE_EFFECT.with(|cell| {
        let effect = cell.get_or_init(|| compile(GLASS_SKSL));

        let uniform_size = effect.uniform_size();
        let mut data = vec![0u8; uniform_size];

        for u in effect.uniforms().iter() {
            let name: &str = &u.name();
            let off = u.offset();
            match name {
                "u_resolution" => {
                    write_f32(&mut data, off, iw as f32);
                    write_f32(&mut data, off + 4, ih as f32);
                }
                "u_frost" => {
                    write_f32(&mut data, off, glass.frost);
                }
                "u_specularOpacity" => {
                    write_f32(&mut data, off, glass.specular_opacity);
                }
                "u_specularSaturation" => {
                    write_f32(&mut data, off, glass.specular_saturation);
                }
                "u_scale" => {
                    write_f32(&mut data, off, scale);
                }
                _ => {}
            }
        }

        // Three child shaders: blurred (refracted+blurred) + original (unblurred BG) + displacement
        let children = vec![
            skia::runtime_effect::ChildPtr::Shader(blurred_shader),
            skia::runtime_effect::ChildPtr::Shader(original_shader),
            skia::runtime_effect::ChildPtr::Shader(displacement_shader),
        ];

        effect.make_shader(skia::Data::new_copy(&data), &children, None)
    });

    let glass_shader = match glass_shader {
        Some(s) => s,
        None => return,
    };

    // ── Draw glass onto the target surface ─────────────────────────────
    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    canvas.save();

    canvas.scale((scale, scale));
    canvas.translate(translation);
    canvas.concat(&local_matrix);

    // Clip to actual shape geometry
    clip_to_shape(canvas, shape);

    // Reset matrix so shader runs in device-pixel space matching snapshots
    canvas.reset_matrix();

    let mut paint = skia::Paint::default();
    paint.set_shader(glass_shader);
    paint.set_blend_mode(skia::BlendMode::Src);
    canvas.draw_paint(&paint);

    canvas.restore();
}
