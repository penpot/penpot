//! Paragraph typography policies. Geometry and source offsets are shared.
//! Regional presets use CLREQ (2026-10-07); Korean restrictions use KLREQ
//! (2026-03-21). These are named presets, not universal regional mandates.

use super::jlreq::{
    break_allowed_between, forbidden_at_line_end, forbidden_at_line_start,
    keeps_together_classified, pair_rule, JlreqClass,
};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum KoreanWrap {
    #[default]
    Syllable = 0,
    /// Preserve the original experiment when old content is reopened.
    SpacesOnly = 1,
    KeepWords = 2,
}

/// Regional Chinese preset, chosen by the region subtag only.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum ChineseRegion {
    /// Original basic preset, retained for tags without a known region.
    #[default]
    Basic,
    /// CN and SG.
    Mainland,
    /// TW.
    Taiwan,
    /// HK and MO.
    HongKong,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum TextLayoutPolicy {
    #[default]
    Plain,
    Japanese,
    Chinese(ChineseRegion),
    Korean(KoreanWrap),
}

/// The parts of a BCP 47 language tag the layout and font choices read.
/// Subtags may be separated by `-` or `_`; matching ignores case.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct LanguageTag<'a> {
    pub primary: &'a str,
    /// Four-letter script subtag (`Hans`, `Hant`).
    pub script: Option<&'a str>,
    /// Two-letter or three-digit region subtag (`CN`, `TW`, `419`).
    pub region: Option<&'a str>,
}

impl<'a> LanguageTag<'a> {
    pub fn parse(tag: &'a str) -> Self {
        let mut subtags = tag.split(['-', '_']);
        let primary = subtags.next().unwrap_or_default();
        let mut script = None;
        let mut region = None;
        // Script and region precede extensions and private use, which start
        // at a singleton subtag.
        for subtag in subtags.take_while(|subtag| subtag.len() != 1) {
            if script.is_none()
                && subtag.len() == 4
                && subtag.bytes().all(|b| b.is_ascii_alphabetic())
            {
                script = Some(subtag);
            } else if region.is_none()
                && (subtag.len() == 2
                    || (subtag.len() == 3 && subtag.bytes().all(|b| b.is_ascii_digit())))
            {
                region = Some(subtag);
            }
        }
        Self {
            primary,
            script,
            region,
        }
    }

    pub fn is(&self, primary: &str) -> bool {
        self.primary.eq_ignore_ascii_case(primary)
    }

    pub fn has_script(&self, script: &str) -> bool {
        self.script.is_some_and(|s| s.eq_ignore_ascii_case(script))
    }

    pub fn has_region(&self, regions: &[&str]) -> bool {
        self.region
            .is_some_and(|r| regions.iter().any(|region| r.eq_ignore_ascii_case(region)))
    }
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
    /// The policy a paragraph language (a BCP 47 tag) selects. Script never
    /// implies region. `korean_wrap` applies only to Korean.
    pub fn for_language(language: &str, korean_wrap: KoreanWrap) -> Self {
        let tag = LanguageTag::parse(language);
        if tag.is("ja") {
            Self::Japanese
        } else if tag.is("zh") {
            Self::Chinese(if tag.has_region(&["CN", "SG"]) {
                ChineseRegion::Mainland
            } else if tag.has_region(&["TW"]) {
                ChineseRegion::Taiwan
            } else if tag.has_region(&["HK", "MO"]) {
                ChineseRegion::HongKong
            } else {
                ChineseRegion::Basic
            })
        } else if tag.is("ko") {
            Self::Korean(korean_wrap)
        } else {
            Self::Plain
        }
    }

    pub fn is_chinese(self) -> bool {
        matches!(self, Self::Chinese(_))
    }

    pub fn is_korean(self) -> bool {
        matches!(self, Self::Korean(_))
    }

    /// Japanese layout: JLREQ classes, pair rules, kinsoku, aki and the
    /// squeeze/justify passes built on them.
    pub fn uses_jlreq(self) -> bool {
        self == Self::Japanese
    }

    /// Horizontal layout texts always carry this policy's break rules.
    /// Japanese plans them only for text with ruby or CJK characters
    /// (`uses_japanese_layout`); plain text keeps Skia's breaks.
    pub fn plans_breaks(self) -> bool {
        matches!(self, Self::Chinese(_) | Self::Korean(_))
    }

    /// An indivisible unit wider than the box overflows whole instead of
    /// taking Skia's emergency split. The legacy Korean spaces-only mode
    /// keeps its earlier behaviour.
    pub fn keeps_overlong_units(self) -> bool {
        self == Self::Japanese || self.reopens_overlong_words()
    }

    /// An overlong word may wrap at safe grapheme boundaries the policy's
    /// emergency rules allow.
    pub fn reopens_overlong_words(self) -> bool {
        matches!(
            self,
            Self::Chinese(_) | Self::Korean(KoreanWrap::Syllable | KoreanWrap::KeepWords)
        )
    }

    /// Justification expands the policy's pairwise opportunities by bounded
    /// amounts (`expansion_em`) instead of leaving it to Skia.
    pub fn bounded_justify(self) -> bool {
        self.reopens_overlong_words()
    }

    /// The legacy Korean spaces-only mode never justified vertical columns.
    pub fn justifies_columns(self) -> bool {
        self != Self::Korean(KoreanWrap::SpacesOnly)
    }

    /// Whether a vertical column break measures the whole unit that would
    /// move to the next column, or (legacy spaces-only) only the part up to
    /// the overflowing cell.
    pub fn measures_whole_unit(self) -> bool {
        self != Self::Korean(KoreanWrap::SpacesOnly)
    }

    /// Western word spaces set to one third em (JLREQ §3.2.6).
    pub fn normalizes_western_spaces(self) -> bool {
        self == Self::Japanese
    }

    /// Automatic emphasis and underline positions follow CLREQ: emphasis
    /// below horizontal text, underlines below the ink.
    pub fn uses_clreq_positions(self) -> bool {
        self.is_chinese()
    }

    /// Whether horizontal layout text needs a WORD JOINER between two
    /// adjacent grapheme bases, given with their JLREQ classes, to block a
    /// break Skia would otherwise take.
    pub fn needs_joiner(self, before: (char, JlreqClass), after: (char, JlreqClass)) -> bool {
        if self.uses_jlreq() {
            pair_rule(before.1, after.1).suppress_break_with_joiner
                || keeps_together_classified(before, after)
        } else {
            !self.break_allowed(before.0, after.0)
        }
    }

    /// Even emergency wrapping must retain punctuation, nonbreaking controls,
    /// numeric expressions and paired marks. Grapheme/annotation atomicity is
    /// enforced by the caller on the complete paragraph, across style runs.
    pub fn emergency_break_allowed(self, before: char, after: char) -> bool {
        if matches!(self, Self::Plain | Self::Korean(KoreanWrap::SpacesOnly)) {
            return true;
        }
        !protected(before)
            && !protected(after)
            && !opening(before)
            && !closing(after)
            && !(self.is_korean() && "‐‑–-・·々〻ー".contains(after))
            && !(self.uses_jlreq()
                && (forbidden_at_line_start(after) || forbidden_at_line_end(before)))
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
            Self::Korean(KoreanWrap::SpacesOnly) => before.is_whitespace() || after.is_whitespace(),
            Self::Plain => true,
            Self::Chinese(_) | Self::Korean(_) => {
                self.emergency_break_allowed(before, after)
                    && !(before.is_ascii_alphanumeric() && after.is_ascii_alphanumeric())
                    && !(self == Self::Korean(KoreanWrap::KeepWords)
                        && before.is_alphanumeric()
                        && after.is_alphanumeric())
            }
        }
    }

    /// Removable leading/trailing side bearings of full-width punctuation.
    /// TW uses a solid preset. CN/HK compress only adjacent punctuation; fixed
    /// marks (notably vertical colon/semicolon/question/exclamation) keep 1em.
    pub fn punctuation_space(self, ch: char, vertical: bool) -> (f32, f32) {
        let Self::Chinese(region @ (ChineseRegion::Mainland | ChineseRegion::HongKong)) = self
        else {
            return (0.0, 0.0);
        };
        if "（［｛〈《「『【〔〖〘〚".contains(ch) {
            return (0.5, 0.0);
        }
        if "）］｝〉》」』】〕〗〙〛".contains(ch) {
            return (0.0, 0.5);
        }
        let adjustable = "，。、．".contains(ch)
            || (!vertical && region == ChineseRegion::Mainland && "：；！？".contains(ch))
            || (!vertical && region == ChineseRegion::HongKong && "：；".contains(ch));
        if !adjustable {
            return (0.0, 0.0);
        }
        if region == ChineseRegion::Mainland {
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
            && matches!(
                self,
                Self::Chinese(ChineseRegion::Taiwan | ChineseRegion::HongKong)
            )
            && "，。、．".contains(ch)
        {
            0.5
        } else {
            0.0
        }
    }

    pub fn script_spacing_em(self, before: char, after: char) -> f32 {
        let regional = matches!(self, Self::Chinese(region) if region != ChineseRegion::Basic);
        if regional
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
        if self == Self::Korean(KoreanWrap::Syllable)
            && before.is_alphanumeric()
            && after.is_alphanumeric()
        {
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
            (
                "ZH-Hant-TW",
                TextLayoutPolicy::Chinese(ChineseRegion::Taiwan),
            ),
            ("ko", TextLayoutPolicy::Korean(KoreanWrap::Syllable)),
        ] {
            assert_eq!(
                TextLayoutPolicy::for_language(language, KoreanWrap::Syllable),
                expected
            );
        }
        assert_eq!(
            TextLayoutPolicy::for_language("ko-KR", KoreanWrap::SpacesOnly),
            TextLayoutPolicy::Korean(KoreanWrap::SpacesOnly)
        );
        assert_eq!(
            TextLayoutPolicy::for_language("zh", KoreanWrap::SpacesOnly),
            TextLayoutPolicy::Chinese(ChineseRegion::Basic)
        );
    }

    #[test]
    fn chinese_regions_are_explicit_and_independent_of_script() {
        let tag = LanguageTag::parse("zh-Hant-TW-x-hk");
        assert!(tag.is("ZH"));
        assert!(tag.has_script("hant"));
        assert!(tag.has_region(&["tw"]));
        assert!(!tag.has_region(&["HK"]));
        assert_eq!(LanguageTag::parse("es-419").region, Some("419"));
        for (language, expected) in [
            ("zh-Hant-HK", ChineseRegion::HongKong),
            ("zh-MO", ChineseRegion::HongKong),
            ("zh-SG", ChineseRegion::Mainland),
        ] {
            assert_eq!(
                TextLayoutPolicy::for_language(language, KoreanWrap::Syllable),
                TextLayoutPolicy::Chinese(expected),
                "{language}"
            );
        }
        let mainland = TextLayoutPolicy::for_language("zh-Hans-CN", KoreanWrap::Syllable);
        let mainland_traditional =
            TextLayoutPolicy::for_language("zh-Hant-CN", KoreanWrap::Syllable);
        let taiwan = TextLayoutPolicy::for_language("zh-Hant-TW", KoreanWrap::Syllable);
        let hong_kong = TextLayoutPolicy::for_language("zh-Hant-HK", KoreanWrap::Syllable);
        assert_eq!(mainland, mainland_traditional);
        assert_ne!(mainland, taiwan);
        assert_ne!(taiwan, hong_kong);
        assert_eq!(
            mainland,
            TextLayoutPolicy::for_language("ZH-cn", KoreanWrap::Syllable)
        );
        assert_eq!(
            TextLayoutPolicy::Chinese(ChineseRegion::Basic),
            TextLayoutPolicy::for_language("zh-Hant", KoreanWrap::Syllable)
        );
    }

    #[test]
    fn korean_punctuation_and_numeric_units_stay_attached() {
        let policy = TextLayoutPolicy::Korean(KoreanWrap::Syllable);
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
        let policy = TextLayoutPolicy::for_language("ko-KR", KoreanWrap::KeepWords);
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
        assert!(!TextLayoutPolicy::Korean(KoreanWrap::SpacesOnly).break_allowed(')', '한'));
    }

    #[test]
    fn regional_fitting_uses_its_own_spacing_and_orientation_rules() {
        let cn = TextLayoutPolicy::Chinese(ChineseRegion::Mainland);
        let tw = TextLayoutPolicy::Chinese(ChineseRegion::Taiwan);
        let hk = TextLayoutPolicy::Chinese(ChineseRegion::HongKong);
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
            TextLayoutPolicy::Korean(KoreanWrap::KeepWords).script_spacing_em('漢', 'A'),
            0.0
        );
        assert_eq!(
            TextLayoutPolicy::Korean(KoreanWrap::KeepWords).expansion_em('한', '글'),
            (0, 0.0)
        );
        assert_eq!(
            TextLayoutPolicy::Korean(KoreanWrap::KeepWords).expansion_em(' ', '한'),
            (1, 0.5)
        );
    }

    #[test]
    fn chinese_basic_restrictions_do_not_include_small_kana() {
        let policy = TextLayoutPolicy::Chinese(ChineseRegion::Basic);
        assert!(!policy.break_allowed('「', '中'));
        assert!(!policy.break_allowed('文', '」'));
        assert!(policy.break_allowed('中', '文'));
        assert!(policy.break_allowed('あ', 'っ'));
        assert!(!TextLayoutPolicy::Japanese.break_allowed('あ', 'っ'));
    }
}
