use crate::wasm::svg_attrs::{RawFillRule, RawStrokeLineCap, RawStrokeLineJoin};

#[derive(Debug, Clone, PartialEq, Copy, Default)]
pub enum FillRule {
    #[default]
    Nonzero,
    Evenodd,
}

#[derive(Debug, Clone, PartialEq, Copy, Default)]
pub enum StrokeLineCap {
    #[default]
    Butt,
    Round,
    Square,
}

#[derive(Debug, Clone, PartialEq, Copy, Default)]
pub enum StrokeLineJoin {
    #[default]
    Miter,
    Round,
    Bevel,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct SvgAttrs {
    pub fill_rule: FillRule,
    pub stroke_linecap: StrokeLineCap,
    pub stroke_linejoin: StrokeLineJoin,
    /// Indicates that this shape has an explicit `fill="none"` attribute.
    ///
    /// In SVG, the `fill` attribute is inheritable from container elements like `<g>`.
    /// However, when a shape explicitly sets `fill="none"`, it breaks the color
    /// inheritance chain - the shape will not inherit fill colors from parent containers.
    ///
    /// This is different from having an empty fills array, as it explicitly signals
    /// the intention to have no fill, preventing inheritance.
    pub fill_none: bool,
}

impl Default for SvgAttrs {
    fn default() -> Self {
        Self {
            fill_rule: FillRule::Nonzero,
            stroke_linecap: StrokeLineCap::Butt,
            stroke_linejoin: StrokeLineJoin::Miter,
            fill_none: false,
        }
    }
}

impl SvgAttrs {
    pub fn from_raw(
        fill_rule: u8,
        stroke_linecap: u8,
        stroke_linejoin: u8,
        fill_none: bool,
    ) -> Self {
        Self {
            fill_rule: RawFillRule::from(fill_rule).into(),
            stroke_linecap: RawStrokeLineCap::from(stroke_linecap).into(),
            stroke_linejoin: RawStrokeLineJoin::from(stroke_linejoin).into(),
            fill_none,
        }
    }
}
