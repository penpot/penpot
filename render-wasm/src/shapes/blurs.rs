#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BlurType {
    None,
    Layer,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Blur {
    pub hidden: bool,
    pub blur_type: BlurType,
    pub value: f32,
}

impl From<u8> for BlurType {
    fn from(value: u8) -> Self {
        match value {
            1 => BlurType::Layer,
            _ => BlurType::None,
        }
    }
}

impl Blur {
    pub fn default() -> Self {
        Blur {
            blur_type: BlurType::None,
            hidden: true,
            value: 0.,
        }
    }
    pub fn new(blur_type: u8, hidden: bool, value: f32) -> Self {
        Blur {
            blur_type: BlurType::from(blur_type),
            hidden,
            value,
        }
    }
}
