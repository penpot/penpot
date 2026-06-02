use skia_safe::{self as skia, image_filters, ImageFilter, Paint};

use super::Color;
use crate::render::filters::compose_filters;

#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub enum ShadowStyle {
    #[default]
    Drop,
    Inner,
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

    pub fn get_inner_shadow_paint(
        &self,
        antialias: bool,
        blur_filter: Option<&ImageFilter>,
    ) -> Paint {
        let mut paint = Paint::default();
        let shadow_filter = self.get_inner_shadow_filter();
        let filter = compose_filters(blur_filter, shadow_filter.as_ref());
        paint.set_image_filter(filter);
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

    pub fn scale_content(&mut self, value: f32) {
        self.blur *= value;
        self.spread *= value;
        self.offset.0 *= value;
        self.offset.1 *= value;
    }
}
