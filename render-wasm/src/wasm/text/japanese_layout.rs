use crate::shapes::{
    self, AnnotationClearance, FontFeatures, RubyAlign, RubyOverhang, RubySide, RubySize,
    TextCombineUpright, TextEmphasis,
};
use macros::ToJs;

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawWritingMode {
    HorizontalTb = 0,
    VerticalRl = 1,
}

impl From<RawWritingMode> for shapes::WritingMode {
    fn from(value: RawWritingMode) -> Self {
        match value {
            RawWritingMode::HorizontalTb => shapes::WritingMode::HorizontalTb,
            RawWritingMode::VerticalRl => shapes::WritingMode::VerticalRl,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextOrientation {
    Mixed = 0,
    Upright = 1,
}

impl From<RawTextOrientation> for shapes::TextOrientation {
    fn from(value: RawTextOrientation) -> Self {
        match value {
            RawTextOrientation::Mixed => shapes::TextOrientation::Mixed,
            RawTextOrientation::Upright => shapes::TextOrientation::Upright,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextCombineUpright {
    None = 0,
    All = 1,
    Digits = 2,
    Digits2 = 3,
    Digits3 = 4,
}

impl From<RawTextCombineUpright> for TextCombineUpright {
    fn from(value: RawTextCombineUpright) -> Self {
        match value {
            RawTextCombineUpright::None => TextCombineUpright::None,
            RawTextCombineUpright::All => TextCombineUpright::All,
            RawTextCombineUpright::Digits => TextCombineUpright::Digits,
            RawTextCombineUpright::Digits2 => TextCombineUpright::Digits2,
            RawTextCombineUpright::Digits3 => TextCombineUpright::Digits3,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextEmphasis {
    None = 0,
    FilledDot = 1,
    OpenDot = 2,
    FilledCircle = 3,
    OpenCircle = 4,
    FilledSesame = 5,
    OpenSesame = 6,
}

impl From<RawTextEmphasis> for TextEmphasis {
    fn from(value: RawTextEmphasis) -> Self {
        match value {
            RawTextEmphasis::None => TextEmphasis::None,
            RawTextEmphasis::FilledDot => TextEmphasis::FilledDot,
            RawTextEmphasis::OpenDot => TextEmphasis::OpenDot,
            RawTextEmphasis::FilledCircle => TextEmphasis::FilledCircle,
            RawTextEmphasis::OpenCircle => TextEmphasis::OpenCircle,
            RawTextEmphasis::FilledSesame => TextEmphasis::FilledSesame,
            RawTextEmphasis::OpenSesame => TextEmphasis::OpenSesame,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawWarichu {
    None = 0,
    Warichu = 1,
}

impl From<RawWarichu> for bool {
    fn from(value: RawWarichu) -> Self {
        value == RawWarichu::Warichu
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawFontFeatures {
    None = 0,
    Palt = 1,
    Vpal = 2,
}

impl From<RawFontFeatures> for FontFeatures {
    fn from(value: RawFontFeatures) -> Self {
        match value {
            RawFontFeatures::None => FontFeatures::None,
            RawFontFeatures::Palt => FontFeatures::Palt,
            RawFontFeatures::Vpal => FontFeatures::Vpal,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAnnotationClearance {
    None = 0,
    Auto = 1,
}

impl From<RawAnnotationClearance> for AnnotationClearance {
    fn from(value: RawAnnotationClearance) -> Self {
        match value {
            RawAnnotationClearance::None => AnnotationClearance::None,
            RawAnnotationClearance::Auto => AnnotationClearance::Auto,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubySize {
    Half = 0,
    Third = 1,
    Quarter = 2,
}

impl From<RawRubySize> for RubySize {
    fn from(value: RawRubySize) -> Self {
        match value {
            RawRubySize::Half => RubySize::Half,
            RawRubySize::Third => RubySize::Third,
            RawRubySize::Quarter => RubySize::Quarter,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubyAlign {
    SpaceAround = 0,
    Center = 1,
    Start = 2,
    SpaceBetween = 3,
}

impl From<RawRubyAlign> for RubyAlign {
    fn from(value: RawRubyAlign) -> Self {
        match value {
            RawRubyAlign::SpaceAround => RubyAlign::SpaceAround,
            RawRubyAlign::Center => RubyAlign::Center,
            RawRubyAlign::Start => RubyAlign::Start,
            RawRubyAlign::SpaceBetween => RubyAlign::SpaceBetween,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubyOverhang {
    Auto = 0,
    None = 1,
}

impl From<RawRubyOverhang> for RubyOverhang {
    fn from(value: RawRubyOverhang) -> Self {
        match value {
            RawRubyOverhang::Auto => RubyOverhang::Auto,
            RawRubyOverhang::None => RubyOverhang::None,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubySide {
    Over = 0,
    Under = 1,
}

impl From<RawRubySide> for RubySide {
    fn from(value: RawRubySide) -> Self {
        match value {
            RawRubySide::Over => RubySide::Over,
            RawRubySide::Under => RubySide::Under,
        }
    }
}
