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

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BlurType {
    LayerBlur,
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
