//! Typographic span and paragraph attributes shared by the horizontal and
//! vertical engines: writing mode, orientation, emphasis, decoration
//! positions, annotation clearance, font features, line adjustment and the
//! ruby presentation enums.

use crate::shapes::jlreq::classify;

pub const EMPHASIS_FONT_SCALE: f32 = 0.5;

/// Whether an emphasis mark goes on `character`: never on whitespace nor on
/// the punctuation JLREQ leaves unmarked.
pub(crate) fn emphasis_char_allowed(character: char) -> bool {
    !character.is_whitespace() && !classify(character).is_emphasis_prohibited()
}

/// Block flow direction of a paragraph. Horizontal uses skparagraph;
/// vertical-rl uses the custom vertical pass: columns top to bottom,
/// advancing right to left.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum WritingMode {
    #[default]
    HorizontalTb,
    VerticalRl,
}

/// How a line slightly too long for its box is fitted (JLREQ §3.8.1; Adobe's
/// Kinsoku Type). Squeezing reduces punctuation aki to keep the next
/// character; pushing out moves characters to the next line. Alignment then
/// decides what happens to the space a line leaves over.
// The variants are Adobe's Kinsoku Type values and the JS keys.
#[allow(clippy::enum_variant_names)]
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum LineAdjustment {
    /// Squeeze first; push out when the aki cannot absorb the overflow.
    #[default]
    PushInFirst,
    /// Push out first; squeeze only when kinsoku leaves no other break.
    PushOutFirst,
    /// Never squeeze.
    PushOutOnly,
}

/// Glyph orientation inside vertical flow: `Mixed` rotates non-CJK runs
/// sideways, `Upright` keeps every character upright. Ignored in
/// horizontal writing.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextOrientation {
    #[default]
    Mixed,
    Upright,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextCombineUpright {
    #[default]
    None,
    All,
    /// Runs of up to 4 ASCII or full-width digits combine into one upright
    /// cell, so a lone digit stands upright (CSS `digits`).
    Digits,
    /// Like `Digits`, for runs of up to 2 (CSS `digits 2`).
    Digits2,
    /// Like `Digits`, for runs of up to 3.
    Digits3,
}

impl TextCombineUpright {
    /// Longest digit run that combines, when digits mode is active.
    pub fn digits_max(self) -> Option<usize> {
        match self {
            TextCombineUpright::Digits => Some(4),
            TextCombineUpright::Digits2 => Some(2),
            TextCombineUpright::Digits3 => Some(3),
            _ => None,
        }
    }
}

/// Independent emphasis positions in the two writing directions.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextEmphasisPosition {
    #[default]
    Auto,
    OverRight,
    OverLeft,
    UnderRight,
    UnderLeft,
}

impl TextEmphasisPosition {
    pub fn side(self, vertical: bool, chinese: bool) -> AnnotationSide {
        let under = if vertical {
            matches!(self, Self::OverLeft | Self::UnderLeft)
        } else {
            matches!(self, Self::UnderRight | Self::UnderLeft) || (self == Self::Auto && chinese)
        };
        if under {
            AnnotationSide::Under
        } else {
            AnnotationSide::Over
        }
    }
}

/// CSS underline position: horizontal depth and vertical side are independent.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextUnderlinePosition {
    #[default]
    Auto,
    Under,
    Left,
    Right,
    UnderLeft,
    UnderRight,
}

impl TextUnderlinePosition {
    pub fn side(self) -> AnnotationSide {
        if matches!(self, Self::Right | Self::UnderRight) {
            AnnotationSide::Over
        } else {
            AnnotationSide::Under
        }
    }

    pub fn below_ink(self, chinese: bool) -> bool {
        matches!(self, Self::Under | Self::UnderLeft | Self::UnderRight)
            || (self == Self::Auto && chinese)
    }
}

/// Emphasis mark (圏点 / bouten) applied per span, mirroring CSS
/// `text-emphasis-style`. Position is a separate style with a language default.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextEmphasis {
    #[default]
    None,
    FilledDot,
    OpenDot,
    FilledCircle,
    OpenCircle,
    FilledSesame,
    OpenSesame,
}

impl TextEmphasis {
    pub fn is_none(self) -> bool {
        matches!(self, TextEmphasis::None)
    }

    /// The glyph drawn as the emphasis mark, following the CSS
    /// `text-emphasis-style` character mapping.
    pub fn mark_char(self) -> Option<char> {
        match self {
            TextEmphasis::None => None,
            TextEmphasis::FilledDot => Some('•'),
            TextEmphasis::OpenDot => Some('◦'),
            TextEmphasis::FilledCircle => Some('●'),
            TextEmphasis::OpenCircle => Some('○'),
            TextEmphasis::FilledSesame => Some('﹅'),
            TextEmphasis::OpenSesame => Some('﹆'),
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum FontFeatures {
    #[default]
    None,
    Palt,
    Vpal,
}

/// Whether ruby and emphasis on the same side stack. `Auto` places emphasis
/// outside over-side ruby and reserves room for both; `None` lets them share
/// one layer.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum AnnotationClearance {
    #[default]
    None,
    Auto,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubySize {
    #[default]
    Half,
    Third,
    Quarter,
}

impl RubySize {
    pub fn scale(self) -> f32 {
        match self {
            Self::Half => 0.5,
            Self::Third => 1.0 / 3.0,
            Self::Quarter => 0.25,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyAlign {
    #[default]
    SpaceAround,
    Center,
    Start,
    SpaceBetween,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyOverhang {
    #[default]
    Auto,
    None,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum AnnotationSide {
    #[default]
    Over,
    Under,
}

impl AnnotationClearance {
    pub fn is_auto(self) -> bool {
        matches!(self, AnnotationClearance::Auto)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emphasis_excludes_whitespace_and_japanese_punctuation() {
        for character in " \t\n、。，．「」『』（）［］【】〔〕〈〉《》‘’“”".chars()
        {
            assert!(
                !emphasis_char_allowed(character),
                "emphasis must skip {character:?}"
            );
        }
        for character in "漢あA1・！？".chars() {
            assert!(
                emphasis_char_allowed(character),
                "emphasis should mark {character:?}"
            );
        }
    }
}
