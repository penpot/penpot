// Mandarin Zhuyin annotations: one Han base and one syllable per span.

use std::ops::Range;

use skia_safe::{self as skia, textlayout::TypefaceFontProvider, FontMgr};

use super::text_vertical::{shape_segment_with_fallbacks, span_font_families, ShapedRun};
use super::TextSpan;
use crate::render::FallbackFonts;

pub(crate) const FONT_SCALE: f32 = 0.3;
pub(crate) const STRIP_WIDTH: f32 = 0.6;
pub(crate) const DIRECTION_ZHUYIN: u32 = 5;

pub(crate) struct Slot {
    pub ch: char,
    pub range: Range<usize>,
    /// Centre of the glyph's ink, in base em, relative to the strip top-left.
    pub x: f32,
    pub y: f32,
}

pub(crate) struct Syllable {
    chars: Vec<char>,
    symbols: Vec<usize>,
    tone: Option<usize>,
}

impl Syllable {
    pub fn parse(reading: &str) -> Option<Self> {
        let chars: Vec<char> = reading.chars().take(5).collect();
        if chars.len() > 4 {
            return None;
        }
        let mut symbols = Vec::new();
        let mut tone = None;
        for (index, ch) in chars.iter().enumerate() {
            match ch {
                '\u{3105}'..='\u{3129}' => symbols.push(index),
                'ˉ' | 'ˊ' | 'ˇ' | 'ˋ' | '˙' if tone.is_none() => tone = Some(index),
                _ => return None,
            }
        }
        if !(1..=3).contains(&symbols.len())
            || tone.is_some_and(|i| i + 1 != chars.len() && !(i == 0 && chars[i] == '˙'))
        {
            return None;
        }
        Some(Self {
            chars,
            symbols,
            tone,
        })
    }

    pub fn slots(&self) -> Vec<Slot> {
        let neutral = self.tone.is_some_and(|i| self.chars[i] == '˙');
        let lead = if neutral { 1.0 / 15.0 } else { 0.0 };
        let top = (1.0 - self.symbols.len() as f32 * FONT_SCALE - lead) / 2.0;
        self.chars
            .iter()
            .enumerate()
            .map(|(i, ch)| {
                let (x, y) = if let Some(symbol) = self.symbols.iter().position(|s| *s == i) {
                    (
                        FONT_SCALE / 2.0,
                        top + lead + (symbol as f32 + 0.5) * FONT_SCALE,
                    )
                } else if neutral {
                    (FONT_SCALE / 2.0, top + lead / 2.0)
                } else {
                    (0.45, top + (self.symbols.len() as f32 - 1.0) * FONT_SCALE)
                };
                Slot {
                    ch: *ch,
                    range: i..i + 1,
                    x,
                    y,
                }
            })
            .collect()
    }
}

/// A Han grapheme, including an optional variation selector. Do not attach
/// a syllable to a group, Latin text, or an incomplete surrogate sequence.
pub(crate) fn single_han(text: &str) -> bool {
    let mut chars = text.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    matches!(first as u32, 0x3400..=0x9fff | 0xf900..=0xfaff | 0x20000..=0x323af)
        && chars.next().is_none_or(|ch| {
            matches!(ch as u32, 0xfe00..=0xfe0f | 0xe0100..=0xe01ef) && chars.next().is_none()
        })
}

pub(crate) fn syllable(span: &TextSpan) -> Option<Syllable> {
    (span.ruby_zhuyin && span.has_ruby() && single_han(&span.text))
        .then(|| Syllable::parse(span.ruby_text()))
        .flatten()
}

pub(crate) struct Glyph {
    pub run: ShapedRun,
    pub glyph: usize,
    pub range: Range<usize>,
    /// Drawing baseline relative to the base em's top-right corner.
    pub origin: skia::Point,
}

/// Shape each symbol upright, retaining each fallback font and source range.
/// Ink-centred placement also works for fonts with narrow spacing tone marks.
pub(crate) fn shape(
    span: &TextSpan,
    syllable: &Syllable,
    provider: &TypefaceFontProvider,
    fallbacks: &FallbackFonts,
) -> Vec<Glyph> {
    let manager = FontMgr::from(provider.clone());
    let leading = span.ruby.encode_utf16().count() - span.ruby.trim_start().encode_utf16().count();
    syllable
        .slots()
        .into_iter()
        .flat_map(|slot| {
            shape_segment_with_fallbacks(
                &slot.ch.to_string(),
                span.font_size * FONT_SCALE,
                &span_font_families(span, fallbacks),
                provider,
                false,
                span.font_features,
                &manager,
                &span.language,
            )
            .into_iter()
            .flat_map(move |run| {
                (0..run.glyphs.len()).map(move |glyph| {
                    let mut bounds = [skia::Rect::default()];
                    run.font.get_bounds(&[run.glyphs[glyph]], &mut bounds, None);
                    Glyph {
                        origin: skia::Point::new(
                            slot.x * span.font_size - bounds[0].center_x(),
                            slot.y * span.font_size - bounds[0].center_y(),
                        ),
                        run: run.clone(),
                        glyph,
                        range: leading + slot.range.start..leading + slot.range.end,
                    }
                })
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zhuyin_keeps_neutral_tone_above_and_other_tones_beside_the_last_symbol() {
        for reading in ["ㄓ", "ㄋㄧˇ", "ㄓㄨㄤˋ", "˙ㄉㄜ", "ㄉㄜ˙", "ㄏㄠˊ", "ㄇㄚˉ"]
        {
            let syllable = Syllable::parse(reading).expect(reading);
            let slots = syllable.slots();
            assert_eq!(slots.len(), reading.chars().count());
            let symbols: Vec<_> = slots.iter().filter(|s| s.ch >= '\u{3105}').collect();
            assert!(symbols.windows(2).all(|s| s[0].y < s[1].y));
            for tone in slots.iter().filter(|s| s.ch < '\u{3105}') {
                if tone.ch == '˙' {
                    assert!(tone.y < symbols[0].y);
                    assert_eq!(tone.x, symbols[0].x);
                } else {
                    assert!(tone.x > symbols.last().unwrap().x);
                    assert!(tone.y < symbols.last().unwrap().y);
                }
            }
        }
    }

    #[test]
    fn zhuyin_rejects_malformed_or_multiple_syllables_without_guessing() {
        for reading in [
            "",
            "nǐ",
            "ˇ",
            "ㄋˇㄧ",
            "ㄋㄧˇˋ",
            "ㄋ ㄧ",
            "ㄅㄆㄇㄈ",
            "ㄋ\nㄧ",
        ] {
            assert!(Syllable::parse(reading).is_none(), "{reading:?}");
        }
    }
}
