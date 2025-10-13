use skia_safe as skia;

#[derive(Debug, PartialEq, Clone, Copy)]
pub struct BlendMode(pub skia::BlendMode);

impl Default for BlendMode {
    fn default() -> Self {
        BlendMode(skia::BlendMode::SrcOver)
    }
}

impl From<BlendMode> for skia::BlendMode {
    fn from(val: BlendMode) -> Self {
        match val {
            BlendMode(skia_blend) => skia_blend,
        }
    }
}
