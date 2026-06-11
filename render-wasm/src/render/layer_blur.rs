use crate::shapes::{Blur, BlurType, Shape};

/// Combines every visible layer blur currently active (ancestors + shape)
/// into a single equivalent blur. Layer blur radii compound by adding their
/// variances (σ² = radius²), so we:
///   1. Convert each blur radius into variance via `blur_variance`.
///   2. Sum all variances.
///   3. Convert the total variance back to a radius with `blur_from_variance`.
///
/// This keeps blur math consistent everywhere we need to merge blur sources.
pub fn combined_layer_blur(
    nested_blurs: &[Option<Blur>],
    cached_layer_blur: &mut Option<Option<Blur>>,
    shape_blur: Option<Blur>,
) -> Option<Blur> {
    if let Some(ref cached) = cached_layer_blur {
        return *cached;
    }
    let mut total = 0.;

    for nested_blur in nested_blurs.iter().flatten() {
        total += blur_variance(Some(*nested_blur));
    }

    total += blur_variance(shape_blur);

    let result = blur_from_variance(total);
    *cached_layer_blur = Some(result);
    result
}

/// Returns the variance (radius²) for a visible layer blur, or zero if the
/// blur is hidden/absent. Working in variance space lets us add multiple
/// blur radii correctly.
pub fn blur_variance(blur: Option<Blur>) -> f32 {
    match blur {
        Some(blur) if !blur.hidden && blur.blur_type == BlurType::LayerBlur => {
            blur.value.powi(2)
        }
        _ => 0.,
    }
}

/// Builds a blur from an accumulated variance value. If no variance was
/// contributed, we return `None`; otherwise the equivalent single radius is
/// `sqrt(total)`.
pub fn blur_from_variance(total: f32) -> Option<Blur> {
    (total > 0.).then(|| Blur::new(BlurType::LayerBlur, false, total.sqrt()))
}

/// Convenience helper to merge two optional layer blurs using the same
/// variance math as `combined_layer_blur`.
pub fn combine_blur_values(base: Option<Blur>, extra: Option<Blur>) -> Option<Blur> {
    let total = blur_variance(base) + blur_variance(extra);
    blur_from_variance(total)
}

pub fn frame_clip_layer_blur(shape: &Shape) -> Option<Blur> {
    shape.frame_clip_layer_blur()
}
