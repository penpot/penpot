/// Skia's kBLUR_SIGMA_SCALE (1/√3 ≈ 0.57735). Used to convert blur radius to sigma
const BLUR_SIGMA_SCALE: f32 = 0.577_350_27;

/// Converts a blur radius to sigma (standard deviation) for Skia's blur APIs.
/// Matches Skia's SkBlurMask::ConvertRadiusToSigma:
#[inline]
pub fn radius_to_sigma(radius: f32) -> f32 {
    if radius > 0.0 {
        BLUR_SIGMA_SCALE * radius + 0.5
    } else {
        0.0
    }
}

/// Inverse of [`radius_to_sigma`].
///
/// Sigmas below the constant term have no radius that produces them, so they
/// collapse to zero — a sub-pixel blur, which Skia would round away anyway.
#[inline]
pub fn sigma_to_radius(sigma: f32) -> f32 {
    if sigma > 0.5 {
        (sigma - 0.5) / BLUR_SIGMA_SCALE
    } else {
        0.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BlurType {
    LayerBlur,
    BackgroundBlur,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Blur {
    pub hidden: bool,
    pub blur_type: BlurType,
    pub value: f32,
}

impl Blur {
    pub fn new(blur_type: BlurType, hidden: bool, value: f32) -> Self {
        Blur {
            blur_type,
            hidden,
            value,
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.value *= value;
    }

    /// Returns the sigma (standard deviation) for Skia blur APIs.
    /// The stored `value` is a blur radius; this converts it to sigma.
    #[inline]
    pub fn sigma(&self) -> f32 {
        radius_to_sigma(self.value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sigma_to_radius_round_trips() {
        for radius in [0.0_f32, 1.0, 4.0, 20.0, 200.0] {
            let sigma = radius_to_sigma(radius);
            assert!((sigma_to_radius(sigma) - radius).abs() < 0.001);
        }
    }

    #[test]
    fn sub_pixel_sigma_has_no_radius() {
        assert_eq!(sigma_to_radius(0.0), 0.0);
        assert_eq!(sigma_to_radius(0.5), 0.0);
    }
}
