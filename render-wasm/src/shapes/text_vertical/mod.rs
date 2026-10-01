// Vertical (tategaki) text layout: columns flow top->bottom and advance
// right->left. Skia's skparagraph has no vertical writing mode, so this
// module owns the whole vertical pipeline:
//
//   segment spans by glyph orientation (`orientation`) -> shape each segment
//   with SkShaper, OpenType `vert`/`vrt2` for upright runs (`shaping`) ->
//   derive cells: one per upright glyph cluster, one per rotated run, one per
//   composite (`cells`) -> JLREQ spacing and kinsoku-aware column breaks
//   (`flow`) -> place columns and annotations (`layout`, `annotations`) ->
//   paint / outline export (`paint`) and position data / hit-testing
//   (`positions`) from the cells.
//
// Offset discipline: cells use UTF-16 offsets in transformed layout text.
// Position data maps those ranges back to the original span text before it
// crosses the WASM boundary; the WORD JOINER OffsetMap remains exclusive to
// skparagraph-driven horizontal breaks.
//
// Layouts are computed on demand (like the horizontal path, which rebuilds
// its skparagraph objects per paint); only per-typeface font tables are
// cached.
//
// Text-align aligns each column's glyphs along the vertical (inline) axis:
// Left/Start->top, Center->middle, Right/End->bottom of the wrap budget.
// Justify stretches every column but the last to fill the wrap budget.
//
// Letter-spacing adds inter-glyph advance along the column — once per
// upright cluster and once per glyph inside a rotated run — mirroring the
// horizontal `letter-spacing` that Skia applies to each glyph advance.
//
// Deferred to a later phase: PDF/vector emoji overlays, inner shadows and
// block-axis vertical-align.

mod annotations;
mod cells;
mod flow;
mod font_tables;
mod layout;
mod orientation;
mod paint;
mod positions;
mod shaping;

#[cfg(test)]
mod test_support;

pub(crate) use annotations::distribute_ruby_tops;
pub use layout::{block_axis_offset, layout_for_box, measure_content, VerticalLayout};
pub use paint::{
    paint_drop_shadow, paint_grid, paint_stroke, paint_text_vertical, vertical_text_paths,
};
pub use positions::{caret_from_point, caret_rect, intersects, position_data, range_rects};
pub(crate) use shaping::{shape_segment_with_fallbacks, single_glyph_blob, span_font_families};
