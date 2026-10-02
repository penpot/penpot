// Builders that turn one span's text into flow cells: one per upright glyph
// cluster, one per rotated (sideways) run, and one per tate-chu-yoko or
// warichu composite.

use skia_safe::{textlayout::TypefaceFontProvider, FontMgr};

use crate::shapes::japanese::{classify, JapaneseClass};
use crate::shapes::text_japanese::{warichu_text_lines, WARICHU_FONT_SCALE};
use crate::shapes::{FontFeatures, TextCombineUpright, TextSpan};

use super::flow::{minimum_oikomi_extent, FlowCell, FlowScript};
use super::font_tables::{upright_baseline, vertical_metrics, vpal_table};
use super::layout::{CellKind, VerticalCell};
use super::orientation::{
    is_upright_char, segment_by_orientation, uses_rotated_vertical_fallback, Segment,
};
use super::shaping::{shape_segment_with_fallbacks, span_font_families, ShapedRun};

/// Smallest scale for `all` tate-chu-yoko; digit modes use 1 / run length.
const MIN_TCY_SCALE: f32 = 0.5;

/// Fonts available to the layout: the provider's registered faces and the
/// fallback families tried after each span's own font.
pub(super) struct Fonts<'a> {
    pub provider: &'a TypefaceFontProvider,
    pub fallback_mgr: &'a FontMgr,
    pub fallback_families: &'a [String],
}

/// Centered punctuation (jlreq cl-05 中点類: middle dot, full-width colon and
/// semicolon), set at the centre of the em box in vertical writing.
fn is_centered_punctuation(c: char) -> bool {
    classify(c) == JapaneseClass::MiddleDot
}

/// Flow-axis shift that centres a glyph's ink band in its em body.
fn centered_flow_shift(ink_top: f32, ink_bottom: f32, em_body: f32) -> f32 {
    em_body / 2.0 - (ink_top + ink_bottom) / 2.0
}

/// A single-glyph cluster whose font has no vertical alternate for it: the
/// shaped glyph is still the horizontal one.
fn needs_rotated_vertical_fallback(run: &ShapedRun, glyph: usize, count: usize, ch: char) -> bool {
    count == 1
        && uses_rotated_vertical_fallback(ch)
        && run.glyphs[glyph] == run.font.unichar_to_glyph(ch as i32)
}

fn is_tcy_digit(c: char) -> bool {
    c.is_ascii_digit() || ('０'..='９').contains(&c)
}

/// Split a TCY `digits` span into pieces. Maximal ASCII or full-width digit
/// runs of 2..=max chars are marked tcy; the rest keep normal vertical
/// layout. Returns (piece text, span-relative UTF-16 start, tcy).
fn split_digit_runs(text: &str, max: usize) -> Vec<(String, usize, bool)> {
    let mut pieces: Vec<(String, usize, bool)> = Vec::new();
    let mut utf16 = 0usize;
    for c in text.chars() {
        let digit = is_tcy_digit(c);
        match pieces.last_mut() {
            Some((piece, _, piece_digit)) if *piece_digit == digit => piece.push(c),
            _ => pieces.push((c.to_string(), utf16, digit)),
        }
        utf16 += c.len_utf16();
    }
    for (piece, _, tcy) in pieces.iter_mut() {
        *tcy = *tcy && (2..=max).contains(&piece.chars().count());
    }
    pieces
}

/// UTF-16 offset, within the shaped piece, of a UTF-8 offset in `segment`.
fn segment_utf16(segment: &Segment, utf8: usize) -> usize {
    segment.utf16_start
        + segment.text[..utf8.min(segment.text.len())]
            .chars()
            .map(char::len_utf16)
            .sum::<usize>()
}

/// Cell builder for one span of a paragraph.
pub(super) struct SpanCells<'a> {
    fonts: &'a Fonts<'a>,
    span: &'a TextSpan,
    paragraph: usize,
    span_index: usize,
    paint: usize,
    /// UTF-16 offset of the span in its paragraph's layout text.
    start: usize,
    /// The span's own font, then emoji and the registered fallback fonts.
    families: Vec<String>,
}

impl<'a> SpanCells<'a> {
    pub(super) fn new(
        fonts: &'a Fonts<'a>,
        span: &'a TextSpan,
        paragraph: usize,
        span_index: usize,
        paint: usize,
        start: usize,
    ) -> Self {
        Self {
            fonts,
            span,
            paragraph,
            span_index,
            paint,
            start,
            families: span_font_families(span, fonts.fallback_families),
        }
    }

    fn shape(&self, text: &str, font_size: f32, upright: bool) -> Vec<ShapedRun> {
        shape_segment_with_fallbacks(
            text,
            font_size,
            &self.families,
            self.fonts.provider,
            upright,
            self.span.font_features,
            self.fonts.fallback_mgr,
        )
    }

    /// A cell of this span covering `start..end` (paragraph UTF-16), whose
    /// ink fills its extent.
    fn cell(&self, kind: CellKind, start: usize, end: usize, extent: f32) -> VerticalCell {
        VerticalCell {
            kind,
            paragraph: self.paragraph,
            span: self.span_index,
            start,
            end,
            column: 0,
            top: 0.0,
            extent,
            minimum_oikomi_extent: extent,
            h_advance: self.span.font_size,
            ink_top: 0.0,
            ink_bottom: extent - self.span.letter_spacing,
            paint: self.paint,
            font_size: self.span.font_size,
            decoration: self.span.text_decoration,
            glyph_flow_shift: 0.0,
        }
    }

    /// Push the cells of the span's layout `text`. Runs are appended to
    /// `runs`, which the cells index.
    pub(super) fn push(&self, text: &str, runs: &mut Vec<ShapedRun>, cells: &mut Vec<FlowCell>) {
        let combine = self.span.text_combine_upright;
        if combine == TextCombineUpright::All
            && self.push_tate_chu_yoko(text, 0, MIN_TCY_SCALE, runs, cells)
        {
            return;
        }
        if self.span.is_warichu() && self.push_warichu(text, runs, cells) {
            return;
        }
        // `digits` merges each run of 2..=max digits into one upright cell.
        let pieces = match combine.digits_max() {
            Some(max) => split_digit_runs(text, max),
            None => vec![(text.to_string(), 0, false)],
        };
        for (piece, piece_start, tcy) in &pieces {
            let min_scale = 1.0 / piece.chars().count().max(1) as f32;
            if *tcy && self.push_tate_chu_yoko(piece, *piece_start, min_scale, runs, cells) {
                continue;
            }
            let piece_base = self.start + piece_start;
            for segment in segment_by_orientation(piece, self.span.text_orientation) {
                for mut run in self.shape(&segment.text, self.span.font_size, segment.upright) {
                    let run_index = runs.len();
                    if segment.upright {
                        self.push_upright_clusters(&segment, piece_base, &run, run_index, cells);
                    } else {
                        cells.push(self.rotated_cell(&segment, piece_base, &mut run, run_index));
                    }
                    runs.push(run);
                }
            }
        }
    }

    /// Compose `piece` (a whole TCY span or a digit run in one) into one
    /// upright composite cell, with fallback runs side by side. Returns false
    /// when the composite would scale below `min_scale`; the caller then uses
    /// normal vertical layout.
    fn push_tate_chu_yoko(
        &self,
        piece: &str,
        piece_start: usize,
        min_scale: f32,
        runs: &mut Vec<ShapedRun>,
        cells: &mut Vec<FlowCell>,
    ) -> bool {
        // A lone upright CJK char (e.g. a ruby base span that inherits `all`)
        // skips TCY, which would shrink it below its neighbours.
        let mut chars = piece.chars();
        if matches!((chars.next(), chars.next()), (Some(ch), None) if is_upright_char(ch)) {
            return false;
        }

        let letter_spacing = self.span.letter_spacing;
        let mut shaped = self.shape(piece, self.span.font_size, false);
        if shaped.is_empty() {
            return false;
        }
        let em = self.span.font_size.max(1.0);
        let mut combined_advance = 0.0f32;
        let mut run_height = 1.0f32;
        for run in &mut shaped {
            run.advance = run.spread_glyphs(letter_spacing);
            let (_, metrics) = run.font.metrics();
            run_height = run_height.max(metrics.descent - metrics.ascent);
            combined_advance += run.advance;
        }
        // Letter-spacing also separates adjacent fallback runs.
        combined_advance += letter_spacing * (shaped.len() - 1) as f32;
        let scale = (em / combined_advance.max(1.0))
            .min(em / run_height.max(1.0))
            .min(1.0);
        if scale < min_scale {
            return false;
        }

        let extent = em + letter_spacing;
        let kind = CellKind::TateChuYoko {
            run_start: runs.len(),
            run_count: shaped.len(),
            scale,
        };
        runs.extend(shaped);
        let start = self.start + piece_start;
        let end = start + piece.encode_utf16().count();
        let cell = VerticalCell {
            h_advance: combined_advance * scale,
            ink_bottom: em,
            ..self.cell(kind, start, end, extent)
        };
        cells.push(FlowCell::new(
            cell,
            None,
            FlowScript::Upright,
            letter_spacing,
        ));
        true
    }

    /// Warichu (割注): the span becomes one composite cell holding two
    /// half-size sub-lines side by side in the column (the first on the
    /// right, jlreq reading order), split by `warichu_split_chars`. Returns
    /// false when a sub-line shapes empty.
    fn push_warichu(
        &self,
        text: &str,
        runs: &mut Vec<ShapedRun>,
        cells: &mut Vec<FlowCell>,
    ) -> bool {
        let half_size = self.span.font_size * WARICHU_FONT_SCALE;
        let (first_text, second_text) = warichu_text_lines(text);
        let first_runs = self.shape(first_text, half_size, true);
        let second_runs = self.shape(second_text, half_size, true);
        if first_runs.is_empty() || second_runs.is_empty() {
            return false;
        }
        let line_extent = |runs: &[ShapedRun]| runs.iter().map(|r| r.advance).sum::<f32>();
        let extent =
            line_extent(&first_runs).max(line_extent(&second_runs)) + self.span.letter_spacing;
        let kind = CellKind::Warichu {
            run_start: runs.len(),
            run_count: first_runs.len() + second_runs.len(),
            first_count: first_runs.len(),
            first_chars: first_text.encode_utf16().count(),
        };
        runs.extend(first_runs);
        runs.extend(second_runs);
        // Two half-em sub-columns fill the em, the default `h_advance`.
        let end = self.start + text.encode_utf16().count();
        let cell = self.cell(kind, self.start, end, extent);
        cells.push(FlowCell::new(
            cell,
            None,
            FlowScript::Upright,
            self.span.letter_spacing,
        ));
        true
    }

    /// One upright cell per glyph cluster of `run`, so combining sequences
    /// stay in one cell.
    fn push_upright_clusters(
        &self,
        segment: &Segment,
        piece_base: usize,
        run: &ShapedRun,
        run_index: usize,
        cells: &mut Vec<FlowCell>,
    ) {
        let font_size = self.span.font_size;
        let letter_spacing = self.span.letter_spacing;
        let vmetrics = vertical_metrics(&run.font);
        // vpal applies here: SkShaper's horizontal shaping skips vertical GPOS.
        let vpal = (self.span.font_features == FontFeatures::Vpal)
            .then(|| vpal_table(&run.font))
            .flatten();

        for (glyph, count) in run.cluster_spans() {
            let glyphs = &run.glyphs[glyph..glyph + count];
            let cluster_utf8 = run.clusters[glyph] as usize;
            let next_cluster_utf8 = run
                .clusters
                .get(glyph + count)
                .map_or(run.utf8_range.end, |c| *c as usize);
            let ch = segment.text[cluster_utf8..].chars().next();
            let h_advance = match run.cluster_advance(glyph, count) {
                advance if advance > 0.0 => advance,
                _ => font_size,
            };
            // No `vmtx`: h-advance, with forced-upright Latin floored to an em.
            let horizontal_fallback = if ch.is_some_and(|c| !is_upright_char(c)) {
                h_advance.max(font_size)
            } else {
                h_advance
            };
            let vertical_advance = vmetrics
                .as_ref()
                .map(|vm| {
                    glyphs
                        .iter()
                        .map(|g| vm.advance(*g, font_size))
                        .sum::<f32>()
                })
                .filter(|advance| *advance > 0.0)
                .unwrap_or(horizontal_fallback);
            // vpal tightens the cell and lifts the ink by the font's deltas.
            let vpal_delta = vpal
                .as_ref()
                .and_then(|table| table.cluster_delta(glyphs, font_size));
            let (vpal_advance, vpal_flow_shift) = vpal_delta.unwrap_or((0.0, 0.0));
            let extent = vertical_advance + vpal_advance + letter_spacing;

            let synthetic_rotation =
                ch.is_some_and(|ch| needs_rotated_vertical_fallback(run, glyph, count, ch));
            let ink = run.cluster_ink_bounds(glyph, count).map(|ink| {
                if synthetic_rotation {
                    (ink.left, ink.right)
                } else {
                    let offset = upright_baseline(&run.font, run.font.size());
                    (ink.top + offset, ink.bottom + offset)
                }
            });
            let (ink_top, ink_bottom) = ink.unwrap_or((0.0, extent - letter_spacing));
            let (mut ink_top, mut ink_bottom) =
                (ink_top + vpal_flow_shift, ink_bottom + vpal_flow_shift);
            // Centre cl-05 ink in the em body, unless vpal already centres it.
            let glyph_flow_shift = if !synthetic_rotation
                && vpal_delta.is_none()
                && ch.is_some_and(is_centered_punctuation)
            {
                let shift = centered_flow_shift(ink_top, ink_bottom, extent - letter_spacing);
                ink_top += shift;
                ink_bottom += shift;
                shift
            } else {
                vpal_flow_shift
            };

            let kind = if synthetic_rotation {
                CellKind::SyntheticRotated {
                    run: run_index,
                    glyph,
                    count,
                }
            } else {
                CellKind::Upright {
                    run: run_index,
                    glyph,
                    count,
                }
            };
            let start = piece_base + segment_utf16(segment, cluster_utf8);
            let end = piece_base + segment_utf16(segment, next_cluster_utf8);
            let cell = VerticalCell {
                minimum_oikomi_extent: minimum_oikomi_extent(ch, extent, font_size, letter_spacing),
                h_advance,
                ink_top,
                ink_bottom,
                glyph_flow_shift,
                ..self.cell(kind, start, end, extent)
            };
            cells.push(FlowCell::new(cell, ch, FlowScript::Upright, letter_spacing));
        }
    }

    /// One cell for a whole sideways Western run. Letter-spacing spreads its
    /// glyphs down the column (post-rotation +x).
    fn rotated_cell(
        &self,
        segment: &Segment,
        piece_base: usize,
        run: &mut ShapedRun,
        run_index: usize,
    ) -> FlowCell {
        let letter_spacing = self.span.letter_spacing;
        run.normalize_word_spaces(&segment.text, self.span.font_size);
        let extent = run.spread_glyphs(letter_spacing);
        let run_text = &segment.text[run.utf8_range.clone()];
        let script = FlowScript::Rotated {
            starts_alphanumeric: run_text.chars().next().is_some_and(char::is_alphanumeric),
            ends_alphanumeric: run_text
                .chars()
                .next_back()
                .is_some_and(char::is_alphanumeric),
        };
        let start = piece_base + segment_utf16(segment, run.utf8_range.start);
        let end = piece_base + segment_utf16(segment, run.utf8_range.end);
        let (ink_top, ink_bottom) = run
            .ink_bounds()
            .map_or((0.0, extent - letter_spacing), |ink| (ink.left, ink.right));
        let cell = VerticalCell {
            // Unused for centring: rotated runs draw from `run.positions`.
            h_advance: run.advance,
            ink_top,
            ink_bottom,
            ..self.cell(CellKind::Rotated { run: run_index }, start, end, extent)
        };
        FlowCell::new(cell, None, script, letter_spacing)
    }
}

#[cfg(test)]
mod tests {
    use super::super::layout::VerticalCell;
    use super::super::shaping::WESTERN_WORD_SPACING_EM;
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::{Fill, SolidColor, TextDecoration, TextOrientation};
    use skia_safe as skia;

    fn vpal_content(text: &str, font_features: FontFeatures) -> crate::shapes::TextContent {
        spans_content(
            vec![TextSpan {
                font_features,
                ..make_span(text)
            }],
            1000.0,
        )
    }

    #[test]
    fn missing_vertical_alternate_uses_a_character_granular_rotated_cell() {
        let mut content = make_content(&["“"], 1000.0);
        content.paragraphs_mut()[0].children_mut()[0].text_orientation = TextOrientation::Upright;
        let layout = layout_with(&provider(TEST_FONT), &content);
        assert_eq!(layout.cells.len(), 1);
        assert!(matches!(
            layout.cells[0].kind,
            CellKind::SyntheticRotated { .. }
        ));
    }

    #[test]
    fn upright_narrow_latin_reserves_a_full_em_without_vmtx() {
        // Under text-orientation: upright, Latin letters stand upright. The
        // test face has no `vmtx`, so each letter advances a full em.
        let em = 20.0;
        let mut content = make_content(&["ab"], 1000.0);
        content.paragraphs_mut()[0].children_mut()[0].text_orientation = TextOrientation::Upright;
        let layout = layout_with(&provider(TEST_FONT), &content);
        assert_eq!(layout.cells.len(), 2, "one upright cell per Latin letter");
        for (index, cell) in layout.cells.iter().enumerate() {
            assert!(
                matches!(cell.kind, CellKind::Upright { .. }),
                "cell {index} should be an upright Latin letter"
            );
            assert!(
                (cell.extent - em).abs() < 0.5,
                "upright Latin cell {index} reserves a full em, got {}",
                cell.extent
            );
        }
        // The letters advance a full em down the column, so they never overlap.
        assert!(
            layout.cells[1].top - layout.cells[0].top >= em - 0.5,
            "successive upright letters are one em apart, got {}",
            layout.cells[1].top - layout.cells[0].top
        );
    }

    #[test]
    fn warichu_span_composes_two_half_size_sub_lines() {
        let content = warichu_content("ABCD", 400.0);
        let layout = layout_content(&content, 400.0);
        assert_eq!(layout.cells.len(), 1, "the whole span is one composite");
        let cell = &layout.cells[0];
        let CellKind::Warichu {
            run_count,
            first_count,
            ..
        } = cell.kind
        else {
            panic!("expected a warichu cell");
        };
        assert!(first_count >= 1 && run_count > first_count);
        // Two half-size chars per sub-line: about one em, not the four em of
        // normal layout.
        assert!(
            cell.extent < 2.0 * 20.0,
            "two half-size sub-lines take about one em, got {}",
            cell.extent
        );
        assert!(
            (cell.h_advance - 20.0).abs() < 0.001,
            "the composite fills the full em width"
        );
        assert_eq!(cell.start, 0);
        assert_eq!(cell.end, 4);
    }

    #[test]
    fn warichu_single_char_keeps_normal_layout() {
        let content = warichu_content("A", 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout
            .cells
            .iter()
            .all(|c| !matches!(c.kind, CellKind::Warichu { .. })));
    }

    #[test]
    fn tate_chu_yoko_span_becomes_one_upright_cell() {
        let mut content = make_content_with_spans(&["20", "年"], 200.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);

        let layout = layout_content(&content, 200.0);

        assert_eq!(layout.cells.len(), 2);
        assert!(matches!(layout.cells[0].kind, CellKind::TateChuYoko { .. }));
        assert_eq!(layout.cells[0].start, 0);
        assert_eq!(layout.cells[0].end, 2);
        assert_eq!(layout.cells[0].extent, 20.0);
        assert_eq!(layout.cells[1].start, 2);
    }

    #[test]
    fn tate_chu_yoko_all_keeps_single_upright_ruby_base_at_full_size() {
        let mut content = make_content_with_spans(&["く", "くくく"], 400.0);
        let spans = content.paragraphs_mut()[0].children_mut();
        spans[0].ruby = "あ".to_string();
        for span in spans {
            span.set_text_combine_upright(TextCombineUpright::All);
        }

        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let ruby_base = layout
            .cells
            .iter()
            .find(|cell| cell.span == 0)
            .expect("ruby base cell");
        let following_base = layout
            .cells
            .iter()
            .find(|cell| cell.span == 1)
            .expect("following base cell");

        assert!(matches!(ruby_base.kind, CellKind::Upright { .. }));
        assert!(!layout.ruby_cells.is_empty(), "ruby annotation is present");
        assert_eq!(ruby_base.font_size, following_base.font_size);
        assert!(layout
            .cells
            .iter()
            .all(|cell| !matches!(cell.kind, CellKind::TateChuYoko { .. })));
    }

    #[test]
    fn tate_chu_yoko_composes_covered_run_to_single_cell() {
        // A CJK-covering face keeps the whole marked span in one upright
        // composite cell (run_count >= 1); the next span is a separate cell.
        let mut content = make_content_with_spans(&["くく", "あ"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let CellKind::TateChuYoko { run_count, .. } = layout.cells[0].kind else {
            panic!("expected a Tate-chu-yoko cell");
        };
        assert!(run_count >= 1, "composite references at least one run");
        assert_eq!(layout.cells[0].start, 0);
        assert_eq!(layout.cells[0].end, 2);
        assert_eq!(layout.cells[1].start, 2);
    }

    #[test]
    fn tate_chu_yoko_wide_run_falls_back_to_normal_layout() {
        // A run far wider than the em would scale below MIN_TCY_SCALE, so it
        // gets normal layout.
        let mut content = make_content_with_spans(&["123456789"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_content(&content, 400.0);
        assert!(
            !layout
                .cells
                .iter()
                .any(|c| matches!(c.kind, CellKind::TateChuYoko { .. })),
            "an over-wide run must not become a tate-chu-yoko composite"
        );
    }

    #[test]
    fn split_digit_runs_honours_the_max_parameter() {
        // max 2: only exactly-two-digit runs combine.
        let pieces = split_digit_runs("平成31年123日", 2);
        let flags: Vec<(&str, bool)> = pieces
            .iter()
            .map(|(text, _, tcy)| (text.as_str(), *tcy))
            .collect();
        assert_eq!(
            flags,
            vec![
                ("平成", false),
                ("31", true),
                ("年", false),
                ("123", false),
                ("日", false),
            ]
        );
        // max 3 admits the three-digit run.
        let pieces = split_digit_runs("平成31年123日", 3);
        assert!(pieces.iter().any(|(text, _, tcy)| text == "123" && *tcy));
    }

    #[test]
    fn split_digit_runs_marks_two_to_four_digit_runs() {
        let pieces = split_digit_runs("平成31年12345日5", 4);
        let flags: Vec<(&str, usize, bool)> = pieces
            .iter()
            .map(|(text, start, tcy)| (text.as_str(), *start, *tcy))
            .collect();
        assert_eq!(
            flags,
            vec![
                ("平成", 0, false),
                ("31", 2, true),
                ("年", 4, false),
                ("12345", 5, false),
                ("日", 10, false),
                ("5", 11, false),
            ]
        );
    }

    #[test]
    fn split_digit_runs_recognizes_full_width_digits() {
        let pieces = split_digit_runs("２０２６夏号", 4);
        let flags: Vec<(&str, bool)> = pieces
            .iter()
            .map(|(text, _, tcy)| (text.as_str(), *tcy))
            .collect();
        assert_eq!(flags, vec![("２０２６", true), ("夏号", false)]);
    }

    #[test]
    fn tate_chu_yoko_digits_combines_only_digit_runs() {
        let mut content = make_content_with_spans(&["あ31く"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let tcy: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|c| matches!(c.kind, CellKind::TateChuYoko { .. }))
            .collect();
        assert_eq!(tcy.len(), 1, "exactly the digit run combines");
        assert_eq!(tcy[0].start, 1);
        assert_eq!(tcy[0].end, 3);
        // The surrounding characters keep their own cells in text order.
        let starts: Vec<usize> = layout.cells.iter().map(|c| c.start).collect();
        let mut sorted = starts.clone();
        sorted.sort_unstable();
        assert_eq!(starts, sorted, "cells stay in text order");
        assert_eq!(layout.cells.len(), 3);
    }

    #[test]
    fn tate_chu_yoko_digits_combines_four_ascii_digits() {
        let mut content = make_content_with_spans(&["2025年"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let tcy = layout
            .cells
            .iter()
            .find(|cell| matches!(cell.kind, CellKind::TateChuYoko { .. }))
            .expect("four-digit run combines");
        assert_eq!(tcy.start, 0);
        assert_eq!(tcy.end, 4);
    }

    #[test]
    fn tate_chu_yoko_digits_combines_full_width_unicode_digits() {
        let mut content = make_content_with_spans(&["２０２６年"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let tcy = layout
            .cells
            .iter()
            .find(|cell| matches!(cell.kind, CellKind::TateChuYoko { .. }))
            .expect("full-width digit run combines");
        assert_eq!(tcy.start, 0);
        assert_eq!(tcy.end, 4);
    }

    #[test]
    fn letter_spacing_extends_upright_cells() {
        // Each upright cluster gains `letter_spacing` of flow advance; the
        // centring width (`h_advance`) stays the same.
        let plain = layout_content(&spaced_content("あい", 0.0), 1000.0);
        let spaced = layout_content(&spaced_content("あい", 5.0), 1000.0);
        assert_eq!(plain.cells.len(), spaced.cells.len());
        for i in 0..plain.cells.len() {
            assert!(
                (spaced.cells[i].extent - plain.cells[i].extent - 5.0).abs() < 0.01,
                "cell {i} extent grows by letter-spacing"
            );
            assert!(
                (spaced.cells[i].h_advance - plain.cells[i].h_advance).abs() < 0.01,
                "cell {i} centring width is unchanged"
            );
        }
        assert!(
            spaced.cells[1].top - plain.cells[1].top - 5.0 > -0.01,
            "the second cell is pushed down by the spacing"
        );
    }

    #[test]
    fn letter_spacing_spreads_rotated_run() {
        // A sideways Latin run grows by `letter_spacing` per glyph and its
        // glyphs shift apart along the (post-rotation) column axis.
        let plain = layout_content(&spaced_content("AB", 0.0), 1000.0);
        let spaced = layout_content(&spaced_content("AB", 5.0), 1000.0);
        let plain_cell = plain
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Rotated { .. }))
            .expect("a rotated cell");
        let spaced_cell = spaced
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Rotated { .. }))
            .expect("a rotated cell");
        let (CellKind::Rotated { run: plain_run }, CellKind::Rotated { run: spaced_run }) =
            (plain_cell.kind, spaced_cell.kind)
        else {
            unreachable!();
        };
        let glyphs = spaced.runs[spaced_run].glyphs.len();
        assert!(glyphs >= 2, "AB shapes to at least two glyphs");
        assert!(
            (spaced_cell.extent - plain_cell.extent - 5.0 * glyphs as f32).abs() < 0.01,
            "rotated extent grows by letter_spacing * glyph count"
        );
        let plain_gap = plain.runs[plain_run].positions[1].x - plain.runs[plain_run].positions[0].x;
        let spaced_gap =
            spaced.runs[spaced_run].positions[1].x - spaced.runs[spaced_run].positions[0].x;
        assert!(
            (spaced_gap - plain_gap - 5.0).abs() < 0.01,
            "adjacent glyph gap grows by letter-spacing"
        );
    }

    // A tiny Noto Sans JP subset with `vmtx`/`vhea`: U+3031 (〱, vertical kana
    // repeat mark) advances 2em vertically and 1em horizontally;
    // U+3042/U+304F are symmetric controls.

    #[test]
    fn vertical_advance_uses_vmtx() {
        // 〱 flows down the column by its 2em vertical advance (vmtx), not
        // its 1em horizontal width; the glyph still centres on the 1em width.
        let content = make_content(&["〱"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let cell = &layout.cells[0];
        let CellKind::Upright { run, glyph, count } = cell.kind else {
            panic!("expected an upright cell");
        };
        let horizontal: f32 = layout.runs[run].advances[glyph..glyph + count].iter().sum();
        assert!(
            (horizontal - 20.0).abs() < 0.5,
            "horizontal advance ~1em, got {horizontal}"
        );
        assert!(
            (cell.extent - 40.0).abs() < 0.5,
            "vertical extent ~2em from vmtx, got {}",
            cell.extent
        );
        assert!(
            (cell.h_advance - 20.0).abs() < 0.5,
            "h_advance stays ~1em, got {}",
            cell.h_advance
        );
    }

    #[test]
    fn vertical_advance_symmetric_glyph_unchanged() {
        // A glyph whose vmtx equals its hmtx keeps extent == h_advance.
        let content = make_content(&["く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let cell = &layout.cells[0];
        assert!(
            (cell.extent - 20.0).abs() < 0.5,
            "extent ~1em, got {}",
            cell.extent
        );
        assert!(
            (cell.h_advance - cell.extent).abs() < 0.01,
            "symmetric glyph: extent == h_advance"
        );
    }

    // Subset of Noto Sans JP carrying GSUB `vert` and GPOS `vpal`: the
    // vertical alternates of 、。「」 halve their vertical advances (「 also
    // lifts its ink by 481 units) and the あ/く alternates tighten by
    // 58/60 units with small placement lifts.

    #[test]
    fn native_vertical_alternates_are_not_synthetically_rotated() {
        let layout = layout_with(
            &provider(VPAL_TEST_FONT),
            &vpal_content("「」、。", FontFeatures::None),
        );
        assert_eq!(layout.cells.len(), 4);
        assert!(layout
            .cells
            .iter()
            .all(|cell| matches!(cell.kind, CellKind::Upright { .. })));
    }

    #[test]
    fn vpal_tightens_upright_kana() {
        let provider = provider(VPAL_TEST_FONT);
        let plain = layout_with(&provider, &vpal_content("あ", FontFeatures::None));
        let tight = layout_with(&provider, &vpal_content("あ", FontFeatures::Vpal));
        assert!(
            (plain.cells[0].extent - 20.0).abs() < 0.01,
            "without vpal あ keeps its full em, got {}",
            plain.cells[0].extent
        );
        // あ's vertical alternate carries YAdvance -58, YPlacement 39.
        let expected = 20.0 * (1000.0 - 58.0) / 1000.0;
        assert!(
            (tight.cells[0].extent - expected).abs() < 0.01,
            "vpal advance delta tightens the extent, got {}",
            tight.cells[0].extent
        );
        let expected_shift = -20.0 * 39.0 / 1000.0;
        assert!(
            (tight.cells[0].glyph_flow_shift - expected_shift).abs() < 0.01,
            "vpal placement lifts the drawn ink, got {}",
            tight.cells[0].glyph_flow_shift
        );
    }

    #[test]
    fn vpal_opening_bracket_uses_font_placement() {
        // Mid-line, an opening bracket keeps its leading aki. Under vpal its
        // placement comes from the font's YPlacement (481 units), not a
        // synthetic sequence shed.
        let provider = provider(VPAL_TEST_FONT);
        let shed = layout_with(&provider, &vpal_content("あ「あ", FontFeatures::None));
        let vpal = layout_with(&provider, &vpal_content("あ「あ", FontFeatures::Vpal));
        assert_eq!(
            shed.cells[1].glyph_flow_shift, 0.0,
            "without vpal the preferred aki needs no synthetic shift, got {}",
            shed.cells[1].glyph_flow_shift
        );
        let expected = -20.0 * 481.0 / 1000.0;
        assert!(
            (vpal.cells[1].glyph_flow_shift - expected).abs() < 0.01,
            "with vpal 「 lifts by the font's placement delta, got {}",
            vpal.cells[1].glyph_flow_shift
        );
        assert!(
            (vpal.cells[1].extent - 10.0).abs() < 0.01,
            "「 is half-width under vpal, got {}",
            vpal.cells[1].extent
        );
    }

    #[test]
    fn centered_punctuation_shift_centres_ink_in_em_body() {
        assert!(is_centered_punctuation('・'));
        assert!(is_centered_punctuation('：'));
        assert!(is_centered_punctuation('；'));
        assert!(!is_centered_punctuation('あ'));
        // Ink at 14..18 in a 20 body: midpoint 16 moves to 10, so shift == -6.
        let shift = centered_flow_shift(14.0, 18.0, 20.0);
        assert!((shift + 6.0).abs() < 1e-4, "expected -6, got {shift}");
        // Already-centred ink needs no shift.
        assert!(centered_flow_shift(8.0, 12.0, 20.0).abs() < 1e-4);
    }

    #[test]
    fn vertical_base_text_switches_font_inside_an_upright_segment() {
        let content = make_content(&["あ、く"], 400.0);
        let provider = provider_with_fallback(VMTX_TEST_FONT, VPAL_TEST_FONT);
        let layout = layout_with_fallback(&provider, &content, 400.0, &["fallback".to_string()]);

        assert_eq!(layout.cells.len(), 3);
        let run_ids: Vec<u32> = layout
            .cells
            .iter()
            .map(|cell| match cell.kind {
                CellKind::Upright { run, .. } => layout.runs[run].font.typeface().unique_id(),
                _ => panic!("expected upright fallback cells"),
            })
            .collect();
        assert_eq!(run_ids[0], run_ids[2]);
        assert_ne!(run_ids[0], run_ids[1]);
        let CellKind::Upright { run, glyph, .. } = layout.cells[1].kind else {
            unreachable!();
        };
        assert_ne!(layout.runs[run].glyphs[glyph], 0);
    }

    #[test]
    fn layout_mixed_text_has_rotated_run() {
        let content = make_content(&["あAB1い"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        assert!(layout
            .cells
            .iter()
            .any(|c| matches!(c.kind, CellKind::Rotated { .. })));
        // The rotated run covers the Latin range 1..4 (UTF-16).
        let rotated = layout
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Rotated { .. }))
            .unwrap();
        assert_eq!(rotated.start, 1);
        assert_eq!(rotated.end, 4);
    }

    #[test]
    fn western_word_space_uses_one_third_em() {
        let without_space = layout_content(&make_content(&["ab"], 1000.0), 1000.0);
        let with_space = layout_content(&make_content(&["a b"], 1000.0), 1000.0);
        assert_eq!(without_space.cells.len(), 1);
        assert_eq!(with_space.cells.len(), 1);
        let added = with_space.cells[0].extent - without_space.cells[0].extent;
        assert!(
            (added - 20.0 * WESTERN_WORD_SPACING_EM).abs() < 0.01,
            "word space should add one third em, got {added}"
        );
    }

    #[test]
    fn cells_carry_font_size_and_decoration() {
        let content = decorated_content("あい", TextDecoration::UNDERLINE);
        let layout = layout_content(&content, 1000.0);
        assert!(!layout.cells.is_empty());
        for cell in &layout.cells {
            assert_eq!(cell.font_size, 20.0);
            assert_eq!(cell.decoration, Some(TextDecoration::UNDERLINE));
        }
    }

    #[test]
    fn mixed_spans_preserve_fill_color_and_opacity() {
        let mut content = make_content_with_spans(&["あ", "A", "い"], 1000.0);
        let colors = [
            skia::Color::from_argb(255, 255, 0, 0),
            skia::Color::from_argb(128, 0, 255, 0),
            skia::Color::from_argb(64, 0, 0, 255),
        ];
        for (span, color) in content.paragraphs_mut()[0]
            .children_mut()
            .iter_mut()
            .zip(colors)
        {
            span.fills = vec![Fill::Solid(SolidColor(color))];
        }

        let layout = layout_content(&content, 1000.0);
        assert_eq!(layout.paints.len(), colors.len());
        for (paint, color) in layout.paints.iter().zip(colors) {
            assert_eq!(paint.color(), color);
        }
        assert!(layout
            .cells
            .iter()
            .any(|cell| matches!(cell.kind, CellKind::Rotated { .. })));
    }
}
