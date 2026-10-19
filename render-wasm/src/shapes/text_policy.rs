//! Paragraph typography policies. Geometry and source offsets are shared.
//! Regional presets use CLREQ (2026-10-07); Korean restrictions use KLREQ
//! (2026-03-21). These are named presets, not universal regional mandates.

use super::japanese::break_allowed_between;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum KoreanWrap {
    #[default]
    Syllable = 0,
    /// Preserve the original experiment when old content is reopened.
    SpacesOnly = 1,
    KeepWords = 2,
}

impl From<u8> for KoreanWrap {
    fn from(value: u8) -> Self {
        match value {
            1 => Self::SpacesOnly,
            2 => Self::KeepWords,
            _ => Self::Syllable,
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum TextLayoutPolicy {
    #[default]
    Plain,
    Japanese,
    /// Original basic preset, retained for tags without a region.
    Chinese,
    ChineseMainland,
    ChineseTaiwan,
    ChineseHongKong,
    KoreanSyllable,
    KoreanSpacesOnly,
    KoreanWords,
}

pub fn is_han(ch: char) -> bool {
    matches!(ch as u32, 0x3400..=0x4dbf | 0x4e00..=0x9fff | 0xf900..=0xfaff | 0x20000..=0x323af)
}

fn numeric(ch: char) -> bool {
    ch.is_numeric() || "一二三四五六七八九十百千萬万億亿〇零".contains(ch)
}

fn opening(ch: char) -> bool {
    "（［｛〈《「『【〔〖〘〚‘“([{".contains(ch)
}

fn closing(ch: char) -> bool {
    "，。、．！？：；）］｝〉》」』】〕〗〙〛’”)]},.!?:;".contains(ch)
}

fn protected(ch: char) -> bool {
    matches!(
        ch,
        '\u{00a0}' | '\u{202f}' | '\u{2060}' | '\u{feff}' | '\u{2011}'
    )
}

impl TextLayoutPolicy {
    pub fn for_language(language: &str, korean_spaces_only: bool) -> Self {
        Self::for_language_with_wrap(
            language,
            if korean_spaces_only {
                KoreanWrap::SpacesOnly
            } else {
                KoreanWrap::Syllable
            },
        )
    }

    pub fn for_language_with_wrap(language: &str, wrap: KoreanWrap) -> Self {
        let mut parts = language.split('-');
        let primary = parts.next().unwrap_or_default();
        if primary.eq_ignore_ascii_case("ja") {
            Self::Japanese
        } else if primary.eq_ignore_ascii_case("zh") {
            // Region precedes extensions/private use; script never implies region.
            let region = parts.take_while(|part| part.len() != 1).find(|part| {
                part.len() == 2 || (part.len() == 3 && part.bytes().all(|b| b.is_ascii_digit()))
            });
            match region {
                Some(region)
                    if region.eq_ignore_ascii_case("CN") || region.eq_ignore_ascii_case("SG") =>
                {
                    Self::ChineseMainland
                }
                Some(region) if region.eq_ignore_ascii_case("TW") => Self::ChineseTaiwan,
                Some(region)
                    if region.eq_ignore_ascii_case("HK") || region.eq_ignore_ascii_case("MO") =>
                {
                    Self::ChineseHongKong
                }
                _ => Self::Chinese,
            }
        } else if primary.eq_ignore_ascii_case("ko") {
            match wrap {
                KoreanWrap::Syllable => Self::KoreanSyllable,
                KoreanWrap::SpacesOnly => Self::KoreanSpacesOnly,
                KoreanWrap::KeepWords => Self::KoreanWords,
            }
        } else {
            Self::Plain
        }
    }

    pub fn is_chinese(self) -> bool {
        matches!(
            self,
            Self::Chinese | Self::ChineseMainland | Self::ChineseTaiwan | Self::ChineseHongKong
        )
    }

    pub fn is_regional_chinese(self) -> bool {
        matches!(
            self,
            Self::ChineseMainland | Self::ChineseTaiwan | Self::ChineseHongKong
        )
    }

    pub fn is_korean(self) -> bool {
        matches!(
            self,
            Self::KoreanSyllable | Self::KoreanSpacesOnly | Self::KoreanWords
        )
    }

    /// Even emergency wrapping must retain punctuation, nonbreaking controls,
    /// numeric expressions and paired marks. Grapheme/annotation atomicity is
    /// enforced by the caller on the complete paragraph, across style runs.
    pub fn emergency_break_allowed(self, before: char, after: char) -> bool {
        if matches!(self, Self::Plain | Self::KoreanSpacesOnly) {
            return true;
        }
        !protected(before)
            && !protected(after)
            && !opening(before)
            && !closing(after)
            && !(self.is_korean() && "‐‑–-・·々〻ー".contains(after))
            && !(before == after && matches!(after, '…' | '‥' | '—' | '―'))
            && !("$＄£€₩￥¥₽₺₫+＋-－−".contains(before) && numeric(after))
            && !(numeric(before) && "%％‰℃℉°".contains(after))
            && !(numeric(before) && matches!(after, '.' | ',' | '．' | '，'))
            && !(matches!(before, '.' | ',' | '．' | '，') && numeric(after))
    }

    /// Legal boundary between graphemes, before fitting a line or column.
    pub fn break_allowed(self, before: char, after: char) -> bool {
        match self {
            Self::Japanese => break_allowed_between(before, after),
            Self::KoreanSpacesOnly => before.is_whitespace() || after.is_whitespace(),
            Self::Plain => true,
            _ => {
                self.emergency_break_allowed(before, after)
                    && !(before.is_ascii_alphanumeric() && after.is_ascii_alphanumeric())
                    && !(self == Self::KoreanWords
                        && before.is_alphanumeric()
                        && after.is_alphanumeric())
            }
        }
    }

    /// Removable leading/trailing side bearings of full-width punctuation.
    /// TW uses a solid preset. CN/HK compress only adjacent punctuation; fixed
    /// marks (notably vertical colon/semicolon/question/exclamation) keep 1em.
    pub fn punctuation_space(self, ch: char, vertical: bool) -> (f32, f32) {
        if !matches!(self, Self::ChineseMainland | Self::ChineseHongKong) {
            return (0.0, 0.0);
        }
        if "（［｛〈《「『【〔〖〘〚".contains(ch) {
            return (0.5, 0.0);
        }
        if "）］｝〉》」』】〕〗〙〛".contains(ch) {
            return (0.0, 0.5);
        }
        let adjustable = "，。、．".contains(ch)
            || (!vertical && self == Self::ChineseMainland && "：；！？".contains(ch))
            || (!vertical && self == Self::ChineseHongKong && "：；".contains(ch));
        if !adjustable {
            return (0.0, 0.0);
        }
        if self == Self::ChineseMainland {
            (0.0, 0.5)
        } else {
            (0.25, 0.25)
        }
    }

    pub fn compression_em(self, before: char, after: char, vertical: bool) -> f32 {
        let a = self.punctuation_space(before, vertical);
        let b = self.punctuation_space(after, vertical);
        // Both characters must be adjustable punctuation. Never trim ordinary
        // Han cells or assume proportional ASCII punctuation contains half an em.
        if a == (0.0, 0.0) || b == (0.0, 0.0) {
            0.0
        } else {
            (a.1 + b.0).min(0.5)
        }
    }

    /// This preset permits hanging only in vertical traditional composition.
    /// Consecutive marks are excluded by the planner. Horizontal CN uses
    /// compression and push-out; no preset silently enables horizontal hanging.
    pub fn hanging_em(self, ch: char, vertical: bool) -> f32 {
        if vertical
            && matches!(self, Self::ChineseTaiwan | Self::ChineseHongKong)
            && "，。、．".contains(ch)
        {
            0.5
        } else {
            0.0
        }
    }

    pub fn script_spacing_em(self, before: char, after: char) -> f32 {
        if self.is_regional_chinese()
            && ((is_han(before) && after.is_ascii_alphanumeric())
                || (before.is_ascii_alphanumeric() && is_han(after)))
        {
            0.25
        } else {
            0.0
        }
    }

    /// Bounded justification: spaces first for Korean, character gaps for
    /// Chinese and unspaced Korean syllable layout. Never stretch word mode.
    pub fn expansion_em(self, before: char, after: char) -> (u8, f32) {
        if !self.break_allowed(before, after) {
            return (0, 0.0);
        }
        if before == ' ' && !after.is_whitespace() && self.is_korean() {
            return (1, 0.5);
        }
        if self.is_chinese()
            && !before.is_whitespace()
            && !after.is_whitespace()
            && (is_han(before) || is_han(after))
        {
            return (1, 0.5);
        }
        if self == Self::KoreanSyllable && before.is_alphanumeric() && after.is_alphanumeric() {
            return (2, 0.125);
        }
        (0, 0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_selects_one_policy_without_script_detection() {
        for (language, expected) in [
            ("", TextLayoutPolicy::Plain),
            ("en", TextLayoutPolicy::Plain),
            ("ja-JP", TextLayoutPolicy::Japanese),
            ("ZH-Hant-TW", TextLayoutPolicy::ChineseTaiwan),
            ("ko", TextLayoutPolicy::KoreanSyllable),
        ] {
            assert_eq!(TextLayoutPolicy::for_language(language, false), expected);
        }
        assert_eq!(
            TextLayoutPolicy::for_language("ko-KR", true),
            TextLayoutPolicy::KoreanSpacesOnly
        );
        assert_eq!(
            TextLayoutPolicy::for_language("zh", true),
            TextLayoutPolicy::Chinese
        );
    }

    #[test]
    fn chinese_regions_are_explicit_and_independent_of_script() {
        let mainland = TextLayoutPolicy::for_language("zh-Hans-CN", false);
        let mainland_traditional = TextLayoutPolicy::for_language("zh-Hant-CN", false);
        let taiwan = TextLayoutPolicy::for_language("zh-Hant-TW", false);
        let hong_kong = TextLayoutPolicy::for_language("zh-Hant-HK", false);
        assert_eq!(mainland, mainland_traditional);
        assert_ne!(mainland, taiwan);
        assert_ne!(taiwan, hong_kong);
        assert_eq!(mainland, TextLayoutPolicy::for_language("ZH-cn", false));
        assert_eq!(
            TextLayoutPolicy::Chinese,
            TextLayoutPolicy::for_language("zh-Hant", false)
        );
    }

    #[test]
    fn korean_punctuation_and_numeric_units_stay_attached() {
        let policy = TextLayoutPolicy::KoreanSyllable;
        for (before, after) in [
            ('한', '-'),
            ('한', '·'),
            ('₩', '1'),
            ('1', '%'),
            ('1', '.'),
            ('.', '2'),
            (',', '2'),
            ('，', '2'),
            ('-', '2'),
            ('－', '2'),
            ('…', '…'),
            ('―', '―'),
            ('한', '\u{00a0}'),
            ('\u{00a0}', '글'),
            ('한', '\u{2060}'),
        ] {
            assert!(!policy.break_allowed(before, after), "{before}|{after}");
        }
        assert!(policy.break_allowed('한', '글'));
        assert!(policy.break_allowed('한', '漢'));
    }

    #[test]
    fn word_mode_retains_punctuation_opportunities_and_blocks_letter_pairs() {
        let policy = TextLayoutPolicy::for_language_with_wrap("ko-KR", KoreanWrap::KeepWords);
        for (before, after) in [
            ('한', '글'),
            ('漢', '字'),
            ('한', 'A'),
            ('A', '漢'),
            ('1', '글'),
        ] {
            assert!(!policy.break_allowed(before, after));
            assert!(policy.emergency_break_allowed(before, after));
        }
        for (before, after) in [
            ('글', '('),
            (')', '한'),
            ('-', '글'),
            ('글', ' '),
            (' ', '한'),
        ] {
            assert!(policy.break_allowed(before, after), "{before}|{after}");
        }
        for (before, after) in [('(', '한'), ('글', ')'), ('한', ','), ('글', '\u{00a0}')] {
            assert!(!policy.emergency_break_allowed(before, after));
        }
        assert!(!TextLayoutPolicy::KoreanSpacesOnly.break_allowed(')', '한'));
    }

    #[test]
    fn regional_fitting_uses_its_own_spacing_and_orientation_rules() {
        let cn = TextLayoutPolicy::ChineseMainland;
        let tw = TextLayoutPolicy::ChineseTaiwan;
        let hk = TextLayoutPolicy::ChineseHongKong;
        assert_eq!(cn.compression_em('，', '」', false), 0.5);
        assert_eq!(tw.compression_em('，', '」', false), 0.0);
        assert_eq!(hk.compression_em('，', '」', false), 0.25);
        assert_eq!(cn.punctuation_space('！', true), (0.0, 0.0));
        assert_eq!(hk.punctuation_space('！', false), (0.0, 0.0));
        assert_eq!(tw.hanging_em('，', false), 0.0);
        assert_eq!(tw.hanging_em('，', true), 0.5);
        assert_eq!(cn.script_spacing_em('中', 'A'), 0.25);
        assert_eq!(cn.script_spacing_em('，', 'A'), 0.0);
        assert_eq!(
            TextLayoutPolicy::KoreanWords.script_spacing_em('漢', 'A'),
            0.0
        );
        assert_eq!(
            TextLayoutPolicy::KoreanWords.expansion_em('한', '글'),
            (0, 0.0)
        );
        assert_eq!(
            TextLayoutPolicy::KoreanWords.expansion_em(' ', '한'),
            (1, 0.5)
        );
    }

    #[test]
    fn chinese_basic_restrictions_do_not_include_small_kana() {
        let policy = TextLayoutPolicy::Chinese;
        assert!(!policy.break_allowed('「', '中'));
        assert!(!policy.break_allowed('文', '」'));
        assert!(policy.break_allowed('中', '文'));
        assert!(policy.break_allowed('あ', 'っ'));
        assert!(!TextLayoutPolicy::Japanese.break_allowed('あ', 'っ'));
    }
}
