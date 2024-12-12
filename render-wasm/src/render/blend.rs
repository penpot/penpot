use skia_safe as skia;

#[derive(Debug, PartialEq, Clone, Copy)]
pub struct BlendMode(skia::BlendMode);

impl Default for BlendMode {
    fn default() -> Self {
        BlendMode(skia::BlendMode::SrcOver)
    }
}

impl From<i32> for BlendMode {
    fn from(value: i32) -> Self {
        if value <= skia::BlendMode::Luminosity as i32 {
            unsafe { Self(std::mem::transmute(value)) }
        } else {
            Self::default()
        }
    }
}

impl Into<skia::BlendMode> for BlendMode {
    fn into(self) -> skia::BlendMode {
        match self {
            Self(skia_blend) => skia_blend,
        }
    }
}
