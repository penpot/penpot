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
}
