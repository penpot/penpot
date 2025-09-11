use skia_safe as skia;

// TODO: maybe move this to the wasm module?
// TODO: find a way to use the ToJS derive macro for this
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
            unsafe { Self(std::mem::transmute::<i32, skia_safe::BlendMode>(value)) }
        } else {
            Self::default()
        }
    }
}

impl From<BlendMode> for skia::BlendMode {
    fn from(val: BlendMode) -> Self {
        match val {
            BlendMode(skia_blend) => skia_blend,
        }
    }
}
