use skia_safe::{self as skia, image_filters, ImageFilter, Paint};

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
    pub color: Color,
    pub blur: f32,
    pub spread: f32,
    pub offset: (f32, f32),
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

    pub fn get_drop_shadow_paint(&self, antialias: bool) -> Paint {
        let mut paint = Paint::default();
        let image_filter = self.get_drop_shadow_filter();

        paint.set_image_filter(image_filter);
        paint.set_anti_alias(antialias);

        paint
    }

    pub fn get_drop_shadow_filter(&self) -> Option<ImageFilter> {
        let mut filter = image_filters::drop_shadow_only(
            (self.offset.0, self.offset.1),
            (self.blur, self.blur),
            self.color,
            None,
            None,
            None,
        );

        if self.spread > 0. {
            filter = image_filters::dilate((self.spread, self.spread), filter, None);
        }

        filter
    }

    pub fn get_inner_shadow_paint(&self, antialias: bool) -> Paint {
        let mut paint = Paint::default();

        let image_filter = self.get_inner_shadow_filter();

        paint.set_image_filter(image_filter);
        paint.set_anti_alias(antialias);

        paint
    }

    pub fn get_inner_shadow_filter(&self) -> Option<ImageFilter> {
        let sigma = self.blur * 0.5;
        let mut filter = skia::image_filters::drop_shadow_only(
            (self.offset.0, self.offset.1), // DPR?
            (sigma, sigma),
            skia::Color::WHITE,
            None,
            None,
            None,
        );

        filter = skia::image_filters::color_filter(
            skia::color_filters::blend(self.color, skia::BlendMode::SrcOut).unwrap(),
            filter,
            None,
        );

        if self.spread > 0. {
            filter = skia::image_filters::dilate((self.spread, self.spread), filter, None);
        }

        filter = skia::image_filters::blend(skia::BlendMode::SrcIn, None, filter, None);

        filter
    }
}
