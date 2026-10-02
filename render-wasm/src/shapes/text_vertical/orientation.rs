use crate::shapes::TextOrientation;

/// Emoji ranges recognized by the frontend font-loader. Emoji stay upright
/// in vertical flow; Latin text rotates under `text-orientation: mixed`.
pub(super) fn is_emoji_char(c: char) -> bool {
    matches!(u32::from(c),
        0x2300..=0x23FF
        | 0x2600..=0x27BF
        | 0x2B00..=0x2BFF
        | 0x1F000..=0x1FAFF
    )
}

/// Characters that stay upright in vertical flow: kana, kanji, CJK
/// punctuation, full-width forms and emoji. Everything else rotates sideways
/// under `text-orientation: mixed`.
pub(super) fn is_upright_char(c: char) -> bool {
    is_emoji_char(c)
        || matches!(u32::from(c),
        0x2E80..=0x2FDF   // CJK radicals, Kangxi radicals
        | 0x3000..=0x303F // CJK symbols and punctuation
        | 0x3040..=0x30FF // hiragana, katakana
        | 0x31C0..=0x31EF // CJK strokes
        | 0x31F0..=0x31FF // katakana phonetic extensions
        | 0x3200..=0x33FF // enclosed CJK, CJK compatibility
        | 0x3400..=0x4DBF // CJK unified ideographs extension A
        | 0x4E00..=0x9FFF // CJK unified ideographs
        | 0xAC00..=0xD7AF // hangul syllables
        | 0xF900..=0xFAFF // CJK compatibility ideographs
        | 0xFE30..=0xFE4F // CJK compatibility forms
        | 0xFF00..=0xFF60 // full-width forms
        | 0xFFE0..=0xFFE6 // full-width signs
        | 0x20000..=0x2FA1F // CJK extensions B..F
        )
}

/// Characters whose horizontal glyph needs a vertical alternate, normally
/// from `vert` / `vrt2`. When the face has no such substitution, the
/// horizontal glyph rotates clockwise as a fallback. Covers UAX #50 `Tr`,
/// plus comma/full-stop punctuation whose plain glyph sits in the wrong
/// half of the vertical em box.
pub(super) fn uses_rotated_vertical_fallback(c: char) -> bool {
    matches!(u32::from(c),
        0x2018..=0x2019 // single quotation marks
        | 0x201C..=0x201D // double quotation marks
        | 0x2329..=0x232A // angle brackets
        | 0x3001..=0x3002 // ideographic comma / full stop
        | 0x3008..=0x301F // CJK brackets, wave dash and quotation marks
        | 0x3030 // wavy dash
        | 0x30A0 // katakana-hiragana double hyphen
        | 0x30FC // prolonged sound mark
        | 0xFE50..=0xFE52 // small comma / ideographic comma / full stop
        | 0xFE59..=0xFE5E // small brackets
        | 0xFF08..=0xFF09 // full-width parentheses
        | 0xFF0C // full-width comma
        | 0xFF0D // full-width hyphen-minus
        | 0xFF0E // full-width full stop
        | 0xFF1A..=0xFF1B // full-width colon / semicolon
        | 0xFF1C..=0xFF1E // full-width comparison signs
        | 0xFF3B // full-width left square bracket
        | 0xFF3D // full-width right square bracket
        | 0xFF3F // full-width low line
        | 0xFF5B..=0xFF60 // full-width braces, bars, tilde and white parentheses
        | 0xFFE3 // full-width macron
    )
}

#[derive(Debug, PartialEq)]
pub(super) struct Segment {
    pub text: String,
    /// UTF-16 offset of the segment start within its span text.
    pub utf16_start: usize,
    pub upright: bool,
}

/// Split text into maximal runs of same orientation. Under
/// `TextOrientation::Upright` every character is upright.
pub(super) fn segment_by_orientation(text: &str, orientation: TextOrientation) -> Vec<Segment> {
    let mut segments: Vec<Segment> = Vec::new();
    let mut utf16_offset = 0;
    for c in text.chars() {
        let upright = orientation == TextOrientation::Upright || is_upright_char(c);
        let emoji = is_emoji_char(c);
        // CJK and emoji are both upright, but split them so the segment
        // probe can select the emoji family: the provider's generic fallback
        // iterator does not reliably switch from a CJK face to a color-emoji
        // face.
        match segments.last_mut() {
            Some(last)
                if last.upright == upright
                    && last.text.chars().next_back().is_some_and(is_emoji_char) == emoji =>
            {
                last.text.push(c)
            }
            _ => segments.push(Segment {
                text: c.to_string(),
                utf16_start: utf16_offset,
                upright,
            }),
        }
        utf16_offset += c.len_utf16();
    }
    segments
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upright_classification() {
        assert!(is_upright_char('あ'));
        assert!(is_upright_char('ア'));
        assert!(is_upright_char('漢'));
        assert!(is_upright_char('。'));
        assert!(is_upright_char('「'));
        assert!(is_upright_char('ー'));
        assert!(is_upright_char('！'));
        assert!(!is_upright_char('A'));
        assert!(!is_upright_char('1'));
        assert!(!is_upright_char(' '));
        assert!(!is_upright_char('.'));
    }

    #[test]
    fn emoji_stay_upright_in_vertical_text() {
        for emoji in ['😆', '💦', '🙇'] {
            assert!(is_upright_char(emoji));
        }

        let segments = segment_by_orientation("久😆元💦🙇", TextOrientation::Mixed);
        assert_eq!(segments.len(), 4);
        assert!(segments.iter().all(|segment| segment.upright));
        assert_eq!(segments[1].text, "😆");
        assert_eq!(segments[3].text, "💦🙇");
    }

    #[test]
    fn transformed_japanese_punctuation_has_a_rotated_font_fallback() {
        for character in [
            '「', '」', '『', '』', '（', '）', '［', '］', '【', '】', '、', '。', 'ー', '〜',
            '：', '；',
        ] {
            assert!(
                uses_rotated_vertical_fallback(character),
                "{character} needs a transformed vertical glyph"
            );
        }
        assert!(!uses_rotated_vertical_fallback('あ'));
        assert!(!uses_rotated_vertical_fallback('漢'));
        assert!(!uses_rotated_vertical_fallback('！'));
    }

    #[test]
    fn segments_mixed_text() {
        let segments = segment_by_orientation("縦書きABC123です", TextOrientation::Mixed);
        assert_eq!(segments.len(), 3);
        assert_eq!(segments[0].text, "縦書き");
        assert!(segments[0].upright);
        assert_eq!(segments[0].utf16_start, 0);
        assert_eq!(segments[1].text, "ABC123");
        assert!(!segments[1].upright);
        assert_eq!(segments[1].utf16_start, 3);
        assert_eq!(segments[2].text, "です");
        assert!(segments[2].upright);
        assert_eq!(segments[2].utf16_start, 9);
    }

    #[test]
    fn segments_upright_orientation_keeps_latin_upright() {
        let segments = segment_by_orientation("縦ABC", TextOrientation::Upright);
        assert_eq!(segments.len(), 1);
        assert!(segments[0].upright);
    }

    #[test]
    fn segments_empty_text() {
        assert!(segment_by_orientation("", TextOrientation::Mixed).is_empty());
    }
}
