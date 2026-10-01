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
    /// Local-y offset that centres the run's ink after 90° rotation.
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

    /// Text blob of one cluster, positioned from its first glyph's pen x.
    pub(super) fn cluster_blob(&self, glyph: usize, count: usize) -> Option<TextBlob> {
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points) = builder.alloc_run_pos(&self.font, count, None);
        glyphs.copy_from_slice(&self.glyphs[glyph..glyph + count]);
        points.copy_from_slice(&self.cluster_positions(glyph, count));
        builder.make()
    }

    /// Text blob of the whole run at its shaped positions.
    pub(super) fn blob(&self) -> Option<TextBlob> {
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points) = builder.alloc_run_pos(&self.font, self.glyphs.len(), None);
        glyphs.copy_from_slice(&self.glyphs);
        points.copy_from_slice(&self.positions);
        builder.make()
    }

    /// Local-y shift that centres a cluster's ink after a 90° rotation.
    pub(super) fn cluster_rotated_baseline_shift(&self, glyph: usize, count: usize) -> f32 {
        rotated_run_baseline_shift(
            &self.font,
            &self.glyphs[glyph..glyph + count],
            &self.cluster_positions(glyph, count),
        )
    }

    /// Spread the glyphs apart by `letter_spacing` per glyph, mirroring the
    /// horizontal per-glyph spacing. Returns the spaced run advance.
    pub(super) fn spread_glyphs(&mut self, letter_spacing: f32) -> f32 {
        if letter_spacing != 0.0 {
            for (i, position) in self.positions.iter_mut().enumerate() {
                position.x += letter_spacing * i as f32;
            }
        }
        self.advance + letter_spacing * self.glyphs.len() as f32
    }

    /// Normalize ASCII word spaces inside a sideways Western run to JLREQ's
    /// preferred one-third em. Shaping retains the source scalar and break
    /// opportunity; only its advance and following glyph positions change.
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
            rotated_baseline_shift: rotated_run_baseline_shift(
                info.font,
                &self.scratch_glyphs,
                &positions,
            ),
            font: info.font.clone(),
            glyphs: self.scratch_glyphs.clone(),
            positions,
            advances,
            clusters: self.scratch_clusters.clone(),
            advance: info.advance.x,
            utf8_range: info.utf8_range.clone(),
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

/// `vpal` is deliberately absent: SkShaper shapes on a horizontal line,
/// where HarfBuzz would apply the feature's y-placement deltas as glyph
/// offsets without its advance deltas. Vertical layout applies the parsed
/// GPOS `vpal` metrics to upright cells itself.
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
    let shaper = skia::Shaper::new(fallback.clone());
    let mut font_iter = skia::Shaper::new_font_mgr_run_iterator(text, font, Some(fallback));
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
    shaper.shape_with_iterators_and_features(
        text,
        &mut font_iter,
        &mut bidi_iter,
        &mut script_iter,
        &mut lang_iter,
        &features,
        f32::MAX,
        &mut collector,
    );
    collector.runs
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

/// Shape a segment with explicit per-character font fallback. Penpot's
/// `TypefaceFontProvider` can resolve named families, but its character
/// fallback hook is not available consistently in every Skia build. Resolve
/// coverage here so missing glyphs never depend on that hook.
pub(crate) fn shape_segment_with_fallbacks(
    text: &str,
    font_size: f32,
    families: &[String],
    font_provider: &TypefaceFontProvider,
    upright: bool,
    font_features: FontFeatures,
    fallback_mgr: &FontMgr,
) -> Vec<ShapedRun> {
    let typefaces: Vec<Typeface> = families
        .iter()
        .filter_map(|family| font_provider.match_family_style(family, skia::FontStyle::default()))
        .collect();
    if typefaces.is_empty() {
        return Vec::new();
    }

    let mut result = Vec::new();
    for (range, typeface_index) in typeface_chunks(text, &typefaces) {
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

/// Centres the glyphs' actual ink; ascent/descent metrics are only the
/// fallback for runs without visible ink.
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

    #[test]
    fn rotated_run_centres_actual_lowercase_ink() {
        let layout = layout_content(&make_content(&["a"], 1000.0), 1000.0);
        let cell = &layout.cells[0];
        let CellKind::Rotated { run } = cell.kind else {
            panic!("expected a rotated run");
        };
        let run = &layout.runs[run];
        let mut bounds = vec![skia::Rect::default(); run.glyphs.len()];
        run.font.get_bounds(&run.glyphs, &mut bounds, None);
        let top = bounds
            .iter()
            .zip(&run.positions)
            .map(|(bound, position)| bound.top + position.y)
            .fold(f32::MAX, f32::min);
        let bottom = bounds
            .iter()
            .zip(&run.positions)
            .map(|(bound, position)| bound.bottom + position.y)
            .fold(f32::MIN, f32::max);
        let shift = run.rotated_baseline_shift;
        assert!(((top + bottom) / 2.0 + shift).abs() < 0.01);

        // Lowercase ink does not occupy the face's full ascent/descent band;
        // this guards against regressing to font-wide metric centring.
        let (_, metrics) = run.font.metrics();
        let metrics_shift = rotated_baseline_shift(metrics.ascent, metrics.descent);
        assert!((shift - metrics_shift).abs() > 0.1);
    }
}
