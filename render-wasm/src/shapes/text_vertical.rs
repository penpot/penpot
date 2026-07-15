// Vertical (tategaki) text layout: columns flow top->bottom and advance
// right->left. Skia's skparagraph has no vertical writing mode, so this
// module owns the whole vertical pipeline:
//
//   segment spans by glyph orientation -> shape each segment with SkShaper
//   (OpenType `vert`/`vrt2` for upright runs) -> plan kinsoku-aware column
//   breaks -> derive cells (one per upright glyph cluster / one per rotated
//   run) -> paint / position-data / hit-testing from the cells.
//
// Offset discipline: cells use UTF-16 offsets in transformed layout text.
// Position data maps those ranges back to the original span text before it
// crosses the WASM boundary; the WORD JOINER OffsetMap remains exclusive to
// skparagraph-driven horizontal breaks.
//
// Layouts are computed on demand (like the horizontal path, which rebuilds
// its skparagraph objects per paint); nothing is cached.
//
// Strokes (center/inner/outer, masked to the glyph silhouettes), drop
// shadows and decorations (underline / line-through as bars along the
// column) are painted from the same cells as the fills.
//
// Text-align aligns each column's glyphs along the vertical (inline) axis:
// Left/Start->top, Center->middle, Right/End->bottom of the wrap budget.
// Justify stretches every column but the last to fill the wrap budget,
// distributing the leftover space evenly between the column's cells.
//
// Letter-spacing adds inter-glyph advance along the column — once per
// upright cluster and once per glyph inside a rotated run — mirroring the
// horizontal `letter-spacing` that Skia applies to each glyph advance.
// Upright CJK <-> rotated alphanumeric boundaries additionally receive the
// conventional quarter-em inter-script gap.
//
// Deferred to a later phase (documented in the phase handoff): PDF/vector
// emoji overlays, inner shadows and block-axis vertical-align.

use skia_safe::{
    self as skia,
    canvas::SaveLayerRec,
    shaper::{
        run_handler::{Buffer, RunInfo},
        Feature, RunHandler,
    },
    textlayout::{Paragraph as SkiaParagraph, TextDecoration, TypefaceFontProvider},
    BlendMode, Canvas, Contains, Font, FontMgr, GlyphId, ImageFilter, Paint, Point as SkPoint,
    TextBlob, TextBlobBuilder,
};

use crate::get_render_state;
use crate::math::Rect;
use crate::render::DEFAULT_EMOJI_FONT;
use crate::shapes::japanese::{classify, pair_rule, JapaneseClass};
use crate::shapes::kinsoku::{forbidden_at_line_end, forbidden_at_line_start};
use crate::shapes::{
    merge_fills, AppliedTextTransform, FontFeatures, GrowType, PositionData, RubyAlign,
    RubyOverhang, RubySide, Stroke, StrokeKind, TextAlign, TextCombineUpright, TextContent,
    TextOrientation, TextSpan, VerticalAlign,
};
use crate::utils::get_fallback_fonts;

/// Emoji ranges recognized by the frontend font-loader. Emoji use their
/// intrinsic upright presentation in vertical flow instead of rotating like
/// Latin text under `text-orientation: mixed`.
fn is_emoji_char(c: char) -> bool {
    matches!(u32::from(c),
        0x2300..=0x23FF
        | 0x2600..=0x27BF
        | 0x2B00..=0x2BFF
        | 0x1F000..=0x1FAFF
    )
}

fn span_font_families(span: &TextSpan, fallback_families: &[String]) -> Vec<String> {
    let mut families = vec![
        format!("{}", span.font_family),
        DEFAULT_EMOJI_FONT.to_string(),
    ];
    families.extend(fallback_families.iter().cloned());
    families
}

/// Characters that stay upright in vertical flow: kana, kanji, CJK
/// punctuation, full-width forms and emoji. Everything else rotates sideways
/// under `text-orientation: mixed`.
pub fn is_upright_char(c: char) -> bool {
    is_emoji_char(c)
        || matches!(u32::from(c),
        0x2E80..=0x2FDF   // CJK radicals, Kangxi radicals
        | 0x3000..=0x303F // CJK symbols and punctuation
        | 0x3040..=0x30FF // hiragana, katakana
        | 0x31C0..=0x31EF // CJK strokes
        | 0x31F0..=0x31FF // katakana phonetic extensions
        | 0x3200..=0x33FF // enclosed CJK, CJK compatibility
        | 0x3400..=0x4DBF // CJK unified ideographs extension A
        | 0x4E00..=0x9FFF // CJK unified ideographs
        | 0xAC00..=0xD7AF // hangul syllables
        | 0xF900..=0xFAFF // CJK compatibility ideographs
        | 0xFE30..=0xFE4F // CJK compatibility forms
        | 0xFF00..=0xFF60 // full-width forms
        | 0xFFE0..=0xFFE6 // full-width signs
        | 0x20000..=0x2FA1F // CJK extensions B..F
        )
}

/// Characters whose horizontal glyph needs a vertical alternate. `vert` /
/// `vrt2` normally supplies that alternate; when the selected face has no
/// such substitution, rotate the horizontal glyph clockwise as a legible
/// fallback. The set covers UAX #50 `Tr`, plus comma/full-stop punctuation
/// whose untransformed glyph otherwise occupies the wrong half of the
/// vertical em box.
fn uses_rotated_vertical_fallback(c: char) -> bool {
    matches!(u32::from(c),
        0x2018..=0x2019 // single quotation marks
        | 0x201C..=0x201D // double quotation marks
        | 0x2329..=0x232A // angle brackets
        | 0x3001..=0x3002 // ideographic comma / full stop
        | 0x3008..=0x301F // CJK brackets, wave dash and quotation marks
        | 0x3030 // wavy dash
        | 0x30A0 // katakana-hiragana double hyphen
        | 0x30FC // prolonged sound mark
        | 0xFE50..=0xFE52 // small comma / ideographic comma / full stop
        | 0xFE59..=0xFE5E // small brackets
        | 0xFF08..=0xFF09 // full-width parentheses
        | 0xFF0C // full-width comma
        | 0xFF0D // full-width hyphen-minus
        | 0xFF0E // full-width full stop
        | 0xFF1A..=0xFF1B // full-width colon / semicolon
        | 0xFF1C..=0xFF1E // full-width comparison signs
        | 0xFF3B // full-width left square bracket
        | 0xFF3D // full-width right square bracket
        | 0xFF3F // full-width low line
        | 0xFF5B..=0xFF60 // full-width braces, bars, tilde and white parentheses
        | 0xFFE3 // full-width macron
    )
}

fn cluster_needs_rotated_vertical_fallback(
    run: &ShapedRun,
    glyph: usize,
    count: usize,
    ch: Option<char>,
) -> bool {
    let Some(ch) = ch else {
        return false;
    };
    count == 1
        && uses_rotated_vertical_fallback(ch)
        && run.glyphs[glyph] == run.font.unichar_to_glyph(ch as i32)
}

#[derive(Debug, PartialEq)]
pub struct Segment {
    pub text: String,
    /// UTF-16 offset of the segment start within its span text.
    pub utf16_start: usize,
    pub upright: bool,
}

/// Split text into maximal runs of same orientation. Under
/// `TextOrientation::Upright` every character is upright.
pub fn segment_by_orientation(text: &str, orientation: TextOrientation) -> Vec<Segment> {
    let mut segments: Vec<Segment> = Vec::new();
    let mut utf16_offset = 0;
    for c in text.chars() {
        let upright = orientation == TextOrientation::Upright || is_upright_char(c);
        let emoji = is_emoji_char(c);
        // CJK and emoji are both upright, but keep a shaping boundary between
        // them so the segment probe can explicitly select the emoji family.
        // The provider's generic fallback iterator does not reliably switch
        // from a registered CJK face to a registered color-emoji face.
        match segments.last_mut() {
            Some(last)
                if last.upright == upright
                    && last.text.chars().next_back().is_some_and(is_emoji_char) == emoji =>
            {
                last.text.push(c)
            }
            _ => segments.push(Segment {
                text: c.to_string(),
                utf16_start: utf16_offset,
                upright,
            }),
        }
        utf16_offset += c.len_utf16();
    }
    segments
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
    pub utf8_range: std::ops::Range<usize>,
    /// Local-y offset that centres the run's ink after 90° rotation.
    pub rotated_baseline_shift: f32,
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
        let mut advances: Vec<f32> = Vec::with_capacity(info.glyph_count);
        for i in 0..positions.len() {
            let next = if i + 1 < positions.len() {
                positions[i + 1].x
            } else {
                end
            };
            advances.push(next - positions[i].x);
        }
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
fn shape_segment(
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

/// Vertical advances from a font's `vhea`/`vmtx` tables. Upright cells
/// advance down the column by the glyph's true vertical advance rather
/// than its shaped horizontal advance: identical for full-width CJK, but
/// correct for vertical alternates and proportional glyphs whose `vmtx`
/// differs from `hmtx` (e.g. the vertical kana repeat marks).
struct VerticalMetrics {
    units_per_em: f32,
    /// Advance heights of the first `advances.len()` glyph ids.
    advances: Vec<u16>,
    /// Advance for glyph ids at or beyond the long-metric count.
    last: u16,
}

impl VerticalMetrics {
    /// Parse `vhea`/`vmtx` off the font's typeface. Returns `None` when the
    /// font carries no vertical metrics (the caller then keeps horizontal
    /// advances).
    fn from_font(font: &Font) -> Option<Self> {
        let typeface = font.typeface();
        let units_per_em = typeface.units_per_em()? as f32;
        if units_per_em <= 0.0 {
            return None;
        }
        let vhea = typeface.copy_table_data(u32::from_be_bytes(*b"vhea"))?;
        let vmtx = typeface.copy_table_data(u32::from_be_bytes(*b"vmtx"))?;
        let vhea = vhea.as_bytes();
        let vmtx = vmtx.as_bytes();
        // `numberOfLongVerMetrics` is the trailing u16 of the 36-byte header.
        let num_long = u16::from_be_bytes([*vhea.get(34)?, *vhea.get(35)?]) as usize;
        if num_long == 0 {
            return None;
        }
        // Each long metric is { advanceHeight: u16, topSideBearing: i16 }.
        let mut advances = Vec::with_capacity(num_long);
        for i in 0..num_long {
            let o = i * 4;
            let (Some(&hi), Some(&lo)) = (vmtx.get(o), vmtx.get(o + 1)) else {
                break;
            };
            advances.push(u16::from_be_bytes([hi, lo]));
        }
        let last = *advances.last()?;
        Some(Self {
            units_per_em,
            advances,
            last,
        })
    }

    /// Vertical advance of `glyph` at `font_size`, in pixels at the shaped
    /// size (same units as the horizontal advances).
    fn advance(&self, glyph: GlyphId, font_size: f32) -> f32 {
        let raw = self
            .advances
            .get(glyph as usize)
            .copied()
            .unwrap_or(self.last);
        raw as f32 * font_size / self.units_per_em
    }
}

thread_local! {
    /// Parsed `vhea`/`vmtx` per typeface. Layouts are recomputed on every
    /// paint but a face's vertical metrics never change, so the table
    /// parse (which copies the whole table) is done once per typeface.
    // Keyed by the typeface's unique id (`SkTypefaceID`, a `u32`).
    static VERTICAL_METRICS_CACHE: std::cell::RefCell<
        std::collections::HashMap<u32, Option<std::rc::Rc<VerticalMetrics>>>,
    > = std::cell::RefCell::new(std::collections::HashMap::new());
}

fn vertical_metrics(font: &Font) -> Option<std::rc::Rc<VerticalMetrics>> {
    let id = font.typeface().unique_id();
    VERTICAL_METRICS_CACHE.with(|cache| {
        cache
            .borrow_mut()
            .entry(id)
            .or_insert_with(|| VerticalMetrics::from_font(font).map(std::rc::Rc::new))
            .clone()
    })
}

/// GPOS `vpal` deltas for one typeface, in font units.
struct VpalTable {
    units_per_em: f32,
    deltas: std::collections::HashMap<u16, super::gpos_vpal::VpalDelta>,
}

impl VpalTable {
    fn from_font(font: &Font) -> Option<Self> {
        let typeface = font.typeface();
        let units_per_em = typeface.units_per_em()? as f32;
        if units_per_em <= 0.0 {
            return None;
        }
        let gpos = typeface.copy_table_data(u32::from_be_bytes(*b"GPOS"))?;
        let deltas = super::gpos_vpal::parse_vpal(gpos.as_bytes())?;
        Some(Self {
            units_per_em,
            deltas,
        })
    }

    /// Pixel deltas for a cluster at `font_size`: summed advance delta
    /// (negative when the cell tightens) and the flow-axis shift of the
    /// drawn ink (positive down the column). GPOS `yPlacement` is y-up,
    /// so its sign flips into flow space. `None` when no glyph of the
    /// cluster is covered.
    fn cluster_delta(&self, glyphs: &[GlyphId], font_size: f32) -> Option<(f32, f32)> {
        let scale = font_size / self.units_per_em;
        let mut advance = 0.0f32;
        let mut flow_shift = None;
        for glyph in glyphs {
            if let Some(delta) = self.deltas.get(glyph) {
                advance += delta.y_advance as f32 * scale;
                flow_shift.get_or_insert(-(delta.y_placement as f32) * scale);
            }
        }
        flow_shift.map(|shift| (advance, shift))
    }
}

thread_local! {
    /// Parsed GPOS `vpal` deltas per typeface, keyed by typeface unique id
    /// (same lifetime argument as `VERTICAL_METRICS_CACHE`).
    static VPAL_TABLE_CACHE: std::cell::RefCell<
        std::collections::HashMap<u32, Option<std::rc::Rc<VpalTable>>>,
    > = std::cell::RefCell::new(std::collections::HashMap::new());
}

fn vpal_table(font: &Font) -> Option<std::rc::Rc<VpalTable>> {
    let id = font.typeface().unique_id();
    VPAL_TABLE_CACHE.with(|cache| {
        cache
            .borrow_mut()
            .entry(id)
            .or_insert_with(|| VpalTable::from_font(font).map(std::rc::Rc::new))
            .clone()
    })
}

/// OS/2 typographic ascender/descender, normalised to the em (design units /
/// unitsPerEm). These bound the ideographic em box and are what the browser
/// uses for the vertical central baseline; `hhea`/`Font::metrics` are oversized
/// for CJK faces (their ascent exceeds the em) and would push an upright glyph
/// off the ideographic centre of its cell.
#[derive(Clone, Copy)]
struct TypoMetrics {
    /// sTypoAscender / unitsPerEm (positive, above the baseline).
    ascender: f32,
    /// sTypoDescender / unitsPerEm (negative, below the baseline).
    descender: f32,
}

impl TypoMetrics {
    fn from_font(font: &Font) -> Option<Self> {
        let typeface = font.typeface();
        let units_per_em = typeface.units_per_em()? as f32;
        if units_per_em <= 0.0 {
            return None;
        }
        let os2 = typeface.copy_table_data(u32::from_be_bytes(*b"OS/2"))?;
        let os2 = os2.as_bytes();
        // sTypoAscender @ 68 (i16), sTypoDescender @ 70 (i16); present in every
        // OS/2 table version.
        let ascender = i16::from_be_bytes([*os2.get(68)?, *os2.get(69)?]) as f32;
        let descender = i16::from_be_bytes([*os2.get(70)?, *os2.get(71)?]) as f32;
        Some(Self {
            ascender: ascender / units_per_em,
            descender: descender / units_per_em,
        })
    }
}

thread_local! {
    /// Parsed OS/2 typographic metrics per typeface, cached like the vertical
    /// metrics because layouts recompute every paint but a face's metrics are
    /// constant. Keyed by the typeface's unique id.
    static TYPO_METRICS_CACHE: std::cell::RefCell<
        std::collections::HashMap<u32, Option<TypoMetrics>>,
    > = std::cell::RefCell::new(std::collections::HashMap::new());
}

/// Ascent/descent for centring an upright cell, in Skia's sign convention
/// (ascent negative, descent positive) at the font's current size. Prefers the
/// OS/2 typographic metrics (the ideographic em box, matching the browser's
/// vertical central baseline used by the SVG/foreignObject export); falls back
/// to `Font::metrics` when the face carries no OS/2 table.
fn upright_centre_metrics(font: &Font) -> (f32, f32) {
    let id = font.typeface().unique_id();
    let typo = TYPO_METRICS_CACHE.with(|cache| {
        *cache
            .borrow_mut()
            .entry(id)
            .or_insert_with(|| TypoMetrics::from_font(font))
    });
    if let Some(typo) = typo {
        let size = font.size();
        return (-typo.ascender * size, -typo.descender * size);
    }
    let (_, metrics) = font.metrics();
    (metrics.ascent, metrics.descent)
}

/// A placeable item in the vertical flow: its extent along the column and,
/// for single-character cells, the character (used for kinsoku decisions
/// at column breaks). Rotated runs carry no character and are unsplittable.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FlowItem {
    pub extent: f32,
    pub ch: Option<char>,
    /// This item and its predecessor came from the same source character
    /// after a length-expanding text transform, so a column break must not
    /// split them.
    pub keep_with_previous: bool,
}

/// Assign items to columns: returns (column-index, offset-from-top) per
/// item. An item that overflows the column height starts a new column;
/// items taller than the column occupy one on their own. Kinsoku: a break
/// may not leave a forbidden-at-line-end character at the column bottom
/// nor put a forbidden-at-line-start character at the next column top;
/// offending predecessors are pushed to the new column (oidashi), bounded
/// so a pathological run cannot empty its column.
/// Punctuation allowed to hang past the column bottom (ぶら下げ / burasage): the
/// ideographic and full-width comma and period. When such a mark is the item
/// that would overflow the column, it protrudes into the margin instead of
/// wrapping (itself and its predecessor) to the next column.
fn can_hang(c: char) -> bool {
    matches!(c, '、' | '。' | '，' | '．')
}

pub fn plan_columns(items: &[FlowItem], max_height: f32) -> Vec<(usize, f32)> {
    const MAX_KINSOKU_SHIFT: usize = 4;

    let mut placements: Vec<(usize, f32)> = Vec::with_capacity(items.len());
    let mut column = 0usize;
    let mut cursor = 0.0f32;
    let mut column_start = 0usize;

    for (i, item) in items.iter().enumerate() {
        // Burasage: let a comma/period hang past the column bottom rather than
        // break. The next non-hangable item still sees the overflowed cursor
        // and wraps normally; oidashi never pulls the hung marks because they
        // are forbidden-at-line-start, not forbidden-at-line-end.
        if cursor > 0.0 && cursor + item.extent > max_height && !item.ch.is_some_and(can_hang) {
            let mut break_at = i;
            while break_at > column_start && items[break_at].keep_with_previous {
                break_at -= 1;
            }
            // The current item closes an atomic composite that began at the
            // column head (group ruby or an expanded source scalar). Keep the
            // complete unit together even when it is taller than the nominal
            // column; there is no legal internal break to choose instead.
            if break_at == column_start && items[i].keep_with_previous {
                placements.push((column, cursor));
                cursor += item.extent;
                continue;
            }
            let mut shifted = 0;
            while break_at > column_start
                && shifted < MAX_KINSOKU_SHIFT
                && (items[break_at].ch.is_some_and(forbidden_at_line_start)
                    || items[break_at - 1].ch.is_some_and(forbidden_at_line_end))
            {
                break_at -= 1;
                while break_at > column_start && items[break_at].keep_with_previous {
                    break_at -= 1;
                }
                shifted += 1;
            }
            // Give up on the shift when the moved items plus the current one
            // would overflow the new column too: overflowing the wrap budget
            // is worse than the kinsoku violation.
            let shifted_extent: f32 = items[break_at..i].iter().map(|it| it.extent).sum();
            if break_at < i && shifted_extent + item.extent > max_height {
                break_at = i;
            }
            column += 1;
            let mut new_cursor = 0.0;
            for k in break_at..i {
                placements[k] = (column, new_cursor);
                new_cursor += items[k].extent;
            }
            cursor = new_cursor;
            column_start = break_at;
        }
        placements.push((column, cursor));
        cursor += item.extent;
    }
    placements
}

/// Offset of a column's content along the column (vertical/inline) axis for
/// a given `text-align`. In `vertical-rl` the inline axis runs top->bottom, so
/// Left/Start anchor to the top, Right/End to the bottom and Center to the
/// middle of the wrap budget. `budget` is the column wrap height (the box
/// height); an unbounded budget (auto-width, columns are snug) yields no shift.
/// Justify yields no uniform shift here — its space is distributed between
/// cells per column by `justify_extra` in the placement loop.
pub fn align_offset_along_column(align: TextAlign, budget: f32, used: f32) -> f32 {
    if !budget.is_finite() || budget >= f32::MAX || budget <= 0.0 {
        return 0.0;
    }
    let slack = (budget - used).max(0.0);
    match align {
        TextAlign::Center => slack / 2.0,
        TextAlign::Right | TextAlign::End => slack,
        _ => 0.0,
    }
}

const INTER_SCRIPT_SPACING_EM: f32 = 0.25;
const WESTERN_WORD_SPACING_EM: f32 = 1.0 / 3.0;

#[derive(Debug, Clone, Copy)]
enum FlowScript {
    Upright,
    Rotated {
        starts_alphanumeric: bool,
        ends_alphanumeric: bool,
    },
}

/// Full-width-font punctuation whose glyph body occupies the leading (top)
/// half of its em. Its normal trailing half-em aki remains in ordinary text
/// and is shed only at punctuation-sequence boundaries.
fn sheds_trailing_aki(c: char) -> bool {
    classify(c).is_trailing_aki_punctuation()
}

/// Opening punctuation has a half-width glyph body and normally keeps a
/// leading half-em aki. That aki is shed after another opening bracket or a
/// middle dot, and at a wrapped line head (JIS X 4051 tentsuki policy).
fn sheds_leading_aki(c: char) -> bool {
    classify(c) == JapaneseClass::OpeningBracket
}

fn embedded_leading_aki(class: JapaneseClass) -> f32 {
    match class {
        JapaneseClass::OpeningBracket => 0.5,
        JapaneseClass::MiddleDot => 0.25,
        _ => 0.0,
    }
}

fn embedded_trailing_aki(class: JapaneseClass) -> f32 {
    match class {
        JapaneseClass::ClosingBracket | JapaneseClass::FullStop | JapaneseClass::Comma => 0.5,
        JapaneseClass::MiddleDot => 0.25,
        _ => 0.0,
    }
}

fn flow_class(cell: &VerticalCell, item: &FlowItem) -> Option<JapaneseClass> {
    match cell.kind {
        CellKind::TateChuYoko { .. } => Some(JapaneseClass::TateChuYoko),
        CellKind::Rotated { .. } => Some(JapaneseClass::Western),
        _ => item.ch.map(classify),
    }
}

/// Divide `amount` equally across capped opportunities, redistributing the
/// remainder whenever one opportunity reaches its cap.
fn capped_equal_allocations(capacities: &[(usize, f32)], amount: f32) -> Vec<(usize, f32)> {
    let mut allocations: Vec<(usize, f32)> = capacities.iter().map(|(i, _)| (*i, 0.0)).collect();
    let mut remaining = amount.max(0.0);
    while remaining > 0.0001 {
        let active: Vec<usize> = capacities
            .iter()
            .enumerate()
            .filter_map(|(slot, (_, cap))| (allocations[slot].1 + 0.0001 < *cap).then_some(slot))
            .collect();
        if active.is_empty() {
            break;
        }
        let share = remaining / active.len() as f32;
        let mut used = 0.0;
        for slot in active {
            let cap = capacities[slot].1;
            let delta = share.min(cap - allocations[slot].1);
            allocations[slot].1 += delta;
            used += delta;
        }
        if used <= 0.0001 {
            break;
        }
        remaining -= used;
    }
    allocations
}

fn spacing_owner(_before: JapaneseClass, after: JapaneseClass, boundary: usize) -> (usize, bool) {
    if matches!(
        after,
        JapaneseClass::OpeningBracket | JapaneseClass::MiddleDot
    ) {
        // Sequence layout removes any redundant preceding trailing half; the
        // remaining aki is the next glyph's embedded leading space.
        (boundary + 1, true)
    } else {
        (boundary, false)
    }
}

fn reduce_cell_spacing(
    cells: &mut [VerticalCell],
    items: &mut [FlowItem],
    index: usize,
    amount: f32,
    leading: bool,
) {
    let amount = amount.min(items[index].extent.max(0.0));
    items[index].extent -= amount;
    cells[index].extent -= amount;
    if leading {
        cells[index].glyph_flow_shift -= amount;
    }
}

/// JLREQ oikomi: before wrapping a non-hanging item, try to keep it in the
/// current column by reducing only legal aki, in table priority order. If the
/// complete deficit cannot be recovered, leave the line untouched for the
/// subsequent oidashi/kinsoku planner.
fn apply_ordered_oikomi(
    cells: &mut [VerticalCell],
    items: &mut [FlowItem],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &mut [f32],
    max_height: f32,
) {
    if !max_height.is_finite() || max_height <= 0.0 || max_height >= f32::MAX {
        return;
    }
    let mut column_start = 0usize;
    let mut cursor = 0.0f32;
    for i in 0..items.len() {
        if cursor > 0.0
            && cursor + items[i].extent > max_height
            && !items[i].ch.is_some_and(can_hang)
        {
            let deficit = cursor + items[i].extent - max_height;
            let mut opportunities: Vec<(usize, u8, f32, f32)> = Vec::new();
            for boundary in column_start..i {
                let (Some(before), Some(after)) = (classes[boundary], classes[boundary + 1]) else {
                    continue;
                };
                let rule = pair_rule(before, after);
                if rule.shrink_priority == 0 || pair_spacing_em[boundary] <= rule.minimum_em {
                    continue;
                }
                let em = cells[boundary].font_size.min(cells[boundary + 1].font_size);
                let capacity = (pair_spacing_em[boundary] - rule.minimum_em) * em;
                opportunities.push((boundary, rule.shrink_priority, capacity, em));
            }
            let total_capacity: f32 = opportunities.iter().map(|(_, _, cap, _)| *cap).sum();
            if total_capacity + 0.0001 >= deficit {
                let mut remaining = deficit;
                for priority in 1..=5 {
                    if remaining <= 0.0001 {
                        break;
                    }
                    let caps: Vec<(usize, f32)> = opportunities
                        .iter()
                        .filter(|(_, p, _, _)| *p == priority)
                        .map(|(boundary, _, cap, _)| (*boundary, *cap))
                        .collect();
                    if caps.is_empty() {
                        continue;
                    }
                    let available: f32 = caps.iter().map(|(_, cap)| *cap).sum();
                    for (boundary, reduction) in
                        capped_equal_allocations(&caps, remaining.min(available))
                    {
                        let (Some(before), Some(after)) =
                            (classes[boundary], classes[boundary + 1])
                        else {
                            continue;
                        };
                        let em = cells[boundary].font_size.min(cells[boundary + 1].font_size);
                        let (owner, leading) = spacing_owner(before, after, boundary);
                        reduce_cell_spacing(cells, items, owner, reduction, leading);
                        pair_spacing_em[boundary] -= reduction / em;
                        if owner < i {
                            cursor -= reduction;
                        }
                        remaining -= reduction;
                    }
                }
            }
            if cursor + items[i].extent > max_height + 0.0001 {
                column_start = i;
                cursor = 0.0;
            }
        }
        cursor += items[i].extent;
    }
}

/// Ordered oidashi expansion for justified columns. Stages 1–3 respect the
/// table caps; if slack remains, stage 4 distributes it equally across every
/// otherwise-expandable boundary, as required by JLREQ §3.8.4.
fn ordered_expansion_offsets(
    cells: &[VerticalCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &[f32],
    placements: &[(usize, f32)],
    column_used: &[f32],
    max_height: f32,
    last_column: usize,
) -> Vec<f32> {
    let mut boundary_expansion = vec![0.0f32; cells.len().saturating_sub(1)];
    for (column, used) in column_used.iter().enumerate().take(last_column) {
        let mut boundaries: Vec<(usize, u8, f32)> = Vec::new();
        for boundary in 0..cells.len().saturating_sub(1) {
            if placements[boundary].0 != column || placements[boundary + 1].0 != column {
                continue;
            }
            let (Some(before), Some(after)) = (classes[boundary], classes[boundary + 1]) else {
                continue;
            };
            let rule = pair_rule(before, after);
            if rule.expand_priority == 0 {
                continue;
            }
            let em = cells[boundary].font_size.min(cells[boundary + 1].font_size);
            let cap = (rule.maximum_em - pair_spacing_em[boundary]).max(0.0) * em;
            boundaries.push((boundary, rule.expand_priority, cap));
        }
        let mut remaining = (max_height - used).max(0.0);
        for priority in 1..=3 {
            let caps: Vec<(usize, f32)> = boundaries
                .iter()
                .filter(|(_, p, _)| *p == priority)
                .map(|(boundary, _, cap)| (*boundary, *cap))
                .collect();
            let available: f32 = caps.iter().map(|(_, cap)| *cap).sum();
            for (boundary, expansion) in capped_equal_allocations(&caps, remaining.min(available)) {
                boundary_expansion[boundary] += expansion;
                remaining -= expansion;
            }
        }
        if remaining > 0.0001 && !boundaries.is_empty() {
            let extra = remaining / boundaries.len() as f32;
            for (boundary, _, _) in &boundaries {
                boundary_expansion[*boundary] += extra;
            }
        }
    }

    let mut offsets = vec![0.0f32; cells.len()];
    for i in 1..cells.len() {
        if placements[i].0 == placements[i - 1].0 {
            offsets[i] = offsets[i - 1] + boundary_expansion[i - 1];
        }
    }
    offsets
}

/// Centered punctuation (jlreq class cl-05 中点類): the middle dot, the
/// full-width colon and the full-width semicolon are placed at the centre of
/// the em box in vertical writing rather than on the horizontal baseline.
fn is_centered_punctuation(c: char) -> bool {
    classify(c) == JapaneseClass::MiddleDot
}

/// Flow-axis shift that centres a glyph's ink band within its em body: moves
/// the ink midpoint (`(ink_top + ink_bottom) / 2`) to the body midpoint.
fn centered_flow_shift(ink_top: f32, ink_bottom: f32, em_body: f32) -> f32 {
    em_body / 2.0 - (ink_top + ink_bottom) / 2.0
}

/// Japanese inter-script spacing at an upright CJK <-> rotated alphabetic or
/// numeric boundary. Punctuation and explicit whitespace do not create an
/// automatic gap. Use the smaller adjacent font size so a large neighboring
/// run cannot create a disproportionate gap.
fn inter_script_spacing(
    previous: FlowScript,
    previous_font_size: f32,
    next: FlowScript,
    next_font_size: f32,
) -> f32 {
    let boundary = matches!(
        (previous, next),
        (
            FlowScript::Upright,
            FlowScript::Rotated {
                starts_alphanumeric: true,
                ..
            }
        ) | (
            FlowScript::Rotated {
                ends_alphanumeric: true,
                ..
            },
            FlowScript::Upright
        )
    );
    if boundary {
        previous_font_size.min(next_font_size) * INTER_SCRIPT_SPACING_EM
    } else {
        0.0
    }
}

/// Offset that centres a local-y band on the column axis after the run is
/// rotated 90°. The draw-time path supplies the glyphs' actual ink bounds;
/// ascent/descent metrics are only the fallback for runs without visible ink.
pub fn rotated_baseline_shift(top: f32, bottom: f32) -> f32 {
    -(top + bottom) / 2.0
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

fn rotated_run_baseline_shift(font: &Font, glyphs: &[GlyphId], positions: &[SkPoint]) -> f32 {
    if let Some(ink) = glyph_run_ink_bounds(font, glyphs, positions) {
        rotated_baseline_shift(ink.top, ink.bottom)
    } else {
        let (_, metrics) = font.metrics();
        rotated_baseline_shift(metrics.ascent, metrics.descent)
    }
}

/// Normalize ASCII word spaces inside a sideways Western run to JLREQ's
/// preferred one-third em. Shaping retains the source scalar and break
/// opportunity; only its advance and following glyph positions change.
fn normalize_rotated_word_spaces(segment_text: &str, run: &mut ShapedRun, font_size: f32) {
    let mut glyph = 0usize;
    let mut accumulated_shift = 0.0f32;
    while glyph < run.glyphs.len() {
        let cluster = run.clusters[glyph];
        let mut count = 1usize;
        while glyph + count < run.glyphs.len() && run.clusters[glyph + count] == cluster {
            count += 1;
        }
        for position in &mut run.positions[glyph..glyph + count] {
            position.x += accumulated_shift;
        }
        let is_word_space = segment_text
            .get(cluster as usize..)
            .and_then(|text| text.chars().next())
            .is_some_and(|character| character == ' ');
        if is_word_space {
            let natural: f32 = run.advances[glyph..glyph + count].iter().sum();
            let delta = font_size * WESTERN_WORD_SPACING_EM - natural;
            run.advances[glyph + count - 1] += delta;
            accumulated_shift += delta;
        }
        glyph += count;
    }
    run.advance += accumulated_shift;
}

/// Vertical offset from a cell's top edge to the glyph baseline for an upright
/// cell. Centres the glyph's line box within the em cell (as the Tate-chu-yoko
/// path does) instead of hanging it from the horizontal ascent: CJK faces have
/// an ascent larger than the em, so hanging from it pushes every glyph below
/// its cell and the whole column overflows its bounds.
fn upright_baseline_offset(ascent: f32, descent: f32, font_size: f32) -> f32 {
    font_size / 2.0 - (ascent + descent) / 2.0
}

fn upright_flow_ink_bounds(run: &ShapedRun, glyph: usize, count: usize) -> Option<(f32, f32)> {
    let base_x = run.positions[glyph].x;
    let positions: Vec<SkPoint> = run.positions[glyph..glyph + count]
        .iter()
        .map(|position| SkPoint::new(position.x - base_x, position.y))
        .collect();
    let ink = glyph_run_ink_bounds(&run.font, &run.glyphs[glyph..glyph + count], &positions)?;
    let (ascent, descent) = upright_centre_metrics(&run.font);
    let offset = upright_baseline_offset(ascent, descent, run.font.size());
    Some((ink.top + offset, ink.bottom + offset))
}

fn rotated_cluster_flow_ink_bounds(
    run: &ShapedRun,
    glyph: usize,
    count: usize,
) -> Option<(f32, f32)> {
    let base_x = run.positions[glyph].x;
    let positions: Vec<SkPoint> = run.positions[glyph..glyph + count]
        .iter()
        .map(|position| SkPoint::new(position.x - base_x, position.y))
        .collect();
    let ink = glyph_run_ink_bounds(&run.font, &run.glyphs[glyph..glyph + count], &positions)?;
    Some((ink.left, ink.right))
}

fn cluster_text_blob(run: &ShapedRun, glyph: usize, count: usize) -> Option<TextBlob> {
    let mut builder = TextBlobBuilder::new();
    let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
    let base_x = run.positions[glyph].x;
    for i in 0..count {
        glyphs[i] = run.glyphs[glyph + i];
        points[i] = SkPoint::new(
            run.positions[glyph + i].x - base_x,
            run.positions[glyph + i].y,
        );
    }
    builder.make()
}

fn rotated_cluster_baseline_shift(run: &ShapedRun, glyph: usize, count: usize) -> f32 {
    let base_x = run.positions[glyph].x;
    let positions: Vec<SkPoint> = run.positions[glyph..glyph + count]
        .iter()
        .map(|position| SkPoint::new(position.x - base_x, position.y))
        .collect();
    rotated_run_baseline_shift(&run.font, &run.glyphs[glyph..glyph + count], &positions)
}

fn rotated_flow_ink_bounds(run: &ShapedRun) -> Option<(f32, f32)> {
    let ink = glyph_run_ink_bounds(&run.font, &run.glyphs, &run.positions)?;
    Some((ink.left, ink.right))
}

#[derive(Debug, Clone, Copy)]
pub enum CellKind {
    Upright {
        run: usize,
        glyph: usize,
        count: usize,
    },
    /// A character that participates in upright Japanese flow and kinsoku,
    /// but whose font has no `vert`/`vrt2` alternate. It is rotated per cell
    /// instead of becoming a sideways run so wrapping and editor offsets stay
    /// character-granular.
    SyntheticRotated {
        run: usize,
        glyph: usize,
        count: usize,
    },
    Rotated {
        run: usize,
    },
    TateChuYoko {
        /// Composite of one or more shaped runs (fallback fonts each add a
        /// run) laid side by side; `[run_start, run_start + run_count)`.
        run_start: usize,
        run_count: usize,
        scale: f32,
    },
    Warichu {
        /// Two half-size sub-lines stacked side by side within the column:
        /// runs `[run_start, run_start + first_count)` are the first (right)
        /// sub-line, the rest up to `run_start + run_count` the second (left).
        run_start: usize,
        run_count: usize,
        first_count: usize,
        /// UTF-16 length of the first sub-line's text (the split point,
        /// relative to the cell's `start`).
        first_chars: usize,
    },
}

/// One placed piece of the vertical flow. Offsets are UTF-16,
/// paragraph-relative (all spans concatenated), in original text space.
pub struct VerticalCell {
    pub kind: CellKind,
    pub paragraph: usize,
    pub span: usize,
    pub start: usize,
    pub end: usize,
    pub column: usize,
    pub top: f32,
    /// Advance along the column (vertical/flow axis) — from `vmtx` when
    /// available, else the shaped horizontal advance.
    pub extent: f32,
    /// Shaped horizontal glyph advance, used to centre the glyph on the
    /// column axis (independent of the vertical flow `extent`).
    pub h_advance: f32,
    /// Visible glyph-ink edges along the flow axis, relative to `top`.
    pub ink_top: f32,
    pub ink_bottom: f32,
    pub paint: usize,
    /// Span font size, for decoration bar geometry.
    pub font_size: f32,
    /// Span text decoration, painted as vertical bars along the column.
    pub decoration: Option<TextDecoration>,
    /// Extra flow-axis (vertical) shift applied to the drawn glyph only, used to
    /// pull half-width opening punctuation up into its compressed cell so its
    /// ink hugs the preceding character (jlreq leading aki removal). Zero for
    /// every other cell; never affects extent, caret, or position-data.
    pub glyph_flow_shift: f32,
}

/// A laid-out column, x measured from the *content* left edge (the content
/// block is anchored to the shape's right edge by consumers).
#[derive(Debug, Clone, Copy)]
pub struct VerticalColumn {
    pub x: f32,
    pub width: f32,
    /// Reserved annotation gutter before the base band (left / `under`).
    pub base_offset: f32,
    /// Line-height-controlled column advance. Base glyphs centre on this band.
    /// Ruby reserves additional column width but attaches to the centred base
    /// em, so extra leading does not become base-to-ruby spacing.
    pub base_width: f32,
}

fn column_base_center(column: &VerticalColumn) -> f32 {
    column.x + column.base_offset + column.base_width / 2.0
}

/// One shaped ruby glyph, retaining its fallback-font run and source range.
#[derive(Debug, Clone, Copy)]
pub struct RubyGlyph {
    pub run: usize,
    pub glyph: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
}

/// A ruby annotation placed alongside one column of base characters.
/// Painted from `ruby_runs`; kept out of `cells` so base metrics and caret
/// geometry are unaffected.
#[derive(Debug, Clone)]
pub struct RubyCell {
    /// Ordered glyphs for this base column. Each retains the shaped run that
    /// supplied its font so fallback boundaries do not drop ruby content.
    pub glyphs: Vec<RubyGlyph>,
    pub paragraph: usize,
    pub span: usize,
    pub column: usize,
    /// Flow-axis (top, extent) of each base character this ruby annotates,
    /// in flow order, restricted to the annotated column. Group ruby spreads
    /// the annotation over the union; mono ruby maps ruby glyphs onto the
    /// individual segments.
    pub base_segments: Vec<(f32, f32)>,
    /// Final flow-axis positions computed from the explicit ruby mapping.
    pub glyph_tops: Vec<f32>,
    pub font_size: f32,
    pub base_font_size: f32,
    pub side: RubySide,
    pub paint: usize,
}

struct RubyColumnSegments {
    column: usize,
    paint: usize,
    segments: Vec<RubyBaseSegment>,
}

#[derive(Debug, Clone, Copy)]
struct RubyBaseSegment {
    top: f32,
    extent: f32,
}

/// One emphasis mark (圏点 / bouten) drawn beside a base character. Kept out of
/// `cells` like ruby, so base metrics, caret and position-data never see it.
/// The mark glyph is the single-glyph `run`; it is centred on the base cell's
/// flow extent and drawn in the column's right-side gutter.
pub struct EmphasisMark {
    pub run: usize,
    pub column: usize,
    /// Flow-axis top and extent of the annotated base cell.
    pub top: f32,
    pub extent: f32,
    pub paint: usize,
    pub font_size: f32,
    pub outside_offset: f32,
}

pub struct VerticalLayout {
    pub runs: Vec<ShapedRun>,
    pub paints: Vec<skia::Paint>,
    pub cells: Vec<VerticalCell>,
    pub columns: Vec<VerticalColumn>,
    /// Shaped ruby annotation runs, indexed by `RubyCell::run`.
    pub ruby_runs: Vec<ShapedRun>,
    pub ruby_cells: Vec<RubyCell>,
    /// Shaped emphasis-mark runs (single glyph each), indexed by
    /// `EmphasisMark::run`.
    pub emphasis_runs: Vec<ShapedRun>,
    pub emphasis_marks: Vec<EmphasisMark>,
    /// Per paragraph: [start, end) range into `columns`.
    pub paragraph_columns: Vec<(usize, usize)>,
    /// Per paragraph: UTF-16 start offset of each span (paragraph-relative).
    pub span_utf16_starts: Vec<Vec<usize>>,
    /// Per paragraph: source UTF-16 start offset of each span.
    pub span_source_utf16_starts: Vec<Vec<usize>>,
    /// Per paragraph and span: transformed scalar ownership in source text.
    pub span_transforms: Vec<Vec<AppliedTextTransform>>,
    /// Per paragraph: UTF-16 offset of every Unicode scalar boundary. Editor
    /// positions use indices into this table, while cells and position data
    /// keep their browser-facing UTF-16 offsets.
    pub paragraph_utf16_boundaries: Vec<Vec<usize>>,
    pub width: f32,
    pub height: f32,
}

impl VerticalLayout {
    /// Content origin (top-left of the laid-out block) in the same
    /// coordinate space as `bounds`. In vertical-rl, block-start is the
    /// right edge, block-center is the horizontal center and block-end is
    /// the left edge.
    pub fn origin(&self, bounds: &Rect, align: VerticalAlign) -> (f32, f32) {
        (
            bounds.left + block_axis_offset(bounds.width(), self.width, align),
            bounds.top,
        )
    }
}

/// Horizontal offset of vertical content within its shape. The existing
/// top/center/bottom values describe block-start/center/end; for vertical-rl
/// those positions map to right/center/left respectively.
pub fn block_axis_offset(container_width: f32, content_width: f32, align: VerticalAlign) -> f32 {
    let slack = (container_width - content_width).max(0.0);
    match align {
        VerticalAlign::Top => slack,
        VerticalAlign::Center => slack / 2.0,
        VerticalAlign::Bottom => 0.0,
    }
}

/// The column-wrap limit for a vertical text content: auto-width shapes
/// grow to fit (columns never wrap), everything else wraps at the shape
/// height. This is the phase's explicit auto-size decision: auto-height
/// behaves like fixed under vertical writing for now.
pub fn wrap_height(text_content: &TextContent, height: f32) -> f32 {
    match text_content.grow_type() {
        GrowType::AutoWidth => f32::MAX,
        _ => f32::max(height, 1.0),
    }
}

/// Return the proportional item range belonging to a contiguous slice of the
/// base text. This keeps a ruby reading monotonic when its base wraps across
/// columns while ensuring the final column receives any rounding remainder.
fn proportional_range(
    item_count: usize,
    base_start: usize,
    base_count: usize,
    total_base_count: usize,
) -> std::ops::Range<usize> {
    if item_count == 0 || total_base_count == 0 {
        return 0..0;
    }
    let start = base_start.saturating_mul(item_count) / total_base_count;
    let end_base = base_start.saturating_add(base_count).min(total_base_count);
    let end = if end_base == total_base_count {
        item_count
    } else {
        end_base.saturating_mul(item_count) / total_base_count
    };
    start.min(item_count)..end.min(item_count)
}

/// Expand gaps between already-placed base cells for long ruby annotations.
///
/// This is deliberately post-placement and bounded: it only shifts later base
/// cells in the same span/column, and only into slack before the next cell (or
/// the column bottom). It avoids re-wrapping columns while making the base span
/// long enough for common long compound-word readings when there is room.
#[derive(Debug, Clone, Copy)]
struct RubyBaseUnit {
    span: usize,
    start: usize,
    end: usize,
    ruby_len: usize,
    ruby_font_size: f32,
    overhang: RubyOverhang,
}

fn spread_ruby_base_cells(
    cells: &mut [VerticalCell],
    ruby_units: &[RubyBaseUnit],
    max_height: f32,
) {
    for unit in ruby_units {
        let mut columns: Vec<usize> = cells
            .iter()
            .filter(|cell| cell.span == unit.span && cell.start < unit.end && cell.end > unit.start)
            .map(|cell| cell.column)
            .collect();
        columns.sort_unstable();
        columns.dedup();

        let total_base_count = cells
            .iter()
            .filter(|cell| cell.span == unit.span && cell.start < unit.end && cell.end > unit.start)
            .count();
        let mut base_start = 0usize;

        for column in columns {
            let mut group: Vec<usize> = cells
                .iter()
                .enumerate()
                .filter(|(_, cell)| {
                    cell.span == unit.span
                        && cell.column == column
                        && cell.start < unit.end
                        && cell.end > unit.start
                })
                .map(|(index, _)| index)
                .collect();
            if group.len() < 2 {
                base_start += group.len();
                continue;
            }
            group.sort_by(|a, b| {
                cells[*a]
                    .top
                    .partial_cmp(&cells[*b].top)
                    .unwrap_or(std::cmp::Ordering::Equal)
            });

            let ruby_range =
                proportional_range(unit.ruby_len, base_start, group.len(), total_base_count);
            base_start += group.len();
            let ruby_line = unit.ruby_font_size * ruby_range.len() as f32;
            let first = group[0];
            let last = *group.last().unwrap();
            let base_top = cells[first].top;
            let base_bottom = cells[last].top + cells[last].extent;
            let base_extent = base_bottom - base_top;
            if ruby_line <= base_extent {
                continue;
            }

            let next_top = cells
                .iter()
                .enumerate()
                .filter(|(index, cell)| {
                    cell.column == column && !group.contains(index) && cell.top >= base_bottom
                })
                .map(|(_, cell)| cell.top)
                .min_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            let column_limit = if max_height.is_finite() && max_height < f32::MAX {
                max_height
            } else {
                f32::MAX
            };
            let limit = next_top.unwrap_or(column_limit);
            let spread = (ruby_line - base_extent).min((limit - base_bottom).max(0.0));
            if spread <= 0.0 {
                continue;
            }

            let gap = spread / (group.len() - 1) as f32;
            for (position, index) in group.iter().enumerate().skip(1) {
                cells[*index].top += gap * position as f32;
            }
        }
    }
}

/// Split a TCY `digits` span into pieces: maximal ASCII or full-width digit runs of
/// 2..=max characters become upright composites, everything else keeps the
/// normal vertical layout. Returns (piece text, span-relative UTF-16 start,
/// tcy).
fn is_tcy_digit(c: char) -> bool {
    c.is_ascii_digit() || ('０'..='９').contains(&c)
}

fn split_digit_runs(text: &str, max: usize) -> Vec<(String, usize, bool)> {
    let mut pieces: Vec<(String, usize, bool)> = Vec::new();
    let mut utf16 = 0usize;
    let mut current = String::new();
    let mut current_start = 0usize;
    let mut current_digit = false;
    for c in text.chars() {
        let digit = is_tcy_digit(c);
        if current.is_empty() {
            current_start = utf16;
            current_digit = digit;
        } else if digit != current_digit {
            pieces.push((std::mem::take(&mut current), current_start, current_digit));
            current_start = utf16;
            current_digit = digit;
        }
        current.push(c);
        utf16 += c.len_utf16();
    }
    if !current.is_empty() {
        pieces.push((current, current_start, current_digit));
    }
    for piece in pieces.iter_mut() {
        if piece.2 {
            let len = piece.0.chars().count();
            piece.2 = (2..=max).contains(&len);
        }
    }
    pieces
}

/// Compose `piece` (a whole TCY span or a digit run inside one) into one
/// upright composite cell, shaping with the first family that covers the
/// piece and composing any fallback runs side by side. Returns false when
/// the composite would compress below `min_scale`; the caller then falls back
/// to the normal vertical segmentation.
#[allow(clippy::too_many_arguments)]
fn try_push_tcy_composite(
    piece: &str,
    piece_utf16_start: usize,
    min_scale: f32,
    span: &TextSpan,
    span_utf16_offset: usize,
    paragraph_index: usize,
    span_index: usize,
    paint_index: usize,
    families: &[String],
    font_provider: &TypefaceFontProvider,
    fallback_mgr: &FontMgr,
    runs: &mut Vec<ShapedRun>,
    para_cells: &mut Vec<VerticalCell>,
    items: &mut Vec<FlowItem>,
    scripts: &mut Vec<FlowScript>,
    trailing_spacings: &mut Vec<f32>,
) -> bool {
    // Ruby and other span-level formatting can split an otherwise continuous
    // vertical run into a one-character span while preserving an inherited
    // `text-combine-upright: all`. A single CJK character is already upright;
    // routing it through the horizontal TCY path can scale it down to fit the
    // font's line metrics and make it smaller than adjacent base characters.
    // Keep naturally upright single characters on the normal vertical path.
    let mut chars = piece.chars();
    if matches!(
        (chars.next(), chars.next()),
        (Some(ch), None) if is_upright_char(ch)
    ) {
        return false;
    }

    let letter_spacing = span.letter_spacing;
    let probe = piece.chars().next().unwrap_or(' ');
    let candidates = || {
        families.iter().filter_map(|family| {
            font_provider.match_family_style(family, skia::FontStyle::default())
        })
    };
    // Prefer a face that covers the whole run; else one covering the first
    // character; else the span's own face. Any glyph still uncovered is
    // shaped through the fallback manager, which adds extra runs that
    // compose into the same upright cell.
    let typeface = candidates()
        .find(|tf| piece.chars().all(|c| tf.unichar_to_glyph(c as i32) != 0))
        .or_else(|| candidates().find(|tf| tf.unichar_to_glyph(probe as i32) != 0))
        .or_else(|| {
            font_provider.match_family_style(families[0].as_str(), skia::FontStyle::default())
        });
    let Some(typeface) = typeface else {
        return false;
    };
    let font = Font::new(typeface, span.font_size);
    let mut shaped = shape_segment(
        piece,
        &font,
        false,
        span.font_features,
        fallback_mgr.clone(),
    );
    if shaped.is_empty() {
        return false;
    }
    let em = span.font_size.max(1.0);
    let mut combined_advance = 0.0f32;
    let mut run_height = 1.0f32;
    for run in &mut shaped {
        if letter_spacing != 0.0 {
            for (i, position) in run.positions.iter_mut().enumerate() {
                position.x += letter_spacing * i as f32;
            }
            run.advance += letter_spacing * run.glyphs.len() as f32;
        }
        let (_, metrics) = run.font.metrics();
        run_height = run_height.max(metrics.descent - metrics.ascent);
        combined_advance += run.advance;
    }
    // Letter-spacing also separates adjacent fallback runs.
    if letter_spacing != 0.0 && shaped.len() > 1 {
        combined_advance += letter_spacing * (shaped.len() - 1) as f32;
    }
    let scale = (em / combined_advance.max(1.0))
        .min(em / run_height.max(1.0))
        .min(1.0);
    // Width limit: reject runs wider than the active TCY mode permits. The
    // generic `all` mode keeps the conservative half-scale limit, while the
    // counted digit modes allow the 1/run-length scale needed by full-width
    // digits.
    if scale < min_scale {
        return false;
    }
    let extent = em + letter_spacing;
    let run_start = runs.len();
    let run_count = shaped.len();
    for run in shaped {
        runs.push(run);
    }
    let start = span_utf16_offset + piece_utf16_start;
    let end = start + piece.encode_utf16().count();
    para_cells.push(VerticalCell {
        kind: CellKind::TateChuYoko {
            run_start,
            run_count,
            scale,
        },
        paragraph: paragraph_index,
        span: span_index,
        start,
        end,
        column: 0,
        top: 0.0,
        extent,
        h_advance: combined_advance * scale,
        ink_top: 0.0,
        ink_bottom: em,
        paint: paint_index,
        font_size: span.font_size,
        decoration: span.text_decoration,
        glyph_flow_shift: 0.0,
    });
    items.push(FlowItem {
        extent,
        ch: None,
        keep_with_previous: false,
    });
    scripts.push(FlowScript::Upright);
    trailing_spacings.push(letter_spacing);
    true
}

/// Lay out the whole content vertically. Pure of global state: fonts come
/// through the provider/fallback arguments so native tests can supply
/// their own.
pub fn layout_vertical(
    text_content: &TextContent,
    max_height: f32,
    font_provider: &TypefaceFontProvider,
    fallback_mgr: FontMgr,
    fallback_families: &[String],
    bounds: Rect,
) -> VerticalLayout {
    let mut runs: Vec<ShapedRun> = Vec::new();
    let mut paints: Vec<skia::Paint> = Vec::new();
    let mut cells: Vec<VerticalCell> = Vec::new();
    let mut columns: Vec<VerticalColumn> = Vec::new();
    let mut paragraph_columns: Vec<(usize, usize)> = Vec::new();
    let mut span_utf16_starts: Vec<Vec<usize>> = Vec::new();
    let span_transforms: Vec<Vec<AppliedTextTransform>> = text_content
        .paragraphs()
        .iter()
        .map(|paragraph| {
            paragraph
                .children()
                .iter()
                .map(TextSpan::apply_text_transform_with_source_ranges)
                .collect()
        })
        .collect();
    let paragraph_utf16_boundaries: Vec<Vec<usize>> = span_transforms
        .iter()
        .map(|paragraph| {
            let mut boundaries = vec![0usize];
            for span in paragraph {
                for character in span.text.chars() {
                    let next = boundaries.last().copied().unwrap_or(0) + character.len_utf16();
                    boundaries.push(next);
                }
            }
            boundaries
        })
        .collect();
    let span_source_utf16_starts: Vec<Vec<usize>> = text_content
        .paragraphs()
        .iter()
        .map(|paragraph| {
            let mut offset = 0usize;
            paragraph
                .children()
                .iter()
                .map(|span| {
                    let start = offset;
                    offset += span.text.encode_utf16().count();
                    start
                })
                .collect()
        })
        .collect();

    for (paragraph_index, paragraph) in text_content.paragraphs().iter().enumerate() {
        let line_height = if paragraph.line_height() > 0.0 {
            paragraph.line_height()
        } else {
            1.2
        };
        let max_font_size = paragraph
            .children()
            .iter()
            .map(|s| s.font_size)
            .fold(12.0, f32::max);
        let column_advance = max_font_size * line_height;

        // Ruby reserves its configured-size gutter on the logical annotation
        // side. In vertical-rl, `over` is right and `under` is left.
        let ruby_over_gutter = paragraph
            .children()
            .iter()
            .filter(|span| !span.ruby.trim().is_empty() && span.ruby_side == RubySide::Over)
            .map(|span| span.font_size * span.ruby_size.scale())
            .fold(0.0, f32::max);
        let ruby_under_gutter = paragraph
            .children()
            .iter()
            .filter(|span| !span.ruby.trim().is_empty() && span.ruby_side == RubySide::Under)
            .map(|span| span.font_size * span.ruby_size.scale())
            .fold(0.0, f32::max);
        // Emphasis occupies the over/right side. Auto-clearance spans carrying
        // both annotation types stack there; opposite-side ruby stays separate.
        let paragraph_has_emphasis = paragraph
            .children()
            .iter()
            .any(|s| !s.text_emphasis.is_none());
        let emphasis_gutter = if paragraph_has_emphasis {
            max_font_size * EMPHASIS_FONT_SCALE
        } else {
            0.0
        };
        let has_stacked_auto_annotations = paragraph.children().iter().any(|s| {
            s.annotation_clearance.is_auto()
                && !s.ruby.trim().is_empty()
                && s.ruby_side == RubySide::Over
                && !s.text_emphasis.is_none()
        });
        let over_gutter = if has_stacked_auto_annotations {
            ruby_over_gutter + emphasis_gutter
        } else {
            ruby_over_gutter.max(emphasis_gutter)
        };
        let column_width = ruby_under_gutter + column_advance + over_gutter;

        // Cells of this paragraph, parallel to `items`, placed after
        // column planning.
        let mut para_cells: Vec<VerticalCell> = Vec::new();
        let mut items: Vec<FlowItem> = Vec::new();
        let mut scripts: Vec<FlowScript> = Vec::new();
        let mut trailing_spacings: Vec<f32> = Vec::new();
        let mut span_starts: Vec<usize> = Vec::new();
        let mut span_utf16_offset = 0usize;
        let mut ruby_units: Vec<RubyBaseUnit> = Vec::new();

        for (span_index, span) in paragraph.children().iter().enumerate() {
            span_starts.push(span_utf16_offset);
            let text = span_transforms[paragraph_index][span_index].text.clone();
            if text.is_empty() {
                continue;
            }
            if !span.ruby.trim().is_empty() {
                ruby_units.push(RubyBaseUnit {
                    span: span_index,
                    start: span_utf16_offset,
                    end: span_utf16_offset + text.encode_utf16().count(),
                    ruby_len: span.ruby.trim().chars().count(),
                    ruby_font_size: span.font_size * span.ruby_size.scale(),
                    overhang: span.ruby_overhang,
                });
            }
            paints.push(merge_fills(&span.fills, bounds));
            let paint_index = paints.len() - 1;

            // Candidate families: the span's own font first, then the
            // registered fallback fonts (Noto etc.). The typeface font
            // provider does no per-character fallback, so pick the first
            // family that covers the whole segment (falling back to one that
            // covers its first character).
            let families = span_font_families(span, fallback_families);

            let orientation = span.text_orientation;
            let letter_spacing = span.letter_spacing;
            if span.text_combine_upright == TextCombineUpright::All
                && try_push_tcy_composite(
                    &text,
                    0,
                    MIN_TCY_SCALE,
                    span,
                    span_utf16_offset,
                    paragraph_index,
                    span_index,
                    paint_index,
                    &families,
                    font_provider,
                    &fallback_mgr,
                    &mut runs,
                    &mut para_cells,
                    &mut items,
                    &mut scripts,
                    &mut trailing_spacings,
                )
            {
                span_utf16_offset += text.encode_utf16().count();
                continue;
            }
            // Warichu (割注): the span becomes one composite cell holding two
            // half-size sub-lines laid side by side within the column (the
            // first sub-line on the right, jlreq reading order). The split is
            // balanced by character count with the first line the longer one,
            // nudged so the sub-lines respect kinsoku.
            if span.warichu && text.chars().count() >= 2 {
                let candidates = || {
                    families.iter().filter_map(|family| {
                        font_provider.match_family_style(family, skia::FontStyle::default())
                    })
                };
                let probe = text.chars().next().unwrap_or(' ');
                let typeface = candidates()
                    .find(|tf| text.chars().all(|c| tf.unichar_to_glyph(c as i32) != 0))
                    .or_else(|| candidates().find(|tf| tf.unichar_to_glyph(probe as i32) != 0))
                    .or_else(|| {
                        font_provider
                            .match_family_style(families[0].as_str(), skia::FontStyle::default())
                    });
                if let Some(typeface) = typeface {
                    let half_size = span.font_size * WARICHU_FONT_SCALE;
                    let font = Font::new(typeface, half_size);
                    let split_chars = warichu_split_chars(&text);
                    let split_utf8 = text
                        .char_indices()
                        .nth(split_chars)
                        .map(|(i, _)| i)
                        .unwrap_or(text.len());
                    let (first_text, second_text) = text.split_at(split_utf8);
                    let first_chars = first_text.encode_utf16().count();
                    let mut first_runs = shape_segment(
                        first_text,
                        &font,
                        true,
                        span.font_features,
                        fallback_mgr.clone(),
                    );
                    let mut second_runs = shape_segment(
                        second_text,
                        &font,
                        true,
                        span.font_features,
                        fallback_mgr.clone(),
                    );
                    if !first_runs.is_empty() && !second_runs.is_empty() {
                        let first_extent: f32 = first_runs.iter().map(|r| r.advance).sum();
                        let second_extent: f32 = second_runs.iter().map(|r| r.advance).sum();
                        let extent = first_extent.max(second_extent) + letter_spacing;
                        let run_start = runs.len();
                        let first_count = first_runs.len();
                        let run_count = first_count + second_runs.len();
                        runs.append(&mut first_runs);
                        runs.append(&mut second_runs);
                        let end = span_utf16_offset + text.encode_utf16().count();
                        para_cells.push(VerticalCell {
                            kind: CellKind::Warichu {
                                run_start,
                                run_count,
                                first_count,
                                first_chars,
                            },
                            paragraph: paragraph_index,
                            span: span_index,
                            start: span_utf16_offset,
                            end,
                            column: 0,
                            top: 0.0,
                            extent,
                            // Two half-em sub-columns side by side fill the em.
                            h_advance: span.font_size,
                            ink_top: 0.0,
                            ink_bottom: extent - letter_spacing,
                            paint: paint_index,
                            font_size: span.font_size,
                            decoration: span.text_decoration,
                            glyph_flow_shift: 0.0,
                        });
                        items.push(FlowItem {
                            extent,
                            ch: None,
                            keep_with_previous: false,
                        });
                        scripts.push(FlowScript::Upright);
                        trailing_spacings.push(letter_spacing);
                        span_utf16_offset += text.encode_utf16().count();
                        continue;
                    }
                }
            }
            // `digits` combines runs of 2..=max consecutive ASCII or full-width digits
            // (max 4, or 2/3 for the counted variants) into one upright
            // composite; the rest of the span (and every other span) flows
            // through the normal orientation segmentation.
            let pieces: Vec<(String, usize, bool)> =
                if let Some(max) = span.text_combine_upright.digits_max() {
                    split_digit_runs(&text, max)
                } else {
                    vec![(text.clone(), 0, false)]
                };
            for (piece_text, piece_utf16_start, piece_tcy) in &pieces {
                let piece_base = span_utf16_offset + piece_utf16_start;
                let min_tcy_scale = 1.0 / piece_text.chars().count().max(1) as f32;
                if *piece_tcy
                    && try_push_tcy_composite(
                        piece_text,
                        *piece_utf16_start,
                        min_tcy_scale,
                        span,
                        span_utf16_offset,
                        paragraph_index,
                        span_index,
                        paint_index,
                        &families,
                        font_provider,
                        &fallback_mgr,
                        &mut runs,
                        &mut para_cells,
                        &mut items,
                        &mut scripts,
                        &mut trailing_spacings,
                    )
                {
                    continue;
                }
                for segment in segment_by_orientation(piece_text, orientation) {
                    let probe = segment.text.chars().next().unwrap_or(' ');
                    let typeface = families
                        .iter()
                        .filter_map(|family| {
                            font_provider.match_family_style(family, skia::FontStyle::default())
                        })
                        .find(|tf| tf.unichar_to_glyph(probe as i32) != 0)
                        .or_else(|| {
                            font_provider.match_family_style(
                                families[0].as_str(),
                                skia::FontStyle::default(),
                            )
                        });
                    let Some(typeface) = typeface else {
                        continue;
                    };
                    let font = Font::new(typeface, span.font_size);
                    let shaped = shape_segment(
                        &segment.text,
                        &font,
                        segment.upright,
                        span.font_features,
                        fallback_mgr.clone(),
                    );

                    // Map UTF-8 offsets in the segment text to UTF-16 offsets
                    // in the span text.
                    let utf8_to_utf16 = |utf8: usize| -> usize {
                        segment.utf16_start
                            + segment.text[..utf8.min(segment.text.len())]
                                .chars()
                                .map(char::len_utf16)
                                .sum::<usize>()
                    };

                    for mut run in shaped {
                        let run_index = runs.len();
                        let seg_utf16_end = utf8_to_utf16(run.utf8_range.end);
                        if segment.upright {
                            let vmetrics = vertical_metrics(&run.font);
                            // vpal deltas apply here in layout, not in the
                            // shaper: SkShaper shapes on a horizontal line,
                            // where vertical GPOS positioning never fires.
                            let vpal = (span.font_features == FontFeatures::Vpal)
                                .then(|| vpal_table(&run.font))
                                .flatten();
                            // Group glyphs by cluster so combining sequences
                            // stay in one cell.
                            let mut glyph = 0usize;
                            while glyph < run.glyphs.len() {
                                let cluster = run.clusters[glyph];
                                let mut count = 1;
                                while glyph + count < run.glyphs.len()
                                    && run.clusters[glyph + count] == cluster
                                {
                                    count += 1;
                                }
                                let next_cluster_utf8 = run
                                    .clusters
                                    .get(glyph + count)
                                    .map(|c| *c as usize)
                                    .unwrap_or(run.utf8_range.end);
                                let h_advance: f32 =
                                    run.advances[glyph..glyph + count].iter().sum();
                                let h_advance = if h_advance > 0.0 {
                                    h_advance
                                } else {
                                    span.font_size
                                };
                                // Flow down the column by the true vertical
                                // advance when the font has `vmtx`, else the
                                // horizontal advance (exact for full-width CJK).
                                // Letter-spacing widens the gap after each cluster.
                                let extent = vmetrics
                                    .as_ref()
                                    .map(|vm| {
                                        run.glyphs[glyph..glyph + count]
                                            .iter()
                                            .map(|g| vm.advance(*g, span.font_size))
                                            .sum::<f32>()
                                    })
                                    .filter(|v| *v > 0.0)
                                    .unwrap_or(h_advance);
                                // vpal: the font's advance delta tightens the
                                // cell and its placement delta lifts the drawn
                                // ink to keep it inside. The tightened extent
                                // flows into caret, position-data and the aki
                                // sheds (which skip already-half-width cells).
                                let vpal_delta = vpal.as_ref().and_then(|table| {
                                    table.cluster_delta(
                                        &run.glyphs[glyph..glyph + count],
                                        span.font_size,
                                    )
                                });
                                let extent = extent
                                    + vpal_delta.map(|(advance, _)| advance).unwrap_or(0.0)
                                    + letter_spacing;
                                let vpal_flow_shift =
                                    vpal_delta.map(|(_, shift)| shift).unwrap_or(0.0);
                                let start = piece_base + utf8_to_utf16(cluster as usize);
                                let end = piece_base + utf8_to_utf16(next_cluster_utf8);
                                let ch = segment.text[(cluster as usize)..].chars().next();
                                let synthetic_rotation =
                                    cluster_needs_rotated_vertical_fallback(&run, glyph, count, ch);
                                let (mut ink_top, mut ink_bottom) = if synthetic_rotation {
                                    rotated_cluster_flow_ink_bounds(&run, glyph, count)
                                } else {
                                    upright_flow_ink_bounds(&run, glyph, count)
                                }
                                .unwrap_or((0.0, extent - letter_spacing));
                                ink_top += vpal_flow_shift;
                                ink_bottom += vpal_flow_shift;
                                // Centre middle-dot / colon / semicolon ink in the
                                // em body; the shift moves the drawn glyph only.
                                // A vpal-covered glyph keeps the font's own
                                // centring instead.
                                let glyph_flow_shift = if !synthetic_rotation
                                    && vpal_delta.is_none()
                                    && ch.is_some_and(is_centered_punctuation)
                                {
                                    let shift = centered_flow_shift(
                                        ink_top,
                                        ink_bottom,
                                        extent - letter_spacing,
                                    );
                                    ink_top += shift;
                                    ink_bottom += shift;
                                    shift
                                } else {
                                    vpal_flow_shift
                                };
                                para_cells.push(VerticalCell {
                                    kind: if synthetic_rotation {
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
                                    },
                                    paragraph: paragraph_index,
                                    span: span_index,
                                    start,
                                    end,
                                    column: 0,
                                    top: 0.0,
                                    extent,
                                    h_advance,
                                    ink_top,
                                    ink_bottom,
                                    paint: paint_index,
                                    font_size: span.font_size,
                                    decoration: span.text_decoration,
                                    glyph_flow_shift,
                                });
                                items.push(FlowItem {
                                    extent,
                                    ch,
                                    keep_with_previous: false,
                                });
                                scripts.push(FlowScript::Upright);
                                trailing_spacings.push(letter_spacing);
                                glyph += count;
                            }
                        } else {
                            normalize_rotated_word_spaces(&segment.text, &mut run, span.font_size);
                            // Spread the sideways glyphs down the column by
                            // letter-spacing (post-rotation +x runs down the
                            // column), matching the per-glyph horizontal spacing.
                            let extent = if letter_spacing != 0.0 {
                                for (i, position) in run.positions.iter_mut().enumerate() {
                                    position.x += letter_spacing * i as f32;
                                }
                                run.advance + letter_spacing * run.glyphs.len() as f32
                            } else {
                                run.advance
                            };
                            let run_text = &segment.text[run.utf8_range.clone()];
                            let starts_alphanumeric =
                                run_text.chars().next().is_some_and(char::is_alphanumeric);
                            let ends_alphanumeric = run_text
                                .chars()
                                .next_back()
                                .is_some_and(char::is_alphanumeric);
                            let start = piece_base + utf8_to_utf16(run.utf8_range.start);
                            let (ink_top, ink_bottom) = rotated_flow_ink_bounds(&run)
                                .unwrap_or((0.0, extent - letter_spacing));
                            para_cells.push(VerticalCell {
                                kind: CellKind::Rotated { run: run_index },
                                paragraph: paragraph_index,
                                span: span_index,
                                start,
                                end: piece_base + seg_utf16_end,
                                column: 0,
                                top: 0.0,
                                extent,
                                // Rotated runs draw from `run.positions`; the
                                // centring path doesn't read `h_advance`.
                                h_advance: run.advance,
                                ink_top,
                                ink_bottom,
                                paint: paint_index,
                                font_size: span.font_size,
                                decoration: span.text_decoration,
                                glyph_flow_shift: 0.0,
                            });
                            items.push(FlowItem {
                                extent,
                                ch: None,
                                keep_with_previous: false,
                            });
                            scripts.push(FlowScript::Rotated {
                                starts_alphanumeric,
                                ends_alphanumeric,
                            });
                            trailing_spacings.push(letter_spacing);
                        }
                        runs.push(run);
                    }
                }
            }
            span_utf16_offset += text.encode_utf16().count();
        }

        // A CSS transform may expand one source character into several shaped
        // cells. Keep those cells in one column so a source slice is rendered
        // exactly once by the SVG fallback (for example `ß` -> `SS`).
        for index in 1..para_cells.len() {
            let previous = &para_cells[index - 1];
            let current = &para_cells[index];
            if previous.span != current.span {
                continue;
            }
            let transform = &span_transforms[paragraph_index][current.span];
            let transformed_span_start = span_starts[current.span];
            let previous_source = transform.source_utf16_range(
                previous.start - transformed_span_start..previous.end - transformed_span_start,
            );
            let current_source = transform.source_utf16_range(
                current.start - transformed_span_start..current.end - transformed_span_start,
            );
            items[index].keep_with_previous = previous_source == current_source;
        }

        let ruby_classes: Vec<Option<JapaneseClass>> = paragraph
            .children()
            .iter()
            .map(|span| (!span.ruby.trim().is_empty()).then_some(JapaneseClass::SimpleRuby))
            .collect();
        // Adjust the preceding cell's flow advance so the *visible ink* edges
        // have the target gap. Logical advances alone are asymmetric around
        // mixed fonts/glyphs (e.g. `うpenあ`) because their side bearings differ.
        // Preserve the preceding cell's explicit trailing letter-spacing.
        for i in 1..para_cells.len() {
            let target_gap = inter_script_spacing(
                scripts[i - 1],
                para_cells[i - 1].font_size,
                scripts[i],
                para_cells[i].font_size,
            );
            if target_gap > 0.0 {
                let previous = &para_cells[i - 1];
                let natural_gap_without_letter_spacing = previous.extent - trailing_spacings[i - 1]
                    + para_cells[i].ink_top
                    - previous.ink_bottom;
                let correction = target_gap - natural_gap_without_letter_spacing;
                para_cells[i - 1].extent = (previous.extent + correction).max(0.0);
                items[i - 1].extent = para_cells[i - 1].extent;
            }
        }

        // JLREQ punctuation and cl-30 adjacency. Full-width fonts bake the half-em aki
        // into punctuation advances. Keep that preferred aki in ordinary text,
        // but remove one half-em at the internal boundaries defined by §3.1.4:
        // closing punctuation sequences set solid, closing→opening retains a
        // single half-em, opening sequences set solid after the first bracket,
        // and middle-dot adjacency retains its own quarter-em side spacing.
        let flow_classes: Vec<Option<JapaneseClass>> = para_cells
            .iter()
            .zip(&items)
            .map(|(cell, item)| ruby_classes[cell.span].or_else(|| flow_class(cell, item)))
            .collect();
        for i in 0..para_cells.len() {
            let Some(ch) = items[i].ch else {
                continue;
            };
            let closing = sheds_trailing_aki(ch);
            let opening = sheds_leading_aki(ch);
            if !closing && !opening {
                continue;
            }
            let previous_class = i.checked_sub(1).and_then(|index| flow_classes[index]);
            let next_class = flow_classes.get(i + 1).copied().flatten();
            let shed = if closing {
                next_class.is_some_and(|class| {
                    let natural = 0.5 + embedded_leading_aki(class);
                    let preferred = pair_rule(classify(ch), class).preferred_em;
                    natural > preferred + f32::EPSILON
                })
            } else {
                previous_class.is_some_and(|class| {
                    // A preceding trailing-aki mark owns the reduction for
                    // closing→opening, so never remove both halves.
                    !class.is_trailing_aki_punctuation()
                        && embedded_trailing_aki(class) + 0.5
                            > pair_rule(class, classify(ch)).preferred_em + f32::EPSILON
                })
            };
            if !shed {
                continue;
            }
            let target = 0.5 * para_cells[i].font_size + trailing_spacings[i];
            if target >= para_cells[i].extent {
                continue;
            }
            // Opening punctuation keeps its ink in the trailing half of the em,
            // so pulling it up by the shed amount lands the glyph inside the
            // compressed cell and hugs the preceding character. Closing
            // punctuation already sits in the leading half; no shift is needed.
            if opening {
                para_cells[i].glyph_flow_shift += -(para_cells[i].extent - target);
            }
            para_cells[i].extent = target;
            items[i].extent = target;
        }

        // Long ruby with no slack: grow the base span's flow extent *before*
        // column planning so the wrap itself makes room (forced spreading).
        // The growth becomes inter-character gaps, so only the cells before
        // the last one grow. Single-character bases keep the capped-overhang
        // behaviour of the post-placement pass.
        for unit in &ruby_units {
            let indices: Vec<usize> = para_cells
                .iter()
                .enumerate()
                .filter(|(_, cell)| {
                    cell.span == unit.span && cell.start < unit.end && cell.end > unit.start
                })
                .map(|(index, _)| index)
                .collect();
            if indices.is_empty() {
                continue;
            }
            let ruby_line = unit.ruby_font_size * unit.ruby_len as f32;
            let base_total: f32 = indices.iter().map(|index| para_cells[*index].extent).sum();
            let deficit = ruby_line - base_total;
            if deficit <= 0.0 {
                continue;
            }
            if indices.len() == 1 {
                if unit.overhang == RubyOverhang::None {
                    para_cells[indices[0]].extent += deficit;
                    items[indices[0]].extent += deficit;
                }
                continue;
            }
            let gap = deficit / (indices.len() - 1) as f32;
            for index in &indices[..indices.len() - 1] {
                para_cells[*index].extent += gap;
                items[*index].extent += gap;
            }
        }

        let mut pair_spacing_em: Vec<f32> = flow_classes
            .windows(2)
            .map(|pair| match (pair[0], pair[1]) {
                (Some(before), Some(after)) => pair_rule(before, after).preferred_em,
                _ => 0.0,
            })
            .collect();
        apply_ordered_oikomi(
            &mut para_cells,
            &mut items,
            &flow_classes,
            &mut pair_spacing_em,
            max_height,
        );

        // Wrapped opening brackets use the JIS X 4051 tentsuki policy: discard
        // their leading half-em at a column head. Re-plan after each newly
        // trimmed bracket because the shorter line may pull one more cell into
        // the preceding column. The loop is bounded and each iteration can
        // only shrink a previously untrimmed item.
        let mut placements = plan_columns(&items, max_height);
        for _ in 0..para_cells.len() {
            let mut changed = false;
            for i in 0..para_cells.len() {
                if placements[i].1 != 0.0 || !items[i].ch.is_some_and(sheds_leading_aki) {
                    continue;
                }
                let target = 0.5 * para_cells[i].font_size + trailing_spacings[i];
                if target >= para_cells[i].extent {
                    continue;
                }
                para_cells[i].glyph_flow_shift += -(para_cells[i].extent - target);
                para_cells[i].extent = target;
                items[i].extent = target;
                changed = true;
            }
            if !changed {
                break;
            }
            placements = plan_columns(&items, max_height);
        }
        let columns_used = placements.last().map(|(c, _)| c + 1).unwrap_or(1);

        let column_base = columns.len();
        for _ in 0..columns_used {
            columns.push(VerticalColumn {
                x: 0.0,
                width: column_width,
                base_offset: ruby_under_gutter,
                base_width: column_advance,
            });
        }
        paragraph_columns.push((column_base, column_base + columns_used));

        // Align each column's cells along the vertical (inline) axis per the
        // paragraph's text-align. The wrap height `max_height` is the budget;
        // a column's used length is its farthest cell bottom.
        let text_align = paragraph.text_align();
        let mut column_used = vec![0.0f32; columns_used];
        for (item, (column, top)) in items.iter().zip(placements.iter()) {
            column_used[*column] = column_used[*column].max(top + item.extent);
        }
        let column_offset: Vec<f32> = column_used
            .iter()
            .map(|used| align_offset_along_column(text_align, max_height, *used))
            .collect();

        // Justify stretches every column but the last (the last "line") to
        // fill the wrap budget; a snug auto-width budget has no slack.
        let justify = matches!(text_align, TextAlign::Justify)
            && max_height.is_finite()
            && max_height < f32::MAX
            && max_height > 0.0;
        let last_column = columns_used.saturating_sub(1);
        let expansion_offsets = if justify {
            ordered_expansion_offsets(
                &para_cells,
                &flow_classes,
                &pair_spacing_em,
                &placements,
                &column_used,
                max_height,
                last_column,
            )
        } else {
            vec![0.0; para_cells.len()]
        };
        let paragraph_cell_start = cells.len();

        for (index, (mut cell, (column, top))) in para_cells.into_iter().zip(placements).enumerate()
        {
            cell.column = column_base + column;
            cell.top = top + column_offset[column] + expansion_offsets[index];
            cells.push(cell);
        }
        spread_ruby_base_cells(&mut cells[paragraph_cell_start..], &ruby_units, max_height);

        span_utf16_starts.push(span_starts);
    }

    // Columns advance right->left: column 0 is the rightmost.
    let width: f32 = columns.iter().map(|c| c.width).sum();
    let mut right = width;
    for column in columns.iter_mut() {
        right -= column.width;
        column.x = right;
    }

    // Ruby (furigana) post-pass. Runs after column placement because a ruby
    // annotation's strip is positioned from its base characters' final column
    // and flow extent.
    let mut ruby_runs: Vec<ShapedRun> = Vec::new();
    let mut ruby_cells: Vec<RubyCell> = Vec::new();
    for (paragraph_index, paragraph) in text_content.paragraphs().iter().enumerate() {
        for (span_index, span) in paragraph.children().iter().enumerate() {
            let ruby_text = span.ruby.trim();
            if ruby_text.is_empty() {
                continue;
            }
            // Base characters of this span grouped by column, in flow (column
            // index) order; segments within a column sorted along the flow.
            let mut span_columns: Vec<RubyColumnSegments> = Vec::new();
            for cell in &cells {
                if cell.paragraph != paragraph_index || cell.span != span_index {
                    continue;
                }
                match span_columns
                    .iter_mut()
                    .find(|data| data.column == cell.column)
                {
                    Some(data) => data.segments.push(RubyBaseSegment {
                        top: cell.top,
                        extent: cell.extent,
                    }),
                    None => span_columns.push(RubyColumnSegments {
                        column: cell.column,
                        paint: cell.paint,
                        segments: vec![RubyBaseSegment {
                            top: cell.top,
                            extent: cell.extent,
                        }],
                    }),
                }
            }
            if span_columns.is_empty() {
                continue;
            }
            span_columns.sort_by_key(|data| data.column);
            for data in span_columns.iter_mut() {
                data.segments.sort_by(|a, b| {
                    a.top
                        .partial_cmp(&b.top)
                        .unwrap_or(std::cmp::Ordering::Equal)
                });
            }

            let ruby_font_size = span.font_size * span.ruby_size.scale();
            let probe = ruby_text.chars().next().unwrap_or(' ');
            let families = span_font_families(span, fallback_families);
            let typeface = families
                .iter()
                .filter_map(|family| {
                    font_provider.match_family_style(family, skia::FontStyle::default())
                })
                .find(|tf| tf.unichar_to_glyph(probe as i32) != 0)
                .or_else(|| {
                    font_provider
                        .match_family_style(families[0].as_str(), skia::FontStyle::default())
                });
            let Some(typeface) = typeface else {
                continue;
            };
            let font = Font::new(typeface, ruby_font_size);
            let shaped = shape_segment(
                ruby_text,
                &font,
                true,
                span.font_features,
                fallback_mgr.clone(),
            );
            if shaped.is_empty() {
                continue;
            }
            let trim_utf16 = span.ruby[..span.ruby.len() - span.ruby.trim_start().len()]
                .encode_utf16()
                .count();
            let run_start = ruby_runs.len();
            let mut glyphs: Vec<RubyGlyph> = shaped
                .iter()
                .enumerate()
                .flat_map(|(run_index, run)| {
                    run.clusters
                        .iter()
                        .enumerate()
                        .map(move |(glyph, cluster)| {
                            let utf8 = (*cluster as usize).min(ruby_text.len());
                            RubyGlyph {
                                run: run_start + run_index,
                                glyph,
                                utf16_start: trim_utf16 + ruby_text[..utf8].encode_utf16().count(),
                                utf16_end: 0,
                            }
                        })
                })
                .collect();
            if glyphs.is_empty() {
                continue;
            }
            let ruby_utf16_end = trim_utf16 + ruby_text.encode_utf16().count();
            for index in 0..glyphs.len() {
                glyphs[index].utf16_end = glyphs
                    .get(index + 1)
                    .map(|next| next.utf16_start)
                    .unwrap_or(ruby_utf16_end);
            }
            ruby_runs.extend(shaped);

            let total_base_count: usize = span_columns.iter().map(|data| data.segments.len()).sum();
            let mut base_start = 0usize;
            for data in &span_columns {
                let base_segments: Vec<(f32, f32)> = data
                    .segments
                    .iter()
                    .map(|segment| (segment.top, segment.extent))
                    .collect();
                if base_segments.is_empty() {
                    continue;
                }
                let glyph_range = proportional_range(
                    glyphs.len(),
                    base_start,
                    base_segments.len(),
                    total_base_count,
                );
                base_start += base_segments.len();
                let column_glyphs = glyphs[glyph_range].to_vec();
                if column_glyphs.is_empty() {
                    continue;
                }
                let top = base_segments
                    .first()
                    .map(|segment| segment.0)
                    .unwrap_or(0.0);
                let bottom = base_segments
                    .last()
                    .map(|segment| segment.0 + segment.1)
                    .unwrap_or(top);
                let glyph_tops = distribute_ruby_tops(
                    top,
                    (bottom - top).max(0.0),
                    column_glyphs.len(),
                    ruby_font_size,
                    span.ruby_align,
                    span.ruby_overhang,
                );
                ruby_cells.push(RubyCell {
                    glyphs: column_glyphs,
                    paragraph: paragraph_index,
                    span: span_index,
                    column: data.column,
                    base_segments,
                    glyph_tops,
                    font_size: ruby_font_size,
                    base_font_size: span.font_size,
                    side: span.ruby_side,
                    paint: data.paint,
                });
            }
        }
    }

    // Emphasis marks (圏点 / bouten): one mark glyph per base cell of every
    // span that carries `text_emphasis`. The mark is shaped once per span and
    // drawn beside each Upright base cell in the right-side gutter. Like ruby,
    // marks stay out of `cells` so base metrics and the editor are untouched.
    // Whitespace cells get no mark (CSS `text-emphasis` behaviour).
    let mut emphasis_runs: Vec<ShapedRun> = Vec::new();
    let mut emphasis_marks: Vec<EmphasisMark> = Vec::new();
    for (paragraph_index, paragraph) in text_content.paragraphs().iter().enumerate() {
        for (span_index, span) in paragraph.children().iter().enumerate() {
            let Some(mark) = span.text_emphasis.mark_char() else {
                continue;
            };
            let mark_font_size = span.font_size * EMPHASIS_FONT_SCALE;
            let families = span_font_families(span, fallback_families);
            let typeface = families
                .iter()
                .filter_map(|family| {
                    font_provider.match_family_style(family, skia::FontStyle::default())
                })
                .find(|tf| tf.unichar_to_glyph(mark as i32) != 0)
                .or_else(|| {
                    font_provider
                        .match_family_style(families[0].as_str(), skia::FontStyle::default())
                });
            let Some(typeface) = typeface else {
                continue;
            };
            let font = Font::new(typeface, mark_font_size);
            let mut shaped = shape_segment(
                &mark.to_string(),
                &font,
                true,
                FontFeatures::None,
                fallback_mgr.clone(),
            );
            if shaped.is_empty() || shaped[0].glyphs.is_empty() {
                continue;
            }
            let run_index = emphasis_runs.len();
            emphasis_runs.push(shaped.remove(0));
            let span_start = span_utf16_starts[paragraph_index][span_index];
            for cell in &cells {
                if cell.paragraph != paragraph_index
                    || cell.span != span_index
                    || !matches!(
                        cell.kind,
                        CellKind::Upright { .. } | CellKind::SyntheticRotated { .. }
                    )
                {
                    continue;
                }
                if !utf16_range_allows_emphasis(
                    &span.text,
                    cell.start - span_start,
                    cell.end - span_start,
                ) {
                    continue;
                }
                emphasis_marks.push(EmphasisMark {
                    run: run_index,
                    column: cell.column,
                    top: cell.top,
                    extent: cell.extent,
                    paint: cell.paint,
                    font_size: mark_font_size,
                    outside_offset: if span.annotation_clearance.is_auto()
                        && !span.ruby.trim().is_empty()
                        && span.ruby_side == RubySide::Over
                    {
                        span.font_size * span.ruby_size.scale()
                    } else {
                        0.0
                    },
                });
            }
        }
    }

    let height = cells
        .iter()
        .map(|c| c.top + c.extent)
        .fold(0.0f32, f32::max);

    VerticalLayout {
        runs,
        paints,
        cells,
        columns,
        ruby_runs,
        ruby_cells,
        emphasis_runs,
        emphasis_marks,
        paragraph_columns,
        span_utf16_starts,
        span_source_utf16_starts,
        span_transforms,
        paragraph_utf16_boundaries,
        width,
        height,
    }
}

/// Emphasis-mark (圏点) font size relative to the base em.
const EMPHASIS_FONT_SCALE: f32 = super::text_japanese::EMPHASIS_FONT_SCALE;

/// Warichu (割注) sub-line font size relative to the base em; two half-size
/// sub-columns side by side fill the base em width.
const WARICHU_FONT_SCALE: f32 = super::text_japanese::WARICHU_FONT_SCALE;

/// Char index where a warichu run splits into its two sub-lines: the
/// balanced midpoint (first line longer), nudged so the second sub-line
/// does not start with a line-start-prohibited character and the first
/// does not end with a line-end-prohibited one. Nudging forward pulls the
/// offending mark up into the first sub-line (jlreq); backward is the
/// fallback, and the midpoint stands when no split satisfies kinsoku.
pub(crate) fn warichu_split_chars(text: &str) -> usize {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mid = n.div_ceil(2);
    let valid = |split: usize| {
        split >= 1
            && split < n
            && !forbidden_at_line_start(chars[split])
            && !forbidden_at_line_end(chars[split - 1])
    };
    if valid(mid) {
        return mid;
    }
    for distance in 1..n {
        if valid(mid + distance) {
            return mid + distance;
        }
        if mid > distance && valid(mid - distance) {
            return mid - distance;
        }
    }
    mid
}

/// True when the UTF-16 range contains an emphasis-eligible character.
fn utf16_range_allows_emphasis(text: &str, start: usize, end: usize) -> bool {
    let mut offset = 0;
    for c in text.chars() {
        if offset >= end {
            break;
        }
        let len = c.len_utf16();
        if offset + len > start && super::text_japanese::emphasis_char_allowed(c) {
            return true;
        }
        offset += len;
    }
    false
}

/// Minimum scale for unconstrained `all` tate-chu-yoko. Counted digit modes
/// derive their limit from the eligible run length instead.
const MIN_TCY_SCALE: f32 = 0.5;

/// Distribute `count` ruby glyphs of the given `advance` along a single base
/// segment `[seg_top, seg_top + seg_extent)`, returning each glyph's flow-axis
/// top. Two jlreq regimes:
///
/// - Ruby no longer than the base (`Lr <= seg_extent`): even distribution
///   (均等割り付け). Each glyph gets an equal slot `seg_extent / count` and is
///   centred in its slot, which yields equal inter-glyph gaps and half gaps at
///   both ends.
/// - Ruby longer than the base (`Lr > seg_extent`): the glyphs are packed at
///   their own advance and the block is centred on the base, overhanging both
///   ends symmetrically (オーバーハング). The per-end overhang is capped at one
///   ruby em so a long annotation cannot swallow its neighbours' cells.
fn distribute_ruby_tops(
    seg_top: f32,
    seg_extent: f32,
    count: usize,
    advance: f32,
    align: RubyAlign,
    overhang_policy: RubyOverhang,
) -> Vec<f32> {
    if count == 0 {
        return Vec::new();
    }
    let line = advance * count as f32;
    if line <= seg_extent {
        match align {
            RubyAlign::SpaceAround => {
                let slot = seg_extent / count as f32;
                (0..count)
                    .map(|i| seg_top + slot * (i as f32 + 0.5) - advance / 2.0)
                    .collect()
            }
            RubyAlign::Center => {
                let start = seg_top + (seg_extent - line) / 2.0;
                (0..count).map(|i| start + advance * i as f32).collect()
            }
            RubyAlign::Start => (0..count).map(|i| seg_top + advance * i as f32).collect(),
            RubyAlign::SpaceBetween if count > 1 => {
                let gap = (seg_extent - line) / (count - 1) as f32;
                (0..count)
                    .map(|i| seg_top + (advance + gap) * i as f32)
                    .collect()
            }
            RubyAlign::SpaceBetween => vec![seg_top + (seg_extent - advance) / 2.0],
        }
    } else {
        let overflow = line - seg_extent;
        let overhang = if overhang_policy == RubyOverhang::Auto {
            (overflow / 2.0).min(advance)
        } else {
            0.0
        };
        let start = seg_top - overhang;
        (0..count).map(|i| start + advance * i as f32).collect()
    }
}

/// Production entry point: lay out with the render state's font store.
pub fn layout_from_content(text_content: &TextContent, max_height: f32) -> VerticalLayout {
    let font_provider = get_render_state().fonts().font_provider();
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    layout_vertical(
        text_content,
        max_height,
        font_provider,
        fallback_mgr,
        &fallback_families,
        text_content.bounds(),
    )
}

/// Content size (width, height) of a vertical layout for auto-sizing.
pub fn measure_content(text_content: &TextContent, max_height: f32) -> (f32, f32) {
    let layout = layout_from_content(text_content, max_height);
    (layout.width, layout.height)
}

/// Paint the text content vertically inside its bounds. Returns false
/// when the content is not vertical, so the caller falls back to the
/// horizontal path.
pub fn paint_text_vertical(
    canvas: &Canvas,
    text_content: &TextContent,
    vertical_align: VerticalAlign,
) -> bool {
    if !text_content.is_vertical() {
        return false;
    }

    let bounds = text_content.bounds();
    let max_height = wrap_height(text_content, bounds.height());
    let layout = layout_from_content(text_content, max_height);
    paint_layout(canvas, &layout, &bounds, vertical_align);
    true
}

fn text_blob_path(mut blob: TextBlob, offset: impl Into<SkPoint>) -> skia::Path {
    // SkParagraph normalizes extracted glyph outlines against the blob's ink
    // bounds. Restore that origin before applying the draw offset so the path
    // occupies the same document coordinates as Canvas::draw_text_blob.
    let bounds = *blob.bounds();
    let offset = offset.into();
    SkiaParagraph::get_path(&mut blob).with_offset((offset.x + bounds.left, offset.y + bounds.top))
}

fn push_text_path(
    paths: &mut Vec<(skia::Path, Paint)>,
    path: skia::Path,
    paint: &Paint,
    antialias: bool,
) {
    if path.is_empty() {
        return;
    }
    let mut paint = paint.clone();
    paint.set_anti_alias(antialias);
    paths.push((path, paint));
}

fn append_warichu_line_paths(
    paths: &mut Vec<(skia::Path, Paint)>,
    runs: &[ShapedRun],
    x_center: f32,
    y_top: f32,
    paint: &Paint,
    antialias: bool,
) {
    let mut cursor = y_top;
    for run in runs {
        let font_size = run.font.size();
        let (ascent, descent) = upright_centre_metrics(&run.font);
        let baseline_offset = upright_baseline_offset(ascent, descent, font_size);
        let mut glyph = 0usize;
        while glyph < run.glyphs.len() {
            let cluster = run.clusters[glyph];
            let mut count = 1;
            while glyph + count < run.glyphs.len() && run.clusters[glyph + count] == cluster {
                count += 1;
            }
            let advance: f32 = run.advances[glyph..glyph + count].iter().sum();
            let advance = if advance > 0.0 { advance } else { font_size };
            let mut builder = TextBlobBuilder::new();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            let base_x = run.positions[glyph].x;
            for i in 0..count {
                glyphs[i] = run.glyphs[glyph + i];
                points[i] = SkPoint::new(
                    run.positions[glyph + i].x - base_x,
                    run.positions[glyph + i].y,
                );
            }
            if let Some(blob) = builder.make() {
                let path =
                    text_blob_path(blob, (x_center - advance / 2.0, cursor + baseline_offset));
                push_text_path(paths, path, paint, antialias);
            }
            cursor += advance;
            glyph += count;
        }
    }
}

fn append_cell_paths(
    paths: &mut Vec<(skia::Path, Paint)>,
    layout: &VerticalLayout,
    cell: &VerticalCell,
    origin_x: f32,
    origin_y: f32,
    antialias: bool,
) {
    let column = &layout.columns[cell.column];
    let x_center = origin_x + column_base_center(column);
    let y_top = origin_y + cell.top;
    let paint = &layout.paints[cell.paint];
    match cell.kind {
        CellKind::Upright { run, glyph, count } => {
            let run = &layout.runs[run];
            let (ascent, descent) = upright_centre_metrics(&run.font);
            let baseline = y_top
                + cell.glyph_flow_shift
                + upright_baseline_offset(ascent, descent, cell.font_size);
            let x = x_center - cell.h_advance / 2.0;
            let mut builder = TextBlobBuilder::new();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            let base_x = run.positions[glyph].x;
            for i in 0..count {
                glyphs[i] = run.glyphs[glyph + i];
                points[i] = SkPoint::new(
                    run.positions[glyph + i].x - base_x,
                    run.positions[glyph + i].y,
                );
            }
            if let Some(blob) = builder.make() {
                push_text_path(paths, text_blob_path(blob, (x, baseline)), paint, antialias);
            }
        }
        CellKind::SyntheticRotated { run, glyph, count } => {
            let run = &layout.runs[run];
            if let Some(blob) = cluster_text_blob(run, glyph, count) {
                let path = text_blob_path(
                    blob,
                    (0.0, rotated_cluster_baseline_shift(run, glyph, count)),
                )
                .make_transform(&skia::Matrix::rotate_deg(90.0))
                .with_offset((x_center, y_top + cell.glyph_flow_shift));
                push_text_path(paths, path, paint, antialias);
            }
        }
        CellKind::Rotated { run } => {
            let run = &layout.runs[run];
            let mut builder = TextBlobBuilder::new();
            let count = run.glyphs.len();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            glyphs.copy_from_slice(&run.glyphs);
            points.copy_from_slice(&run.positions);
            if let Some(blob) = builder.make() {
                let path = text_blob_path(blob, (0.0, run.rotated_baseline_shift))
                    .make_transform(&skia::Matrix::rotate_deg(90.0))
                    .with_offset((x_center, y_top));
                push_text_path(paths, path, paint, antialias);
            }
        }
        CellKind::TateChuYoko {
            run_start,
            run_count,
            scale,
        } => {
            let composite = &layout.runs[run_start..run_start + run_count];
            let combined_advance: f32 = composite.iter().map(|run| run.advance).sum();
            let (ascent, descent) = composite.iter().fold((0.0f32, 0.0f32), |(a, d), run| {
                let (_, metrics) = run.font.metrics();
                (a.min(metrics.ascent), d.max(metrics.descent))
            });
            let x0 = x_center - (combined_advance * scale) / 2.0;
            let baseline = y_top + cell.font_size / 2.0 - ((ascent + descent) * scale) / 2.0;
            let mut cursor = 0.0f32;
            for run in composite {
                let mut builder = TextBlobBuilder::new();
                let count = run.glyphs.len();
                let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
                glyphs.copy_from_slice(&run.glyphs);
                points.copy_from_slice(&run.positions);
                if let Some(blob) = builder.make() {
                    let path = text_blob_path(blob, (cursor, 0.0))
                        .make_transform(&skia::Matrix::scale((scale, scale)))
                        .with_offset((x0, baseline));
                    push_text_path(paths, path, paint, antialias);
                }
                cursor += run.advance;
            }
        }
        CellKind::Warichu {
            run_start,
            run_count,
            first_count,
            ..
        } => {
            let all = &layout.runs[run_start..run_start + run_count];
            let (first, second) = all.split_at(first_count);
            let quarter = cell.font_size / 4.0;
            append_warichu_line_paths(paths, first, x_center + quarter, y_top, paint, antialias);
            append_warichu_line_paths(paths, second, x_center - quarter, y_top, paint, antialias);
        }
    }

    if let Some(decoration) = cell.decoration {
        if decoration.contains(TextDecoration::UNDERLINE) {
            push_text_path(
                paths,
                skia::Path::rect(
                    decoration_bar(layout, cell, origin_x, origin_y, false),
                    None,
                ),
                paint,
                antialias,
            );
        }
        if decoration.contains(TextDecoration::LINE_THROUGH) {
            push_text_path(
                paths,
                skia::Path::rect(decoration_bar(layout, cell, origin_x, origin_y, true), None),
                paint,
                antialias,
            );
        }
    }
}

fn append_ruby_paths(
    paths: &mut Vec<(skia::Path, Paint)>,
    layout: &VerticalLayout,
    ruby: &RubyCell,
    origin_x: f32,
    origin_y: f32,
    antialias: bool,
) {
    let column = &layout.columns[ruby.column];
    let paint = &layout.paints[ruby.paint];
    let gutter_center = origin_x
        + ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side)
        + ruby.font_size / 2.0;
    for (ruby_glyph, top) in ruby.glyphs.iter().zip(&ruby.glyph_tops) {
        let run = &layout.ruby_runs[ruby_glyph.run];
        let Some(glyph) = run.glyphs.get(ruby_glyph.glyph) else {
            continue;
        };
        let (_, metrics) = run.font.metrics();
        let baseline = origin_y + *top - metrics.ascent;
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points) = builder.alloc_run_pos(&run.font, 1, None);
        glyphs[0] = *glyph;
        points[0] = SkPoint::new(0.0, 0.0);
        if let Some(blob) = builder.make() {
            let advance = run
                .advances
                .get(ruby_glyph.glyph)
                .copied()
                .unwrap_or(ruby.font_size);
            push_text_path(
                paths,
                text_blob_path(blob, (gutter_center - advance / 2.0, baseline)),
                paint,
                antialias,
            );
        }
    }
}

fn append_emphasis_path(
    paths: &mut Vec<(skia::Path, Paint)>,
    layout: &VerticalLayout,
    mark: &EmphasisMark,
    origin_x: f32,
    origin_y: f32,
    antialias: bool,
) {
    let column = &layout.columns[mark.column];
    let run = &layout.emphasis_runs[mark.run];
    let Some(glyph) = run.glyphs.first() else {
        return;
    };
    let paint = &layout.paints[mark.paint];
    let base_font_size = mark.font_size / EMPHASIS_FONT_SCALE;
    let gutter_center = origin_x
        + column_base_center(column)
        + base_font_size / 2.0
        + mark.outside_offset
        + mark.font_size / 2.0;
    let (_, metrics) = run.font.metrics();
    let cell_center = origin_y + mark.top + mark.extent / 2.0;
    let baseline = cell_center - (metrics.ascent + metrics.descent) / 2.0;
    let advance = run.advances.first().copied().unwrap_or(0.0);
    let mut builder = TextBlobBuilder::new();
    let (glyphs, points) = builder.alloc_run_pos(&run.font, 1, None);
    glyphs[0] = *glyph;
    points[0] = SkPoint::new(0.0, 0.0);
    if let Some(blob) = builder.make() {
        push_text_path(
            paths,
            text_blob_path(blob, (gutter_center - advance / 2.0, baseline)),
            paint,
            antialias,
        );
    }
}

fn paths_from_layout(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    antialias: bool,
) -> Vec<(skia::Path, Paint)> {
    let mut paths = Vec::new();
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        append_cell_paths(&mut paths, layout, cell, origin_x, origin_y, antialias);
    }
    for ruby in &layout.ruby_cells {
        append_ruby_paths(&mut paths, layout, ruby, origin_x, origin_y, antialias);
    }
    for mark in &layout.emphasis_marks {
        append_emphasis_path(&mut paths, layout, mark, origin_x, origin_y, antialias);
    }
    paths
}

/// Convert the custom vertical layout to glyph-outline paths using the same
/// cells, composite transforms, annotations and alignment as the canvas pass.
pub fn vertical_text_paths(
    text_content: &TextContent,
    vertical_align: VerticalAlign,
    antialias: bool,
) -> Vec<(skia::Path, Paint)> {
    if !text_content.is_vertical() {
        return Vec::new();
    }
    let bounds = text_content.bounds();
    let max_height = wrap_height(text_content, bounds.height());
    let layout = layout_from_content(text_content, max_height);
    paths_from_layout(&layout, &bounds, vertical_align, antialias)
}

/// Split `total` glyphs across segments proportionally to their extents
/// (rounded per segment, remainder to the last) so no glyph is dropped.
fn split_counts_by_extent(extents: &[f32], total: usize) -> Vec<usize> {
    let mut counts = vec![0usize; extents.len()];
    if extents.is_empty() || total == 0 {
        return counts;
    }
    let sum: f32 = extents.iter().sum();
    if sum <= 0.0 {
        counts[extents.len() - 1] = total;
        return counts;
    }
    let mut assigned = 0usize;
    let last = extents.len() - 1;
    for (index, extent) in extents.iter().enumerate() {
        let count = if index == last {
            total - assigned
        } else {
            (((extent / sum) * total as f32).round() as usize).min(total - assigned)
        };
        counts[index] = count;
        assigned += count;
    }
    counts
}

fn next_horizontal_ruby_range(
    offset_map: &super::kinsoku::OffsetMap,
    utf16_cursor: &mut usize,
    text: &str,
) -> std::ops::Range<usize> {
    let start = *utf16_cursor;
    *utf16_cursor += text.encode_utf16().count();
    offset_map.to_shifted(start)..offset_map.to_shifted(*utf16_cursor)
}

/// Paint ruby annotations for one horizontally laid-out paragraph. Draw-only:
/// base rects come from the already laid-out skparagraph
/// (`get_rects_for_range`), the annotation is shaped at half the span size
/// and distributed over each line's base rect with the same jlreq
/// distribution the vertical path uses (`distribute_ruby_tops` along the
/// horizontal flow). Lines are not reflowed to reserve an annotation band;
/// the ruby draws in the natural leading above the base line.
pub fn paint_horizontal_ruby(
    canvas: &Canvas,
    text_content: &TextContent,
    paragraph_index: usize,
    laid_out: &skia::textlayout::Paragraph,
    x: f32,
    y: f32,
) {
    let Some(paragraph) = text_content.paragraphs().get(paragraph_index) else {
        return;
    };
    if paragraph
        .children()
        .iter()
        .all(|span| span.ruby.trim().is_empty())
    {
        return;
    }
    let font_provider = get_render_state().fonts().font_provider();
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    let bounds = text_content.bounds();
    let (_, offset_map) = paragraph.layout_span_texts();

    let mut utf16_cursor = 0usize;
    for span in paragraph.children() {
        let span_text = span.apply_text_transform();
        let span_range = next_horizontal_ruby_range(&offset_map, &mut utf16_cursor, &span_text);
        let ruby_text = span.ruby.trim().to_string();
        if ruby_text.is_empty() || span_range.is_empty() {
            continue;
        }
        let ruby_font_size = span.font_size * span.ruby_size.scale();
        let families = span_font_families(span, &fallback_families);
        let paint = merge_fills(&span.fills, bounds);
        let shifted_range = span_range;
        let rects = laid_out.get_rects_for_range(
            shifted_range,
            skia::textlayout::RectHeightStyle::Tight,
            skia::textlayout::RectWidthStyle::Tight,
        );
        let probe = ruby_text.chars().next().unwrap_or(' ');
        let typeface = families
            .iter()
            .filter_map(|family| {
                font_provider.match_family_style(family, skia::FontStyle::default())
            })
            .find(|tf| tf.unichar_to_glyph(probe as i32) != 0)
            .or_else(|| {
                font_provider.match_family_style(families[0].as_str(), skia::FontStyle::default())
            });
        let Some(typeface) = typeface else {
            continue;
        };
        let font = Font::new(typeface, ruby_font_size);
        let shaped = shape_segment(
            &ruby_text,
            &font,
            false,
            span.font_features,
            fallback_mgr.clone(),
        );
        let glyphs: Vec<(usize, usize, f32)> = shaped
            .iter()
            .enumerate()
            .flat_map(|(run_index, run)| {
                (0..run.glyphs.len()).map(move |glyph| {
                    (
                        run_index,
                        glyph,
                        run.advances.get(glyph).copied().unwrap_or(ruby_font_size),
                    )
                })
            })
            .collect();
        let extents: Vec<f32> = rects.iter().map(|rect| rect.rect.width()).collect();
        let counts = split_counts_by_extent(&extents, glyphs.len());
        let mut assigned = 0usize;
        for (rect_box, count) in rects.iter().zip(counts) {
            if count == 0 {
                continue;
            }
            let slice = &glyphs[assigned..assigned + count];
            assigned += count;
            let advance = slice
                .iter()
                .map(|(_, _, advance)| *advance)
                .fold(0.0f32, f32::max)
                .max(1.0);
            // Horizontal SkParagraph has already fixed the base geometry.
            // When overhang is prohibited, fit the annotation strip to
            // that geometry instead of allowing either end to escape it.
            let glyph_scale = if span.ruby_overhang == RubyOverhang::None {
                (rect_box.rect.width() / (advance * count as f32)).min(1.0)
            } else {
                1.0
            };
            let layout_advance = advance * glyph_scale;
            let lefts = distribute_ruby_tops(
                rect_box.rect.left(),
                rect_box.rect.width(),
                count,
                layout_advance,
                span.ruby_align,
                span.ruby_overhang,
            );
            for ((run_index, glyph, glyph_advance), left) in slice.iter().zip(lefts) {
                let run = &shaped[*run_index];
                let (_, metrics) = run.font.metrics();
                let baseline = match span.ruby_side {
                    RubySide::Over => y + rect_box.rect.top() - metrics.descent,
                    RubySide::Under => y + rect_box.rect.bottom() - metrics.ascent,
                };
                let mut builder = TextBlobBuilder::new();
                let (out_glyphs, points) = builder.alloc_run_pos(&run.font, 1, None);
                out_glyphs[0] = run.glyphs[*glyph];
                points[0] = SkPoint::new(0.0, 0.0);
                if let Some(blob) = builder.make() {
                    let gx = x + left + (layout_advance - glyph_advance * glyph_scale) / 2.0;
                    if glyph_scale < 1.0 {
                        canvas.save();
                        canvas.translate((gx, baseline));
                        canvas.scale((glyph_scale, 1.0));
                        canvas.draw_text_blob(&blob, (0.0, 0.0), &paint);
                        canvas.restore();
                    } else {
                        canvas.draw_text_blob(&blob, (gx, baseline), &paint);
                    }
                }
            }
        }
    }
}

/// Draw one cell's glyphs with an explicit paint (used both for the fill
/// pass — cell's own fill — and for the stroke / shadow silhouette passes,
/// which override the paint for every cell).
fn draw_cell_glyph(
    canvas: &Canvas,
    layout: &VerticalLayout,
    cell: &VerticalCell,
    origin_x: f32,
    origin_y: f32,
    paint: &Paint,
) {
    let column = &layout.columns[cell.column];
    let x_center = origin_x + column_base_center(column);
    let y_top = origin_y + cell.top;
    match cell.kind {
        CellKind::Upright { run, glyph, count } => {
            let run = &layout.runs[run];
            let (ascent, descent) = upright_centre_metrics(&run.font);
            let baseline = y_top
                + cell.glyph_flow_shift
                + upright_baseline_offset(ascent, descent, cell.font_size);
            let x = x_center - cell.h_advance / 2.0;
            let mut builder = TextBlobBuilder::new();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            let base_x = run.positions[glyph].x;
            for i in 0..count {
                glyphs[i] = run.glyphs[glyph + i];
                points[i] = SkPoint::new(
                    run.positions[glyph + i].x - base_x,
                    run.positions[glyph + i].y,
                );
            }
            if let Some(blob) = builder.make() {
                canvas.draw_text_blob(&blob, (x, baseline), paint);
            }
        }
        CellKind::SyntheticRotated { run, glyph, count } => {
            let run = &layout.runs[run];
            if let Some(blob) = cluster_text_blob(run, glyph, count) {
                canvas.save();
                canvas.translate((x_center, y_top + cell.glyph_flow_shift));
                canvas.rotate(90.0, None);
                canvas.draw_text_blob(
                    &blob,
                    (0.0, rotated_cluster_baseline_shift(run, glyph, count)),
                    paint,
                );
                canvas.restore();
            }
        }
        CellKind::Rotated { run } => {
            let run = &layout.runs[run];
            let mut builder = TextBlobBuilder::new();
            let count = run.glyphs.len();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            glyphs.copy_from_slice(&run.glyphs);
            points.copy_from_slice(&run.positions);
            if let Some(blob) = builder.make() {
                canvas.save();
                canvas.translate((x_center, y_top));
                canvas.rotate(90.0, None);
                // After rotation +x runs down the column and +y runs across it;
                // centre the run's actual ink band on the column axis.
                canvas.draw_text_blob(&blob, (0.0, run.rotated_baseline_shift), paint);
                canvas.restore();
            }
        }
        CellKind::TateChuYoko {
            run_start,
            run_count,
            scale,
        } => {
            let composite = &layout.runs[run_start..run_start + run_count];
            let combined_advance: f32 = composite.iter().map(|r| r.advance).sum();
            // Vertical centring uses the tallest run's ascent/descent.
            let (ascent, descent) = composite.iter().fold((0.0f32, 0.0f32), |(a, d), r| {
                let (_, metrics) = r.font.metrics();
                (a.min(metrics.ascent), d.max(metrics.descent))
            });
            let x0 = x_center - (combined_advance * scale) / 2.0;
            let baseline = y_top + cell.font_size / 2.0 - ((ascent + descent) * scale) / 2.0;
            canvas.save();
            canvas.translate((x0, baseline));
            canvas.scale((scale, scale));
            let mut cursor = 0.0f32;
            for run in composite {
                let mut builder = TextBlobBuilder::new();
                let count = run.glyphs.len();
                let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
                glyphs.copy_from_slice(&run.glyphs);
                points.copy_from_slice(&run.positions);
                if let Some(blob) = builder.make() {
                    canvas.draw_text_blob(&blob, (cursor, 0.0), paint);
                }
                cursor += run.advance;
            }
            canvas.restore();
        }
        CellKind::Warichu {
            run_start,
            run_count,
            first_count,
            ..
        } => {
            let all = &layout.runs[run_start..run_start + run_count];
            let (first, second) = all.split_at(first_count);
            let quarter = cell.font_size / 4.0;
            // vertical-rl: the first sub-line reads first, on the right half.
            draw_warichu_line(canvas, first, x_center + quarter, y_top, paint);
            draw_warichu_line(canvas, second, x_center - quarter, y_top, paint);
        }
    }
}

/// Draw one warichu sub-line: upright half-size glyphs stacked down the
/// sub-column centred on `x_center`, clusters kept together like the normal
/// upright path.
fn draw_warichu_line(
    canvas: &Canvas,
    runs: &[ShapedRun],
    x_center: f32,
    y_top: f32,
    paint: &Paint,
) {
    let mut cursor = y_top;
    for run in runs {
        let font_size = run.font.size();
        let (ascent, descent) = upright_centre_metrics(&run.font);
        let baseline_offset = upright_baseline_offset(ascent, descent, font_size);
        let mut glyph = 0usize;
        while glyph < run.glyphs.len() {
            let cluster = run.clusters[glyph];
            let mut count = 1;
            while glyph + count < run.glyphs.len() && run.clusters[glyph + count] == cluster {
                count += 1;
            }
            let advance: f32 = run.advances[glyph..glyph + count].iter().sum();
            let advance = if advance > 0.0 { advance } else { font_size };
            let mut builder = TextBlobBuilder::new();
            let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
            let base_x = run.positions[glyph].x;
            for i in 0..count {
                glyphs[i] = run.glyphs[glyph + i];
                points[i] = SkPoint::new(
                    run.positions[glyph + i].x - base_x,
                    run.positions[glyph + i].y,
                );
            }
            if let Some(blob) = builder.make() {
                canvas.draw_text_blob(
                    &blob,
                    (x_center - advance / 2.0, cursor + baseline_offset),
                    paint,
                );
            }
            cursor += advance;
            glyph += count;
        }
    }
}

/// Decoration bar geometry for a cell in absolute coordinates. Underline
/// runs along the *left* side of the column (the under side in
/// `vertical-rl`); line-through runs down the column centre. Both span the
/// cell's vertical extent so consecutive decorated cells tile a continuous
/// bar. Returns (rect, thickness-adjusted rect) as a skia rect.
fn decoration_bar(
    layout: &VerticalLayout,
    cell: &VerticalCell,
    origin_x: f32,
    origin_y: f32,
    line_through: bool,
) -> skia::Rect {
    let column = &layout.columns[cell.column];
    let x_center = origin_x + column_base_center(column);
    let y_top = origin_y + cell.top;
    let thickness = (cell.font_size * 0.06).max(1.0);
    let bar_x = if line_through {
        x_center
    } else {
        x_center - cell.font_size / 2.0 - cell.font_size / 9.0
    };
    skia::Rect::from_ltrb(
        bar_x - thickness / 2.0,
        y_top,
        bar_x + thickness / 2.0,
        y_top + cell.extent,
    )
}

fn draw_cell_decorations(
    canvas: &Canvas,
    layout: &VerticalLayout,
    cell: &VerticalCell,
    origin_x: f32,
    origin_y: f32,
) {
    let Some(decoration) = cell.decoration else {
        return;
    };
    let mut paint = layout.paints[cell.paint].clone();
    paint.set_style(skia::PaintStyle::Fill);
    paint.set_anti_alias(true);
    if decoration.contains(TextDecoration::UNDERLINE) {
        canvas.draw_rect(
            decoration_bar(layout, cell, origin_x, origin_y, false),
            &paint,
        );
    }
    if decoration.contains(TextDecoration::LINE_THROUGH) {
        canvas.draw_rect(
            decoration_bar(layout, cell, origin_x, origin_y, true),
            &paint,
        );
    }
}

/// Paint every cell's glyphs with a single overriding paint (stroke /
/// shadow silhouette passes).
fn paint_glyphs(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    paint: &Paint,
) {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        draw_cell_glyph(canvas, layout, cell, origin_x, origin_y, paint);
    }
}

/// Fill pass: each cell drawn with its own fill paint, then its decorations.
pub fn paint_layout(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
) {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        let paint = &layout.paints[cell.paint];
        draw_cell_glyph(canvas, layout, cell, origin_x, origin_y, paint);
        draw_cell_decorations(canvas, layout, cell, origin_x, origin_y);
    }
    for ruby in &layout.ruby_cells {
        draw_ruby_cell(canvas, layout, ruby, origin_x, origin_y);
    }
    for mark in &layout.emphasis_marks {
        draw_emphasis_mark(canvas, layout, mark, origin_x, origin_y);
    }
}

/// Draw one emphasis mark (圏点) centred on its base cell's flow extent, in the
/// column's right-side gutter. The mark reuses the base cell's fill paint.
fn draw_emphasis_mark(
    canvas: &Canvas,
    layout: &VerticalLayout,
    mark: &EmphasisMark,
    origin_x: f32,
    origin_y: f32,
) {
    let column = &layout.columns[mark.column];
    let run = &layout.emphasis_runs[mark.run];
    if run.glyphs.is_empty() {
        return;
    }
    let paint = &layout.paints[mark.paint];
    let base_font_size = mark.font_size / EMPHASIS_FONT_SCALE;
    let gutter_center = origin_x
        + column_base_center(column)
        + base_font_size / 2.0
        + mark.outside_offset
        + mark.font_size / 2.0;
    let (_, metrics) = run.font.metrics();
    let cell_center = origin_y + mark.top + mark.extent / 2.0;
    let baseline = cell_center - (metrics.ascent + metrics.descent) / 2.0;
    let advance = run.advances.first().copied().unwrap_or(0.0);
    let mut builder = TextBlobBuilder::new();
    let (glyphs, points) = builder.alloc_run_pos(&run.font, 1, None);
    glyphs[0] = run.glyphs[0];
    points[0] = SkPoint::new(0.0, 0.0);
    if let Some(blob) = builder.make() {
        let x = gutter_center - advance / 2.0;
        canvas.draw_text_blob(&blob, (x, baseline), paint);
    }
}

/// Draw one column's slice of a ruby annotation stacked upright in its
/// column's ruby gutter. Each glyph's flow-axis position comes from
/// the whole base span; each glyph is centred across the gutter using its own
/// fallback-font run.
fn draw_ruby_cell(
    canvas: &Canvas,
    layout: &VerticalLayout,
    ruby: &RubyCell,
    origin_x: f32,
    origin_y: f32,
) {
    let column = &layout.columns[ruby.column];
    let paint = &layout.paints[ruby.paint];
    if ruby.glyphs.is_empty() {
        return;
    }
    let gutter_center = origin_x
        + ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side)
        + ruby.font_size / 2.0;
    for (ruby_glyph, top) in ruby.glyphs.iter().zip(&ruby.glyph_tops) {
        let run = &layout.ruby_runs[ruby_glyph.run];
        let Some(glyph) = run.glyphs.get(ruby_glyph.glyph) else {
            continue;
        };
        let (_, metrics) = run.font.metrics();
        let glyph_top = origin_y + *top;
        let baseline = glyph_top - metrics.ascent;
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points) = builder.alloc_run_pos(&run.font, 1, None);
        glyphs[0] = *glyph;
        points[0] = SkPoint::new(0.0, 0.0);
        if let Some(blob) = builder.make() {
            let h_advance = run
                .advances
                .get(ruby_glyph.glyph)
                .copied()
                .unwrap_or(ruby.font_size);
            let x = gutter_center - h_advance / 2.0;
            canvas.draw_text_blob(&blob, (x, baseline), paint);
        }
    }
}

/// Cross-axis start of a vertical ruby strip. Line height controls the column
/// advance (`base_width`), but must not become spacing between an annotation
/// and its base glyph. Centre the base em in that advance and attach ruby to
/// the em edge; any extra leading remains outside the base+ruby group.
fn ruby_strip_x(
    column: &VerticalColumn,
    ruby_font_size: f32,
    base_font_size: f32,
    side: RubySide,
) -> f32 {
    let base_center = column_base_center(column);
    match side {
        RubySide::Over => base_center + base_font_size / 2.0,
        RubySide::Under => base_center - base_font_size / 2.0 - ruby_font_size,
    }
}

/// Developer overlay: draw a jlreq-style character-frame grid over the
/// laid-out vertical cells. For every column it outlines the column band;
/// for every cell it draws the advance box (the real layout cell), the
/// virtual body / em square centred on the column axis, and the glyph-ink
/// band. This exposes solid-setting, aki, letter-spacing and column
/// planning visually, mirroring the grids in the jlreq figures. It is never
/// emitted to exported or persisted output — only the on-screen fills pass
/// calls it, gated by the `TEXT_GRID_VISIBLE` render flag.
pub fn paint_grid(canvas: &Canvas, layout: &VerticalLayout, bounds: &Rect, align: VerticalAlign) {
    let (origin_x, origin_y) = layout.origin(bounds, align);

    let mut column_paint = Paint::default();
    column_paint.set_anti_alias(true);
    column_paint.set_style(skia::PaintStyle::Stroke);
    column_paint.set_stroke_width(1.0);
    column_paint.set_color(skia::Color::from_argb(0x55, 0x88, 0x88, 0x88));

    let mut advance_paint = Paint::default();
    advance_paint.set_anti_alias(true);
    advance_paint.set_style(skia::PaintStyle::Stroke);
    advance_paint.set_stroke_width(1.0);
    advance_paint.set_color(skia::Color::from_argb(0xAA, 0x2F, 0x80, 0xED));

    let mut em_paint = Paint::default();
    em_paint.set_anti_alias(true);
    em_paint.set_style(skia::PaintStyle::Stroke);
    em_paint.set_stroke_width(1.0);
    em_paint.set_color(skia::Color::from_argb(0x99, 0xEB, 0x57, 0x57));

    let mut ink_paint = Paint::default();
    ink_paint.set_anti_alias(true);
    ink_paint.set_style(skia::PaintStyle::Stroke);
    ink_paint.set_stroke_width(1.0);
    ink_paint.set_color(skia::Color::from_argb(0x77, 0x27, 0xAE, 0x60));

    // Column bands over the full used height.
    for column in &layout.columns {
        let x = origin_x + column.x;
        canvas.draw_rect(
            Rect::from_xywh(x, origin_y, column.width, layout.height),
            &column_paint,
        );
    }

    for cell in &layout.cells {
        let column = &layout.columns[cell.column];
        let x_left = origin_x + column.x;
        let y_top = origin_y + cell.top;
        let x_center = x_left + column.base_offset + column.base_width / 2.0;

        // Advance box: the real layout cell along the column axis.
        canvas.draw_rect(
            Rect::from_xywh(x_left, y_top, column.base_width, cell.extent),
            &advance_paint,
        );

        // Virtual body / em square, centred on the column axis and on the
        // cell's advance so aki and letter-spacing are visible.
        let em = cell.font_size.max(1.0);
        canvas.draw_rect(
            Rect::from_xywh(
                x_center - em / 2.0,
                y_top + (cell.extent - em) / 2.0,
                em,
                em,
            ),
            &em_paint,
        );

        // Glyph-ink band along the flow axis.
        if cell.ink_bottom > cell.ink_top {
            canvas.draw_line(
                (x_left, origin_y + cell.top + cell.ink_top),
                (x_left + column.width, origin_y + cell.top + cell.ink_top),
                &ink_paint,
            );
            canvas.draw_line(
                (x_left, origin_y + cell.top + cell.ink_bottom),
                (x_left + column.width, origin_y + cell.top + cell.ink_bottom),
                &ink_paint,
            );
        }
    }
}

/// Paint the vertical glyph shadows. The shadow paint carries the
/// blur/offset image filter; drawing the glyphs directly through it renders
/// each glyph's shadow without allocating a full-content offscreen layer
/// (a `save_layer` with the filter can exceed GPU limits on tall columns).
pub fn paint_drop_shadow(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    shadow_paint: &Paint,
) {
    let mut paint = shadow_paint.clone();
    paint.set_color(skia::Color::BLACK);
    paint.set_anti_alias(true);
    paint_glyphs(canvas, layout, bounds, vertical_align, &paint);
}

/// Paint a stroke masked to the vertical glyph silhouettes. Center strokes
/// draw the stroked outline directly; inner/outer strokes are masked with
/// `SrcIn` / `SrcOut` against the glyph silhouette (mirrors the horizontal
/// masked-stroke path).
pub fn paint_stroke(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    stroke: &Stroke,
    selrect: &Rect,
    blur: Option<&ImageFilter>,
) {
    let (stroke_paints, layer_opacity) =
        crate::render::text::get_text_stroke_paints(stroke, selrect, false);

    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        canvas.save_layer(&SaveLayerRec::default().paint(&blur_paint));
    }
    if let Some(opacity) = layer_opacity {
        let mut opacity_paint = Paint::default();
        opacity_paint.set_alpha_f(opacity);
        canvas.save_layer(&SaveLayerRec::default().paint(&opacity_paint));
    }

    for stroke_paint in &stroke_paints {
        match stroke.kind {
            StrokeKind::Center => {
                paint_glyphs(canvas, layout, bounds, vertical_align, stroke_paint)
            }
            StrokeKind::Inner => paint_masked_stroke(
                canvas,
                layout,
                bounds,
                vertical_align,
                stroke_paint,
                BlendMode::SrcIn,
            ),
            StrokeKind::Outer => paint_masked_stroke(
                canvas,
                layout,
                bounds,
                vertical_align,
                stroke_paint,
                BlendMode::SrcOut,
            ),
        }
    }

    if layer_opacity.is_some() {
        canvas.restore();
    }
    if blur.is_some() {
        canvas.restore();
    }
}

fn paint_masked_stroke(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    stroke_paint: &Paint,
    blend: BlendMode,
) {
    let mut mask = Paint::default();
    mask.set_color(skia::Color::BLACK);
    mask.set_anti_alias(true);

    canvas.save_layer(&SaveLayerRec::default());
    paint_glyphs(canvas, layout, bounds, vertical_align, &mask);

    let mut blend_paint = Paint::default();
    blend_paint.set_blend_mode(blend);
    canvas.save_layer(&SaveLayerRec::default().paint(&blend_paint));
    paint_glyphs(canvas, layout, bounds, vertical_align, stroke_paint);
    canvas.restore();

    canvas.restore();
}

/// Position-data `direction` value marking a vertical (vertical-rl)
/// strip; 0/1 are the horizontal rtl/ltr values. The CLJS deserializer
/// turns it into `:writing-mode "vertical-rl"` on the entry so the
/// legacy SVG renderer can draw the strip vertically.
pub const DIRECTION_VERTICAL_RL: u32 = 2;

/// Position-data `direction` value marking a ruby annotation strip: the
/// entry's offsets index the span's *ruby* string and the geometry is the
/// exact gutter placement the canvas paints.
pub const DIRECTION_VERTICAL_RUBY: u32 = 3;

fn cell_source_utf16_range(layout: &VerticalLayout, cell: &VerticalCell) -> std::ops::Range<usize> {
    let transformed_span_start = layout.span_utf16_starts[cell.paragraph][cell.span];
    let source_span_start = layout.span_source_utf16_starts[cell.paragraph][cell.span];
    let relative = layout.span_transforms[cell.paragraph][cell.span]
        .source_utf16_range(cell.start - transformed_span_start..cell.end - transformed_span_start);
    source_span_start + relative.start..source_span_start + relative.end
}

/// Position-data entries for the v2 editor / exports: consecutive cells of
/// the same span in the same column merge into one vertical strip.
pub fn position_data(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
) -> Vec<PositionData> {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    let mut result: Vec<PositionData> = Vec::new();

    let mut i = 0;
    while i < layout.cells.len() {
        let first = &layout.cells[i];
        let mut source_range = cell_source_utf16_range(layout, first);
        let mut bottom = first.top + first.extent;
        let mut j = i + 1;
        while j < layout.cells.len() {
            let next = &layout.cells[j];
            if next.paragraph == first.paragraph
                && next.span == first.span
                && next.column == first.column
            {
                let next_source = cell_source_utf16_range(layout, next);
                source_range.start = source_range.start.min(next_source.start);
                source_range.end = source_range.end.max(next_source.end);
                bottom = next.top + next.extent;
                j += 1;
            } else {
                break;
            }
        }
        let column = &layout.columns[first.column];
        let span_start = layout.span_source_utf16_starts[first.paragraph][first.span];
        result.push(PositionData {
            paragraph: first.paragraph as u32,
            span: first.span as u32,
            start_pos: (source_range.start - span_start) as u32,
            end_pos: (source_range.end - span_start) as u32,
            x: origin_x + column.x,
            y: origin_y + first.top,
            // Base text occupies the base sub-band; any ruby gutter is
            // excluded so the editor overlay and selection track the glyphs.
            width: column.base_width,
            height: bottom - first.top,
            direction: DIRECTION_VERTICAL_RL,
        });
        i = j;
    }

    // Ruby annotation strips, with the exact flow-axis placement the canvas
    // paints. `start_pos`/`end_pos` are UTF-16 offsets
    // into the span's ruby string, taken from the shaped clusters so
    // surrogate-pair readings slice correctly. The consumer renders them as
    // their own half-size vertical strips in the column's right-side gutter.
    for ruby in &layout.ruby_cells {
        let (Some(first), Some(last)) = (ruby.glyphs.first(), ruby.glyphs.last()) else {
            continue;
        };
        if ruby.base_segments.is_empty() {
            continue;
        }
        let column = &layout.columns[ruby.column];
        let top = ruby.glyph_tops.iter().copied().fold(f32::MAX, f32::min);
        let bottom = ruby.glyph_tops.iter().copied().fold(f32::MIN, f32::max) + ruby.font_size;
        result.push(PositionData {
            paragraph: ruby.paragraph as u32,
            span: ruby.span as u32,
            start_pos: first.utf16_start as u32,
            end_pos: last.utf16_end as u32,
            x: origin_x + ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side),
            y: origin_y + top,
            width: ruby.font_size,
            height: bottom - top,
            direction: DIRECTION_VERTICAL_RUBY,
        });
    }
    result
}

/// True when the point (in the same space as `bounds`) hits laid-out text.
pub fn intersects(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    x: f32,
    y: f32,
) -> bool {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    layout.cells.iter().any(|cell| {
        let column = &layout.columns[cell.column];
        let rect = Rect::from_xywh(
            origin_x + column.x,
            origin_y + cell.top,
            column.width,
            cell.extent,
        );
        rect.contains(&SkPoint::new(x, y))
    })
}

fn scalar_offset_from_utf16(
    layout: &VerticalLayout,
    paragraph: usize,
    utf16_offset: usize,
) -> Option<usize> {
    let boundaries = layout.paragraph_utf16_boundaries.get(paragraph)?;
    Some(match boundaries.binary_search(&utf16_offset) {
        Ok(index) => index,
        Err(index) => index.saturating_sub(1),
    })
}

fn cell_scalar_range(layout: &VerticalLayout, cell: &VerticalCell) -> Option<(usize, usize)> {
    Some((
        scalar_offset_from_utf16(layout, cell.paragraph, cell.start)?,
        scalar_offset_from_utf16(layout, cell.paragraph, cell.end)?,
    ))
}

/// Caret position (paragraph index, paragraph-relative Unicode scalar offset) for
/// a point given relative to the content block's top-left origin.
pub fn caret_from_point(layout: &VerticalLayout, x: f32, y: f32) -> Option<(usize, usize)> {
    if layout.columns.is_empty() {
        return None;
    }

    // Columns are ordered right->left, i.e. descending x.
    let column_index = layout
        .columns
        .iter()
        .position(|c| x >= c.x && x < c.x + c.width)
        .unwrap_or(if x >= layout.width {
            0
        } else {
            layout.columns.len() - 1
        });

    let paragraph = layout
        .paragraph_columns
        .iter()
        .position(|(start, end)| column_index >= *start && column_index < *end)?;

    let column_cells: Vec<&VerticalCell> = layout
        .cells
        .iter()
        .filter(|c| c.column == column_index)
        .collect();

    if column_cells.is_empty() {
        return Some((paragraph, 0));
    }

    for cell in &column_cells {
        if y < cell.top + cell.extent {
            let (cell_start, cell_end) = cell_scalar_range(layout, cell)?;
            let chars = (cell_end - cell_start).max(1);
            let offset = match cell.kind {
                CellKind::Rotated { .. } => {
                    // Proportional position along the rotated run.
                    let frac = ((y - cell.top) / cell.extent).clamp(0.0, 1.0);
                    cell_start + ((frac * chars as f32).round() as usize).min(chars)
                }
                CellKind::TateChuYoko { .. } => {
                    // The digits run left->right inside the composite, so the
                    // horizontal position picks the offset within it.
                    let column = &layout.columns[cell.column];
                    let left = column_base_center(column) - cell.h_advance / 2.0;
                    let frac = ((x - left) / cell.h_advance.max(1.0)).clamp(0.0, 1.0);
                    cell_start + ((frac * chars as f32).round() as usize).min(chars)
                }
                CellKind::Warichu { first_chars, .. } => {
                    // Right sub-column holds the first sub-line's characters,
                    // left sub-column the second; the vertical position picks
                    // the offset within the chosen sub-line.
                    let column = &layout.columns[cell.column];
                    let centre = column_base_center(column);
                    let first =
                        scalar_offset_from_utf16(layout, cell.paragraph, cell.start + first_chars)?
                            .saturating_sub(cell_start)
                            .min(chars);
                    let (lo, hi) = if x >= centre {
                        (0, first)
                    } else {
                        (first, chars)
                    };
                    let line_chars = (hi - lo).max(1);
                    let frac = ((y - cell.top) / cell.extent.max(1.0)).clamp(0.0, 1.0);
                    cell_start + lo + ((frac * line_chars as f32).round() as usize).min(hi - lo)
                }
                CellKind::Upright { .. } | CellKind::SyntheticRotated { .. } => {
                    if y < cell.top + cell.extent / 2.0 {
                        cell_start
                    } else {
                        cell_end
                    }
                }
            };
            return Some((paragraph, offset));
        }
    }

    Some((
        paragraph,
        scalar_offset_from_utf16(layout, paragraph, column_cells.last().unwrap().end)?,
    ))
}

/// Caret rectangle for a paragraph-relative Unicode scalar offset, in
/// content-local coordinates. The rect spans the column width; its height
/// is the extent of the character at the offset (used by overtype-mode
/// carets to cover the glyph) or zero when the caret sits after the last
/// character, where a thin bar is drawn instead.
pub fn caret_rect(layout: &VerticalLayout, paragraph: usize, offset: usize) -> Option<Rect> {
    let (col_start, _) = *layout.paragraph_columns.get(paragraph)?;

    let mut result: Option<Rect> = None;
    for cell in layout.cells.iter().filter(|c| c.paragraph == paragraph) {
        let (cell_start, cell_end) = cell_scalar_range(layout, cell)?;
        if cell_start > offset {
            continue;
        }
        let column = &layout.columns[cell.column];
        let chars = (cell_end - cell_start).max(1);
        let rect = if offset >= cell_end {
            Rect::from_xywh(column.x, cell.top + cell.extent, column.base_width, 0.0)
        } else {
            match cell.kind {
                CellKind::TateChuYoko { .. } => {
                    // Digits run left->right inside the composite: the offset
                    // moves the caret along the horizontal axis while the
                    // rect keeps the composite's flow extent.
                    let left = column_base_center(column) - cell.h_advance / 2.0;
                    let width = cell.h_advance / chars as f32;
                    let x = left + (offset - cell_start) as f32 * width;
                    Rect::from_xywh(x, cell.top, width, cell.extent)
                }
                CellKind::Warichu { first_chars, .. } => {
                    // Right sub-column holds the first sub-line's
                    // characters, left sub-column the second (jlreq reading
                    // order); the caret tracks the offset down the chosen
                    // half-width sub-line.
                    let first =
                        scalar_offset_from_utf16(layout, cell.paragraph, cell.start + first_chars)?
                            .saturating_sub(cell_start)
                            .min(chars);
                    let within = offset - cell_start;
                    let centre = column_base_center(column);
                    let half = cell.font_size / 2.0;
                    let (band_x, line_start, line_chars) = if within < first {
                        (centre, 0, first)
                    } else {
                        (centre - half, first, chars - first)
                    };
                    let line_chars = line_chars.max(1);
                    let frac = (within - line_start) as f32 / line_chars as f32;
                    Rect::from_xywh(
                        band_x,
                        cell.top + frac * cell.extent,
                        half,
                        cell.extent / line_chars as f32,
                    )
                }
                _ => {
                    let frac = (offset - cell_start) as f32 / chars as f32;
                    Rect::from_xywh(
                        column.x,
                        cell.top + frac * cell.extent,
                        column.base_width,
                        cell.extent / chars as f32,
                    )
                }
            }
        };
        result = Some(rect);
        if offset < cell_end {
            break;
        }
    }

    result.or_else(|| {
        // Empty paragraph: caret at the top of its (empty) first column.
        layout
            .columns
            .get(col_start)
            .map(|column| Rect::from_xywh(column.x, 0.0, column.base_width, 0.0))
    })
}

/// Selection rectangles for a paragraph-relative Unicode scalar offset range, in
/// content-local coordinates.
pub fn range_rects(
    layout: &VerticalLayout,
    paragraph: usize,
    start: usize,
    end: usize,
) -> Vec<Rect> {
    let mut rects: Vec<Rect> = Vec::new();
    for cell in layout.cells.iter().filter(|c| c.paragraph == paragraph) {
        let Some((cell_start, cell_end)) = cell_scalar_range(layout, cell) else {
            continue;
        };
        if cell_end <= start || cell_start >= end {
            continue;
        }
        let column = &layout.columns[cell.column];
        let chars = (cell_end - cell_start).max(1);
        let sel_top = if start > cell_start {
            cell.top + ((start - cell_start) as f32 / chars as f32) * cell.extent
        } else {
            cell.top
        };
        let sel_bottom = if end < cell_end {
            cell.top + ((end - cell_start) as f32 / chars as f32) * cell.extent
        } else {
            cell.top + cell.extent
        };
        if sel_bottom <= sel_top {
            continue;
        }
        // Merge with the previous rect when contiguous in the same column.
        if let Some(last) = rects.last_mut() {
            if (last.left - column.x).abs() < f32::EPSILON && (last.bottom - sel_top).abs() < 0.01 {
                last.bottom = sel_bottom;
                continue;
            }
        }
        rects.push(Rect::from_ltrb(
            column.x,
            sel_top,
            column.x + column.base_width,
            sel_bottom,
        ));
    }
    rects
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(extent: f32, ch: char) -> FlowItem {
        FlowItem {
            extent,
            ch: Some(ch),
            keep_with_previous: false,
        }
    }

    #[test]
    fn upright_classification() {
        assert!(is_upright_char('あ'));
        assert!(is_upright_char('ア'));
        assert!(is_upright_char('漢'));
        assert!(is_upright_char('。'));
        assert!(is_upright_char('「'));
        assert!(is_upright_char('ー'));
        assert!(is_upright_char('！'));
        assert!(!is_upright_char('A'));
        assert!(!is_upright_char('1'));
        assert!(!is_upright_char(' '));
        assert!(!is_upright_char('.'));
    }

    #[test]
    fn emoji_stay_upright_in_vertical_text() {
        for emoji in ['😆', '💦', '🙇'] {
            assert!(is_upright_char(emoji));
        }

        let segments = segment_by_orientation("久😆元💦🙇", TextOrientation::Mixed);
        assert_eq!(segments.len(), 4);
        assert!(segments.iter().all(|segment| segment.upright));
        assert_eq!(segments[1].text, "😆");
        assert_eq!(segments[3].text, "💦🙇");

        let families = span_font_families(&make_span("😆"), &[]);
        assert_eq!(families[1], DEFAULT_EMOJI_FONT);
    }

    #[test]
    fn transformed_japanese_punctuation_has_a_rotated_font_fallback() {
        for character in [
            '「', '」', '『', '』', '（', '）', '［', '］', '【', '】', '、', '。', 'ー', '〜',
            '：', '；',
        ] {
            assert!(
                uses_rotated_vertical_fallback(character),
                "{character} needs a transformed vertical glyph"
            );
        }
        assert!(!uses_rotated_vertical_fallback('あ'));
        assert!(!uses_rotated_vertical_fallback('漢'));
        assert!(!uses_rotated_vertical_fallback('！'));
    }

    #[test]
    fn missing_vertical_alternate_uses_a_character_granular_rotated_cell() {
        let mut content = make_content(&["“"], 1000.0);
        content.paragraphs_mut()[0].children_mut()[0].text_orientation = TextOrientation::Upright;
        let layout = layout_with(&test_provider(), &content);
        assert_eq!(layout.cells.len(), 1);
        assert!(matches!(
            layout.cells[0].kind,
            CellKind::SyntheticRotated { .. }
        ));
    }

    #[test]
    fn segments_mixed_text() {
        let segments = segment_by_orientation("縦書きABC123です", TextOrientation::Mixed);
        assert_eq!(segments.len(), 3);
        assert_eq!(segments[0].text, "縦書き");
        assert!(segments[0].upright);
        assert_eq!(segments[0].utf16_start, 0);
        assert_eq!(segments[1].text, "ABC123");
        assert!(!segments[1].upright);
        assert_eq!(segments[1].utf16_start, 3);
        assert_eq!(segments[2].text, "です");
        assert!(segments[2].upright);
        assert_eq!(segments[2].utf16_start, 9);
    }

    #[test]
    fn segments_upright_orientation_keeps_latin_upright() {
        let segments = segment_by_orientation("縦ABC", TextOrientation::Upright);
        assert_eq!(segments.len(), 1);
        assert!(segments[0].upright);
    }

    #[test]
    fn segments_empty_text() {
        assert!(segment_by_orientation("", TextOrientation::Mixed).is_empty());
    }

    #[test]
    fn plan_columns_breaks_on_overflow() {
        let items: Vec<FlowItem> = "あいうえお".chars().map(|c| item(10.0, c)).collect();
        let placements = plan_columns(&items, 25.0);
        assert_eq!(
            placements,
            vec![(0, 0.0), (0, 10.0), (1, 0.0), (1, 10.0), (2, 0.0)]
        );
    }

    #[test]
    fn plan_columns_oversized_item_gets_own_column() {
        let items = vec![item(10.0, 'あ'), item(100.0, 'い'), item(10.0, 'う')];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (1, 0.0), (2, 0.0)]);
    }

    #[test]
    fn plan_columns_burasage_hangs_comma_period() {
        // 。 overflows the two-cell column; instead of oidashi (pushing い down
        // with it), burasage lets it hang past the column bottom. The invariant
        // "no forbidden-at-line-start char at a column top" still holds — 。
        // never reaches the next column's top.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, 'い'),
            item(10.0, '。'),
            item(10.0, 'う'),
        ];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (0, 10.0), (0, 20.0), (1, 0.0)]);
        assert_eq!(placements[2].0, 0, "。 hangs in the current column");
    }

    #[test]
    fn plan_columns_kinsoku_no_forbidden_end_at_column_bottom() {
        // 「 would be left at the bottom of column 0; it moves down.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, '「'),
            item(10.0, 'い'),
            item(10.0, 'う'),
        ];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (1, 0.0), (1, 10.0), (2, 0.0)]);
    }

    #[test]
    fn plan_columns_kinsoku_shift_is_bounded() {
        // A column full of open brackets cannot be emptied: the shift
        // stops after MAX_KINSOKU_SHIFT characters.
        let items: Vec<FlowItem> = "「「「「「「あ".chars().map(|c| item(10.0, c)).collect();
        let placements = plan_columns(&items, 60.0);
        let first_column_count = placements.iter().filter(|(c, _)| *c == 0).count();
        assert!(first_column_count >= 2);
    }

    #[test]
    fn plan_columns_kinsoku_shift_never_overflows_budget() {
        // A two-item budget: moving the trailing 「「 down with the
        // overflowing い would put three items (30.0) in a 20.0 column;
        // the shift is dropped instead, keeping every column within the
        // wrap budget.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, '「'),
            item(10.0, '「'),
            item(10.0, 'い'),
        ];
        let placements = plan_columns(&items, 20.0);
        let mut column_used = std::collections::BTreeMap::new();
        for (it, (column, top)) in items.iter().zip(&placements) {
            let used: &mut f32 = column_used.entry(*column).or_default();
            *used = used.max(top + it.extent);
        }
        for (column, used) in column_used {
            assert!(used <= 20.0, "column {column} overflows: {used}");
        }
    }

    // -----------------------------------------------------------------
    // Full layout pipeline (real shaping with the bundled test font;
    // glyph coverage does not matter for offsets/columns, tofu still
    // shapes with real advances)
    // -----------------------------------------------------------------

    use crate::math::Rect as MathRect;
    use crate::shapes::{
        AnnotationClearance, Fill, FontFamily, FontStyle, GrowType, Paragraph, RubyAlign,
        RubyOverhang, RubySide, RubySize, SolidColor, TextAlign, TextCombineUpright, TextContent,
        TextDirection, TextEmphasis, TextOrientation, TextPositionWithAffinity, TextSpan,
        TextTransform, WritingMode,
    };
    use crate::wasm::text::helpers as text_helpers;
    use crate::Uuid;

    const TEST_FONT: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");
    const RUBY_FALLBACK_FONT: &[u8] = include_bytes!("../fonts/notosansjp-vmtx-test.ttf");

    fn test_provider() -> TypefaceFontProvider {
        let font_mgr = FontMgr::new();
        let typeface = font_mgr
            .new_from_data(TEST_FONT, None)
            .expect("failed to load test font");
        let mut provider = TypefaceFontProvider::new();
        // The span font family serializes as "uuid-weight-style"; register
        // the test face under the nil-uuid regular name so it matches.
        let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
        provider.register_typeface(typeface, Some(family.as_str()));
        provider
    }

    fn make_span(text: &str) -> TextSpan {
        TextSpan {
            text: text.to_string(),
            font_family: FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            font_size: 20.0,
            line_height: 1.0,
            letter_spacing: 0.0,
            font_weight: 400,
            font_variant_id: Uuid::nil(),
            text_decoration: None,
            text_transform: None,
            text_direction: TextDirection::LTR,
            text_orientation: TextOrientation::Mixed,
            text_combine_upright: TextCombineUpright::None,
            text_emphasis: TextEmphasis::None,
            ruby: String::default(),
            warichu: false,
            font_features: FontFeatures::None,
            annotation_clearance: AnnotationClearance::None,
            ruby_size: RubySize::Half,
            ruby_align: RubyAlign::SpaceAround,
            ruby_overhang: RubyOverhang::Auto,
            ruby_side: RubySide::Over,
            fills: vec![],
        }
    }

    fn make_content(texts: &[&str], height: f32) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        for text in texts {
            let mut paragraph = Paragraph::new(
                TextAlign::Left,
                TextDirection::LTR,
                None,
                None,
                1.0,
                0.0,
                vec![make_span(text)],
            );
            paragraph.set_writing_mode(WritingMode::VerticalRl);
            content.add_paragraph(paragraph);
        }
        content
    }

    fn make_content_with_spans(texts: &[&str], height: f32) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            texts.iter().map(|text| make_span(text)).collect(),
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    fn make_content_aligned(text: &str, height: f32, align: TextAlign) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        let mut paragraph = Paragraph::new(
            align,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![make_span(text)],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    fn make_ruby_content(base: &str, ruby: &str, height: f32) -> TextContent {
        make_ruby_content_with_line_height(base, ruby, height, 1.0)
    }

    fn make_ruby_content_with_line_height(
        base: &str,
        ruby: &str,
        height: f32,
        line_height: f32,
    ) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        let mut span = make_span(base);
        span.ruby = ruby.to_string();
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            line_height,
            0.0,
            vec![span],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    fn make_emphasis_content(base: &str, emphasis: TextEmphasis, height: f32) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        let mut span = make_span(base);
        span.text_emphasis = emphasis;
        span.text_orientation = TextOrientation::Upright;
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    #[test]
    fn emphasis_span_reserves_gutter_and_emits_one_mark_per_upright_cell() {
        let content = make_emphasis_content("AB", TextEmphasis::FilledDot, 400.0);
        let layout = layout_content(&content, 400.0);
        let upright = layout
            .cells
            .iter()
            .filter(|c| matches!(c.kind, CellKind::Upright { .. }))
            .count();
        assert_eq!(upright, 2, "AB upright yields two base cells");
        assert_eq!(
            layout.emphasis_marks.len(),
            upright,
            "one emphasis mark per upright base cell"
        );
        for column in &layout.columns {
            assert!(
                column.width > column.base_width,
                "an emphasis paragraph reserves a gutter beside the base band"
            );
        }
        let mark = &layout.emphasis_marks[0];
        assert!(
            !layout.emphasis_runs[mark.run].glyphs.is_empty(),
            "the emphasis mark must be shaped"
        );
    }

    #[test]
    fn emphasis_skips_whitespace_cells() {
        let content = make_emphasis_content("A B", TextEmphasis::FilledDot, 400.0);
        let layout = layout_content(&content, 400.0);
        let upright = layout
            .cells
            .iter()
            .filter(|c| matches!(c.kind, CellKind::Upright { .. }))
            .count();
        assert_eq!(upright, 3, "'A B' upright yields three base cells");
        assert_eq!(
            layout.emphasis_marks.len(),
            2,
            "the whitespace cell gets no emphasis mark"
        );
    }

    #[test]
    fn emphasis_skips_japanese_commas_stops_and_brackets() {
        let content = make_emphasis_content("A、。（B）」", TextEmphasis::FilledDot, 400.0);
        let layout = layout_content(&content, 400.0);

        assert_eq!(
            layout.emphasis_marks.len(),
            2,
            "only A and B receive emphasis marks"
        );
    }

    fn make_warichu_content(base: &str, height: f32) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, height),
            GrowType::Fixed,
        );
        let mut span = make_span(base);
        span.warichu = true;
        span.text_orientation = TextOrientation::Upright;
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    #[test]
    fn warichu_span_composes_two_half_size_sub_lines() {
        let content = make_warichu_content("ABCD", 400.0);
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
        // Two chars per sub-line at half size: the block's flow extent is
        // roughly one em, far below the four em of normal layout.
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
        let content = make_warichu_content("A", 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout
            .cells
            .iter()
            .all(|c| !matches!(c.kind, CellKind::Warichu { .. })));
    }

    #[test]
    fn no_emphasis_emits_no_marks() {
        let content = make_content(&["AB"], 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout.emphasis_marks.is_empty());
        assert!(layout.emphasis_runs.is_empty());
    }

    fn layout_content(content: &TextContent, max_height: f32) -> VerticalLayout {
        let provider = test_provider();
        let fallback_mgr = FontMgr::from(provider.clone());
        layout_vertical(
            content,
            max_height,
            &provider,
            fallback_mgr,
            &[],
            content.bounds(),
        )
    }

    #[test]
    fn ruby_span_reserves_gutter_and_emits_ruby_cells() {
        let content = make_ruby_content("AB", "ab", 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(
            !layout.ruby_cells.is_empty(),
            "a span with ruby must emit ruby cells"
        );
        for column in &layout.columns {
            assert!(
                column.width > column.base_width,
                "a ruby paragraph reserves a gutter beside the base band"
            );
        }
        let ruby = &layout.ruby_cells[0];
        assert!(
            ruby.glyphs.iter().all(|glyph| layout
                .ruby_runs
                .get(glyph.run)
                .and_then(|run| run.glyphs.get(glyph.glyph))
                .is_some()),
            "every ruby glyph must retain its shaped run"
        );
    }

    #[test]
    fn ruby_size_and_side_control_gutter_geometry() {
        let mut content = make_ruby_content("AB", "ab", 400.0);
        let span = &mut content.paragraphs_mut()[0].children_mut()[0];
        span.ruby_size = RubySize::Quarter;
        span.ruby_side = RubySide::Under;

        let layout = layout_content(&content, 400.0);
        let column = &layout.columns[0];
        let ruby = &layout.ruby_cells[0];

        assert!((ruby.font_size - 5.0).abs() < 0.001);
        assert!((column.base_offset - 5.0).abs() < 0.001);
        assert!((column.width - column.base_width - 5.0).abs() < 0.001);
        assert_eq!(ruby.side, RubySide::Under);
    }

    #[test]
    fn auto_clearance_stacks_ruby_and_emphasis_gutters() {
        let mut legacy_content = make_ruby_content("漢字", "かんじ", 400.0);
        legacy_content.paragraphs_mut()[0].children_mut()[0].text_emphasis =
            TextEmphasis::FilledDot;
        let legacy = layout_content(&legacy_content, 400.0);

        let mut auto_content = legacy_content.clone();
        auto_content.paragraphs_mut()[0].children_mut()[0].annotation_clearance =
            AnnotationClearance::Auto;
        let auto = layout_content(&auto_content, 400.0);

        let legacy_gutter = legacy.columns[0].width - legacy.columns[0].base_width;
        let auto_gutter = auto.columns[0].width - auto.columns[0].base_width;
        assert!((legacy_gutter - 10.0).abs() < 0.001);
        assert!((auto_gutter - 20.0).abs() < 0.001);
        assert!(auto.emphasis_marks[0].outside_offset > 0.0);
    }

    #[test]
    fn ruby_attachment_distance_is_independent_of_line_height() {
        let compact_content = make_ruby_content_with_line_height("AB", "ab", 400.0, 1.0);
        let loose_content = make_ruby_content_with_line_height("AB", "ab", 400.0, 2.0);
        let compact = layout_content(&compact_content, 400.0);
        let loose = layout_content(&loose_content, 400.0);

        let attachment_gap = |layout: &VerticalLayout| {
            let ruby = &layout.ruby_cells[0];
            let column = &layout.columns[ruby.column];
            let base_right = column_base_center(column) + ruby.base_font_size / 2.0;
            ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side) - base_right
        };

        assert!(
            loose.columns[0].base_width > compact.columns[0].base_width,
            "line height must still increase column progression"
        );
        assert!(attachment_gap(&compact).abs() < 0.001);
        assert!(attachment_gap(&loose).abs() < 0.001);

        let loose_positions = position_data(&loose, &loose_content.bounds(), VerticalAlign::Top);
        let ruby_position = loose_positions
            .iter()
            .find(|entry| entry.direction == DIRECTION_VERTICAL_RUBY)
            .expect("ruby position data");
        assert!(
            (ruby_position.width - loose.ruby_cells[0].font_size).abs() < 0.001,
            "export geometry must use the attached ruby strip, not the line-height band"
        );
    }

    #[test]
    fn vertical_ruby_preserves_every_fallback_run() {
        let content = make_ruby_content("AB", "aあ", 400.0);
        let font_mgr = FontMgr::new();
        let source = font_mgr.new_from_data(TEST_FONT, None).unwrap();
        let fallback = font_mgr.new_from_data(RUBY_FALLBACK_FONT, None).unwrap();
        let mut provider = TypefaceFontProvider::new();
        let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
        provider.register_typeface(source, Some(family.as_str()));
        provider.register_typeface(fallback, Some("ruby-fallback"));
        let fallback_mgr = FontMgr::from(provider.clone());
        let layout = layout_vertical(
            &content,
            400.0,
            &provider,
            fallback_mgr,
            &["ruby-fallback".to_string()],
            content.bounds(),
        );

        let referenced_runs: std::collections::BTreeSet<usize> = layout
            .ruby_cells
            .iter()
            .flat_map(|cell| cell.glyphs.iter().map(|glyph| glyph.run))
            .collect();
        assert_eq!(referenced_runs.len(), 2, "ruby must retain both typefaces");
        assert_eq!(
            layout
                .ruby_cells
                .iter()
                .map(|cell| cell.glyphs.len())
                .sum::<usize>(),
            layout.ruby_runs.iter().map(|run| run.glyphs.len()).sum(),
            "every fallback glyph must be assigned to a ruby cell"
        );

        let position = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        let ruby_position = position
            .iter()
            .find(|entry| entry.direction == DIRECTION_VERTICAL_RUBY)
            .expect("ruby position data");
        assert_eq!(ruby_position.start_pos, 0);
        assert_eq!(ruby_position.end_pos, 2);

        let mut surface = skia::surfaces::raster_n32_premul((256, 256)).unwrap();
        paint_layout(
            surface.canvas(),
            &layout,
            &content.bounds(),
            VerticalAlign::Top,
        );
    }

    #[test]
    fn ruby_selection_geometry_excludes_gutter() {
        let content = make_ruby_content("AB", "ab", 400.0);
        let layout = layout_content(&content, 400.0);
        let base_width = layout.columns[0].base_width;
        assert!(base_width < layout.columns[0].width);

        let pd = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        assert!(!pd.is_empty());
        for entry in pd.iter().filter(|e| e.direction == DIRECTION_VERTICAL_RL) {
            assert!(
                (entry.width - base_width).abs() < 0.01,
                "position-data strip must be the base band, not the full column"
            );
        }

        let rects = range_rects(&layout, 0, 0, 10);
        assert!(!rects.is_empty());
        for rect in &rects {
            assert!(
                (rect.width() - base_width).abs() < 0.01,
                "selection rect must be the base band, not the full column"
            );
        }
    }

    #[test]
    fn no_ruby_keeps_base_width_equal_to_width() {
        let content = make_content(&["AB"], 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout.ruby_cells.is_empty());
        for column in &layout.columns {
            assert_eq!(
                column.width, column.base_width,
                "columns without ruby must not reserve a gutter"
            );
        }
    }

    #[test]
    fn ruby_shorter_than_base_distributes_evenly() {
        // One base char (extent 100) with 2 ruby glyphs at advance 50: the
        // combined ruby line (100) equals the base, so glyphs sit in equal
        // slots of 50, each centred (slot/2 - advance/2 = 0 offset).
        let tops = distribute_ruby_tops(
            0.0,
            100.0,
            2,
            50.0,
            RubyAlign::SpaceAround,
            RubyOverhang::Auto,
        );
        assert_eq!(tops.len(), 2);
        assert!(
            (tops[0] - 0.0).abs() < 0.001,
            "first ruby glyph at slot start"
        );
        assert!(
            (tops[1] - 50.0).abs() < 0.001,
            "second ruby glyph one slot down"
        );

        // A wide base (extent 200) with 2 glyphs of advance 50 must spread out
        // (slot 100) rather than pack tight at advance 50.
        let spread = distribute_ruby_tops(
            0.0,
            200.0,
            2,
            50.0,
            RubyAlign::SpaceAround,
            RubyOverhang::Auto,
        );
        assert!(
            (spread[0] - 25.0).abs() < 0.001,
            "ruby glyph centred in its slot"
        );
        assert!(
            (spread[1] - spread[0] - 100.0).abs() < 0.001,
            "even distribution keeps a full slot between glyphs, not the advance"
        );
    }

    #[test]
    fn ruby_longer_than_base_overhangs_symmetrically() {
        // Base extent 40, four ruby glyphs of advance 20 (line 80 > 40): the
        // block centres on the base and overhangs both ends. Overflow is 40,
        // per-end overhang 20 (capped at one ruby em = 20), so it starts 20
        // above the base top.
        let tops = distribute_ruby_tops(
            0.0,
            40.0,
            4,
            20.0,
            RubyAlign::SpaceAround,
            RubyOverhang::Auto,
        );
        assert_eq!(tops.len(), 4);
        assert!(tops[0] < 0.0, "long ruby overhangs above the base top");
        let block_center = (tops[0] + tops[3] + 20.0) / 2.0;
        assert!(
            (block_center - 20.0).abs() < 0.001,
            "the ruby block stays centred on the base"
        );
    }

    #[test]
    fn ruby_alignment_modes_control_short_annotation_distribution() {
        assert_eq!(
            distribute_ruby_tops(
                0.0,
                20.0,
                2,
                4.0,
                RubyAlign::SpaceAround,
                RubyOverhang::Auto,
            ),
            vec![3.0, 13.0]
        );
        assert_eq!(
            distribute_ruby_tops(0.0, 20.0, 2, 4.0, RubyAlign::Center, RubyOverhang::Auto,),
            vec![6.0, 10.0]
        );
        assert_eq!(
            distribute_ruby_tops(0.0, 20.0, 2, 4.0, RubyAlign::Start, RubyOverhang::Auto,),
            vec![0.0, 4.0]
        );
        assert_eq!(
            distribute_ruby_tops(
                0.0,
                20.0,
                2,
                4.0,
                RubyAlign::SpaceBetween,
                RubyOverhang::Auto,
            ),
            vec![0.0, 16.0]
        );
    }

    #[test]
    fn ruby_overhang_none_keeps_long_annotation_at_base_start() {
        let automatic =
            distribute_ruby_tops(0.0, 10.0, 4, 4.0, RubyAlign::Center, RubyOverhang::Auto);
        let constrained =
            distribute_ruby_tops(0.0, 10.0, 4, 4.0, RubyAlign::Center, RubyOverhang::None);

        assert_eq!(automatic, vec![-3.0, 1.0, 5.0, 9.0]);
        assert_eq!(constrained, vec![0.0, 4.0, 8.0, 12.0]);
    }

    #[test]
    fn ruby_side_attaches_to_opposite_base_edges() {
        let column = VerticalColumn {
            x: 10.0,
            width: 40.0,
            base_offset: 8.0,
            base_width: 24.0,
        };

        assert_eq!(ruby_strip_x(&column, 8.0, 20.0, RubySide::Over), 40.0);
        assert_eq!(ruby_strip_x(&column, 8.0, 20.0, RubySide::Under), 12.0);
    }

    #[test]
    fn ruby_base_spreading_expands_gap_for_long_reading() {
        let content = make_ruby_content("日本", "にほんご", 120.0);
        let layout = layout_content(&content, 120.0);
        let ruby_cells: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell.span == 0 && cell.column == 0)
            .collect();
        assert_eq!(ruby_cells.len(), 2);
        let spacing = ruby_cells[1].top - ruby_cells[0].top;
        assert!(
            spacing > 20.0,
            "long ruby should spread the base characters apart, got {}",
            spacing
        );
        let base_extent = ruby_cells[1].top + ruby_cells[1].extent - ruby_cells[0].top;
        assert!(
            base_extent >= 40.0,
            "base span should be at least as long as the 4-glyph half-em ruby line"
        );
    }

    #[test]
    fn ruby_base_spreading_does_not_overlap_following_text() {
        let content = make_content_with_spans(&["日本", "語"], 60.0);
        let mut content = content;
        content.paragraphs_mut()[0].children_mut()[0].ruby = "にほんご".to_string();
        let layout = layout_content(&content, 60.0);
        let mut same_column: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell.column == 0)
            .collect();
        same_column.sort_by(|a, b| {
            a.top
                .partial_cmp(&b.top)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        for pair in same_column.windows(2) {
            assert!(
                pair[0].top + pair[0].extent <= pair[1].top + 0.001,
                "base spreading must not overlap the following cell"
            );
        }
    }

    #[test]
    fn ruby_base_spreading_forces_room_when_no_slack() {
        // Base 日本 (2 em = 40) with a 6-glyph half-em reading (60) followed
        // by 語 in a 60 budget: there is no post-placement slack, so the base
        // extents must grow before planning and push the follower to the next
        // column instead of falling back to overhang.
        let mut content = make_content_with_spans(&["日本", "語"], 60.0);
        content.paragraphs_mut()[0].children_mut()[0].ruby = "にほんごです".to_string();
        let layout = layout_content(&content, 60.0);
        let base: Vec<&VerticalCell> = layout.cells.iter().filter(|c| c.span == 0).collect();
        assert_eq!(base.len(), 2);
        let base_extent =
            base.last().unwrap().top + base.last().unwrap().extent - base.first().unwrap().top;
        assert!(
            base_extent >= 60.0 - 0.001,
            "the base span must grow to the ruby line length, got {}",
            base_extent
        );
        let follower = layout
            .cells
            .iter()
            .find(|c| c.span == 1)
            .expect("follower cell");
        assert_ne!(
            follower.column, base[0].column,
            "the follower must wrap to the next column instead of overlapping"
        );
    }

    #[test]
    fn ruby_base_and_reading_wrap_across_columns() {
        let content = make_ruby_content("日本語文", "にほんごぶん", 40.0);
        let layout = layout_content(&content, 40.0);

        let base_columns: std::collections::BTreeSet<usize> =
            layout.cells.iter().map(|c| c.column).collect();
        assert_eq!(base_columns.len(), 2, "the ruby base must wrap normally");

        let ruby_columns: std::collections::BTreeSet<usize> =
            layout.ruby_cells.iter().map(|r| r.column).collect();
        assert_eq!(ruby_columns, base_columns);
        assert!(!layout.ruby_cells.is_empty());
        let placed: usize = layout.ruby_cells.iter().map(|r| r.glyphs.len()).sum();
        assert_eq!(
            placed,
            layout.ruby_runs.iter().map(|run| run.glyphs.len()).sum(),
            "the reading is partitioned across columns without duplication or loss"
        );
        assert!(
            layout
                .ruby_cells
                .windows(2)
                .all(|pair| pair[0].glyphs.last().unwrap().utf16_end
                    <= pair[1].glyphs.first().unwrap().utf16_start),
            "ruby source ranges remain monotonic across the column break"
        );
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

        let layout = layout_with(&vmtx_provider(), &content);
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
        let layout = layout_with(&vmtx_provider(), &content);
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
        // A run far wider than the em would compress below MIN_TCY_SCALE; it is
        // not combined into a squished composite but laid out normally.
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
        let layout = layout_with(&vmtx_provider(), &content);
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
    fn tate_chu_yoko_digits_combines_four_full_width_digits() {
        let mut content = make_content_with_spans(&["2025年"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&vmtx_provider(), &content);
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
        let layout = layout_with(&vmtx_provider(), &content);
        let tcy = layout
            .cells
            .iter()
            .find(|cell| matches!(cell.kind, CellKind::TateChuYoko { .. }))
            .expect("full-width digit run combines");
        assert_eq!(tcy.start, 0);
        assert_eq!(tcy.end, 4);
    }

    #[test]
    fn tate_chu_yoko_all_uses_asymmetric_punctuation_aki() {
        // 」→TCY keeps the closing mark's trailing half-em, and TCY→「 keeps
        // the opening mark's leading half-em. TCY on the opposite side of
        // either bracket sets solid.
        let mut content = make_content_with_spans(&["く", "」", "20", "「", "く"], 400.0);
        content.paragraphs_mut()[0].children_mut()[2]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_with(&vmtx_provider(), &content);
        assert!(matches!(layout.cells[2].kind, CellKind::TateChuYoko { .. }));
        for (index, label) in [(1, "」 before TCY"), (2, "TCY"), (3, "「 after TCY")] {
            assert!(
                (layout.cells[index].extent - 20.0).abs() < 0.01,
                "{label} should occupy its preferred one-em frame, got {}",
                layout.cells[index].extent
            );
        }

        let mut reverse = make_content_with_spans(&["く", "「", "20", "」", "く"], 400.0);
        reverse.paragraphs_mut()[0].children_mut()[2]
            .set_text_combine_upright(TextCombineUpright::All);
        let reverse = layout_with(&vmtx_provider(), &reverse);
        assert!(matches!(
            reverse.cells[2].kind,
            CellKind::TateChuYoko { .. }
        ));
        assert!((reverse.cells[1].extent - 20.0).abs() < 0.01);
        assert!((reverse.cells[3].extent - 20.0).abs() < 0.01);
    }

    #[test]
    fn tate_chu_yoko_digits_uses_the_same_cl30_adjacency() {
        let mut content = make_content_with_spans(&["く」31「く"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&vmtx_provider(), &content);
        let tcy_index = layout
            .cells
            .iter()
            .position(|cell| matches!(cell.kind, CellKind::TateChuYoko { .. }))
            .expect("digit TCY cell");
        assert!((layout.cells[tcy_index - 1].extent - 20.0).abs() < 0.01);
        assert!((layout.cells[tcy_index].extent - 20.0).abs() < 0.01);
        assert!((layout.cells[tcy_index + 1].extent - 20.0).abs() < 0.01);
    }

    #[test]
    fn caret_from_point_lands_inside_tcy_composite() {
        let mut content = make_content_with_spans(&["1234", "あ"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_content(&content, 400.0);
        let cell = &layout.cells[0];
        assert!(matches!(cell.kind, CellKind::TateChuYoko { .. }));
        let column = &layout.columns[cell.column];
        let y_mid = cell.top + cell.extent / 2.0;
        let (_, at_left) = caret_from_point(&layout, column.x + 0.5, y_mid).unwrap();
        let (_, at_right) =
            caret_from_point(&layout, column.x + column.base_width - 0.5, y_mid).unwrap();
        assert!(at_left <= 1, "left edge maps near the start, got {at_left}");
        assert!(
            at_right >= 3,
            "right edge maps near the end, got {at_right}"
        );
    }

    #[test]
    fn caret_rect_tracks_horizontal_axis_inside_tcy_composite() {
        let mut content = make_content_with_spans(&["1234", "あ"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_content(&content, 400.0);
        let cell = &layout.cells[0];
        assert!(matches!(cell.kind, CellKind::TateChuYoko { .. }));
        let left = column_base_center(&layout.columns[cell.column]) - cell.h_advance / 2.0;
        let digit = cell.h_advance / 4.0;
        for i in 0..4 {
            let rect = caret_rect(&layout, 0, cell.start + i).expect("caret rect");
            assert!(
                (rect.left - (left + i as f32 * digit)).abs() < 0.01,
                "digit {i} caret sits at its horizontal slot, got {}",
                rect.left
            );
            assert!(
                (rect.width() - digit).abs() < 0.01,
                "digit {i} caret is one digit wide, got {}",
                rect.width()
            );
            assert!(
                (rect.top - cell.top).abs() < 0.01 && (rect.height() - cell.extent).abs() < 0.01,
                "digit {i} caret spans the composite's flow extent"
            );
        }
        // After the composite the caret falls back to the flow axis.
        let rect = caret_rect(&layout, 0, cell.end).expect("caret rect");
        assert!(rect.height() < 0.01 || rect.top >= cell.top + cell.extent - 0.01);
    }

    #[test]
    fn warichu_split_balances_and_respects_kinsoku() {
        // Balanced midpoint when nothing forbids it, first line longer.
        assert_eq!(warichu_split_chars("あいうえおか"), 3);
        assert_eq!(warichu_split_chars("あいうえお"), 3);
        // A comma at the midpoint may end the first sub-line...
        assert_eq!(warichu_split_chars("あい、うえ"), 3);
        // ...but must not start the second one: the split moves forward.
        assert_eq!(warichu_split_chars("あいう、えお"), 4);
        // An opening bracket must not end the first sub-line.
        assert_eq!(warichu_split_chars("あい「うえお"), 4);
        // Pathological all-forbidden text keeps the midpoint.
        assert_eq!(warichu_split_chars("、、、、"), 2);
    }

    #[test]
    fn warichu_cell_carries_kinsoku_split() {
        let content = make_warichu_content("あいう、えお", 400.0);
        let layout = layout_content(&content, 400.0);
        let cell = layout
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Warichu { .. }))
            .expect("a warichu cell");
        let CellKind::Warichu { first_chars, .. } = cell.kind else {
            unreachable!();
        };
        assert_eq!(
            first_chars, 4,
            "the comma is pulled up into the first sub-line"
        );
        // The caret for the second sub-line's first character restarts at
        // the composite top, left of the axis.
        let column = &layout.columns[cell.column];
        let centre = column_base_center(column);
        let rect = caret_rect(&layout, 0, cell.start + 4).expect("caret rect");
        assert!((rect.top - cell.top).abs() < 0.01);
        assert!(rect.left < centre);
    }

    #[test]
    fn caret_rect_tracks_sub_lines_inside_warichu() {
        let content = make_warichu_content("割注二行説明", 400.0);
        let layout = layout_content(&content, 400.0);
        let cell = layout
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Warichu { .. }))
            .expect("a warichu cell");
        let column = &layout.columns[cell.column];
        let centre = column_base_center(column);
        let half = cell.font_size / 2.0;
        // First sub-line (offsets 0..3) sits right of the column axis.
        let first = caret_rect(&layout, 0, cell.start).expect("caret rect");
        assert!(
            (first.left - centre).abs() < 0.01,
            "first sub-line caret starts at the axis, got {}",
            first.left
        );
        assert!(
            (first.width() - half).abs() < 0.01,
            "sub-line caret is half-size wide, got {}",
            first.width()
        );
        assert!((first.top - cell.top).abs() < 0.01);
        // Second sub-line (offsets 3..6) sits left of the axis, restarting
        // at the composite top.
        let second = caret_rect(&layout, 0, cell.start + 3).expect("caret rect");
        assert!(
            (second.left - (centre - half)).abs() < 0.01,
            "second sub-line caret sits left of the axis, got {}",
            second.left
        );
        assert!(
            (second.top - cell.top).abs() < 0.01,
            "second sub-line restarts at the composite top, got {}",
            second.top
        );
        // Offsets advance down the sub-line.
        let deeper = caret_rect(&layout, 0, cell.start + 4).expect("caret rect");
        assert!(
            deeper.top > second.top,
            "later offsets move down the sub-line"
        );
    }

    fn visible_flow_gap(previous: &VerticalCell, next: &VerticalCell) -> f32 {
        next.top + next.ink_top - (previous.top + previous.ink_bottom)
    }

    fn spaced_content(text: &str, letter_spacing: f32) -> TextContent {
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, 1000.0),
            GrowType::Fixed,
        );
        let mut span = make_span(text);
        span.letter_spacing = letter_spacing;
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    #[test]
    fn letter_spacing_extends_upright_cells() {
        // Each upright cluster gains `letter_spacing` of flow advance, so
        // cells stack further apart down the column; the centring width
        // (`h_advance`) is unaffected.
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

    // A tiny Noto Sans JP subset carrying `vmtx`/`vhea`: U+3031 (〱, the
    // vertical kana repeat mark) has a 2em vertical advance vs a 1em
    // horizontal advance; U+3042/U+304F are symmetric controls.
    const VMTX_TEST_FONT: &[u8] = include_bytes!("../fonts/notosansjp-vmtx-test.ttf");

    fn vmtx_provider() -> TypefaceFontProvider {
        let font_mgr = FontMgr::new();
        let typeface = font_mgr
            .new_from_data(VMTX_TEST_FONT, None)
            .expect("failed to load vmtx test font");
        let mut provider = TypefaceFontProvider::new();
        let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
        provider.register_typeface(typeface, Some(family.as_str()));
        provider
    }

    fn layout_with(provider: &TypefaceFontProvider, content: &TextContent) -> VerticalLayout {
        let fallback_mgr = FontMgr::from(provider.clone());
        layout_vertical(
            content,
            1000.0,
            provider,
            fallback_mgr,
            &[],
            content.bounds(),
        )
    }

    #[test]
    fn vertical_advance_uses_vmtx() {
        // 〱 flows down the column by its 2em vertical advance (vmtx), not
        // its 1em horizontal width; the glyph still centres on the 1em width.
        let content = make_content(&["〱"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
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
    fn shape_to_path_places_vertical_glyphs_down_the_column() {
        let content = make_content(&["あく"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);

        let paths = paths_from_layout(&layout, &content.bounds(), VerticalAlign::Top, true);

        assert_eq!(paths.len(), 2, "one outline path per upright glyph");
        let first = paths[0].0.bounds();
        let second = paths[1].0.bounds();
        assert!(
            second.top > first.top,
            "the second glyph outline follows the first down the vertical flow axis"
        );
        let horizontal_shift = (second.center_x() - first.center_x()).abs();
        let vertical_shift = second.center_y() - first.center_y();
        assert!(
            vertical_shift > horizontal_shift,
            "vertical flow dominates the glyphs' optical side-bearing difference"
        );
    }

    #[test]
    fn shape_to_path_preserves_the_text_blob_draw_origin() {
        let content = make_content(&["あ"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        let cell = &layout.cells[0];
        let CellKind::Upright { run, glyph, count } = cell.kind else {
            panic!("expected an upright cell");
        };
        let run = &layout.runs[run];
        let mut builder = TextBlobBuilder::new();
        let (glyphs, points) = builder.alloc_run_pos(&run.font, count, None);
        let base_x = run.positions[glyph].x;
        for i in 0..count {
            glyphs[i] = run.glyphs[glyph + i];
            points[i] = SkPoint::new(
                run.positions[glyph + i].x - base_x,
                run.positions[glyph + i].y,
            );
        }
        let blob = builder.make().expect("a glyph text blob");
        let blob_bounds = *blob.bounds();
        let draw_origin = SkPoint::new(120.0, 340.0);
        let mut normalized_blob = blob.clone();
        let normalized_path = SkiaParagraph::get_path(&mut normalized_blob);
        let normalized_bounds = normalized_path.bounds();

        let path = text_blob_path(blob, draw_origin);
        let path_bounds = path.bounds();

        assert!(
            (path_bounds.left - (draw_origin.x + blob_bounds.left + normalized_bounds.left)).abs()
                < 0.01
        );
        assert!(
            (path_bounds.top - (draw_origin.y + blob_bounds.top + normalized_bounds.top)).abs()
                < 0.01
        );
        assert!((path_bounds.width() - normalized_bounds.width()).abs() < 0.01);
        assert!((path_bounds.height() - normalized_bounds.height()).abs() < 0.01);
    }

    #[test]
    fn vertical_advance_symmetric_glyph_unchanged() {
        // A glyph whose vmtx equals its hmtx keeps extent == h_advance.
        let content = make_content(&["く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
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
    const VPAL_TEST_FONT: &[u8] = include_bytes!("../fonts/notosansjp-vpal-test.ttf");

    fn vpal_provider() -> TypefaceFontProvider {
        let font_mgr = FontMgr::new();
        let typeface = font_mgr
            .new_from_data(VPAL_TEST_FONT, None)
            .expect("failed to load vpal test font");
        let mut provider = TypefaceFontProvider::new();
        let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
        provider.register_typeface(typeface, Some(family.as_str()));
        provider
    }

    fn vpal_content(text: &str, font_features: FontFeatures) -> TextContent {
        let mut content = make_content(&[text], 1000.0);
        content.paragraphs_mut()[0].children_mut()[0].font_features = font_features;
        content
    }

    #[test]
    fn native_vertical_alternates_are_not_synthetically_rotated() {
        let layout = layout_with(
            &vpal_provider(),
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
        let provider = vpal_provider();
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
    fn vpal_punctuation_is_not_double_compressed() {
        // 、's vertical alternate already halves its advance under vpal;
        // the aki shed must not compress the half-width cell again.
        let provider = vpal_provider();
        let layout = layout_with(&provider, &vpal_content("あ、あ", FontFeatures::Vpal));
        assert!(
            (layout.cells[1].extent - 10.0).abs() < 0.01,
            "、 is exactly half-width under vpal, got {}",
            layout.cells[1].extent
        );
    }

    #[test]
    fn vpal_opening_bracket_uses_font_placement() {
        // In the middle of a line a normal opening bracket keeps its leading
        // aki. Under vpal its placement still comes from the font's own
        // YPlacement (481 units), not a synthetic sequence shed.
        let provider = vpal_provider();
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
    fn ordinary_closing_punctuation_keeps_preferred_aki() {
        // In ordinary text, the comma and closing bracket keep their normal
        // half-em glyph body plus half-em trailing aki: one em in total.
        let content = make_content(&["く、く」く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        assert_eq!(layout.cells.len(), 5, "one cell per character");
        let em = 20.0;
        assert!(
            (layout.cells[0].extent - em).abs() < 1.0,
            "leading ideograph keeps full advance, got {}",
            layout.cells[0].extent
        );
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "、 before an ideograph keeps its aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[3].extent - em).abs() < 1.0,
            "」 before an ideograph keeps its aki, got {}",
            layout.cells[3].extent
        );
        assert!(
            (layout.cells[4].extent - em).abs() < 1.0,
            "trailing ideograph keeps full advance, got {}",
            layout.cells[4].extent
        );
    }

    #[test]
    fn closing_then_opening_keeps_half_em_aki() {
        // く」「く: the closing bracket sheds its trailing half, but the
        // opening bracket after it keeps its full em (leading half blank), so
        // the pair keeps the half-em aki JIS X 4051 asks for instead of
        // setting solid.
        let content = make_content(&["く」「く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        assert_eq!(layout.cells.len(), 4, "one cell per character");
        let em = 20.0;
        assert!(
            layout.cells[1].extent <= 0.5 * em + 0.01,
            "」 sheds its trailing aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "「 after a closing mark keeps its full em, got {}",
            layout.cells[2].extent
        );
        assert_eq!(
            layout.cells[2].glyph_flow_shift, 0.0,
            "the unshed opening bracket is not shifted"
        );
    }

    #[test]
    fn ordinary_opening_punctuation_keeps_preferred_aki() {
        // く「く: the opening bracket keeps its leading half-em aki in the
        // middle of a line, so its total advance remains one em.
        let content = make_content(&["く「く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        assert_eq!(layout.cells.len(), 3, "one cell per character");
        let em = 20.0;
        assert!(
            (layout.cells[0].extent - em).abs() < 1.0,
            "leading ideograph keeps full advance, got {}",
            layout.cells[0].extent
        );
        assert_eq!(
            layout.cells[0].glyph_flow_shift, 0.0,
            "ideograph is not shifted"
        );
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "「 keeps its leading aki, got {}",
            layout.cells[1].extent
        );
        assert_eq!(
            layout.cells[1].glyph_flow_shift, 0.0,
            "an uncompressed opening bracket is not shifted, got {}",
            layout.cells[1].glyph_flow_shift
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "trailing ideograph keeps full advance, got {}",
            layout.cells[2].extent
        );
    }

    #[test]
    fn consecutive_closing_punctuation_sets_solid_internally() {
        let content = make_content(&["く。」く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        let em = 20.0;
        assert!(
            layout.cells[1].extent <= 0.5 * em + 0.01,
            "。 sheds its internal trailing aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "the final 」 keeps the sequence's trailing aki, got {}",
            layout.cells[2].extent
        );
    }

    #[test]
    fn consecutive_opening_punctuation_sets_solid_after_first() {
        let content = make_content(&["く「『く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        let em = 20.0;
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "the first opening bracket keeps the sequence's leading aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            layout.cells[2].extent <= 0.5 * em + 0.01,
            "the second opening bracket sets solid, got {}",
            layout.cells[2].extent
        );
        assert!(layout.cells[2].glyph_flow_shift < -0.01);
    }

    #[test]
    fn opening_bracket_at_column_head_is_tentsuki() {
        let content = make_content(&["「く"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        let em = 20.0;
        assert_eq!(layout.cells[0].top, 0.0);
        assert!(
            layout.cells[0].extent <= 0.5 * em + 0.01,
            "line-head 「 sheds its leading aki, got {}",
            layout.cells[0].extent
        );
        assert!(layout.cells[0].glyph_flow_shift < -0.01);
    }

    #[test]
    fn line_end_punctuation_keeps_preferred_half_em_aki() {
        let content = make_content(&["く、"], 40.0);
        let layout = layout_with(&vmtx_provider(), &content);
        assert_eq!(layout.cells[0].column, layout.cells[1].column);
        assert!(
            (layout.cells[1].extent - 20.0).abs() < 1.0,
            "line-end 、 keeps a half-em after its glyph, got {}",
            layout.cells[1].extent
        );
    }

    #[test]
    fn centered_punctuation_shift_centres_ink_in_em_body() {
        assert!(is_centered_punctuation('・'));
        assert!(is_centered_punctuation('：'));
        assert!(is_centered_punctuation('；'));
        assert!(!is_centered_punctuation('あ'));
        // Ink hugging the bottom of the body (14..18 in a 20 body) shifts up so
        // its midpoint (16) lands on the body midpoint (10): shift == -6.
        let shift = centered_flow_shift(14.0, 18.0, 20.0);
        assert!((shift + 6.0).abs() < 1e-4, "expected -6, got {shift}");
        // Already-centred ink needs no shift.
        assert!(centered_flow_shift(8.0, 12.0, 20.0).abs() < 1e-4);
    }

    #[test]
    fn plain_ideographs_keep_full_advance() {
        let content = make_content(&["くくく"], 1000.0);
        let layout = layout_with(&vmtx_provider(), &content);
        for cell in &layout.cells {
            assert!(
                (cell.extent - 20.0).abs() < 1.0,
                "no punctuation: full advance kept, got {}",
                cell.extent
            );
        }
    }

    #[test]
    fn vertical_metrics_parse_vmtx() {
        let font_mgr = FontMgr::new();
        let typeface = font_mgr.new_from_data(VMTX_TEST_FONT, None).unwrap();
        let font = Font::new(typeface, 20.0);
        let vm = VerticalMetrics::from_font(&font).expect("vmtx present");
        // gid 1 = 〱 (2000 units), gid 3+ fall back to the last long metric.
        assert!((vm.advance(1, 20.0) - 40.0).abs() < 0.01);
        assert!((vm.advance(3, 20.0) - 20.0).abs() < 0.01);
        assert!((vm.advance(99, 20.0) - 20.0).abs() < 0.01);
    }

    #[test]
    fn vertical_metrics_absent_without_vmtx() {
        // The bundled Source Sans face carries no `vmtx`.
        let font_mgr = FontMgr::new();
        let typeface = font_mgr.new_from_data(TEST_FONT, None).unwrap();
        let font = Font::new(typeface, 20.0);
        assert!(VerticalMetrics::from_font(&font).is_none());
    }

    #[test]
    fn layout_cells_tile_the_text() {
        let text = "縦書きのAB12テスト。";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        let mut expected = 0usize;
        for cell in &layout.cells {
            assert_eq!(cell.paragraph, 0);
            assert_eq!(cell.start, expected, "cells must tile without gaps");
            assert!(cell.end > cell.start);
            expected = cell.end;
        }
        assert_eq!(expected, text.encode_utf16().count());
    }

    #[test]
    fn layout_columns_respect_wrap_height() {
        let content = make_content(&["あいうえおかきくけこ"], 100.0);
        let layout = layout_content(&content, 60.0);

        assert!(layout.columns.len() > 1, "content must wrap into columns");
        for column_index in 0..layout.columns.len() {
            let bottom = layout
                .cells
                .iter()
                .filter(|c| c.column == column_index)
                .map(|c| c.top + c.extent)
                .fold(0.0f32, f32::max);
            assert!(bottom <= 60.0 + 0.01, "column overflows the wrap height");
        }
        // Columns advance leftward: column 0 is the rightmost.
        assert!(layout.columns[0].x > layout.columns[1].x);
        let total: f32 = layout.columns.iter().map(|c| c.width).sum();
        assert!((layout.width - total).abs() < 0.01);
    }

    #[test]
    fn layout_kinsoku_no_period_at_column_top() {
        // Wrap height fits exactly 2 characters per column; the 。 after
        // the second character would start column 2 without kinsoku.
        let content = make_content(&["あい。うえお"], 100.0);
        let cell_extent = {
            let layout = layout_content(&content, 1000.0);
            layout.cells[0].extent
        };
        let layout = layout_content(&content, cell_extent * 2.0 + 0.1);

        for column_index in 0..layout.columns.len() {
            let first = layout
                .cells
                .iter()
                .filter(|c| c.column == column_index)
                .min_by(|a, b| a.top.partial_cmp(&b.top).unwrap());
            if let Some(first) = first {
                if first.top == 0.0 && first.end - first.start == 1 {
                    let text: Vec<char> = "あい。うえお".chars().collect();
                    let c = text[first.start];
                    assert!(
                        !forbidden_at_line_start(c),
                        "column starts with forbidden char {c}"
                    );
                }
            }
        }
    }

    #[test]
    fn layout_each_paragraph_starts_a_new_column() {
        let content = make_content(&["あい", "うえ"], 1000.0);
        let layout = layout_content(&content, 1000.0);

        assert_eq!(layout.paragraph_columns.len(), 2);
        let (p0_start, p0_end) = layout.paragraph_columns[0];
        let (p1_start, _) = layout.paragraph_columns[1];
        assert_eq!(p0_start, 0);
        assert_eq!(p0_end, p1_start);
        assert!(layout
            .cells
            .iter()
            .all(|c| (c.paragraph == 0) == (c.column < p0_end)));
    }

    #[test]
    fn layout_empty_paragraph_still_takes_a_column() {
        let content = make_content(&["あ", "", "い"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        assert_eq!(layout.columns.len(), 3);
        assert_eq!(layout.paragraph_columns[1], (1, 2));
    }

    #[test]
    fn caret_round_trip() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        for cell in &layout.cells {
            let column = &layout.columns[cell.column];
            // A point in the upper half of the cell resolves to its start.
            let (paragraph, offset) = caret_from_point(
                &layout,
                column.x + column.width / 2.0,
                cell.top + cell.extent * 0.25,
            )
            .expect("caret");
            assert_eq!(paragraph, 0);
            assert_eq!(offset, cell.start);

            // caret_rect for that offset lands inside the same column and
            // carries the character extent (for overtype carets).
            let rect = caret_rect(&layout, paragraph, offset).expect("caret rect");
            assert!((rect.x() - column.x).abs() < 0.01);
            let chars = (cell.end - cell.start).max(1);
            assert!((rect.height() - cell.extent / chars as f32).abs() < 0.01);
        }

        // After the last character there is no glyph to cover: zero height.
        let end_offset = layout.cells.last().unwrap().end;
        let rect = caret_rect(&layout, 0, end_offset).expect("caret rect");
        assert_eq!(rect.height(), 0.0);
    }

    #[test]
    fn non_bmp_caret_offsets_round_trip_through_editor_operations() {
        let content = make_content(&["𠀀あ"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let first = &layout.cells[0];
        let second = &layout.cells[1];
        let column = &layout.columns[first.column];
        let x = column_base_center(column);

        let before = caret_from_point(&layout, x, first.top + first.extent * 0.25).unwrap();
        let after = caret_from_point(&layout, x, first.top + first.extent * 0.75).unwrap();
        assert_eq!(before, (0, 0));
        assert_eq!(after, (0, 1));

        let rect = caret_rect(&layout, after.0, after.1).expect("caret rect");
        assert!((rect.top - second.top).abs() < 0.01);
        assert!((rect.height() - second.extent).abs() < 0.01);

        let rects = range_rects(&layout, 0, 0, 1);
        assert_eq!(rects.len(), 1);
        assert!((rects[0].height() - first.extent).abs() < 0.01);

        let start = TextPositionWithAffinity::new_without_affinity(0, 0);
        let end = TextPositionWithAffinity::new_without_affinity(after.0, after.1);
        assert_eq!(
            text_helpers::move_cursor_forward(&start, content.paragraphs(), false),
            end
        );
        assert_eq!(
            text_helpers::move_cursor_backward(&end, content.paragraphs(), false),
            start
        );

        let mut inserted = make_content(&["𠀀あ"], 1000.0);
        assert_eq!(
            text_helpers::insert_text_at_cursor(&mut inserted, &end, "X"),
            Some(2)
        );
        assert_eq!(inserted.paragraphs()[0].children()[0].text, "𠀀Xあ");

        let mut deleted = make_content(&["𠀀あ"], 1000.0);
        assert_eq!(
            text_helpers::delete_char_before(&mut deleted, &end),
            Some(start)
        );
        assert_eq!(deleted.paragraphs()[0].children()[0].text, "あ");
    }

    #[test]
    fn selection_rects_cover_the_range() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        let rects = range_rects(&layout, 0, 1, 3);
        assert!(!rects.is_empty());
        let covered: f32 = rects.iter().map(|r| r.height()).sum();
        let expected: f32 = layout
            .cells
            .iter()
            .filter(|c| c.start >= 1 && c.end <= 3)
            .map(|c| c.extent)
            .sum();
        assert!((covered - expected).abs() < 0.01);
    }

    #[test]
    fn position_data_merges_by_column_and_maps_span_offsets() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();

        let data = position_data(&layout, &bounds, VerticalAlign::Top);
        assert_eq!(data.len(), 1, "one column, one span => one entry");
        assert_eq!(data[0].start_pos, 0);
        assert_eq!(data[0].end_pos, text.encode_utf16().count() as u32);
        // Right-anchored: the strip ends at the bounds right edge.
        assert!((data[0].x + data[0].width - bounds.right).abs() < 0.01);
    }

    #[test]
    fn position_data_keeps_expanded_transform_in_one_source_safe_strip() {
        let mut content = make_content(&["AßB"], 1000.0);
        let span = &mut content.paragraphs_mut()[0].children_mut()[0];
        span.text_transform = Some(TextTransform::Uppercase);
        span.text_orientation = TextOrientation::Upright;
        let unwrapped = layout_content(&content, 1000.0);
        let expanded_extent: f32 = unwrapped
            .cells
            .iter()
            .filter(|cell| cell_source_utf16_range(&unwrapped, cell) == (1..2))
            .map(|cell| cell.extent)
            .sum();
        let layout = layout_content(&content, expanded_extent + 0.01);

        let expanded: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell_source_utf16_range(&layout, cell) == (1..2))
            .collect();
        assert_eq!(expanded.len(), 2, "ß must shape as the two cells in SS");
        assert_eq!(
            expanded[0].column, expanded[1].column,
            "glyphs from one source character must not split across columns"
        );

        let data = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        let ranges: Vec<(u32, u32)> = data
            .iter()
            .filter(|entry| entry.direction == DIRECTION_VERTICAL_RL)
            .map(|entry| (entry.start_pos, entry.end_pos))
            .collect();
        assert_eq!(ranges, vec![(0, 1), (1, 2), (2, 3)]);
    }

    #[test]
    fn position_data_emits_ruby_annotation_strips() {
        let content = make_ruby_content("漢字", "かんじ", 400.0);
        let layout = layout_content(&content, 400.0);
        let bounds = content.bounds();
        let data = position_data(&layout, &bounds, VerticalAlign::Top);

        let base: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RL)
            .collect();
        let ruby: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RUBY)
            .collect();
        assert_eq!(base.len(), 1);
        assert_eq!(ruby.len(), 1, "one annotated column => one ruby strip");
        assert_eq!(ruby[0].start_pos, 0);
        assert_eq!(ruby[0].end_pos, 3, "offsets index the ruby string");
        // The strip sits in the gutter, to the right of the base band.
        assert!(ruby[0].x >= base[0].x + base[0].width - 0.001);
        assert!(ruby[0].width > 0.0);
        assert!(ruby[0].height > 0.0);
    }

    #[test]
    fn position_data_ruby_offsets_are_utf16_for_non_bmp_readings() {
        // Two surrogate-pair reading characters: 2 glyphs but 4 UTF-16
        // units. The strip offsets must slice the ruby string by UTF-16.
        let content = make_ruby_content("\u{6f22}\u{5b57}", "\u{1d4aa}\u{1d4ab}", 400.0);
        let layout = layout_content(&content, 400.0);
        let bounds = content.bounds();
        let data = position_data(&layout, &bounds, VerticalAlign::Top);
        let ruby: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RUBY)
            .collect();
        assert_eq!(ruby.len(), 1);
        assert_eq!(ruby[0].start_pos, 0);
        assert_eq!(
            ruby[0].end_pos, 4,
            "surrogate pairs count two UTF-16 units each"
        );
    }

    #[test]
    fn horizontal_ruby_ranges_use_utf16_across_spans() {
        let offset_map = crate::shapes::kinsoku::OffsetMap::default();
        let mut cursor = 0;

        assert_eq!(
            next_horizontal_ruby_range(&offset_map, &mut cursor, "𠀀"),
            0..2
        );
        assert_eq!(
            next_horizontal_ruby_range(&offset_map, &mut cursor, "漢"),
            2..3
        );
    }

    #[test]
    fn split_counts_by_extent_drops_no_glyph() {
        assert_eq!(split_counts_by_extent(&[60.0, 40.0], 5), vec![3, 2]);
        assert_eq!(split_counts_by_extent(&[100.0], 4), vec![4]);
        assert_eq!(split_counts_by_extent(&[0.0, 0.0], 3), vec![0, 3]);
        assert_eq!(
            split_counts_by_extent(&[1.0, 1.0, 1.0], 2)
                .iter()
                .sum::<usize>(),
            2
        );
        assert!(split_counts_by_extent(&[], 3).is_empty());
    }

    #[test]
    fn block_axis_alignment_maps_start_center_end_to_right_center_left() {
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Top), 160.0);
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Center), 80.0);
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Bottom), 0.0);
        assert_eq!(block_axis_offset(20.0, 40.0, VerticalAlign::Top), 0.0);
    }

    #[test]
    fn position_data_follows_block_axis_alignment() {
        let content = make_content(&["あいう"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();
        let top = position_data(&layout, &bounds, VerticalAlign::Top);
        let center = position_data(&layout, &bounds, VerticalAlign::Center);
        let bottom = position_data(&layout, &bounds, VerticalAlign::Bottom);

        assert!(top[0].x > center[0].x);
        assert!(center[0].x > bottom[0].x);
        assert!((bottom[0].x - bounds.left).abs() < 0.01);
    }

    #[test]
    fn wrapped_vertical_content_grows_across_columns() {
        let content = make_content(&["あいうえおかきくけこ"], 60.0);
        let layout = layout_content(&content, 60.0);
        assert!(layout.columns.len() > 1);
        assert!(layout.width > layout.columns[0].width);
        assert!(layout.height <= 60.0 + 0.01);
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

    #[test]
    fn inter_script_spacing_separates_cjk_and_latin() {
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM;

        let cjk_latin = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        assert_eq!(cjk_latin.cells.len(), 2);
        assert!(
            (visible_flow_gap(&cjk_latin.cells[0], &cjk_latin.cells[1]) - expected_gap).abs()
                < 0.01
        );

        let latin_cjk = layout_content(&make_content(&["aあ"], 1000.0), 1000.0);
        assert_eq!(latin_cjk.cells.len(), 2);
        assert!(
            (visible_flow_gap(&latin_cjk.cells[0], &latin_cjk.cells[1]) - expected_gap).abs()
                < 0.01
        );
    }

    #[test]
    fn inter_script_spacing_is_visually_symmetric_around_latin_run() {
        let layout = layout_content(&make_content(&["うpenあ"], 1000.0), 1000.0);
        assert_eq!(layout.cells.len(), 3);
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM;
        let before = visible_flow_gap(&layout.cells[0], &layout.cells[1]);
        let after = visible_flow_gap(&layout.cells[1], &layout.cells[2]);
        assert!((before - expected_gap).abs() < 0.01);
        assert!((after - expected_gap).abs() < 0.01);
        assert!((before - after).abs() < 0.01);
    }

    #[test]
    fn inter_script_spacing_preserves_explicit_letter_spacing() {
        let letter_spacing = 3.0;
        let layout = layout_content(&spaced_content("うpenあ", letter_spacing), 1000.0);
        assert_eq!(layout.cells.len(), 3);
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM + letter_spacing;
        assert!((visible_flow_gap(&layout.cells[0], &layout.cells[1]) - expected_gap).abs() < 0.01);
        assert!((visible_flow_gap(&layout.cells[1], &layout.cells[2]) - expected_gap).abs() < 0.01);
    }

    #[test]
    fn inter_script_spacing_crosses_span_boundaries() {
        let joined = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        let split = layout_content(&make_content_with_spans(&["あ", "a"], 1000.0), 1000.0);
        assert_eq!(joined.cells.len(), split.cells.len());
        for (joined, split) in joined.cells.iter().zip(&split.cells) {
            assert!((joined.top - split.top).abs() < 0.01);
            assert!((joined.extent - split.extent).abs() < 0.01);
        }
    }

    #[test]
    fn inter_script_spacing_excludes_punctuation_and_whitespace() {
        let cjk_extent = layout_content(&make_content(&["あ"], 1000.0), 1000.0).cells[0].extent;
        for text in ["あ.", "あ a"] {
            let layout = layout_content(&make_content(&[text], 1000.0), 1000.0);
            assert!(
                (layout.cells[0].extent - cjk_extent).abs() < 0.01,
                "{text:?} must not add spacing immediately after the CJK cell"
            );
        }
    }

    #[test]
    fn oikomi_reduces_script_gap_before_oidashi() {
        let natural = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        assert_eq!(natural.cells.len(), 2);
        let natural_total: f32 = natural.cells.iter().map(|cell| cell.extent).sum();
        let budget = natural_total - 2.0;
        let adjusted = layout_content(&make_content(&["あa"], budget), budget);
        assert_eq!(adjusted.cells[0].column, adjusted.cells[1].column);
        let gap = visible_flow_gap(&adjusted.cells[0], &adjusted.cells[1]);
        assert!(
            (gap - (20.0 * INTER_SCRIPT_SPACING_EM - 2.0)).abs() < 0.01,
            "oikomi should recover the two-pixel deficit from the script gap, got {gap}"
        );
    }

    #[test]
    fn oikomi_preserves_sentence_final_full_stop_aki() {
        let natural = layout_content(&make_content(&["あ。あ"], 1000.0), 1000.0);
        let natural_total: f32 = natural.cells.iter().map(|cell| cell.extent).sum();
        let budget = natural_total - 2.0;
        let adjusted = layout_content(&make_content(&["あ。あ"], budget), budget);
        assert_ne!(
            adjusted.cells[1].column, adjusted.cells[2].column,
            "fixed full-stop aki must not be compressed to retain the next ideograph"
        );
        assert!((adjusted.cells[1].extent - natural.cells[1].extent).abs() < 0.01);
    }

    #[test]
    fn capped_adjustment_redistributes_after_a_boundary_saturates() {
        let allocations = capped_equal_allocations(&[(0, 1.0), (1, 3.0)], 3.0);
        assert_eq!(allocations, vec![(0, 1.0), (1, 2.0)]);
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
    fn align_offset_helper_maps_edges() {
        // Unbounded budget (auto-width): no shift.
        assert_eq!(
            align_offset_along_column(TextAlign::Center, f32::MAX, 40.0),
            0.0
        );
        // Left/Start anchors to the top.
        assert_eq!(align_offset_along_column(TextAlign::Left, 100.0, 40.0), 0.0);
        // Center splits the slack.
        assert_eq!(
            align_offset_along_column(TextAlign::Center, 100.0, 40.0),
            30.0
        );
        // Right/End pushes to the bottom.
        assert_eq!(
            align_offset_along_column(TextAlign::Right, 100.0, 40.0),
            60.0
        );
        assert_eq!(align_offset_along_column(TextAlign::End, 100.0, 40.0), 60.0);
        // Overfull column: no negative shift.
        assert_eq!(
            align_offset_along_column(TextAlign::Right, 40.0, 100.0),
            0.0
        );
    }

    #[test]
    fn text_align_shifts_column_cells() {
        let text = "あいう";
        // Baseline (top-aligned) used length of the single column.
        let used = {
            let content = make_content_aligned(text, 1000.0, TextAlign::Left);
            let layout = layout_content(&content, 1000.0);
            layout
                .cells
                .iter()
                .map(|c| c.top + c.extent)
                .fold(0.0f32, f32::max)
        };

        let top_of = |align: TextAlign| {
            let content = make_content_aligned(text, 1000.0, align);
            let layout = layout_content(&content, 1000.0);
            layout.cells[0].top
        };

        assert!(top_of(TextAlign::Left).abs() < 0.01);
        assert!((top_of(TextAlign::Center) - (1000.0 - used) / 2.0).abs() < 0.5);
        assert!((top_of(TextAlign::Right) - (1000.0 - used)).abs() < 0.5);
        // Cells stay in reading order and keep tiling under the shift.
        let content = make_content_aligned(text, 1000.0, TextAlign::Right);
        let layout = layout_content(&content, 1000.0);
        for pair in layout.cells.windows(2) {
            assert!(pair[1].top >= pair[0].top);
        }
    }

    #[test]
    fn text_align_auto_width_stays_top() {
        // Auto-width columns are snug: alignment must not shift them.
        crate::globals::design_init();
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, 60.0),
            GrowType::AutoWidth,
        );
        let mut paragraph = Paragraph::new(
            TextAlign::Right,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![make_span("あいうえお")],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);

        let layout = layout_content(&content, wrap_height(&content, 60.0));
        assert!(layout.cells[0].top.abs() < 0.01);
    }

    #[test]
    fn rotated_baseline_shift_centres_band() {
        let top = -30.0;
        let bottom = 10.0;
        let shift = rotated_baseline_shift(top, bottom);
        assert!(((top + bottom) / 2.0 + shift).abs() < f32::EPSILON);
        // Symmetric metrics need no shift.
        assert_eq!(rotated_baseline_shift(-20.0, 20.0), 0.0);
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

    #[test]
    fn justify_fills_non_last_columns() {
        use std::collections::BTreeMap;
        let text = "あいうえお";
        // Uniform per-cell extent for the test font.
        let e = {
            let content = make_content_aligned(text, 1000.0, TextAlign::Left);
            layout_content(&content, 1000.0).cells[0].extent
        };
        // A 2.5-cell budget wraps into three columns: {0,1}, {2,3}, {4}. The
        // first two break with 0.5e of slack; the last column is one cell.
        let budget = e * 2.5;
        let content = make_content_aligned(text, budget, TextAlign::Justify);
        let layout = layout_content(&content, budget);

        let mut by_col: BTreeMap<usize, Vec<&VerticalCell>> = BTreeMap::new();
        for c in &layout.cells {
            by_col.entry(c.column).or_default().push(c);
        }
        assert!(by_col.len() >= 2, "text must wrap into multiple columns");
        let last = *by_col.keys().max().unwrap();

        for (&col, col_cells) in &by_col {
            if col != last && col_cells.len() > 1 {
                assert!(col_cells[0].top.abs() < 0.01, "col {col} top cell at top");
                let bottom = col_cells.last().unwrap().top + col_cells.last().unwrap().extent;
                assert!(
                    (bottom - budget).abs() < 0.5,
                    "justified col {col} fills the budget, bottom {bottom} vs {budget}"
                );
            }
        }
        // The last column keeps its natural top (start-aligned, not stretched).
        assert!(
            by_col[&last][0].top.abs() < 0.01,
            "last column is not justified"
        );
    }

    // -----------------------------------------------------------------
    // Strokes / shadows / decorations
    // -----------------------------------------------------------------

    use crate::shapes::{Stroke, StrokeStyle};

    fn make_decorated_content(text: &str, decoration: TextDecoration) -> TextContent {
        crate::globals::design_init();
        let mut span = make_span(text);
        span.text_decoration = Some(decoration);
        let mut content = TextContent::new(
            MathRect::from_xywh(0.0, 0.0, 200.0, 1000.0),
            GrowType::Fixed,
        );
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        );
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        content.add_paragraph(paragraph);
        content
    }

    #[test]
    fn cells_carry_font_size_and_decoration() {
        let content = make_decorated_content("あい", TextDecoration::UNDERLINE);
        let layout = layout_content(&content, 1000.0);
        assert!(!layout.cells.is_empty());
        for cell in &layout.cells {
            assert_eq!(cell.font_size, 20.0);
            assert_eq!(cell.decoration, Some(TextDecoration::UNDERLINE));
        }
    }

    #[test]
    fn decoration_bar_underline_left_of_center_line_through_centered() {
        let content = make_decorated_content("あい", TextDecoration::UNDERLINE);
        let layout = layout_content(&content, 1000.0);
        let (ox, oy) = layout.origin(&content.bounds(), VerticalAlign::Top);
        let cell = &layout.cells[0];
        let column = &layout.columns[cell.column];
        let x_center = ox + column.x + column.width / 2.0;

        let underline = decoration_bar(&layout, cell, ox, oy, false);
        let strike = decoration_bar(&layout, cell, ox, oy, true);

        // Underline sits left of the column axis; line-through is centered.
        assert!(underline.center_x() < x_center);
        assert!((strike.center_x() - x_center).abs() < 0.01);
        // Both bars span the cell's vertical extent.
        assert!((underline.height() - cell.extent).abs() < 0.01);
        // Bar thickness is at least 1px.
        assert!(underline.width() >= 1.0 - 0.01);
    }

    #[test]
    fn paint_passes_do_not_panic() {
        let content = make_decorated_content("あいAB。", TextDecoration::LINE_THROUGH);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();
        let selrect = content.bounds();

        let mut surface = skia::surfaces::raster_n32_premul((256, 256)).unwrap();
        let canvas = surface.canvas();

        // Shape transforms are applied on the caller's canvas. Exercise a
        // non-trivial transform with mixed upright/rotated cells so every
        // vertical paint pass remains transform-safe.
        canvas.translate((12.0, 8.0));
        canvas.rotate(7.0, Some((64.0, 64.0).into()));

        paint_layout(canvas, &layout, &bounds, VerticalAlign::Top);
        paint_drop_shadow(
            canvas,
            &layout,
            &bounds,
            VerticalAlign::Top,
            &Paint::default(),
        );

        // With a real drop-shadow image filter (as `drop_shadow_paints` builds).
        let mut shadow_paint = Paint::default();
        shadow_paint.set_image_filter(skia::image_filters::drop_shadow(
            (12.0, 12.0),
            (6.0, 6.0),
            skia::Color::from_argb(230, 0, 0, 255),
            None,
            None,
            None,
        ));
        paint_drop_shadow(canvas, &layout, &bounds, VerticalAlign::Top, &shadow_paint);

        for kind in [
            Stroke::new_center_stroke(3.0, StrokeStyle::Solid, None, None, None, None),
            Stroke::new_inner_stroke(3.0, StrokeStyle::Solid, None, None, None, None),
            Stroke::new_outer_stroke(3.0, StrokeStyle::Solid, None, None, None, None),
        ] {
            paint_stroke(
                canvas,
                &layout,
                &bounds,
                VerticalAlign::Top,
                &kind,
                &selrect,
                None,
            );
        }
    }
}
