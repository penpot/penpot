use std::cell::RefCell;
use std::ops::Range;

use skia_safe::{
    self as skia,
    shaper::{
        run_handler::{Buffer, RunInfo},
        Feature, RunHandler,
    },
    textlayout::TypefaceFontProvider,
    Font, FontMgr, GlyphId, Point as SkPoint, TextBlob, TextBlobBuilder, Typeface,
};

use crate::render::DEFAULT_EMOJI_FONT;
use crate::shapes::{FontFeatures, TextSpan};

use super::font_tables::upright_centre_metrics;

/// JLREQ preferred width of a word space inside sideways Western text.
pub(super) const WESTERN_WORD_SPACING_EM: f32 = 1.0 / 3.0;

/// Candidate families for a span: its own font first, then emoji and the
/// registered fallback fonts (Noto etc.).
pub(crate) fn span_font_families(span: &TextSpan, fallback_families: &[String]) -> Vec<String> {
    let mut families = vec![
        format!("{}", span.font_family),
        DEFAULT_EMOJI_FONT.to_string(),
    ];
    families.extend(fallback_families.iter().cloned());
    families
}

/// One shaped run: glyphs from a single font, pen-relative positions,
/// per-glyph horizontal advances and per-glyph UTF-8 cluster starts
/// (relative to the shaped segment text).
pub struct ShapedRun {
    pub font: Font,
    pub glyphs: Vec<GlyphId>,
    pub positions: Vec<SkPoint>,
    pub advances: Vec<f32>,
    pub clusters: Vec<u32>,
    pub advance: f32,
    pub utf8_range: Range<usize>,
    /// Source text of `utf8_range`, attached to the run's text blobs.
    pub text: String,
    /// Local-y offset that puts the font's central baseline on the column
    /// axis after 90° rotation.
    pub rotated_baseline_shift: f32,
}

impl ShapedRun {
    /// Glyph clusters as (first glyph, glyph count). A combining sequence
    /// shapes to one cluster and must stay in one cell.
    pub(super) fn cluster_spans(&self) -> impl Iterator<Item = (usize, usize)> + '_ {
        let mut glyph = 0usize;
        std::iter::from_fn(move || {
            let cluster = *self.clusters.get(glyph)?;
            let count = self.clusters[glyph..]
                .iter()
                .take_while(|other| **other == cluster)
                .count();
            let span = (glyph, count);
            glyph += count;
            Some(span)
        })
    }

    /// Flow offset from the run start of each scalar boundary (0..=`chars`)
    /// of a sideways run, from its glyph positions; the last boundary is
    /// `extent`. A scalar inside a ligature takes the next glyph's position.
    pub(super) fn scalar_flow_offsets(&self, extent: f32, chars: usize) -> Option<Vec<f32>> {
        let origin = self.positions.first()?.x;
        let mut offsets = Vec::with_capacity(chars + 1);
        let mut utf8 = self.utf8_range.start;
        for ch in self.text.chars().take(chars) {
            let flow = self
                .clusters
                .iter()
                .position(|cluster| *cluster as usize >= utf8)
                .map_or(extent, |glyph| self.positions[glyph].x - origin);
            offsets.push(flow);
            utf8 += ch.len_utf8();
        }
        offsets.push(extent);
        (offsets.len() == chars + 1).then_some(offsets)
    }

    /// Summed horizontal advance of a cluster.
    pub(super) fn cluster_advance(&self, glyph: usize, count: usize) -> f32 {
        self.advances[glyph..glyph + count].iter().sum()
    }

    /// Positions of a cluster's glyphs relative to its first glyph's pen x.
    fn cluster_positions(&self, glyph: usize, count: usize) -> Vec<SkPoint> {
        let base_x = self.positions[glyph].x;
        self.positions[glyph..glyph + count]
            .iter()
            .map(|position| SkPoint::new(position.x - base_x, position.y))
            .collect()
    }

    /// Ink bounds of a cluster, relative to its first glyph's pen position.
    pub(super) fn cluster_ink_bounds(&self, glyph: usize, count: usize) -> Option<skia::Rect> {
        glyph_run_ink_bounds(
            &self.font,
            &self.glyphs[glyph..glyph + count],
            &self.cluster_positions(glyph, count),
        )
    }

    /// Ink bounds of the whole run at its shaped positions.
    pub(super) fn ink_bounds(&self) -> Option<skia::Rect> {
        glyph_run_ink_bounds(&self.font, &self.glyphs, &self.positions)
    }

    /// Byte range in `text` of the clusters covering `glyph..glyph + count`.
    fn text_range(&self, glyph: usize, count: usize) -> Range<usize> {
        let base = self.utf8_range.start as u32;
        let clusters = &self.clusters[glyph..glyph + count];
        let first = clusters.iter().copied().min().unwrap_or(base);
        let last = clusters.iter().copied().max().unwrap_or(base);
        // Clusters ascend along the run: the next larger one ends the text.
        let end = self.clusters[glyph + count..]
            .iter()
            .copied()
            .find(|cluster| *cluster > last)
            .map_or(self.text.len(), |cluster| {
                cluster.saturating_sub(base) as usize
            });
        (first.saturating_sub(base) as usize).min(end)..end
    }

    /// Text blob of `glyph..glyph + count` at `positions`, carrying the
    /// clusters' source text so PDF output maps vertical alternates back to
    /// the original characters.
    fn text_blob(&self, glyph: usize, count: usize, positions: &[SkPoint]) -> Option<TextBlob> {
        let range = self.text_range(glyph, count);
        let Some(text) = self
            .text
            .as_bytes()
            .get(range.clone())
            .filter(|t| !t.is_empty())
        else {
            let mut builder = TextBlobBuilder::new();
            let (glyphs, points) = builder.alloc_run_pos(&self.font, count, None);
            glyphs.copy_from_slice(&self.glyphs[glyph..glyph + count]);
            points.copy_from_slice(positions);
            return builder.make();
        };
        let text_start = self.utf8_range.start as u32 + range.start as u32;
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points, utf8_text, clusters) =
            builder.alloc_run_text_pos(&self.font, count, text.len(), None);
        glyphs.copy_from_slice(&self.glyphs[glyph..glyph + count]);
        points.copy_from_slice(positions);
        utf8_text.copy_from_slice(text);
        for (cluster, source) in clusters
            .iter_mut()
            .zip(&self.clusters[glyph..glyph + count])
        {
            *cluster = source.saturating_sub(text_start);
        }
        builder.make()
    }

    /// Text blob of one cluster, positioned from its first glyph's pen x.
    pub(super) fn cluster_blob(&self, glyph: usize, count: usize) -> Option<TextBlob> {
        self.text_blob(glyph, count, &self.cluster_positions(glyph, count))
    }

    /// Text blob of the whole run at its shaped positions.
    pub(super) fn blob(&self) -> Option<TextBlob> {
        self.text_blob(0, self.glyphs.len(), &self.positions)
    }

    /// Text blob of one glyph at its pen origin. The first glyph of a
    /// cluster carries the cluster's source text; the rest are glyph-only.
    pub(super) fn glyph_blob(&self, glyph: usize) -> Option<TextBlob> {
        self.glyphs.get(glyph)?;
        let starts_cluster = glyph == 0 || self.clusters.get(glyph) != self.clusters.get(glyph - 1);
        if starts_cluster {
            self.text_blob(glyph, 1, &[SkPoint::default()])
        } else {
            single_glyph_blob(&self.font, self.glyphs[glyph])
        }
    }

    /// Local-y shift that centres a cluster's ink after a 90° rotation.
    pub(super) fn cluster_rotated_baseline_shift(&self, glyph: usize, count: usize) -> f32 {
        rotated_run_baseline_shift(
            &self.font,
            &self.glyphs[glyph..glyph + count],
            &self.cluster_positions(glyph, count),
        )
    }

    /// Spreads the glyphs by `letter_spacing` each, like horizontal
    /// per-glyph spacing. Returns the spaced run advance.
    pub(super) fn spread_glyphs(&mut self, letter_spacing: f32) -> f32 {
        if letter_spacing != 0.0 {
            for (i, position) in self.positions.iter_mut().enumerate() {
                position.x += letter_spacing * i as f32;
            }
        }
        self.advance + letter_spacing * self.glyphs.len() as f32
    }

    /// Sets ASCII word spaces in a sideways Western run to JLREQ's one-third
    /// em. The space keeps its scalar and break opportunity; only its advance
    /// and the following glyph positions change.
    pub(super) fn normalize_word_spaces(&mut self, segment_text: &str, font_size: f32) {
        let clusters: Vec<(usize, usize)> = self.cluster_spans().collect();
        let mut accumulated_shift = 0.0f32;
        for (glyph, count) in clusters {
            for position in &mut self.positions[glyph..glyph + count] {
                position.x += accumulated_shift;
            }
            let is_word_space = segment_text
                .get(self.clusters[glyph] as usize..)
                .and_then(|text| text.chars().next())
                == Some(' ');
            if is_word_space {
                let delta =
                    font_size * WESTERN_WORD_SPACING_EM - self.cluster_advance(glyph, count);
                self.advances[glyph + count - 1] += delta;
                accumulated_shift += delta;
            }
        }
        self.advance += accumulated_shift;
    }
}

/// Text blob holding a single glyph drawn at its pen origin.
pub(crate) fn single_glyph_blob(font: &Font, glyph: GlyphId) -> Option<TextBlob> {
    let mut builder = TextBlobBuilder::new();
    let (glyphs, points) = builder.alloc_run_pos(font, 1, None);
    glyphs[0] = glyph;
    points[0] = SkPoint::default();
    builder.make()
}

#[derive(Default)]
struct RunCollector {
    runs: Vec<ShapedRun>,
    scratch_glyphs: Vec<GlyphId>,
    scratch_positions: Vec<SkPoint>,
    scratch_clusters: Vec<u32>,
}

impl RunHandler for RunCollector {
    fn begin_line(&mut self) {}

    fn run_info(&mut self, _info: &RunInfo) {}

    fn commit_run_info(&mut self) {}

    fn run_buffer(&mut self, info: &RunInfo) -> Buffer<'_> {
        self.scratch_glyphs.resize(info.glyph_count, 0);
        self.scratch_positions
            .resize(info.glyph_count, SkPoint::default());
        self.scratch_clusters.resize(info.glyph_count, 0);
        Buffer {
            glyphs: &mut self.scratch_glyphs,
            positions: &mut self.scratch_positions,
            offsets: None,
            clusters: Some(&mut self.scratch_clusters),
            point: SkPoint::default(),
        }
    }

    fn commit_run_buffer(&mut self, info: &RunInfo) {
        if info.glyph_count == 0 {
            return;
        }
        let origin = self.scratch_positions[0];
        let positions: Vec<SkPoint> = self
            .scratch_positions
            .iter()
            .map(|p| SkPoint::new(p.x - origin.x, p.y))
            .collect();
        let end = positions[0].x + info.advance.x;
        let advances: Vec<f32> = positions
            .iter()
            .enumerate()
            .map(|(i, position)| positions.get(i + 1).map_or(end, |next| next.x) - position.x)
            .collect();
        self.runs.push(ShapedRun {
            rotated_baseline_shift: central_baseline_shift(info.font),
            font: info.font.clone(),
            glyphs: self.scratch_glyphs.clone(),
            positions,
            advances,
            clusters: self.scratch_clusters.clone(),
            advance: info.advance.x,
            utf8_range: info.utf8_range.clone(),
            text: String::new(),
        });
    }

    fn commit_line(&mut self) {}
}

fn feature(tag: &[u8; 4]) -> Feature {
    Feature {
        tag: u32::from_be_bytes(*tag),
        value: 1,
        start: 0,
        end: usize::MAX,
    }
}

/// Omits `vpal`: SkShaper shapes on a horizontal line, where HarfBuzz would
/// apply its y-placement deltas as glyph offsets without the advance deltas.
/// Vertical layout applies the parsed GPOS `vpal` to upright cells itself.
fn font_feature(font_features: FontFeatures) -> Option<Feature> {
    match font_features {
        FontFeatures::None => None,
        FontFeatures::Palt => Some(feature(b"palt")),
        FontFeatures::Vpal => None,
    }
}

/// Shape one orientation segment on a single unbounded line. Upright
/// segments get the OpenType vertical substitution features so CJK
/// punctuation and brackets take their vertical forms.
pub(super) fn shape_segment(
    text: &str,
    font: &Font,
    upright: bool,
    font_features: FontFeatures,
    fallback: FontMgr,
) -> Vec<ShapedRun> {
    let mut font_iter = skia::Shaper::new_font_mgr_run_iterator(text, font, Some(fallback.clone()));
    let mut bidi_iter = skia::shapers::primitive::trivial_bidi_run_iterator(0, text.len());
    let mut script_iter = skia::Shaper::new_hb_icu_script_run_iterator(text);
    let mut lang_iter = skia::Shaper::new_trivial_language_run_iterator("ja", text.len());

    let mut features = if upright {
        vec![feature(b"vert"), feature(b"vrt2")]
    } else {
        vec![]
    };
    if let Some(font_feature) = font_feature(font_features) {
        features.push(font_feature);
    }

    let mut collector = RunCollector::default();
    with_shaper(&fallback, |shaper| {
        shaper.shape_with_iterators_and_features(
            text,
            &mut font_iter,
            &mut bidi_iter,
            &mut script_iter,
            &mut lang_iter,
            &features,
            f32::MAX,
            &mut collector,
        )
    });
    for run in &mut collector.runs {
        run.text = text
            .get(run.utf8_range.clone())
            .unwrap_or_default()
            .to_string();
    }
    collector.runs
}

thread_local! {
    static SHAPER: RefCell<Option<skia::Shaper>> = const { RefCell::new(None) };
}

/// Runs `f` with this thread's shaper, built on first use. One shaper serves
/// every font manager: shaping with explicit iterators takes the fallback
/// from the font run iterator, never from the shaper.
fn with_shaper<R>(fallback: &FontMgr, f: impl FnOnce(&skia::Shaper) -> R) -> R {
    SHAPER.with(|cache| {
        let mut cache = cache.borrow_mut();
        f(cache.get_or_insert_with(|| skia::Shaper::new(fallback.clone())))
    })
}

/// Combining marks, variation selectors and ZWJ belong to the preceding
/// grapheme. They stay in that font run even without a standalone cmap
/// entry, so HarfBuzz gets the complete shaping sequence.
fn extends_previous_grapheme(ch: char) -> bool {
    matches!(u32::from(ch),
        0x0300..=0x036F
        | 0x1AB0..=0x1AFF
        | 0x1DC0..=0x1DFF
        | 0x200D
        | 0x20D0..=0x20FF
        | 0x3099..=0x309A
        | 0xFE00..=0xFE0F
        | 0xFE20..=0xFE2F
        | 0xE0100..=0xE01EF
    )
}

/// Split `text` into byte ranges covered by one typeface each: the first of
/// `typefaces` with a glyph for the character, or the primary face.
fn typeface_chunks(text: &str, typefaces: &[Typeface]) -> Vec<(Range<usize>, usize)> {
    let mut chunks: Vec<(Range<usize>, usize)> = Vec::new();
    for (byte, ch) in text.char_indices() {
        let current = chunks.last().map(|(_, typeface)| *typeface);
        let selected = match current {
            Some(typeface) if extends_previous_grapheme(ch) => typeface,
            _ => typefaces
                .iter()
                .position(|typeface| typeface.unichar_to_glyph(ch as i32) != 0)
                .unwrap_or(0),
        };
        match chunks.last_mut() {
            Some((range, typeface)) if *typeface == selected => range.end = byte + ch.len_utf8(),
            _ => chunks.push((byte..byte + ch.len_utf8(), selected)),
        }
    }
    chunks
}

/// Shape a segment with explicit per-character font fallback.
/// `TypefaceFontProvider` resolves named families, but some Skia builds lack
/// its character fallback hook, so glyph coverage is resolved here.
pub(crate) fn shape_segment_with_fallbacks(
    text: &str,
    font_size: f32,
    families: &[String],
    font_provider: &TypefaceFontProvider,
    upright: bool,
    font_features: FontFeatures,
    fallback_mgr: &FontMgr,
) -> Vec<ShapedRun> {
    shape_with_typefaces(
        text,
        font_size,
        &resolve_typefaces(families, font_provider),
        upright,
        font_features,
        fallback_mgr,
    )
}

/// The typefaces of `families` that `font_provider` holds, in order.
pub(crate) fn resolve_typefaces(
    families: &[String],
    font_provider: &TypefaceFontProvider,
) -> Vec<Typeface> {
    families
        .iter()
        .filter_map(|family| font_provider.match_family_style(family, skia::FontStyle::default()))
        .collect()
}

/// `shape_segment_with_fallbacks` with the families already resolved.
pub(crate) fn shape_with_typefaces(
    text: &str,
    font_size: f32,
    typefaces: &[Typeface],
    upright: bool,
    font_features: FontFeatures,
    fallback_mgr: &FontMgr,
) -> Vec<ShapedRun> {
    if typefaces.is_empty() {
        return Vec::new();
    }

    let mut result = Vec::new();
    for (range, typeface_index) in typeface_chunks(text, typefaces) {
        let font = Font::new(typefaces[typeface_index].clone(), font_size);
        let mut shaped = shape_segment(
            &text[range.clone()],
            &font,
            upright,
            font_features,
            fallback_mgr.clone(),
        );
        for run in &mut shaped {
            run.utf8_range =
                (run.utf8_range.start + range.start)..(run.utf8_range.end + range.start);
            for cluster in &mut run.clusters {
                *cluster += range.start as u32;
            }
        }
        result.extend(shaped);
    }
    result
}

fn glyph_run_ink_bounds(
    font: &Font,
    glyphs: &[GlyphId],
    positions: &[SkPoint],
) -> Option<skia::Rect> {
    let mut bounds = vec![skia::Rect::default(); glyphs.len()];
    font.get_bounds(glyphs, &mut bounds, None);

    let mut ink = skia::Rect::new(f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for (bound, position) in bounds.iter().zip(positions) {
        if bound.right > bound.left && bound.bottom > bound.top {
            ink.left = ink.left.min(bound.left + position.x);
            ink.top = ink.top.min(bound.top + position.y);
            ink.right = ink.right.max(bound.right + position.x);
            ink.bottom = ink.bottom.max(bound.bottom + position.y);
        }
    }

    (ink.right > ink.left && ink.bottom > ink.top).then_some(ink)
}

/// Offset that centres a local-y band on the column axis after the run is
/// rotated 90°.
pub(super) fn rotated_baseline_shift(top: f32, bottom: f32) -> f32 {
    -(top + bottom) / 2.0
}

/// Shift that puts the font's central baseline on the column axis: the
/// middle of the em box upright glyphs centre on (`upright_centre_metrics`),
/// scaled to 1em. It depends only on the font, so every sideways run of a
/// face shares one baseline whatever its ink.
fn central_baseline_shift(font: &Font) -> f32 {
    let (ascent, descent) = upright_centre_metrics(font);
    let band = descent - ascent;
    if band <= 0.0 {
        return 0.0;
    }
    let em_over = -ascent * font.size() / band;
    em_over - font.size() / 2.0
}

/// Centres the glyphs' actual ink; ascent/descent metrics are only the
/// fallback for glyphs without visible ink.
fn rotated_run_baseline_shift(font: &Font, glyphs: &[GlyphId], positions: &[SkPoint]) -> f32 {
    if let Some(ink) = glyph_run_ink_bounds(font, glyphs, positions) {
        rotated_baseline_shift(ink.top, ink.bottom)
    } else {
        let (_, metrics) = font.metrics();
        rotated_baseline_shift(metrics.ascent, metrics.descent)
    }
}

#[cfg(test)]
mod tests {
    use super::super::layout::CellKind;
    use super::super::test_support::*;
    use super::*;

    #[test]
    fn span_families_try_emoji_after_the_span_font() {
        let families = span_font_families(&make_span("😆"), &["fallback".to_string()]);
        assert_eq!(families[1], DEFAULT_EMOJI_FONT);
        assert_eq!(families[2], "fallback");
    }

    fn rotated_shift(text: &str) -> f32 {
        let layout = layout_content(&make_content(&[text], 1000.0), 1000.0);
        let CellKind::Rotated { run } = layout.cells[0].kind else {
            panic!("expected a rotated run");
        };
        layout.runs[run].rotated_baseline_shift
    }

    #[test]
    fn rotated_runs_share_a_baseline_whatever_their_ink() {
        // Ascenders and descenders must not move the baseline across the
        // column.
        let plain = rotated_shift("aaa");
        for text in ["aaaf", "aaag", "aaagf", "AAA"] {
            assert!(
                (rotated_shift(text) - plain).abs() < 0.01,
                "{text} shifted from {plain} to {}",
                rotated_shift(text)
            );
        }
    }

    #[test]
    fn rotated_runs_centre_on_the_upright_em_box() {
        // Noto Sans JP's ascent overshoots the em; sideways runs must centre
        // on the same em box as upright glyphs, or full-width marks such as
        // `…` sit off the column axis.
        let typeface = FontMgr::new()
            .new_from_data(skia_safe::Data::new_copy(VMTX_TEST_FONT), None)
            .unwrap();
        let font = Font::new(typeface, EM);
        let (ascent, descent) = upright_centre_metrics(&font);
        let em_box_middle = -(ascent + descent) / 2.0;
        let shift = central_baseline_shift(&font);
        assert!(
            (shift - em_box_middle).abs() < 0.01,
            "sideways baseline shift {shift}, upright em box middle {em_box_middle}"
        );
    }

    #[test]
    fn rotated_lowercase_sits_on_the_column_axis() {
        // Source Sans: the em-box middle falls within 2% of an em of the
        // x-height middle, so lowercase looks centred in the column.
        let layout = layout_content(&make_content(&["a"], 1000.0), 1000.0);
        let CellKind::Rotated { run } = layout.cells[0].kind else {
            panic!("expected a rotated run");
        };
        let run = &layout.runs[run];
        let ink = run.ink_bounds().expect("visible ink");
        let ink_middle = (ink.top + ink.bottom) / 2.0 + run.rotated_baseline_shift;
        assert!(ink_middle.abs() < 0.02 * EM, "ink middle at {ink_middle}");
    }
}
