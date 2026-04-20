use std::cell::OnceCell;

use skia_safe::{self as skia, RuntimeEffect};

use crate::shapes::{NoiseEffect, Shape, SlotKind, MAX_NOISE_SLOTS, NOISE_SKSL};

use super::{RenderState, SurfaceId};

thread_local! {
    static NOISE_EFFECT: OnceCell<RuntimeEffect> = const { OnceCell::new() };
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

fn write_f32x4(data: &mut [u8], offset: usize, vals: [f32; 4]) {
    for (i, v) in vals.iter().enumerate() {
        write_f32(data, offset + i * 4, *v);
    }
}

/// Straight (non-premultiplied) RGBA normalized to [0,1] for shader uniforms.
fn color_to_vec4(color: skia::Color) -> [f32; 4] {
    [
        color.r() as f32 / 255.0,
        color.g() as f32 / 255.0,
        color.b() as f32 / 255.0,
        color.a() as f32 / 255.0,
    ]
}

/// Peter Acklam's approximation of the inverse standard-normal CDF.
/// Given p ∈ (0, 1), returns z such that Φ(z) = p for N(0, 1).
fn normal_inv_cdf(p: f32) -> f32 {
    let p = (p as f64).clamp(1e-6, 1.0 - 1e-6);
    const A: [f64; 6] = [
        -3.969683028665376e+01,
        2.209460984245205e+02,
        -2.759285104469687e+02,
        1.383577518672690e+02,
        -3.066479806614716e+01,
        2.506628277459239e+00,
    ];
    const B: [f64; 5] = [
        -5.447609879822406e+01,
        1.615858368580409e+02,
        -1.556989798598866e+02,
        6.680131188771972e+01,
        -1.328068155288572e+01,
    ];
    const C: [f64; 6] = [
        -7.784894002430293e-03,
        -3.223964580411365e-01,
        -2.400758277161838e+00,
        -2.549732539343734e+00,
        4.374664141464968e+00,
        2.938163982698783e+00,
    ];
    const D: [f64; 4] = [
        7.784695709041462e-03,
        3.224671290700398e-01,
        2.445134137142996e+00,
        3.754408661907416e+00,
    ];
    const P_LOW: f64 = 0.02425;
    const P_HIGH: f64 = 1.0 - P_LOW;

    let z = if p < P_LOW {
        let q = (-2.0 * p.ln()).sqrt();
        (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5])
            / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1.0)
    } else if p <= P_HIGH {
        let q = p - 0.5;
        let r = q * q;
        (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q
            / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1.0)
    } else {
        let q = (-2.0 * (1.0 - p).ln()).sqrt();
        -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5])
            / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1.0)
    };
    z as f32
}

/// Empirical calibration of Skia's `fractal_noise` output distribution,
/// treated as approximately N(μ, σ). These knobs control both the density →
/// coverage mapping (via `n.r`) and the even split between slots (via `n.g`).
///
/// Iteration history:
/// - μ = 0.50: density=100% produced far less than 50% coverage, 2-slot was
///   ~99/1.
/// - μ = 0.40: still heavily biased toward the first slot; split point was
///   above the actual median of `n.g`.
/// - μ = 0.25: current best empirical fit. If outputs still biased, continue
///   lowering. Skia's `fractal_noise` appears to clip a significant mass at 0
///   which drags the median well below the theoretical 0.5 midpoint.
const FRACTAL_NOISE_MU: f32 = 0.25;
const FRACTAL_NOISE_SIGMA: f32 = 0.15;

/// Returns a threshold `t` such that P(noise.r < t) ≈ `cdf_target` under the
/// N(FRACTAL_NOISE_MU, FRACTAL_NOISE_SIGMA) approximation of `fractal_noise`.
/// Pixels with `noise.r >= t` become colored, so coverage ≈ 1 - cdf_target.
fn percentile_threshold(cdf_target: f32) -> f32 {
    let z = normal_inv_cdf(cdf_target);
    (FRACTAL_NOISE_MU + FRACTAL_NOISE_SIGMA * z).clamp(0.0, 1.0)
}

/// Builds a runtime-effect shader for the noise.
///
/// - `only_kind`: when `Some`, any slot whose kind differs has its alpha forced
///   to 0 so only the matching kind contributes (used by the two-pass prism+
///   solid path so only prism gets blurred).
/// - `full_coverage`: when `true`, the shader outputs colors at alpha=1
///   regardless of the n.r threshold or slot opacity. Used as the pre-blur
///   color pass whose output is later masked by DstIn with a crisp alpha so
///   blur doesn't soften the noise's alpha (which would let the shape's fill
///   show through in partial regions).
#[allow(clippy::too_many_arguments)]
fn build_noise_shader(
    noise: &NoiseEffect,
    freq: f32,
    threshold: f32,
    split_1: f32,
    split_2: f32,
    split_3: f32,
    slot_count: usize,
    bounds: skia::Rect,
    only_kind: Option<SlotKind>,
    full_coverage: bool,
) -> Option<skia::Shader> {
    // Anchor the noise to the shape's origin. Skia's `with_local_matrix(m)`
    // post-concatenates `m` onto the CTM when the shader samples, so the
    // wrapped shader sees `m^-1 * canvas_coord`. To make the shader input be
    // `canvas_coord - bounds.top_left` (shape-local), `m` must be a POSITIVE
    // translate by bounds.top_left — the pattern stays anchored to the shape
    // as the shape moves across the canvas.
    let noise_anchor = skia::Matrix::translate((bounds.left(), bounds.top()));
    let turbulence = skia::shaders::fractal_noise((freq, freq), 4, 0.0, None)?
        .with_local_matrix(&noise_anchor);

    NOISE_EFFECT.with(|cell| {
        let effect = cell.get_or_init(|| compile(NOISE_SKSL));
        let uniform_size = effect.uniform_size();
        let mut data = vec![0u8; uniform_size];

        let mut slots: [[f32; 4]; MAX_NOISE_SLOTS] = [[0.0; 4]; MAX_NOISE_SLOTS];
        let mut kinds: [i32; MAX_NOISE_SLOTS] = [0; MAX_NOISE_SLOTS];
        for (i, s) in noise.slots.iter().take(MAX_NOISE_SLOTS).enumerate() {
            let mut c = color_to_vec4(s.color);
            if let Some(k) = only_kind {
                if s.kind != k {
                    c[3] = 0.0; // mask out slots of the "other" kind
                }
            }
            slots[i] = c;
            kinds[i] = match s.kind {
                SlotKind::Solid => 0,
                SlotKind::Prism => 1,
            };
        }
        let count = slot_count as i32;

        for u in effect.uniforms().iter() {
            let name: &str = &u.name();
            let off = u.offset();
            match name {
                "u_threshold" => write_f32(&mut data, off, threshold),
                "u_full_coverage" => write_i32(&mut data, off, if full_coverage { 1 } else { 0 }),
                "u_split_1" => write_f32(&mut data, off, split_1),
                "u_split_2" => write_f32(&mut data, off, split_2),
                "u_split_3" => write_f32(&mut data, off, split_3),
                "u_slot_count" => write_i32(&mut data, off, count),
                "u_kind_0" => write_i32(&mut data, off, kinds[0]),
                "u_kind_1" => write_i32(&mut data, off, kinds[1]),
                "u_kind_2" => write_i32(&mut data, off, kinds[2]),
                "u_kind_3" => write_i32(&mut data, off, kinds[3]),
                "u_c0" => write_f32x4(&mut data, off, slots[0]),
                "u_c1" => write_f32x4(&mut data, off, slots[1]),
                "u_c2" => write_f32x4(&mut data, off, slots[2]),
                "u_c3" => write_f32x4(&mut data, off, slots[3]),
                _ => {}
            }
        }

        let children = vec![skia::runtime_effect::ChildPtr::Shader(turbulence)];
        effect.make_shader(skia::Data::new_copy(&data), &children, None)
    })
}

/// Draws a noise pass at `bounds`.
///
/// - `mask_shader`: the normal (threshold+opacity) noise shader — drives the
///   final alpha. When `color_shader` is `None` and `sigma == 0` this is the
///   only shader drawn.
/// - `color_shader`: the full-coverage noise shader (alpha=1 everywhere).
///   Only used when `sigma > 0` — it provides the pre-blur color source so
///   the blur smooths colors without softening the alpha.
///
/// When `sigma > 0`, renders in two layers:
///   1. Inner layer: draw `color_shader` with a Gaussian blur image filter.
///      The layer is bounded to `bounds` so blur samples outside read as
///      transparent (the output stays inside the shape).
///   2. Outer layer captures the blurred colors, then `mask_shader` is drawn
///      on top with `DstIn` so the layer's alpha becomes the hard mask while
///      the blurred RGB is preserved (colors blur internally, alpha stays
///      crisp — so the shape's fill underneath is either fully covered or
///      fully visible, never partially tinted through a soft edge).
///   3. On restore the outer layer composites to the canvas with `blend_mode`.
fn draw_noise_pass(
    canvas: &skia::Canvas,
    mask_shader: skia::Shader,
    color_shader: Option<skia::Shader>,
    bounds: skia::Rect,
    blend_mode: skia::BlendMode,
    sigma: f32,
) {
    if sigma > 0.0 {
        if let (Some(color_shader), Some(filter)) = (
            color_shader,
            skia::image_filters::blur((sigma, sigma), None, None, None),
        ) {
            // Outer layer: final blend to the canvas. Bounded to the shape
            // rect so nothing composites outside the shape.
            let mut outer_paint = skia::Paint::default();
            outer_paint.set_anti_alias(true);
            outer_paint.set_blend_mode(blend_mode);
            let outer_rec = skia::canvas::SaveLayerRec::default()
                .bounds(&bounds)
                .paint(&outer_paint);
            canvas.save_layer(&outer_rec);

            // Inner layer: blur the full-coverage color shader. No `bounds`
            // hint — let Skia size the buffer to contain what we draw. We
            // draw a rect expanded by ~3*sigma so that blur samples near the
            // shape edge still hit full-alpha pixels (rather than fading to
            // transparent at the layer boundary, which would otherwise cause
            // edge artifacts that shift against the crisp mask as the shape
            // moves).
            let mut inner_paint = skia::Paint::default();
            inner_paint.set_anti_alias(true);
            inner_paint.set_image_filter(filter);
            let inner_rec = skia::canvas::SaveLayerRec::default().paint(&inner_paint);
            canvas.save_layer(&inner_rec);

            let pad = sigma * 3.0;
            let expanded = skia::Rect::from_ltrb(
                bounds.left() - pad,
                bounds.top() - pad,
                bounds.right() + pad,
                bounds.bottom() + pad,
            );
            let mut color_paint = skia::Paint::default();
            color_paint.set_anti_alias(true);
            color_paint.set_shader(color_shader);
            canvas.draw_rect(expanded, &color_paint);

            canvas.restore(); // blurred colors are now in the outer layer

            // Mask pass: DstIn intersects the outer layer's alpha with the
            // crisp mask shader's alpha — the blurred RGB survives but alpha
            // snaps to the original hard noise mask.
            let mut mask_paint = skia::Paint::default();
            mask_paint.set_anti_alias(true);
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            mask_paint.set_shader(mask_shader);
            canvas.draw_rect(bounds, &mask_paint);

            canvas.restore();
            return;
        }
    }

    // No blur path: draw the mask shader directly.
    let mut paint = skia::Paint::default();
    paint.set_anti_alias(true);
    paint.set_blend_mode(blend_mode);
    paint.set_shader(mask_shader);
    canvas.draw_rect(bounds, &paint);
}

pub fn render_shape_noise(
    render_state: &mut RenderState,
    shape: &Shape,
    surface_id: SurfaceId,
) {
    let noise = match shape.noise.as_ref() {
        Some(n) if !n.hidden && !n.slots.is_empty() => n,
        _ => return,
    };

    let size = noise.noise_size.max(1.0);
    let freq = 1.0 / size;
    let density = noise.density.clamp(0.0, 1.0);
    let slot_count = noise.slots.len().clamp(1, MAX_NOISE_SLOTS);

    // Density → shader threshold:
    //  * 1 slot:  P(colored) = density / (density + 1) ⇒ P(n.r < t) = 1/(d+1)
    //  * 2+ slots: P(colored) = density               ⇒ P(n.r < t) = 1 - density
    let target_p_less = if slot_count <= 1 {
        1.0 / (density + 1.0)
    } else {
        1.0 - density
    };
    let threshold = percentile_threshold(target_p_less);

    // Slot split points on n.g. Empirical quantiles — each slot owns the
    // noise mass between consecutive points, so the N-way split is visually
    // even even though n.g isn't uniformly distributed.
    let (split_1, split_2, split_3) = match slot_count {
        2 => (percentile_threshold(1.0 / 2.0), 0.0, 0.0),
        3 => (percentile_threshold(1.0 / 3.0), percentile_threshold(2.0 / 3.0), 0.0),
        4 => (
            percentile_threshold(1.0 / 4.0),
            percentile_threshold(2.0 / 4.0),
            percentile_threshold(3.0 / 4.0),
        ),
        _ => (0.0, 0.0, 0.0),
    };

    let bounds = shape.selrect();

    let has_prism = noise.slots.iter().any(|s| s.kind == SlotKind::Prism);
    let has_solid = noise.slots.iter().any(|s| s.kind == SlotKind::Solid);

    // Softness → blur sigma in world units (same space as `bounds` and grain
    // size). Blur is only meaningful for prism slots; solid slots stay crisp.
    // sigma scales with grain so the blur feels proportional to the noise.
    let sigma = noise.softness.clamp(0.0, 1.0) * size * 0.6;

    let blend_mode = if noise.apply_to_fill {
        skia::BlendMode::SrcATop
    } else {
        skia::BlendMode::SrcOver
    };

    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    canvas.save();
    // Outer clip: no blur output can spill past the shape's rect onto other
    // shapes' fills.
    canvas.clip_rect(bounds, skia::ClipOp::Intersect, true);

    if sigma > 0.0 && has_prism && has_solid {
        // Two-pass: prism slots get the blur underneath; solid slots are drawn
        // on top crisp so they retain sharp edges.
        if let (Some(prism_mask), Some(prism_color)) = (
            build_noise_shader(
                noise, freq, threshold, split_1, split_2, split_3, slot_count,
                bounds, Some(SlotKind::Prism), false,
            ),
            build_noise_shader(
                noise, freq, threshold, split_1, split_2, split_3, slot_count,
                bounds, Some(SlotKind::Prism), true,
            ),
        ) {
            draw_noise_pass(canvas, prism_mask, Some(prism_color), bounds, blend_mode, sigma);
        }
        if let Some(solid_shader) = build_noise_shader(
            noise, freq, threshold, split_1, split_2, split_3, slot_count, bounds,
            Some(SlotKind::Solid), false,
        ) {
            draw_noise_pass(canvas, solid_shader, None, bounds, blend_mode, 0.0);
        }
    } else {
        // Single pass: either softness=0, or all slots are the same kind.
        // Blur only if there's a prism slot (solid-only noise stays crisp
        // regardless of softness).
        let pass_sigma = if has_prism { sigma } else { 0.0 };
        let mask = build_noise_shader(
            noise, freq, threshold, split_1, split_2, split_3, slot_count, bounds,
            None, false,
        );
        let color = if pass_sigma > 0.0 {
            build_noise_shader(
                noise, freq, threshold, split_1, split_2, split_3, slot_count,
                bounds, None, true,
            )
        } else {
            None
        };
        if let Some(mask) = mask {
            draw_noise_pass(canvas, mask, color, bounds, blend_mode, pass_sigma);
        }
    }

    canvas.restore();
}
