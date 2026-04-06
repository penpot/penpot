#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TextureEffect {
    pub noise_size: f32,
    pub radius: f32,
    pub clip_to_shape: bool,
    pub hidden: bool,
}

impl TextureEffect {
    pub fn new(noise_size: f32, radius: f32, clip_to_shape: bool, hidden: bool) -> Self {
        TextureEffect {
            noise_size,
            radius,
            clip_to_shape,
            hidden,
        }
    }
}
