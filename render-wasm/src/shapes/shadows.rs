use skia_safe::{self as skia, image_filters, ImageFilter, Paint};

use super::blurs::{radius_to_sigma, sigma_to_radius};
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

    /// Same shadow as [`Self::get_drop_shadow_filter`], for a `merge` input.
    /// Skia defers color filters and offsets, and `merge` can draw them
    /// unresolved in 8×8 cells (untinted source, or opaque black). A `blend`
    /// always renders, so the tint is a SrcIn blend of the color over the
    /// offset source.
    pub fn get_layer_drop_shadow_filter(&self) -> Option<ImageFilter> {
        let color = image_filters::shader(skia::shaders::color(self.color), None);
        let offset = image_filters::offset((self.offset.0, self.offset.1), None, None);
        let mut filter = image_filters::blend(skia::BlendMode::SrcIn, offset, color, None);

        // Spread before blur, as in CSS and SVG. Dilating the blur instead
        // works on Skia's downscaled blur output and comes out blocky.
        if self.spread > 0. {
            // Masked-group shadows are already in device space.
            filter = Self::chained_dilate(self.spread, filter);
        }

        let sigma = radius_to_sigma(self.blur);
        if sigma > 0.0 {
            filter = image_filters::blur((sigma, sigma), None, filter, None);
        }

        filter
    }

    /// Square dilate by a device-space `radius`, split into steps Skia will
    /// not clamp. Skia caps a single morphology radius at 256 px; square
    /// dilates add up exactly, so chaining keeps the full spread.
    fn chained_dilate(radius: f32, input: Option<ImageFilter>) -> Option<ImageFilter> {
        const MAX_RADIUS: f32 = 255.0;
        let steps = (radius / MAX_RADIUS).ceil().max(1.0) as usize;
        let step = radius / steps as f32;
        let mut filter = input;
        for _ in 0..steps {
            filter = image_filters::dilate((step, step), filter, None);
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

    /// Scales this shadow into device units, for a filter built on a canvas
    /// that carries no transform of its own.
    ///
    /// Not the same as [`Self::scale_content`]. `radius_to_sigma` is affine
    /// (`k·r + 0.5`), so scaling the radius applies its constant term once at
    /// device scale, while a filter built in document space has that term
    /// scaled by the canvas matrix along with everything else. The radius is
    /// pre-compensated here so both land on the same sigma — otherwise the
    /// same shadow blurs differently depending on which path drew it, by
    /// `0.5 · (scale - 1)` sigma.
    pub fn scale_to_device(&mut self, scale: f32) {
        let device_sigma = radius_to_sigma(self.blur) * scale;
        self.scale_content(scale);
        self.blur = sigma_to_radius(device_sigma);
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

    /// A filter built on an untransformed canvas must reach the same sigma a
    /// document-space filter does once the canvas matrix scales it, or the same
    /// shadow blurs differently depending on which path drew it.
    #[test]
    fn scale_to_device_matches_a_document_space_sigma() {
        for scale in [0.5_f32, 1.0, 2.0, 4.0, 8.0] {
            let original = shadow(10.0, 6.0, 3.0, -2.0);
            let mut device = original;
            device.scale_to_device(scale);

            assert!(
                (radius_to_sigma(device.blur) - radius_to_sigma(original.blur) * scale).abs()
                    < 0.001,
                "sigma disagreement at scale {scale}"
            );

            // Spread and offset are linear, so they scale straight through.
            assert!((device.spread - original.spread * scale).abs() < 0.001);
            assert!((device.offset.0 - original.offset.0 * scale).abs() < 0.001);
            assert!((device.offset.1 - original.offset.1 * scale).abs() < 0.001);
        }
    }

    /// Scaling the radius instead would apply the affine constant once at
    /// device scale, blurring narrower by `0.5 · (scale - 1)` sigma.
    #[test]
    fn scale_to_device_differs_from_scale_content_above_unit_scale() {
        let mut device = shadow(10.0, 0.0, 0.0, 0.0);
        device.scale_to_device(4.0);
        let mut naive = shadow(10.0, 0.0, 0.0, 0.0);
        naive.scale_content(4.0);

        let gap = radius_to_sigma(device.blur) - radius_to_sigma(naive.blur);
        assert!(
            (gap - 0.5 * 3.0).abs() < 0.001,
            "expected 1.5 sigma, got {gap}"
        );
    }

    #[test]
    fn chained_dilate_reaches_the_full_radius() {
        let rect = skia::Rect::from_xywh(0.0, 0.0, 10.0, 10.0);
        // 600 px is three steps of 200 px.
        let filter = Shadow::chained_dilate(600.0, None).expect("dilate");
        let bounds = filter.compute_fast_bounds(rect);
        assert!((bounds.left + 600.0).abs() < 0.01);
        assert!((bounds.right - 610.0).abs() < 0.01);
    }

    #[test]
    fn layer_drop_shadow_filter_matches_drop_shadow_bounds() {
        let rect = skia::Rect::from_xywh(0.0, 0.0, 100.0, 50.0);
        for (blur, spread, ox, oy) in [
            (0.0, 0.0, 4.0, 4.0),
            (4.0, 0.0, 4.0, 4.0),
            (12.0, 6.0, -3.0, 8.0),
        ] {
            let s = shadow(blur, spread, ox, oy);
            let expected = s
                .get_drop_shadow_filter()
                .expect("drop shadow filter")
                .compute_fast_bounds(rect);
            let actual = s
                .get_layer_drop_shadow_filter()
                .expect("layer drop shadow filter")
                .compute_fast_bounds(rect);
            assert!(
                (expected.left - actual.left).abs() < 0.01
                    && (expected.top - actual.top).abs() < 0.01
                    && (expected.right - actual.right).abs() < 0.01
                    && (expected.bottom - actual.bottom).abs() < 0.01,
                "bounds differ for blur {blur}: {expected:?} vs {actual:?}"
            );
        }
    }

    #[test]
    fn overview_scale_vs_extent() {
        // At 0.038 even blur 50 is only ~1.9px — below leaf floor.
        assert!(!shadow(50.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.038, false));
        assert!(shadow(60.0, 0.0, 0.0, 0.0).is_perceptible_at_scale_for(0.038, false));
    }
}
