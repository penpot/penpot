use skia_safe::{
    self as skia, canvas::SaveLayerRec, textlayout::Paragraph as SkiaParagraph,
    textlayout::TextDecoration, BlendMode, Canvas, ImageFilter, Matrix, Paint, Point as SkPoint,
    TextBlob,
};

use crate::math::Rect;
use crate::shapes::{Stroke, StrokeKind, TextContent, VerticalAlign};

use super::annotations::{emphasis_mark_center, ruby_strip_x, EmphasisMark, RubyCell};
use super::font_tables::upright_baseline;
use super::layout::{column_base_center, layout_for_box, CellKind, VerticalCell, VerticalLayout};
use super::shaping::{single_glyph_blob, ShapedRun};

/// One text blob drawn at `offset` in the local space that `transform` maps
/// to the document (identity when `None`). Canvas painting and outline export
/// share these draws, so they match for every cell kind.
struct GlyphDraw {
    blob: TextBlob,
    offset: SkPoint,
    transform: Option<Matrix>,
}

impl GlyphDraw {
    fn at(blob: TextBlob, offset: impl Into<SkPoint>) -> Self {
        Self {
            blob,
            offset: offset.into(),
            transform: None,
        }
    }

    fn transformed(blob: TextBlob, offset: impl Into<SkPoint>, transform: Matrix) -> Self {
        Self {
            blob,
            offset: offset.into(),
            transform: Some(transform),
        }
    }

    fn draw(&self, canvas: &Canvas, paint: &Paint) {
        if crate::render::svg::writing_svg() {
            canvas.draw_path(&self.path(), paint);
            return;
        }
        match &self.transform {
            None => {
                canvas.draw_text_blob(&self.blob, self.offset, paint);
            }
            Some(transform) => {
                canvas.save();
                canvas.concat(transform);
                canvas.draw_text_blob(&self.blob, self.offset, paint);
                canvas.restore();
            }
        }
    }

    fn into_path(self) -> skia::Path {
        self.path()
    }

    fn path(&self) -> skia::Path {
        let path = text_blob_path(self.blob.clone(), self.offset);
        match &self.transform {
            None => path,
            Some(transform) => path.make_transform(transform),
        }
    }
}

/// Glyphs on their side: +x of the blob runs down the column from `origin`.
fn rotated_at(origin: (f32, f32)) -> Matrix {
    let mut matrix = Matrix::translate(origin);
    matrix.pre_rotate(90.0, None);
    matrix
}

/// Glyph draws of one cell, for a layout whose content origin is `origin`.
fn cell_draws(layout: &VerticalLayout, cell: &VerticalCell, origin: (f32, f32)) -> Vec<GlyphDraw> {
    let column = &layout.columns[cell.column];
    let x_center = origin.0 + column_base_center(column);
    let y_top = origin.1 + cell.top;
    match cell.kind {
        CellKind::Upright { run, glyph, count } => {
            let run = &layout.runs[run];
            let baseline =
                y_top + cell.glyph_flow_shift + upright_baseline(&run.font, cell.font_size);
            run.cluster_blob(glyph, count)
                .map(|blob| GlyphDraw::at(blob, (x_center - cell.h_advance / 2.0, baseline)))
                .into_iter()
                .collect()
        }
        CellKind::SyntheticRotated { run, glyph, count } => {
            let run = &layout.runs[run];
            run.cluster_blob(glyph, count)
                .map(|blob| {
                    GlyphDraw::transformed(
                        blob,
                        (0.0, run.cluster_rotated_baseline_shift(glyph, count)),
                        rotated_at((x_center, y_top + cell.glyph_flow_shift)),
                    )
                })
                .into_iter()
                .collect()
        }
        CellKind::Rotated { run } => {
            let run = &layout.runs[run];
            // After rotation +x runs down the column and +y runs across it;
            // the shift puts the font's central baseline on the column axis.
            run.blob()
                .map(|blob| {
                    GlyphDraw::transformed(
                        blob,
                        (0.0, run.rotated_baseline_shift),
                        rotated_at((x_center, y_top)),
                    )
                })
                .into_iter()
                .collect()
        }
        CellKind::TateChuYoko {
            run_start,
            run_count,
            scale,
        } => {
            let composite = &layout.runs[run_start..run_start + run_count];
            let combined_advance: f32 = composite.iter().map(|run| run.advance).sum();
            // Vertical centring uses the tallest run's ascent/descent.
            let (ascent, descent) = composite.iter().fold((0.0f32, 0.0f32), |(a, d), run| {
                let (_, metrics) = run.font.metrics();
                (a.min(metrics.ascent), d.max(metrics.descent))
            });
            let x0 = x_center - (combined_advance * scale) / 2.0;
            let baseline = y_top + cell.font_size / 2.0 - ((ascent + descent) * scale) / 2.0;
            let mut transform = Matrix::translate((x0, baseline));
            transform.pre_scale((scale, scale), None);
            let mut cursor = 0.0f32;
            let mut draws = Vec::with_capacity(composite.len());
            for run in composite {
                if let Some(blob) = run.blob() {
                    draws.push(GlyphDraw::transformed(blob, (cursor, 0.0), transform));
                }
                cursor += run.advance;
            }
            draws
        }
        CellKind::Warichu {
            run_start,
            run_count,
            first_count,
            first_top,
            second_top,
            ..
        } => {
            let (first, second) =
                layout.runs[run_start..run_start + run_count].split_at(first_count);
            let quarter = cell.font_size / 4.0;
            // vertical-rl: the first sub-line reads first, on the right half.
            let mut draws = warichu_line_draws(first, x_center + quarter, y_top + first_top);
            draws.extend(warichu_line_draws(
                second,
                x_center - quarter,
                y_top + second_top,
            ));
            draws
        }
    }
}

/// One warichu sub-line: half-size upright glyphs stacked down the
/// sub-column centred on `x_center`, one cluster per cell.
fn warichu_line_draws(runs: &[ShapedRun], x_center: f32, y_top: f32) -> Vec<GlyphDraw> {
    let mut draws = Vec::new();
    let mut cursor = y_top;
    for run in runs {
        let font_size = run.font.size();
        let baseline_offset = upright_baseline(&run.font, font_size);
        for (glyph, count) in run.cluster_spans() {
            let advance = match run.cluster_advance(glyph, count) {
                advance if advance > 0.0 => advance,
                _ => font_size,
            };
            if let Some(blob) = run.cluster_blob(glyph, count) {
                draws.push(GlyphDraw::at(
                    blob,
                    (x_center - advance / 2.0, cursor + baseline_offset),
                ));
            }
            cursor += advance;
        }
    }
    draws
}

/// One column's slice of a ruby annotation, stacked upright in the column's
/// ruby gutter. Flow positions come from the whole base span; each glyph is
/// centred across the gutter by its own fallback-font run.
fn ruby_draws(layout: &VerticalLayout, ruby: &RubyCell, origin: (f32, f32)) -> Vec<GlyphDraw> {
    let column = &layout.columns[ruby.column];
    let gutter_center = origin.0
        + ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side)
        + ruby.font_size / 2.0;
    ruby.glyphs
        .iter()
        .zip(&ruby.glyph_tops)
        .filter_map(|(ruby_glyph, top)| {
            let run = &layout.ruby_runs[ruby_glyph.run];
            let blob = run.glyph_blob(ruby_glyph.glyph)?;
            let (_, metrics) = run.font.metrics();
            let advance = run
                .advances
                .get(ruby_glyph.glyph)
                .copied()
                .unwrap_or(ruby.font_size);
            Some(GlyphDraw::at(
                blob,
                (
                    gutter_center - advance / 2.0,
                    origin.1 + top - metrics.ascent,
                ),
            ))
        })
        .collect()
}

/// One emphasis mark (圏点) centred on its base cell's flow extent, in the
/// column's right-side gutter.
fn emphasis_draw(
    layout: &VerticalLayout,
    mark: &EmphasisMark,
    origin: (f32, f32),
) -> Option<GlyphDraw> {
    let run = &layout.emphasis_runs[mark.run];
    let blob = single_glyph_blob(&run.font, *run.glyphs.first()?)?;
    let (center_x, center_y) = emphasis_mark_center(layout, mark);
    let gutter_center = origin.0 + center_x;
    let (_, metrics) = run.font.metrics();
    let baseline = origin.1 + center_y - (metrics.ascent + metrics.descent) / 2.0;
    let advance = run.advances.first().copied().unwrap_or(0.0);
    Some(GlyphDraw::at(
        blob,
        (gutter_center - advance / 2.0, baseline),
    ))
}

/// Underline and line-through bars of a cell.
fn decoration_rects(
    layout: &VerticalLayout,
    cell: &VerticalCell,
    origin: (f32, f32),
) -> Vec<skia::Rect> {
    let Some(decoration) = cell.decoration else {
        return Vec::new();
    };
    let mut rects = Vec::new();
    if decoration.contains(TextDecoration::UNDERLINE) {
        rects.push(decoration_bar(layout, cell, origin.0, origin.1, false));
    }
    if decoration.contains(TextDecoration::LINE_THROUGH) {
        rects.push(decoration_bar(layout, cell, origin.0, origin.1, true));
    }
    rects
}

/// Fill pass: each cell drawn with its own fill paint and its decorations,
/// then ruby and emphasis marks with their base cell's paint.
pub fn paint_layout(
    canvas: &Canvas,
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
) {
    let origin = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        let paint = &layout.paints[cell.paint];
        for draw in cell_draws(layout, cell, origin) {
            draw.draw(canvas, paint);
        }
        let mut decoration_paint = paint.clone();
        decoration_paint.set_style(skia::PaintStyle::Fill);
        decoration_paint.set_anti_alias(true);
        for rect in decoration_rects(layout, cell, origin) {
            canvas.draw_rect(rect, &decoration_paint);
        }
    }
    for ruby in &layout.ruby_cells {
        for draw in ruby_draws(layout, ruby, origin) {
            draw.draw(canvas, &layout.paints[ruby.paint]);
        }
    }
    for mark in &layout.emphasis_marks {
        if let Some(draw) = emphasis_draw(layout, mark, origin) {
            draw.draw(canvas, &layout.paints[layout.cells[mark.cell].paint]);
        }
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
    let origin = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        for draw in cell_draws(layout, cell, origin) {
            draw.draw(canvas, paint);
        }
    }
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
    let layout = layout_for_box(text_content, bounds.height());
    paint_layout(canvas, &layout, &bounds, vertical_align);
    true
}

/// Glyph-outline paths of the layout, in the same order and with the same
/// paints as the canvas fill pass.
fn paths_from_layout(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    antialias: bool,
) -> Vec<(skia::Path, Paint)> {
    let mut paths = Vec::new();
    let origin = layout.origin(bounds, vertical_align);
    for cell in &layout.cells {
        let paint = &layout.paints[cell.paint];
        for draw in cell_draws(layout, cell, origin) {
            push_text_path(&mut paths, draw.into_path(), paint, antialias);
        }
        for rect in decoration_rects(layout, cell, origin) {
            push_text_path(&mut paths, skia::Path::rect(rect, None), paint, antialias);
        }
    }
    for ruby in &layout.ruby_cells {
        for draw in ruby_draws(layout, ruby, origin) {
            push_text_path(
                &mut paths,
                draw.into_path(),
                &layout.paints[ruby.paint],
                antialias,
            );
        }
    }
    for mark in &layout.emphasis_marks {
        if let Some(draw) = emphasis_draw(layout, mark, origin) {
            push_text_path(
                &mut paths,
                draw.into_path(),
                &layout.paints[layout.cells[mark.cell].paint],
                antialias,
            );
        }
    }
    paths
}

/// Glyph-outline paths of the vertical layout, matching the canvas pass.
pub fn vertical_text_paths(
    text_content: &TextContent,
    vertical_align: VerticalAlign,
    antialias: bool,
) -> Vec<(skia::Path, Paint)> {
    if !text_content.is_vertical() {
        return Vec::new();
    }
    let bounds = text_content.bounds();
    let layout = layout_for_box(text_content, bounds.height());
    paths_from_layout(&layout, &bounds, vertical_align, antialias)
}

/// Developer overlay: a jlreq-style character-frame grid. Outlines each
/// column band and, per cell, the advance box, the virtual body / em square
/// centred on the column axis, and the glyph-ink band, so aki and
/// letter-spacing are visible. Only the on-screen fills pass calls it, behind
/// the `TEXT_GRID_VISIBLE` render flag; it never reaches exported output.
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
        let x_center = origin_x + column_base_center(column);

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
/// blur/offset image filter, so glyphs draw through it with no offscreen
/// layer (a `save_layer` with the filter can exceed GPU limits on tall
/// columns).
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

/// Paint a stroke on the vertical glyphs. Center strokes draw directly;
/// inner/outer strokes mask with `SrcIn` / `SrcOut` against the glyph
/// silhouette, as the horizontal path does.
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

fn text_blob_path(mut blob: TextBlob, offset: impl Into<SkPoint>) -> skia::Path {
    // get_path is relative to the blob's ink bounds; add them back to match.
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

/// Decoration bar of a cell in absolute coordinates. Underline runs along
/// the *left* side of the column (the under side in `vertical-rl`);
/// line-through runs down the centre. Both span the cell's extent, so
/// adjacent decorated cells form one bar.
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

#[cfg(test)]
mod tests {
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::{StrokeStyle, TextEmphasis, TextOrientation, TextSpan};

    #[test]
    fn shape_to_path_places_vertical_glyphs_down_the_column() {
        let content = make_content(&["あく"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);

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
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let CellKind::Upright { run, glyph, count } = layout.cells[0].kind else {
            panic!("expected an upright cell");
        };
        let blob = layout.runs[run]
            .cluster_blob(glyph, count)
            .expect("a glyph text blob");
        let blob_bounds = *blob.bounds();
        let draw_origin = SkPoint::new(120.0, 340.0);
        let mut normalized_blob = blob.clone();
        let normalized_bounds = *SkiaParagraph::get_path(&mut normalized_blob).bounds();

        let path_bounds = *text_blob_path(blob, draw_origin).bounds();

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
    fn decoration_bar_underline_left_of_center_line_through_centered() {
        let content = decorated_content("あい", TextDecoration::UNDERLINE);
        let layout = layout_content(&content, 1000.0);
        let (ox, oy) = layout.origin(&content.bounds(), VerticalAlign::Top);
        let cell = &layout.cells[0];
        let column = &layout.columns[cell.column];
        let x_center = ox + column.x + column.width / 2.0;

        let underline = decoration_bar(&layout, cell, ox, oy, false);
        let strike = decoration_bar(&layout, cell, ox, oy, true);

        assert!(underline.center_x() < x_center);
        assert!((strike.center_x() - x_center).abs() < 0.01);
        assert!((underline.height() - cell.extent).abs() < 0.01);
        assert!(underline.width() >= 1.0 - 0.01);
    }

    #[test]
    fn outline_export_includes_every_annotation_glyph() {
        let content = spans_content(
            vec![TextSpan {
                ruby: "aあ".to_string(),
                text_emphasis: TextEmphasis::FilledDot,
                text_orientation: TextOrientation::Upright,
                ..make_span("AB")
            }],
            400.0,
        );
        let provider = provider_with_fallback(TEST_FONT, VMTX_TEST_FONT);
        let layout = layout_with_fallback(&provider, &content, 400.0, &["fallback".to_string()]);
        let ruby_glyphs: usize = layout.ruby_cells.iter().map(|ruby| ruby.glyphs.len()).sum();
        assert_eq!(ruby_glyphs, 2, "both fallback runs carry a ruby glyph");

        let paths = paths_from_layout(&layout, &content.bounds(), VerticalAlign::Top, true);

        assert_eq!(
            paths.len(),
            layout.cells.len() + ruby_glyphs + layout.emphasis_marks.len(),
            "one outline per base cell, ruby glyph and emphasis mark"
        );
        let mut surface = skia::surfaces::raster_n32_premul((256, 256)).unwrap();
        paint_layout(
            surface.canvas(),
            &layout,
            &content.bounds(),
            VerticalAlign::Top,
        );
    }

    #[test]
    fn paint_passes_do_not_panic() {
        let content = decorated_content("あいAB。", TextDecoration::LINE_THROUGH);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();
        let selrect = content.bounds();

        let mut surface = skia::surfaces::raster_n32_premul((256, 256)).unwrap();
        let canvas = surface.canvas();

        // Callers apply shape transforms to the canvas; run every pass under
        // a non-trivial transform with upright and rotated cells.
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

        // A real drop-shadow image filter, as `drop_shadow_paints` builds.
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

    /// Uncompressed PDF of the layout's fill pass, as Latin-1 text.
    fn layout_pdf(layout: &VerticalLayout, bounds: &Rect) -> String {
        let mut bytes: Vec<u8> = Vec::new();
        let metadata = skia::pdf::Metadata {
            compression_level: skia::pdf::CompressionLevel::None,
            ..Default::default()
        };
        {
            let document = skia::pdf::new_document(&mut bytes, Some(&metadata));
            let mut page = document.begin_page((400.0, 400.0), None);
            paint_layout(page.canvas(), layout, bounds, VerticalAlign::Top);
            page.end_page().close();
        }
        bytes.iter().map(|byte| *byte as char).collect()
    }

    #[test]
    fn pdf_text_of_vertical_forms_is_the_source_text() {
        let content = make_content(&["「あ」"], 400.0);
        let layout = layout_with(&provider(VPAL_TEST_FONT), &content);

        let pdf = layout_pdf(&layout, &content.bounds()).to_uppercase();
        let to_unicode = pdf
            .split_once("BEGINBFCHAR")
            .and_then(|(_, rest)| rest.split_once("ENDBFCHAR"))
            .map(|(entries, _)| entries)
            .expect("a ToUnicode CMap");

        // The vertical alternates have no cmap entry of their own; the PDF
        // text maps them back to the source characters.
        for source in ["<300C>", "<3042>", "<300D>"] {
            assert!(to_unicode.contains(source), "ToUnicode maps {source}");
        }
        assert!(!to_unicode.contains("<0000>"), "no glyph maps to U+0000");
    }
}
