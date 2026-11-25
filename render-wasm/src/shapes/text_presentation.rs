//! Text vs emoji presentation for Skia font fallbacks.
//!
//! Unicode chooses monochrome vs color emoji via `Emoji_Presentation` and
//! variation selectors U+FE0E (text) / U+FE0F (emoji). Each run gets a matching
//! `font_families` stack.

use std::collections::HashSet;

use unicode_segmentation::UnicodeSegmentation;

const VS15_TEXT: char = '\u{FE0E}';
const VS16_EMOJI: char = '\u{FE0F}';

/// Unicode `Emoji_Presentation=Yes` ranges (emoji-data). Characters outside
/// these defaults to text presentation unless a variation selector says
/// otherwise — e.g. 🌡 U+1F321, 🅰 U+1F170, 📽 U+1F4FD stay text.
const EMOJI_PRESENTATION_RANGES: &[(u32, u32)] = &[
    (0x231A, 0x231B),
    (0x23E9, 0x23EC),
    (0x23F0, 0x23F0),
    (0x23F3, 0x23F3),
    (0x25FD, 0x25FE),
    (0x2614, 0x2615),
    (0x2648, 0x2653),
    (0x267F, 0x267F),
    (0x2693, 0x2693),
    (0x26A1, 0x26A1),
    (0x26AA, 0x26AB),
    (0x26BD, 0x26BE),
    (0x26C4, 0x26C5),
    (0x26CE, 0x26CE),
    (0x26D4, 0x26D4),
    (0x26EA, 0x26EA),
    (0x26F2, 0x26F3),
    (0x26F5, 0x26F5),
    (0x26FA, 0x26FA),
    (0x26FD, 0x26FD),
    (0x2705, 0x2705),
    (0x270A, 0x270B),
    (0x2728, 0x2728),
    (0x274C, 0x274C),
    (0x274E, 0x274E),
    (0x2753, 0x2755),
    (0x2757, 0x2757),
    (0x2795, 0x2797),
    (0x27B0, 0x27B0),
    (0x27BF, 0x27BF),
    (0x2B1B, 0x2B1C),
    (0x2B50, 0x2B50),
    (0x2B55, 0x2B55),
    (0x1F004, 0x1F004),
    (0x1F0CF, 0x1F0CF),
    (0x1F18E, 0x1F18E),
    (0x1F191, 0x1F19A),
    (0x1F1E6, 0x1F1FF),
    (0x1F201, 0x1F201),
    (0x1F21A, 0x1F21A),
    (0x1F22F, 0x1F22F),
    (0x1F232, 0x1F236),
    (0x1F238, 0x1F23A),
    (0x1F250, 0x1F251),
    (0x1F300, 0x1F320),
    (0x1F32D, 0x1F335),
    (0x1F337, 0x1F37C),
    (0x1F37E, 0x1F393),
    (0x1F3A0, 0x1F3CA),
    (0x1F3CF, 0x1F3D3),
    (0x1F3E0, 0x1F3F0),
    (0x1F3F4, 0x1F3F4),
    (0x1F3F8, 0x1F43E),
    (0x1F440, 0x1F440),
    (0x1F442, 0x1F4FC),
    (0x1F4FF, 0x1F53D),
    (0x1F54B, 0x1F54E),
    (0x1F550, 0x1F567),
    (0x1F57A, 0x1F57A),
    (0x1F595, 0x1F596),
    (0x1F5A4, 0x1F5A4),
    (0x1F5FB, 0x1F64F),
    (0x1F680, 0x1F6C5),
    (0x1F6CC, 0x1F6CC),
    (0x1F6D0, 0x1F6D2),
    (0x1F6D5, 0x1F6D9),
    (0x1F6DC, 0x1F6DF),
    (0x1F6EB, 0x1F6EC),
    (0x1F6F4, 0x1F6FC),
    (0x1F7E0, 0x1F7EB),
    (0x1F7F0, 0x1F7F0),
    (0x1F90C, 0x1F93A),
    (0x1F93C, 0x1F945),
    (0x1F947, 0x1F9FF),
    (0x1FA70, 0x1FA7C),
    (0x1FA80, 0x1FAC6),
    (0x1FAC8, 0x1FAC8),
    (0x1FACC, 0x1FADD),
    (0x1FADF, 0x1FAEB),
    (0x1FAEF, 0x1FAFA),
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Presentation {
    Text,
    Emoji,
}

/// Build the Skia family list for a presentation run.
///
/// - Text: primary → default → symbol/script fallbacks → color emoji last
///   (so Symbols can win, but emoji-only glyphs like `😀︎` still resolve).
/// - Emoji: color emoji first so a primary that maps the base codepoint cannot
///   shadow FE0F / default-emoji presentation.
pub fn font_families_for(
    kind: Presentation,
    primary: String,
    default: String,
    emoji: &str,
    fallbacks: &HashSet<String>,
) -> Vec<String> {
    match kind {
        Presentation::Text => {
            let mut v = vec![primary, default];
            v.extend(fallbacks.iter().cloned());
            v.push(emoji.to_string());
            v
        }
        Presentation::Emoji => {
            let mut v = vec![emoji.to_string(), primary, default];
            v.extend(fallbacks.iter().cloned());
            v
        }
    }
}

pub fn presentation_of_cluster(cluster: &str) -> Presentation {
    if cluster.contains(VS15_TEXT) {
        return Presentation::Text;
    }
    if cluster.contains(VS16_EMOJI) {
        return Presentation::Emoji;
    }
    for c in cluster.chars() {
        if c == '\u{200D}' {
            continue;
        }
        if is_default_emoji_presentation(c) {
            return Presentation::Emoji;
        }
    }
    Presentation::Text
}

/// Merge consecutive grapheme clusters that share the same presentation.
/// An empty string yields one empty text run so callers still push a style
/// (needed for blank lines / empty spans).
pub fn split_presentation_runs(text: &str) -> Vec<(Presentation, String)> {
    if text.is_empty() {
        return vec![(Presentation::Text, String::new())];
    }
    let mut runs: Vec<(Presentation, String)> = Vec::new();
    for cluster in text.graphemes(true) {
        let kind = presentation_of_cluster(cluster);
        match runs.last_mut() {
            Some((prev_kind, buf)) if *prev_kind == kind => buf.push_str(cluster),
            _ => runs.push((kind, cluster.to_string())),
        }
    }
    runs
}

fn is_default_emoji_presentation(c: char) -> bool {
    let cp = c as u32;
    EMOJI_PRESENTATION_RANGES
        .binary_search_by(|&(start, end)| {
            if cp < start {
                std::cmp::Ordering::Greater
            } else if cp > end {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_text_default_symbols() {
        for s in ["♥", "☀", "✔", "★", "A", " ", ",", "🌡", "🅰", "📽", "❤"] {
            assert_eq!(presentation_of_cluster(s), Presentation::Text, "{s}");
        }
    }

    #[test]
    fn classifies_variation_selectors() {
        assert_eq!(presentation_of_cluster("⚡\u{FE0E}"), Presentation::Text);
        assert_eq!(presentation_of_cluster("❤\u{FE0E}"), Presentation::Text);
        assert_eq!(presentation_of_cluster("😀\u{FE0E}"), Presentation::Text);
        assert_eq!(presentation_of_cluster("♥\u{FE0F}"), Presentation::Emoji);
        assert_eq!(presentation_of_cluster("❤\u{FE0F}"), Presentation::Emoji);
        assert_eq!(presentation_of_cluster("⚡\u{FE0F}"), Presentation::Emoji);
    }

    #[test]
    fn classifies_default_emoji_presentation() {
        for s in ["✅", "⚡", "😀", "🎉"] {
            assert_eq!(presentation_of_cluster(s), Presentation::Emoji, "{s}");
        }
    }

    #[test]
    fn split_empty_text_keeps_a_text_run() {
        assert_eq!(
            split_presentation_runs(""),
            vec![(Presentation::Text, String::new())]
        );
    }

    #[test]
    fn split_merges_adjacent_same_presentation() {
        let runs = split_presentation_runs("A♥B❤️C");
        assert_eq!(
            runs,
            vec![
                (Presentation::Text, "A♥B".to_string()),
                (Presentation::Emoji, "❤️".to_string()),
                (Presentation::Text, "C".to_string()),
            ]
        );
    }

    #[test]
    fn font_stacks_match_presentation() {
        let mut fallbacks = HashSet::new();
        fallbacks.insert("symbols".to_string());

        assert_eq!(
            font_families_for(
                Presentation::Text,
                "jost".into(),
                "sourcesanspro".into(),
                "noto-color-emoji",
                &fallbacks,
            ),
            vec!["jost", "sourcesanspro", "symbols", "noto-color-emoji"]
        );

        let emoji = font_families_for(
            Presentation::Emoji,
            "jost".into(),
            "sourcesanspro".into(),
            "noto-color-emoji",
            &fallbacks,
        );
        assert_eq!(emoji[0], "noto-color-emoji");
        assert_eq!(emoji[1], "jost");
    }
}
