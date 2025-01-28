use skia_safe::{self as skia, image_filters};

use super::Color;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ShadowStyle {
    Drop,
    Inner,
}

impl From<u8> for ShadowStyle {
    fn from(value: u8) -> Self {
        match value {
            0 => Self::Drop,
            1 => Self::Inner,
            _ => Self::default(),
        }
    }
}

impl Default for ShadowStyle {
    fn default() -> Self {
        Self::Drop
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Shadow {
    color: Color,
    blur: f32,
    spread: f32,
    offset: (f32, f32),
    style: ShadowStyle,
    hidden: bool,
}

// TODO: create shadows out of a chunk of bytes
impl Shadow {
    pub fn new(
        color: Color,
        blur: f32,
        spread: f32,
        offset: (f32, f32),
        style: ShadowStyle,
        hidden: bool,
    ) -> Self {
        Self {
            color,
            blur,
            spread,
            offset,
            style,
            hidden,
        }
    }

    pub fn style(&self) -> ShadowStyle {
        self.style
    }

    pub fn hidden(&self) -> bool {
        self.hidden
    }

    pub fn to_paint(&self, dilate: bool, scale: f32) -> skia::Paint {
        let mut paint = skia::Paint::default();
        let mut filter = image_filters::drop_shadow_only(
            (self.offset.0 * scale, self.offset.1 * scale),
            (self.blur * scale, self.blur * scale),
            self.color,
            None,
            None,
            None,
        );

        if dilate {
            filter =
                image_filters::dilate((self.spread * scale, self.spread * scale), filter, None);
        }

        paint.set_image_filter(filter);
        paint.set_anti_alias(true);

        paint
    }
}
