use skia_safe::Color;

/// Maximum number of slots (solid colors + prism) the noise shader supports.
pub const MAX_NOISE_SLOTS: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SlotKind {
    Solid = 0,
    Prism = 1,
}

impl From<u8> for SlotKind {
    fn from(v: u8) -> Self {
        match v {
            0 => Self::Solid,
            1 => Self::Prism,
            _ => Self::Solid,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NoiseSlot {
    pub kind: SlotKind,
    /// Used when `kind == Solid`. For `Prism` the rgb is ignored and only the
    /// alpha channel (per-slot opacity) matters.
    pub color: Color,
}

impl NoiseSlot {
    pub fn solid(color: Color) -> Self {
        Self {
            kind: SlotKind::Solid,
            color,
        }
    }

    pub fn prism(opacity: f32) -> Self {
        // Encode per-slot opacity as the alpha of the color (rgb is unused).
        let a = (opacity.clamp(0.0, 1.0) * 255.0).round() as u8;
        Self {
            kind: SlotKind::Prism,
            color: Color::from_argb(a, 0, 0, 0),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct NoiseEffect {
    pub slots: Vec<NoiseSlot>,
    pub noise_size: f32,
    pub density: f32,
    /// Edge softness in [0, 1]. 0 = hard threshold (crisp blob edges); 1 =
    /// maximum feather (pastel-looking soft falloff). Mapped to a feather
    /// half-width in noise.r space by `render::noise`.
    pub softness: f32,
    pub apply_to_fill: bool,
    pub hidden: bool,
}

impl NoiseEffect {
    pub fn new(
        slots: Vec<NoiseSlot>,
        noise_size: f32,
        density: f32,
        softness: f32,
        apply_to_fill: bool,
        hidden: bool,
    ) -> Self {
        Self {
            slots,
            noise_size,
            density,
            softness,
            apply_to_fill,
            hidden,
        }
    }
}

pub const NOISE_SKSL: &str = include_str!("../shaders/noise.sksl");
