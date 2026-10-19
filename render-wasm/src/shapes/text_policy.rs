//! Paragraph typography policies. Geometry and source offsets are shared;
//! Japanese spacing remains in the Japanese layout passes.

use super::japanese::break_allowed_between;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum TextLayoutPolicy {
    #[default]
    Plain,
    Japanese,
    Chinese,
    KoreanSyllable,
    /// POC only: suppress every non-space boundary, including punctuation.
    KoreanSpacesOnly,
}

impl TextLayoutPolicy {
    pub fn for_language(language: &str, korean_spaces_only: bool) -> Self {
        let primary = language.split('-').next().unwrap_or_default();
        if primary.eq_ignore_ascii_case("ja") {
            Self::Japanese
        } else if primary.eq_ignore_ascii_case("zh") {
            Self::Chinese
        } else if primary.eq_ignore_ascii_case("ko") {
            if korean_spaces_only {
                Self::KoreanSpacesOnly
            } else {
                Self::KoreanSyllable
            }
        } else {
            Self::Plain
        }
    }

    /// Legal boundary between graphemes, before fitting a line or column.
    pub fn break_allowed(self, before: char, after: char) -> bool {
        match self {
            Self::Japanese => break_allowed_between(before, after),
            Self::KoreanSpacesOnly => before.is_whitespace() || after.is_whitespace(),
            Self::Chinese | Self::KoreanSyllable => {
                !"（［｛〈《「『【〔〖〘〚‘“([{".contains(before)
                    && !"，。、．！？：；）］｝〉》」』】〕〗〙〛’”)]},.!?:;".contains(after)
                    && !(before == after && matches!(after, '…' | '—'))
                    && !(before.is_ascii_alphanumeric() && after.is_ascii_alphanumeric())
            }
            Self::Plain => true,
        }
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
            ("ZH-Hant-TW", TextLayoutPolicy::Chinese),
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
    fn chinese_basic_restrictions_do_not_include_small_kana() {
        let policy = TextLayoutPolicy::Chinese;
        assert!(!policy.break_allowed('「', '中'));
        assert!(!policy.break_allowed('文', '」'));
        assert!(policy.break_allowed('中', '文'));
        assert!(policy.break_allowed('あ', 'っ'));
        assert!(!TextLayoutPolicy::Japanese.break_allowed('あ', 'っ'));
    }
}
