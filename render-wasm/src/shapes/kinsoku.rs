//! Japanese line-breaking rules (kinsoku shori).
//!
//! Skia's default break iterator allows closing punctuation, the
//! prolonged sound mark or small kana at a line start, and opening
//! brackets at a line end. skia-safe exposes no ICU BreakIterator, so
//! the forbidden break opportunities are suppressed by inserting U+2060 WORD
//! JOINER into the text handed to skparagraph. The same lossless layout-text
//! transform inserts JLREQ quarter-em Japanese/Western boundary space and
//! normalizes Western word spaces to one third em.
//!
//! The inserted joiners shift every UTF-16 offset reported by the laid
//! out paragraph (position-data, caret mapping, selection rects). The
//! [`OffsetMap`] returned along with the modified texts translates
//! between original and joiner-shifted offsets. It is a pure function
//! of the span texts, so any consumer can recompute it and stay
//! consistent with the builders by construction.

/// Zero-width character whose UAX #14 class forbids breaking on either
/// side of it.
pub const WORD_JOINER: char = '\u{2060}';
/// Unicode FOUR-PER-EM SPACE, used for the preferred Japanese/Western gap.
pub const JAPANESE_WESTERN_SPACE: char = '\u{2005}';
/// Unicode THREE-PER-EM SPACE, used for Western word spacing in Japanese text.
pub const WESTERN_WORD_SPACE: char = '\u{2004}';

use super::japanese::{classify, pair_rule};

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
    /// UTF-16 indices of inserted joiners or boundary spaces, ascending and
    /// expressed in shifted coordinates. Same-length substitutions need no
    /// entry.
    inserted: Vec<usize>,
}

impl OffsetMap {
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.inserted.is_empty()
    }

    /// Original offset for a shifted offset. An offset pointing at an inserted
    /// character resolves to the source boundary where it was inserted.
    pub fn to_original(&self, shifted: usize) -> usize {
        shifted - self.inserted.iter().take_while(|&&p| p < shifted).count()
    }

    /// Shifted offset for an original offset. A boundary that received a
    /// layout character resolves after it, so carets avoid synthetic spacing.
    pub fn to_shifted(&self, original: usize) -> usize {
        let mut shifted = original;
        for &p in &self.inserted {
            if p <= shifted {
                shifted += 1;
            } else {
                break;
            }
        }
        shifted
    }
}

/// Applies the horizontal Japanese layout-text transform. It inserts WORD
/// JOINER wherever a break would violate kinsoku, inserts a quarter-em space at
/// Japanese↔Western boundaries, and normalizes breakable ASCII word spaces to
/// one third em. Span boundaries are transparent. Returns `None` when the
/// paragraph needs no transformation.
/// Apply the normal Japanese layout transform while additionally protecting
/// annotated base units. `ruby_breaks[span] == Some(boundaries)` means that
/// every internal scalar boundary except those UTF-16 offsets is atomic.
pub fn apply_to_span_texts_with_ruby_breaks(
    span_texts: &[String],
    ruby_breaks: &[Option<Vec<usize>>],
) -> Option<(Vec<String>, OffsetMap)> {
    let mut inserted: Vec<usize> = Vec::new();
    let mut out: Vec<String> = Vec::with_capacity(span_texts.len());
    let mut prev: Option<char> = None;
    let mut changed = false;
    // Running position in shifted UTF-16 coordinates.
    let mut shifted_pos: usize = 0;

    for (span_index, text) in span_texts.iter().enumerate() {
        let mut shifted_text = String::with_capacity(text.len() + 4);
        let mut local_utf16 = 0usize;
        for c in text.chars() {
            let ruby_forbids_break = local_utf16 > 0
                && ruby_breaks
                    .get(span_index)
                    .and_then(Option::as_ref)
                    .is_some_and(|breaks| !breaks.contains(&local_utf16));
            let forbid_break = ruby_forbids_break
                || match prev {
                    Some(p) => pair_rule(classify(p), classify(c)).suppress_break_with_joiner,
                    None => false,
                };
            if forbid_break {
                shifted_text.push(WORD_JOINER);
                inserted.push(shifted_pos);
                shifted_pos += 1;
                changed = true;
            } else if prev.is_some_and(|p| {
                let before = classify(p);
                let after = classify(c);
                (before.is_japanese_letter() && after.is_western_run())
                    || (before.is_western_run() && after.is_japanese_letter())
            }) {
                shifted_text.push(JAPANESE_WESTERN_SPACE);
                inserted.push(shifted_pos);
                shifted_pos += 1;
                changed = true;
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
            prev = Some(c);
        }
        out.push(shifted_text);
    }

    if !changed {
        return None;
    }
    Some((out, OffsetMap { inserted }))
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
        // A leading forbidden-at-start char has no break opportunity
        // before it; nothing to suppress.
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
            .new_from_data(TEST_FONT, None)
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
        // Several widths to move the break positions around. Widths
        // must exceed the longest unbreakable (joined) run, otherwise
        // skparagraph rightfully falls back to an emergency mid-run
        // break.
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
        // suppressed break. This is why callers disable kinsoku for
        // paragraphs with a non-zero letter-spacing. If this test ever
        // fails (Skia stops spacing ignorables), that gate can go.
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
            "letter-spacing no longer affects the joiner; the kinsoku \
             letter-spacing gate can be removed"
        );
    }
}
