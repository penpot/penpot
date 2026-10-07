//! Japanese line-breaking rules (kinsoku shori).
//!
//! Skia's default break iterator allows closing punctuation, the prolonged
//! sound mark or small kana at a line start, and opening brackets at a line
//! end. skia-safe exposes no ICU BreakIterator, so this module suppresses
//! those breaks by inserting U+2060 WORD JOINER into the text handed to
//! skparagraph. UAX #14 also forbids a break before … and ‥, which JLREQ
//! allows; U+200B ZERO WIDTH SPACE reopens it. The same transform inserts the JLREQ quarter-em space at
//! Japanese/Western boundaries and sets Western word spaces to one third em.
//!
//! Inserted characters shift every UTF-16 offset the laid-out paragraph
//! reports (position-data, carets, selection rects). [`OffsetMap`]
//! translates between original and shifted offsets. It depends only on the
//! span texts, so any consumer can recompute it and match the builders.

/// Zero-width character; its UAX #14 class forbids a break on either side.
pub const WORD_JOINER: char = '\u{2060}';
/// ZERO WIDTH SPACE: opens a break UAX #14 forbids but JLREQ allows, before
/// an inseparable mark such as … (JLREQ only keeps two of them together).
pub const BREAK_OPPORTUNITY: char = '\u{200B}';

/// True for the zero-width characters this pass inserts to steer breaks;
/// they take no letter-spacing and count as no character.
pub fn is_inserted_zero_width(ch: char) -> bool {
    ch == WORD_JOINER || ch == BREAK_OPPORTUNITY
}
/// Unicode FOUR-PER-EM SPACE, used for the preferred Japanese/Western gap.
pub const JAPANESE_WESTERN_SPACE: char = '\u{2005}';
/// Unicode THREE-PER-EM SPACE, used for Western word spaces.
pub const WESTERN_WORD_SPACE: char = '\u{2004}';
/// Unicode IDEOGRAPHIC SPACE, the one-em space after a sentence-ending
/// question or exclamation mark.
pub const IDEOGRAPHIC_SPACE: char = '\u{3000}';
/// Joins emoji into one grapheme cluster.
const ZERO_WIDTH_JOINER: char = '\u{200D}';

use super::japanese::{
    classify, extends_grapheme, keeps_together_classified, pair_rule, JapaneseClass,
};

pub fn forbidden_at_line_start(c: char) -> bool {
    classify(c).forbids_line_start()
}

pub fn forbidden_at_line_end(c: char) -> bool {
    classify(c).forbids_line_end()
}

/// Translates between original UTF-16 offsets (the source-of-truth span text)
/// and layout-text UTF-16 offsets (the text handed to skparagraph).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct OffsetMap {
    /// Ascending shifted UTF-16 indices of inserted (not substituted) chars.
    inserted: Vec<usize>,
}

impl OffsetMap {
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.inserted.is_empty()
    }

    /// True when the character at a shifted offset was inserted by the
    /// transform.
    pub fn is_inserted(&self, shifted: usize) -> bool {
        self.inserted.binary_search(&shifted).is_ok()
    }

    /// Original offset for a shifted offset. An offset on an inserted
    /// character resolves to the boundary where it was inserted.
    pub fn to_original(&self, shifted: usize) -> usize {
        shifted - self.inserted.partition_point(|&p| p < shifted)
    }

    /// Shifted offset for an original offset. A boundary that received an
    /// inserted character resolves after it, so carets skip synthetic spacing.
    pub fn to_shifted(&self, original: usize) -> usize {
        // The i-th insertion lands before `original` when it sits at or
        // before `original + i`; `inserted[i] - i` never decreases, so the
        // count is a partition point.
        let (mut low, mut high) = (0, self.inserted.len());
        while low < high {
            let middle = (low + high) / 2;
            if self.inserted[middle] - middle <= original {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        original + low
    }
}

/// Applies the horizontal Japanese layout-text transform: inserts WORD
/// JOINER wherever a break would violate kinsoku, inserts a quarter-em space
/// between Japanese letters and Western letters or digits, and sets
/// breakable ASCII spaces to one third em. Span boundaries are transparent;
/// grapheme clusters are not, so nothing lands inside one. `ruby_breaks[span]
/// == Some(boundaries)` forbids breaks at every internal scalar boundary of
/// that span except those UTF-16 offsets. Returns `None` when nothing changes.
pub fn apply_to_span_texts_with_ruby_breaks(
    span_texts: &[String],
    ruby_breaks: &[Option<Vec<usize>>],
) -> Option<(Vec<String>, OffsetMap)> {
    let mut inserted: Vec<usize> = Vec::new();
    let mut out: Vec<String> = Vec::with_capacity(span_texts.len());
    // Base character of the previous grapheme cluster, with its class.
    let mut prev: Option<(char, JapaneseClass)> = None;
    let mut after_zwj = false;
    let mut changed = false;
    // Running position in shifted UTF-16 coordinates.
    let mut shifted_pos: usize = 0;

    for (span_index, text) in span_texts.iter().enumerate() {
        let mut shifted_text = String::with_capacity(text.len() + 4);
        let mut local_utf16 = 0usize;
        for c in text.chars() {
            if after_zwj || extends_grapheme(c) {
                shifted_text.push(c);
                shifted_pos += c.len_utf16();
                local_utf16 += c.len_utf16();
                after_zwj = c == ZERO_WIDTH_JOINER;
                continue;
            }
            let current = (c, classify(c));
            let ruby_forbids_break = local_utf16 > 0
                && ruby_breaks
                    .get(span_index)
                    .and_then(Option::as_ref)
                    .is_some_and(|breaks| !breaks.contains(&local_utf16));
            let forbid_break = ruby_forbids_break
                || prev.is_some_and(|p| {
                    pair_rule(p.1, current.1).suppress_break_with_joiner
                        || keeps_together_classified(p, current)
                });
            let mut insert = |ch: char| {
                shifted_text.push(ch);
                inserted.push(shifted_pos);
                shifted_pos += 1;
                changed = true;
            };
            if prev.is_some_and(|p| takes_em_space_after(p, current)) {
                insert(IDEOGRAPHIC_SPACE);
            }
            if prev.is_some_and(|p| is_japanese_western_boundary(p, current)) {
                insert(JAPANESE_WESTERN_SPACE);
            }
            // After an inserted space, the joiner keeps the break blocked.
            if forbid_break {
                insert(WORD_JOINER);
            } else if current.1 == JapaneseClass::Inseparable
                && prev.is_some_and(|p| pair_rule(p.1, current.1).break_allowed)
            {
                insert(BREAK_OPPORTUNITY);
            }
            let layout_char = if c == ' ' {
                changed = true;
                WESTERN_WORD_SPACE
            } else {
                c
            };
            shifted_text.push(layout_char);
            shifted_pos += c.len_utf16();
            local_utf16 += c.len_utf16();
            prev = Some(current);
        }
        out.push(shifted_text);
    }

    if !changed {
        return None;
    }
    Some((out, OffsetMap { inserted }))
}

/// JLREQ §3.2.6 boundary between Japanese text and a Western letter or
/// digit. Western symbols, brackets and emoji set solid.
fn is_japanese_western_boundary(
    (before, before_class): (char, JapaneseClass),
    (after, after_class): (char, JapaneseClass),
) -> bool {
    (before_class.is_japanese_text() && after_class.is_western_run() && after.is_alphanumeric())
        || (before_class.is_western_run()
            && before.is_alphanumeric()
            && after_class.is_japanese_text())
}

/// A question or exclamation mark that ends a sentence takes a one-em space
/// (JLREQ §3.1.6), as in vertical layout.
fn takes_em_space_after(
    (_, before_class): (char, JapaneseClass),
    (_, after_class): (char, JapaneseClass),
) -> bool {
    before_class == JapaneseClass::DividingPunctuation
        && pair_rule(before_class, after_class).preferred_em >= 1.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use skia_safe::textlayout::{
        FontCollection, ParagraphBuilder, ParagraphStyle, TextStyle, TypefaceFontProvider,
    };
    use skia_safe::FontMgr;

    const TEST_FONT: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");

    fn strings(texts: &[&str]) -> Vec<String> {
        texts.iter().map(|t| t.to_string()).collect()
    }

    fn apply(texts: &[&str]) -> (Vec<String>, OffsetMap) {
        apply_to_span_texts_with_ruby_breaks(&strings(texts), &[])
            .expect("expected kinsoku insertions")
    }

    // -----------------------------------------------------------------
    // Character classes
    // -----------------------------------------------------------------

    #[test]
    fn classes_forbidden_at_start() {
        for c in "、。」』）ーっゃァッ々・！？".chars() {
            assert!(forbidden_at_line_start(c), "expected start-forbidden: {c}");
        }
        for c in "あ漢A1「（ ".chars() {
            assert!(!forbidden_at_line_start(c), "not start-forbidden: {c}");
        }
    }

    #[test]
    fn classes_forbidden_at_end() {
        for c in "「『（［【〈《".chars() {
            assert!(forbidden_at_line_end(c), "expected end-forbidden: {c}");
        }
        for c in "あ漢A1」）。".chars() {
            assert!(!forbidden_at_line_end(c), "not end-forbidden: {c}");
        }
    }

    // -----------------------------------------------------------------
    // Insertion
    // -----------------------------------------------------------------

    #[test]
    fn western_word_space_uses_one_third_em_character() {
        let (texts, map) = apply(&["hello world"]);
        assert_eq!(texts, vec![format!("hello{WESTERN_WORD_SPACE}world")]);
        assert!(
            map.inserted.is_empty(),
            "substitution does not shift offsets"
        );
    }

    #[test]
    fn no_insertion_for_plain_cjk_text() {
        assert!(
            apply_to_span_texts_with_ruby_breaks(&strings(&["国境の長いトンネル"]), &[]).is_none()
        );
    }

    #[test]
    fn inserts_before_forbidden_start_char() {
        let (texts, map) = apply(&["雪国。"]);
        assert_eq!(texts, vec!["雪国\u{2060}。".to_string()]);
        assert_eq!(map.inserted, vec![2]);
    }

    #[test]
    fn inserts_after_forbidden_end_char() {
        let (texts, _) = apply(&["「雪"]);
        assert_eq!(texts, vec!["「\u{2060}雪".to_string()]);
    }

    #[test]
    fn no_insertion_at_paragraph_start() {
        // A leading start-forbidden char has no break before it to suppress.
        let (texts, _) = apply(&["。あ。"]);
        assert_eq!(texts, vec!["。あ\u{2060}。".to_string()]);
    }

    #[test]
    fn insertion_spans_boundary() {
        // The pair (end of span 0, start of span 1) is evaluated; the
        // joiner lands at the head of span 1.
        let (texts, _) = apply(&["雪国", "。です"]);
        assert_eq!(
            texts,
            vec!["雪国".to_string(), "\u{2060}。です".to_string()]
        );
    }

    #[test]
    fn inserts_quarter_em_at_japanese_western_boundaries() {
        let (texts, map) = apply(&["日本Penpot版"]);
        assert_eq!(
            texts,
            vec![format!(
                "日本{JAPANESE_WESTERN_SPACE}Penpot{JAPANESE_WESTERN_SPACE}版"
            )]
        );
        assert_eq!(map.inserted, vec![2, 9]);
        assert_eq!(map.to_original(map.to_shifted(2)), 2);
        assert_eq!(map.to_original(map.to_shifted(8)), 8);
    }

    #[test]
    fn japanese_western_spacing_crosses_span_boundary() {
        let (texts, map) = apply(&["日本", "Penpot"]);
        assert_eq!(
            texts,
            vec![
                "日本".to_string(),
                format!("{JAPANESE_WESTERN_SPACE}Penpot")
            ]
        );
        assert_eq!(map.inserted, vec![2]);
    }

    #[test]
    fn no_insertion_inside_a_variation_sequence() {
        assert!(apply_to_span_texts_with_ruby_breaks(&strings(&["葛\u{E0100}城"]), &[]).is_none());
    }

    #[test]
    fn no_insertion_before_halfwidth_voiced_marks() {
        assert!(apply_to_span_texts_with_ruby_breaks(&strings(&["ｶﾞｷﾞ"]), &[]).is_none());
    }

    #[test]
    fn no_insertion_inside_an_emoji_zwj_sequence() {
        assert!(
            apply_to_span_texts_with_ruby_breaks(&strings(&["👩\u{200D}💻\u{200D}あ"]), &[])
                .is_none()
        );
    }

    #[test]
    fn grapheme_extenders_keep_the_base_as_previous_character() {
        let (texts, _) = apply(&["葛\u{E0100}Penpot"]);
        assert_eq!(
            texts,
            vec![format!("葛\u{E0100}{JAPANESE_WESTERN_SPACE}Penpot")]
        );
    }

    #[test]
    fn ruby_joiners_stay_outside_grapheme_clusters() {
        let texts = strings(&["葛\u{E0100}城"]);
        let (shifted, _) = apply_to_span_texts_with_ruby_breaks(&texts, &[Some(Vec::new())])
            .expect("expected a ruby joiner");
        assert_eq!(shifted, vec!["葛\u{E0100}\u{2060}城".to_string()]);
    }

    #[test]
    fn no_japanese_western_space_beside_symbols() {
        assert!(apply_to_span_texts_with_ruby_breaks(&strings(&["あ😀い"]), &[]).is_none());
        assert!(apply_to_span_texts_with_ruby_breaks(&strings(&["(注)あ"]), &[]).is_none());
    }

    #[test]
    fn full_width_digits_stay_together() {
        let (texts, _) = apply(&["２０２６年"]);
        assert_eq!(
            texts,
            vec!["２\u{2060}０\u{2060}２\u{2060}６年".to_string()]
        );
    }

    #[test]
    fn numerals_keep_their_unit() {
        let (texts, _) = apply(&["体重60㎏"]);
        assert_eq!(
            texts,
            vec![format!("体重{JAPANESE_WESTERN_SPACE}60\u{2060}㎏")]
        );
    }

    #[test]
    fn prefixed_signs_bind_only_to_numerals() {
        let (texts, _) = apply(&["C# は"]);
        assert_eq!(texts, vec![format!("C#{WESTERN_WORD_SPACE}は")]);
    }

    #[test]
    fn a_line_may_start_with_an_inseparable_mark() {
        // UAX #14 forbids the break before …; the zero-width space reopens it.
        let (texts, _) = apply(&["あ…"]);
        assert_eq!(texts, vec!["あ\u{200B}…".to_string()]);
        let (texts, _) = apply(&["あ……"]);
        assert_eq!(texts, vec!["あ\u{200B}…\u{2060}…".to_string()]);
        let (texts, _) = apply(&["「……"]);
        assert_eq!(texts, vec!["「\u{2060}…\u{2060}…".to_string()]);
    }

    #[test]
    fn japanese_western_space_follows_long_vowels_and_small_kana() {
        let (texts, _) = apply(&["ユーザーID"]);
        assert_eq!(
            texts,
            vec![format!(
                "ユ\u{2060}ーザ\u{2060}ー{JAPANESE_WESTERN_SPACE}ID"
            )]
        );
    }

    #[test]
    fn japanese_western_space_keeps_a_forbidden_break_blocked() {
        let (texts, _) = apply(&["Excelっぽい"]);
        assert_eq!(
            texts,
            vec![format!("Excel{JAPANESE_WESTERN_SPACE}\u{2060}っぽい")]
        );
    }

    #[test]
    fn dividing_punctuation_takes_an_em_space_before_text() {
        let (texts, _) = apply(&["え？はい"]);
        assert_eq!(texts, vec![format!("え\u{2060}？{IDEOGRAPHIC_SPACE}はい")]);
    }

    #[test]
    fn dividing_punctuation_adds_no_space_in_a_sequence_or_before_a_typed_space() {
        let (texts, _) = apply(&["え！？はい"]);
        assert_eq!(
            texts,
            vec![format!("え\u{2060}！\u{2060}？{IDEOGRAPHIC_SPACE}はい")]
        );
        let (texts, _) = apply(&["え？　はい"]);
        assert_eq!(texts, vec!["え\u{2060}？　はい".to_string()]);
        let (texts, _) = apply(&["え？」"]);
        assert_eq!(texts, vec!["え\u{2060}？\u{2060}」".to_string()]);
    }

    #[test]
    fn offset_map_lookups_match_a_linear_scan() {
        let linear_to_shifted = |inserted: &[usize], original: usize| {
            let mut shifted = original;
            for &p in inserted {
                if p <= shifted {
                    shifted += 1;
                } else {
                    break;
                }
            }
            shifted
        };
        for inserted in [
            vec![],
            vec![0],
            vec![2, 3, 4],
            vec![1, 5, 6, 10],
            vec![0, 1, 2],
        ] {
            let map = OffsetMap {
                inserted: inserted.clone(),
            };
            for offset in 0..16 {
                assert_eq!(
                    map.to_shifted(offset),
                    linear_to_shifted(&inserted, offset),
                    "to_shifted({offset}) for {inserted:?}"
                );
                assert_eq!(
                    map.to_original(offset),
                    offset - inserted.iter().take_while(|&&p| p < offset).count(),
                    "to_original({offset}) for {inserted:?}"
                );
            }
        }
    }

    #[test]
    fn consecutive_forbidden_chars() {
        let (texts, map) = apply(&["た」。"]);
        assert_eq!(texts, vec!["た\u{2060}」\u{2060}。".to_string()]);
        assert_eq!(map.inserted, vec![1, 3]);
    }

    #[test]
    fn small_kana_and_prolonged_sound() {
        let (texts, _) = apply(&["コーヒー"]);
        assert_eq!(texts, vec!["コ\u{2060}ーヒ\u{2060}ー".to_string()]);
        let (texts, _) = apply(&["ちょっと"]);
        assert_eq!(texts, vec!["ち\u{2060}ょ\u{2060}っと".to_string()]);
    }

    // -----------------------------------------------------------------
    // Offset map
    // -----------------------------------------------------------------

    #[test]
    fn offset_map_round_trips_every_original_index() {
        let original = "「こんにちは」と彼は言った。『吾輩は猫である』（夏目漱石）！？コーヒー。";
        let (_, map) = apply(&[original]);
        let len = original.encode_utf16().count();
        for i in 0..=len {
            assert_eq!(
                map.to_original(map.to_shifted(i)),
                i,
                "round-trip failed at original index {i}"
            );
        }
    }

    #[test]
    fn offset_map_recovers_original_characters() {
        let original = "た」。あ";
        let (texts, map) = apply(&[original]);
        let shifted = texts.concat();
        let shifted_units: Vec<u16> = shifted.encode_utf16().collect();
        let original_units: Vec<u16> = original.encode_utf16().collect();
        for (s_idx, unit) in shifted_units.iter().enumerate() {
            if *unit == WORD_JOINER as u16 {
                continue;
            }
            assert_eq!(original_units[map.to_original(s_idx)], *unit);
        }
    }

    #[test]
    fn offset_map_on_joiner_resolves_to_boundary() {
        // "雪国。" → joiner at shifted 2; both sides of the joiner map
        // to original boundary 2.
        let (_, map) = apply(&["雪国。"]);
        assert_eq!(map.to_original(2), 2);
        assert_eq!(map.to_original(3), 2);
        assert_eq!(map.to_shifted(2), 3);
    }

    // -----------------------------------------------------------------
    // Real skparagraph layout
    // -----------------------------------------------------------------

    fn font_collection() -> FontCollection {
        let font_mgr = FontMgr::new();
        let typeface = font_mgr
            .new_from_data(skia_safe::Data::new_copy(TEST_FONT), None)
            .expect("failed to load test font");
        let mut provider = TypefaceFontProvider::new();
        provider.register_typeface(typeface, Some("TestFont"));
        let mut collection = FontCollection::new();
        collection.set_asset_font_manager(Some(provider.into()));
        collection.set_default_font_manager(FontMgr::new(), None);
        collection
    }

    /// Lays out `text` at `width` and returns each line as a UTF-16
    /// (start, end) range.
    fn layout_lines(text: &str, width: f32, letter_spacing: f32) -> Vec<(usize, usize)> {
        let collection = font_collection();
        let paragraph_style = ParagraphStyle::default();
        let mut builder = ParagraphBuilder::new(&paragraph_style, collection);
        let mut style = TextStyle::default();
        style.set_font_families(&["TestFont"]);
        style.set_font_size(20.0);
        style.set_letter_spacing(letter_spacing);
        builder.push_style(&style);
        builder.add_text(text);
        let mut paragraph = builder.build();
        paragraph.layout(width);
        paragraph
            .get_line_metrics()
            .iter()
            .map(|line| (line.start_index, line.end_index))
            .collect()
    }

    #[test]
    fn layout_breaks_before_an_ellipsis() {
        // JLREQ Fig 81: という……そういう breaks as という|……そういう; only the
        // two dots of …… stay together.
        let (texts, map) = apply(&["という……そういう"]);
        let shifted = texts.concat();
        assert_eq!(shifted.matches(BREAK_OPPORTUNITY).count(), 1);
        assert!(shifted.contains("う\u{200B}…\u{2060}…"));
        let lines = layout_lines(&shifted, 20.0 * 4.0 + 1.0, 0.0);
        let second = map.to_original(lines[1].0);
        assert_eq!(second, 3, "the second line starts at ……: {lines:?}");
    }

    fn utf16_chars(text: &str) -> Vec<char> {
        // BMP-only fixtures: one char per UTF-16 unit.
        text.chars().collect()
    }

    /// Asserts no line starts with a start-forbidden char nor ends with
    /// an end-forbidden char, in ORIGINAL text coordinates.
    fn assert_kinsoku_clean(original: &str, lines: &[(usize, usize)], map: &OffsetMap) {
        let chars = utf16_chars(original);
        for (i, (start, end)) in lines.iter().enumerate() {
            let orig_start = map.to_original(*start);
            let orig_end = map.to_original(*end);
            if i > 0 {
                let first = chars[orig_start];
                assert!(
                    !forbidden_at_line_start(first),
                    "line {i} starts with forbidden char {first} (text {original})"
                );
            }
            if i < lines.len() - 1 && orig_end > orig_start {
                // The last char actually rendered on the line (end may
                // include trailing whitespace-like positions).
                let last = chars[orig_end - 1];
                assert!(
                    !forbidden_at_line_end(last),
                    "line {i} ends with forbidden char {last} (text {original})"
                );
            }
        }
    }

    /// Lines mapped back to original offsets must tile the original
    /// text exactly: contiguous, in order, full coverage.
    fn assert_lines_tile_original(original: &str, lines: &[(usize, usize)], map: &OffsetMap) {
        let mut expected_start = 0;
        for (start, end) in lines {
            let orig_start = map.to_original(*start);
            let orig_end = map.to_original(*end);
            assert_eq!(orig_start, expected_start, "line ranges must be contiguous");
            expected_start = orig_end;
        }
        assert_eq!(expected_start, original.encode_utf16().count());
    }

    fn fixture_lines(original: &str, width: f32) -> (Vec<(usize, usize)>, OffsetMap) {
        let (texts, map) = apply(&[original]);
        let lines = layout_lines(&texts.concat(), width, 0.0);
        (lines, map)
    }

    fn measure_width(text: &str) -> f32 {
        let collection = font_collection();
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), collection);
        let mut style = TextStyle::default();
        style.set_font_families(&["TestFont"]);
        style.set_font_size(20.0);
        builder.push_style(&style);
        builder.add_text(text);
        let mut p = builder.build();
        p.layout(f32::MAX);
        p.longest_line()
    }

    #[test]
    fn joiner_is_zero_width_in_layout() {
        let plain = layout_lines("国国", f32::MAX, 0.0);
        let joined = layout_lines("国\u{2060}国", f32::MAX, 0.0);
        assert_eq!(plain.len(), 1);
        assert_eq!(joined.len(), 1);

        let collection = font_collection();
        let measure = |text: &str| {
            let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), collection.clone());
            let mut style = TextStyle::default();
            style.set_font_families(&["TestFont"]);
            style.set_font_size(20.0);
            builder.push_style(&style);
            builder.add_text(text);
            let mut p = builder.build();
            p.layout(f32::MAX);
            p.longest_line()
        };
        let diff = (measure("国国") - measure("国\u{2060}国")).abs();
        assert!(diff < 0.01, "WORD JOINER must not add width, diff {diff}");
    }

    #[test]
    fn suppresses_breaks_on_kinsoku_fixtures() {
        let fixtures = [
            // kinsoku-line-start
            "これは長い文章です、句読点や閉じ括弧」が行頭に来てはいけません。小さい「ゃゅょっ」も同様です。",
            // kinsoku-line-end
            "開き括弧「や『それに（と［や【は行末に置けないので、次の行に送り込まれます。",
            // prolonged-sound
            "コーヒーとケーキ、サーバーとルーター。人々の時々の心。",
            // small-kana-sokuon
            "ちょっと待ってください。キャッシュとクッキーをチェックする。",
            // long-paragraph-wrap
            "国境の長いトンネルを抜けると雪国であった。夜の底が白くなった。信号所に汽車が止まった。向側の座席から娘が立って来て、島村の前のガラス窓を落した。雪の冷気が流れこんだ。",
        ];
        // Vary the width to move the breaks. Each width must exceed the
        // longest joined run, or skparagraph falls back to an emergency
        // mid-run break.
        let char_width = measure_width("国");
        for width in [9.0, 12.0, 16.5, 24.0].map(|n: f32| n * char_width) {
            for original in fixtures {
                let (lines, map) = fixture_lines(original, width);
                assert!(lines.len() > 1, "fixture must wrap at width {width}");
                assert_kinsoku_clean(original, &lines, &map);
                assert_lines_tile_original(original, &lines, &map);
            }
        }
    }

    #[test]
    fn mixed_latin_cjk_fixture() {
        let original = "Penpotは2024年にWASMレンダラーを導入した。価格は¥1,500（税込）です！";
        let char_width = measure_width("国");
        for width in [9.0, 13.0, 18.0].map(|n: f32| n * char_width) {
            let (lines, map) = fixture_lines(original, width);
            assert_kinsoku_clean(original, &lines, &map);
            assert_lines_tile_original(original, &lines, &map);
        }
    }

    #[test]
    fn joiner_becomes_visible_under_letter_spacing() {
        // skparagraph applies letter-spacing per cluster, INCLUDING the
        // zero-width joiner, which would double the tracking at every
        // suppressed break, so the horizontal builder gives inserted
        // joiners zero letter-spacing. If this test fails (Skia stops
        // spacing ignorables), that special style can go.
        let collection = font_collection();
        let measure = |text: &str| {
            let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), collection.clone());
            let mut style = TextStyle::default();
            style.set_font_families(&["TestFont"]);
            style.set_font_size(20.0);
            style.set_letter_spacing(5.0);
            builder.push_style(&style);
            builder.add_text(text);
            let mut p = builder.build();
            p.layout(f32::MAX);
            p.longest_line()
        };
        let diff = (measure("国\u{2060}国") - measure("国国")).abs();
        assert!(
            diff > 0.01,
            "letter-spacing no longer affects the joiner; the joiner \
             style in add_text_with_sheds can be removed"
        );
    }
}
