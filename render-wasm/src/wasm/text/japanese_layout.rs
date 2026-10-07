use crate::shapes::{
    self, AnnotationClearance, FontFeatures, LineAdjustment, RubyAlign, RubyOverhang, RubySide,
    RubySize, TextCombineUpright, TextEmphasis,
};
use macros::ToJs;

/// Reads a style byte as `$raw`, falling back to `$default` when the byte
/// names no variant, so a bad byte from the CLJS writer never becomes an
/// invalid enum value.
macro_rules! raw_enum_from_byte {
    ($raw:ident, $default:ident, [$($variant:ident),+ $(,)?]) => {
        impl From<u8> for $raw {
            fn from(value: u8) -> Self {
                [$($raw::$variant),+]
                    .into_iter()
                    .find(|variant| *variant as u8 == value)
                    .unwrap_or($raw::$default)
            }
        }
    };
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawWritingMode {
    HorizontalTb = 0,
    VerticalRl = 1,
}

raw_enum_from_byte!(RawWritingMode, HorizontalTb, [HorizontalTb, VerticalRl]);

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
pub enum RawTextOrientation {
    Mixed = 0,
    Upright = 1,
}

raw_enum_from_byte!(RawTextOrientation, Mixed, [Mixed, Upright]);

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
pub enum RawTextCombineUpright {
    None = 0,
    All = 1,
    Digits = 2,
    Digits2 = 3,
    Digits3 = 4,
}

raw_enum_from_byte!(
    RawTextCombineUpright,
    None,
    [None, All, Digits, Digits2, Digits3]
);

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
pub enum RawTextEmphasis {
    None = 0,
    FilledDot = 1,
    OpenDot = 2,
    FilledCircle = 3,
    OpenCircle = 4,
    FilledSesame = 5,
    OpenSesame = 6,
}

raw_enum_from_byte!(
    RawTextEmphasis,
    None,
    [
        None,
        FilledDot,
        OpenDot,
        FilledCircle,
        OpenCircle,
        FilledSesame,
        OpenSesame
    ]
);

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
pub enum RawWarichu {
    None = 0,
    Warichu = 1,
}

raw_enum_from_byte!(RawWarichu, None, [None, Warichu]);

impl From<RawWarichu> for bool {
    fn from(value: RawWarichu) -> Self {
        value == RawWarichu::Warichu
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawFontFeatures {
    None = 0,
    Palt = 1,
    Vpal = 2,
}

raw_enum_from_byte!(RawFontFeatures, None, [None, Palt, Vpal]);

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
pub enum RawAnnotationClearance {
    None = 0,
    Auto = 1,
}

raw_enum_from_byte!(RawAnnotationClearance, None, [None, Auto]);

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
pub enum RawRubySize {
    Half = 0,
    Third = 1,
    Quarter = 2,
}

raw_enum_from_byte!(RawRubySize, Half, [Half, Third, Quarter]);

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
pub enum RawRubyAlign {
    SpaceAround = 0,
    Center = 1,
    Start = 2,
    SpaceBetween = 3,
}

raw_enum_from_byte!(
    RawRubyAlign,
    SpaceAround,
    [SpaceAround, Center, Start, SpaceBetween]
);

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
pub enum RawRubyOverhang {
    Auto = 0,
    None = 1,
}

raw_enum_from_byte!(RawRubyOverhang, Auto, [Auto, None]);

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
pub enum RawRubySide {
    Over = 0,
    Under = 1,
}

raw_enum_from_byte!(RawRubySide, Over, [Over, Under]);

impl From<RawRubySide> for RubySide {
    fn from(value: RawRubySide) -> Self {
        match value {
            RawRubySide::Over => RubySide::Over,
            RawRubySide::Under => RubySide::Under,
        }
    }
}

// The variants are Adobe's Kinsoku Type values and the JS keys.
#[allow(clippy::enum_variant_names)]
#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawLineAdjustment {
    PushInFirst = 0,
    PushOutFirst = 1,
    PushOutOnly = 2,
}

raw_enum_from_byte!(
    RawLineAdjustment,
    PushInFirst,
    [PushInFirst, PushOutFirst, PushOutOnly]
);

impl From<RawLineAdjustment> for LineAdjustment {
    fn from(value: RawLineAdjustment) -> Self {
        match value {
            RawLineAdjustment::PushInFirst => LineAdjustment::PushInFirst,
            RawLineAdjustment::PushOutFirst => LineAdjustment::PushOutFirst,
            RawLineAdjustment::PushOutOnly => LineAdjustment::PushOutOnly,
        }
    }
}
