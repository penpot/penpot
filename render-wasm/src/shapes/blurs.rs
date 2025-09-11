use macros::ToJs;

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
pub enum BlurType {
    None = 0,
    Layer = 1,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Blur {
    pub hidden: bool,
    pub blur_type: BlurType,
    pub value: f32,
}

// TODO: maybe move this to the wasm module?
impl From<u8> for BlurType {
    // TODO: use transmute
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

    pub fn scale_content(&mut self, value: f32) {
        self.value *= value;
    }
}
