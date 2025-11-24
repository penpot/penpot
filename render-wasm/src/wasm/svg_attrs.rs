use macros::ToJs;

use crate::shapes::{FillRule, StrokeLineCap, StrokeLineJoin, SvgAttrs};
use crate::{with_current_shape_mut, STATE};

#[derive(PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawFillRule {
    Nonzero = 0,
    Evenodd = 1,
}

impl From<u8> for RawFillRule {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawFillRule> for FillRule {
    fn from(value: RawFillRule) -> Self {
        match value {
            RawFillRule::Nonzero => FillRule::Nonzero,
            RawFillRule::Evenodd => FillRule::Evenodd,
        }
    }
}

#[derive(PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawStrokeLineCap {
    Butt = 0,
    Round = 1,
    Square = 2,
}

impl From<u8> for RawStrokeLineCap {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawStrokeLineCap> for StrokeLineCap {
    fn from(value: RawStrokeLineCap) -> Self {
        match value {
            RawStrokeLineCap::Butt => StrokeLineCap::Butt,
            RawStrokeLineCap::Round => StrokeLineCap::Round,
            RawStrokeLineCap::Square => StrokeLineCap::Square,
        }
    }
}

#[derive(PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawStrokeLineJoin {
    Miter = 0,
    Round = 1,
    Bevel = 2,
}

impl From<u8> for RawStrokeLineJoin {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawStrokeLineJoin> for StrokeLineJoin {
    fn from(value: RawStrokeLineJoin) -> Self {
        match value {
            RawStrokeLineJoin::Miter => StrokeLineJoin::Miter,
            RawStrokeLineJoin::Round => StrokeLineJoin::Round,
            RawStrokeLineJoin::Bevel => StrokeLineJoin::Bevel,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_svg_attrs(
    fill_rule: u8,
    stroke_linecap: u8,
    stroke_linejoin: u8,
    fill_none: bool,
) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.svg_attrs = Some(SvgAttrs::from_raw(
            fill_rule,
            stroke_linecap,
            stroke_linejoin,
            fill_none,
        ));
    });
}
