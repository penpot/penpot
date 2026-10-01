use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::thread::LocalKey;

use skia_safe::{Font, GlyphId};

use crate::shapes::gpos_vpal::{parse_vpal, VpalDelta};

/// Parsed font tables keyed by `SkTypefaceID`. `None` is cached too: a face
/// without the table never gains it.
type TableCache<T> = RefCell<HashMap<u32, Option<Rc<T>>>>;

thread_local! {
    // Layouts are recomputed on every paint but a face's tables never
    // change, so each table (whose parse copies it) is read once per face.
    static VERTICAL_METRICS: TableCache<VerticalMetrics> = RefCell::default();
    static VPAL_TABLES: TableCache<VpalTable> = RefCell::default();
    static TYPO_METRICS: TableCache<TypoMetrics> = RefCell::default();
}

fn cached_table<T: 'static>(
    cache: &'static LocalKey<TableCache<T>>,
    font: &Font,
    parse: impl FnOnce(&Font) -> Option<T>,
) -> Option<Rc<T>> {
    let id = font.typeface().unique_id();
    cache.with(|cache| {
        cache
            .borrow_mut()
            .entry(id)
            .or_insert_with(|| parse(font).map(Rc::new))
            .clone()
    })
}

fn units_per_em(font: &Font) -> Option<f32> {
    let units_per_em = font.typeface().units_per_em()? as f32;
    (units_per_em > 0.0).then_some(units_per_em)
}

fn table_data(font: &Font, tag: &[u8; 4]) -> Option<skia_safe::Data> {
    font.typeface().copy_table_data(u32::from_be_bytes(*tag))
}

/// Vertical advances from a font's `vhea`/`vmtx` tables. Upright cells
/// advance down the column by the glyph's true vertical advance rather
/// than its shaped horizontal advance: identical for full-width CJK, but
/// correct for vertical alternates and proportional glyphs whose `vmtx`
/// differs from `hmtx` (e.g. the vertical kana repeat marks).
pub(super) struct VerticalMetrics {
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
    pub(super) fn from_font(font: &Font) -> Option<Self> {
        let units_per_em = units_per_em(font)?;
        let vhea = table_data(font, b"vhea")?;
        let vmtx = table_data(font, b"vmtx")?;
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
    pub(super) fn advance(&self, glyph: GlyphId, font_size: f32) -> f32 {
        let raw = self
            .advances
            .get(glyph as usize)
            .copied()
            .unwrap_or(self.last);
        raw as f32 * font_size / self.units_per_em
    }
}

pub(super) fn vertical_metrics(font: &Font) -> Option<Rc<VerticalMetrics>> {
    cached_table(&VERTICAL_METRICS, font, VerticalMetrics::from_font)
}

/// GPOS `vpal` deltas for one typeface, in font units.
pub(super) struct VpalTable {
    units_per_em: f32,
    deltas: HashMap<u16, VpalDelta>,
}

impl VpalTable {
    fn from_font(font: &Font) -> Option<Self> {
        let units_per_em = units_per_em(font)?;
        let gpos = table_data(font, b"GPOS")?;
        let deltas = parse_vpal(gpos.as_bytes())?;
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
    pub(super) fn cluster_delta(&self, glyphs: &[GlyphId], font_size: f32) -> Option<(f32, f32)> {
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

pub(super) fn vpal_table(font: &Font) -> Option<Rc<VpalTable>> {
    cached_table(&VPAL_TABLES, font, VpalTable::from_font)
}

/// OS/2 typographic ascender/descender, normalised to the em (design units /
/// unitsPerEm). These bound the ideographic em box and are what the browser
/// uses for the vertical central baseline; `hhea`/`Font::metrics` are oversized
/// for CJK faces (their ascent exceeds the em) and would push an upright glyph
/// off the ideographic centre of its cell.
struct TypoMetrics {
    /// sTypoAscender / unitsPerEm (positive, above the baseline).
    ascender: f32,
    /// sTypoDescender / unitsPerEm (negative, below the baseline).
    descender: f32,
}

impl TypoMetrics {
    fn from_font(font: &Font) -> Option<Self> {
        let units_per_em = units_per_em(font)?;
        let os2 = table_data(font, b"OS/2")?;
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

/// Ascent/descent for centring an upright cell, in Skia's sign convention
/// (ascent negative, descent positive) at the font's current size. Prefers the
/// OS/2 typographic metrics (the ideographic em box, matching the browser's
/// vertical central baseline used by the SVG/foreignObject export); falls back
/// to `Font::metrics` when the face carries no OS/2 table.
pub(super) fn upright_centre_metrics(font: &Font) -> (f32, f32) {
    if let Some(typo) = cached_table(&TYPO_METRICS, font, TypoMetrics::from_font) {
        let size = font.size();
        return (-typo.ascender * size, -typo.descender * size);
    }
    let (_, metrics) = font.metrics();
    (metrics.ascent, metrics.descent)
}

/// Vertical offset from a cell's top edge to the glyph baseline for an upright
/// cell. Centres the glyph's line box within the em cell (as the Tate-chu-yoko
/// path does) instead of hanging it from the horizontal ascent: CJK faces have
/// an ascent larger than the em, so hanging from it pushes every glyph below
/// its cell and the whole column overflows its bounds.
pub(super) fn upright_baseline_offset(ascent: f32, descent: f32, font_size: f32) -> f32 {
    font_size / 2.0 - (ascent + descent) / 2.0
}

/// Baseline offset for an upright glyph of `font` in an em cell of `font_size`.
pub(super) fn upright_baseline(font: &Font, font_size: f32) -> f32 {
    let (ascent, descent) = upright_centre_metrics(font);
    upright_baseline_offset(ascent, descent, font_size)
}

#[cfg(test)]
mod tests {
    use super::super::test_support::{TEST_FONT, VMTX_TEST_FONT};
    use super::*;
    use skia_safe::FontMgr;

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
}
