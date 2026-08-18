use skia_safe::{self as skia, image_filters, ImageFilter, Paint};

use super::blurs::radius_to_sigma;
use super::Color;
use crate::render::filters::compose_filters;

/// Soft visibility floor in device pixels for leaf shapes. Below this, a drop
/// shadow is visual noise relative to its blur cost.
pub const DROP_SHADOW_MIN_DEVICE_PX: f32 = 2.0;

/// Recursive shapes (frames/groups) redraw children into the shadow layer; they
/// need a clearer on-screen footprint before that cost is worthwhile.
pub const DROP_SHADOW_RECURSIVE_MIN_DEVICE_PX: f32 = 4.0;

/// Generous design-space shadow budget used with [`DROP_SHADOW_MIN_DEVICE_PX`]
/// for a hard global early-out (subpixel even for huge shadows).
pub const DROP_SHADOW_LARGE_DESIGN_PX: f32 = 64.0;

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

    /// Approximate on-screen footprint (blur/spread + offset) at `scale` (zoom×dpr).
    #[inline]
    pub fn device_extent(&self, scale: f32) -> f32 {
        let soft = self.blur.max(self.spread);
        let offset = self.offset.0.abs().max(self.offset.1.abs());
        (soft + offset) * scale
    }

    /// True when this shadow still has a perceptible footprint at `scale`.
    /// Recursive shapes use a higher floor because compositing children into
    /// the shadow layer is far more expensive than a leaf silhouette.
    #[inline]
    pub fn is_perceptible_at_scale(&self, scale: f32) -> bool {
        self.is_perceptible_at_scale_for(scale, false)
    }

    #[inline]
    pub fn is_perceptible_at_scale_for(&self, scale: f32, recursive: bool) -> bool {
        let min = if recursive {
            DROP_SHADOW_RECURSIVE_MIN_DEVICE_PX
        } else {
            DROP_SHADOW_MIN_DEVICE_PX
        };
        self.device_extent(scale) >= min
    }

    pub fn get_drop_shadow_filter(&self) -> Option<ImageFilter> {
        let sigma = radius_to_sigma(self.blur);
        let mut filter = image_filters::drop_shadow_only(
            (self.offset.0, self.offset.1),
            (sigma, sigma),
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
        let sigma = radius_to_sigma(self.blur);
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

#[cfg(test)]
mod tests {
    use super::*;

    fn shadow(blur: f32, spread: f32, ox: f32, oy: f32) -> Shadow {
        Shadow::new(
            skia::Color::BLACK,
            blur,
            spread,
            (ox, oy),
            ShadowStyle::Drop,
            false,
        )
    }

    #[test]
    fn leaf_floor_at_moderate_zoom() {
        // blur 16 @ 0.13 ≈ 2.08px → keep leaf
        assert!(shadow(16.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.13, false));
        // blur 8 @ 0.13 ≈ 1.04px → skip leaf (below 2px)
        assert!(!shadow(8.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.13, false));
    }

    #[test]
    fn recursive_floor_is_stricter() {
        // blur 24 @ 0.13 ≈ 3.12px → keep leaf, skip recursive (needs 4px)
        let s = shadow(24.0, 0.0, 0.0, 0.0);
        assert!(s.is_perceptible_at_scale_for(0.13, false));
        assert!(!s.is_perceptible_at_scale_for(0.13, true));
        // blur 32 @ 0.13 ≈ 4.16px → keep recursive
        assert!(shadow(32.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.13, true));
    }

    #[test]
    fn overview_scale_vs_extent() {
        // At 0.038 even blur 50 is only ~1.9px — below leaf floor.
        assert!(!shadow(50.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.038, false));
        assert!(shadow(60.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.038, false));
    }
}
