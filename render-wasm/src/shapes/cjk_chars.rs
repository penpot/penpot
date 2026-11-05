//! Character ranges and punctuation sets of CJK text, shared by the layout
//! policies, the JLREQ classes, Zhuyin and font fallback. Where two users
//! need different ranges on purpose, each keeps its own function here with
//! a note on why. The JLREQ class tables (`jlreq`) and the vertical upright
//! table (`text_shaping::is_upright_char`) follow their own references and
//! stay with their users.

/// Han ideographs of the BMP unified blocks: Extension A and the URO.
pub fn is_bmp_unified_han(c: char) -> bool {
    matches!(u32::from(c), 0x3400..=0x4DBF | 0x4E00..=0x9FFF)
}

/// Han ideographs for the Chinese and Korean presets: the BMP unified
/// blocks, the compatibility ideographs and Extensions B to H.
pub fn is_han(c: char) -> bool {
    is_bmp_unified_han(c) || matches!(u32::from(c), 0xF900..=0xFAFF | 0x20000..=0x323AF)
}

/// A base a Zhuyin reading may annotate. Besides `is_han`, it takes the
/// Yijing hexagram symbols (U+4DC0..U+4DFF) between Extension A and the URO,
/// which the Zhuyin check has always accepted, so saved readings keep their
/// mode.
pub fn is_zhuyin_base(c: char) -> bool {
    is_han(c) || matches!(u32::from(c), 0x4DC0..=0x4DFF)
}

/// Assigned hiragana, including the voiced marks and the iteration marks.
pub fn is_hiragana(c: char) -> bool {
    matches!(u32::from(c), 0x3041..=0x309F)
}

/// Katakana, its phonetic extensions and half-width katakana.
pub fn is_katakana(c: char) -> bool {
    matches!(u32::from(c), 0x30A0..=0x30FF | 0x31F0..=0x31FF | 0xFF66..=0xFF9F)
}

/// Kana that mark untagged text as Japanese for font choice: the whole
/// Hiragana block (with its unassigned U+3040) and `is_katakana`.
pub fn is_kana(c: char) -> bool {
    u32::from(c) == 0x3040 || is_hiragana(c) || is_katakana(c)
}

/// Hangul syllables, conjoining jamo and compatibility jamo.
pub fn is_hangul(c: char) -> bool {
    matches!(u32::from(c), 0x1100..=0x11FF | 0x3130..=0x318F | 0xAC00..=0xD7AF)
}

/// Full-width opening brackets with a removable leading half-em.
pub const FULLWIDTH_OPENING_BRACKETS: &str = "（［｛〈《「『【〔〖〘〚";

/// Full-width closing brackets with a removable trailing half-em.
pub const FULLWIDTH_CLOSING_BRACKETS: &str = "）］｝〉》」』】〕〗〙〛";

/// Full-width commas and full stops.
pub const COMMAS_AND_FULL_STOPS: &str = "，。、．";

/// CJK and ASCII punctuation, brackets and quotes. A comma or full stop next
/// to one of these does not hang alone.
pub fn is_punctuation(c: char) -> bool {
    COMMAS_AND_FULL_STOPS.contains(c)
        || FULLWIDTH_OPENING_BRACKETS.contains(c)
        || FULLWIDTH_CLOSING_BRACKETS.contains(c)
        || "！？：；…‥—―‘’“”.,;!?:()[]{}".contains(c)
}
