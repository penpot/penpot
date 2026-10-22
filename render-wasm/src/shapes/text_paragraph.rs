//! SkParagraph rounds every line height and baseline, even with its rounding
//! hack disabled. Keep its shaping and horizontal layout, but use the original
//! ascent/descent for vertical geometry and painting.

use skia_safe::{
    textlayout::{
        LineMetrics, Paragraph, ParagraphBuilder, PositionWithAffinity, RectHeightStyle,
        RectWidthStyle, TextBox,
    },
    Canvas, Paint, Point, TextBlob, TextBlobBuilder,
};
use std::ops::{Deref, DerefMut, Range};

#[derive(Debug)]
pub struct LayoutParagraph {
    inner: Paragraph,
    text: String,
    shifts: Vec<f32>,
    height: f32,
    runs: Vec<(TextBlob, Paint, Point)>,
}

impl LayoutParagraph {
    pub fn build(builder: &mut ParagraphBuilder) -> Self {
        let text = builder.get_text().to_owned();
        Self {
            inner: builder.build(),
            text,
            shifts: Vec::new(),
            height: 0.0,
            runs: Vec::new(),
        }
    }

    pub fn layout(&mut self, width: f32) {
        self.inner.layout(width);
        let lines = self.inner.get_line_metrics();
        self.shifts.clear();
        self.height = 0.0;
        // Keep the first baseline anchored as before. Only remove drift
        // between lines; this also preserves existing one-line layouts.
        let anchor = lines
            .first()
            .map_or(0.0, |line| (line.baseline - line.ascent) as f32);
        for line in &lines {
            self.shifts
                .push(self.height + line.ascent as f32 + anchor - line.baseline as f32);
            self.height += (line.ascent + line.descent) as f32;
        }
        if lines.is_empty() {
            self.height = self.inner.height();
        }
        self.runs.clear();
        if self.shifts.iter().all(|shift| shift.abs() < 0.00001) {
            return;
        }
        // Copy the paints before visiting: Skia exposes style metrics as
        // references into the paragraph that visit mutably borrows.
        let paints: Vec<Vec<(usize, Paint)>> = lines
            .iter()
            .map(|line| {
                line.get_style_metrics(0..self.text.len().saturating_add(1))
                    .into_iter()
                    .map(|(at, metric)| (at, metric.text_style.foreground()))
                    .collect()
            })
            .collect();
        drop(lines);
        // Retain UTF-8 clusters in the blobs so PDF text stays selectable.
        let mut boundaries = vec![self.text.len()];
        self.inner.visit(|_, info| {
            if let Some(info) = info {
                boundaries.extend(info.utf8_starts().iter().map(|at| *at as usize));
            }
        });
        boundaries.sort_unstable();
        boundaries.dedup();
        let text = &self.text;
        let shifts = &self.shifts;
        let runs = &mut self.runs;
        self.inner.visit(|line, info| {
            let Some(info) = info.filter(|info| info.count() > 0) else {
                return;
            };
            let starts = info.utf8_starts();
            let start = *starts.iter().min().unwrap() as usize;
            let last = *starts.iter().max().unwrap() as usize;
            let end = boundaries
                .get(boundaries.partition_point(|at| *at <= last))
                .copied()
                .unwrap_or(text.len());
            let paint = paints
                .get(line)
                .and_then(|paints| paints.iter().rev().find(|(at, _)| *at <= start))
                .map(|(_, paint)| paint.clone())
                .unwrap_or_default();
            let mut builder = TextBlobBuilder::new();
            if let Some(source) = text
                .as_bytes()
                .get(start..end)
                .filter(|source| !source.is_empty())
            {
                let (glyphs, points, utf8, clusters) =
                    builder.alloc_run_text_pos(info.font(), info.count(), source.len(), None);
                glyphs.copy_from_slice(info.glyphs());
                points.copy_from_slice(info.positions());
                utf8.copy_from_slice(source);
                for (cluster, at) in clusters.iter_mut().zip(starts) {
                    *cluster = *at - start as u32;
                }
            } else {
                let (glyphs, points) = builder.alloc_run_pos(info.font(), info.count(), None);
                glyphs.copy_from_slice(info.glyphs());
                points.copy_from_slice(info.positions());
            }
            if let Some(blob) = builder.make() {
                let mut origin = info.origin();
                origin.y += shifts.get(line).copied().unwrap_or(0.0);
                runs.push((blob, paint, origin));
            }
        });
    }

    pub fn line_shift(&self, line: usize) -> f32 {
        self.shifts.get(line).copied().unwrap_or(0.0)
    }

    pub fn height(&self) -> f32 {
        self.height
    }

    pub fn ideographic_baseline(&self) -> f32 {
        self.inner.ideographic_baseline() + self.line_shift(0)
    }

    pub fn get_line_metrics(&self) -> Vec<LineMetrics<'_>> {
        let mut lines = self.inner.get_line_metrics();
        for (index, line) in lines.iter_mut().enumerate() {
            line.baseline += self.line_shift(index) as f64;
            line.height = line.ascent + line.descent;
        }
        lines
    }

    pub fn get_rects_for_range(
        &self,
        range: Range<usize>,
        height: RectHeightStyle,
        width: RectWidthStyle,
    ) -> Vec<TextBox> {
        let mut result = Vec::new();
        for (index, line) in self.inner.get_line_metrics().iter().enumerate() {
            let start = range.start.max(line.start_index);
            let end = range.end.min(line.end_including_newline);
            if start >= end {
                continue;
            }
            let mut boxes = self.inner.get_rects_for_range(start..end, height, width);
            for textbox in &mut boxes {
                textbox.rect.offset((0.0, self.line_shift(index)));
            }
            result.extend(boxes);
        }
        result
    }

    pub fn get_rects_for_placeholders(&self) -> Vec<TextBox> {
        let lines = self.inner.get_line_metrics();
        let mut boxes = self.inner.get_rects_for_placeholders();
        for textbox in &mut boxes {
            let center = textbox.rect.center_y();
            let index = lines
                .iter()
                .position(|line| center < (line.baseline + line.descent) as f32)
                .unwrap_or(lines.len().saturating_sub(1));
            textbox.rect.offset((0.0, self.line_shift(index)));
        }
        boxes
    }

    pub fn get_glyph_position_at_coordinate(
        &self,
        point: impl Into<Point>,
    ) -> PositionWithAffinity {
        let mut point = point.into();
        let lines = self.get_line_metrics();
        let index = lines
            .iter()
            .position(|line| point.y < (line.baseline + line.descent) as f32)
            .unwrap_or(lines.len().saturating_sub(1));
        point.y -= self.line_shift(index);
        self.inner.get_glyph_position_at_coordinate(point)
    }

    pub fn paint(&self, canvas: &Canvas, point: impl Into<Point>) {
        let point = point.into();
        if self.runs.is_empty() {
            self.inner.paint(canvas, point);
        } else {
            for (blob, paint, origin) in &self.runs {
                canvas.draw_text_blob(blob, point + *origin, paint);
            }
        }
    }
}

impl Deref for LayoutParagraph {
    type Target = Paragraph;
    fn deref(&self) -> &Paragraph {
        &self.inner
    }
}

impl DerefMut for LayoutParagraph {
    fn deref_mut(&mut self) -> &mut Paragraph {
        &mut self.inner
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::text_vertical::test_support::provider;
    use skia_safe::textlayout::{FontCollection, ParagraphStyle, TextStyle};

    fn builder() -> ParagraphBuilder {
        crate::globals::design_init();
        let mut fonts = FontCollection::new();
        fonts.set_asset_font_manager(Some(
            provider(include_bytes!("../fonts/cjk-locl-test.ttf")).into(),
        ));
        ParagraphBuilder::new(&ParagraphStyle::default(), fonts)
    }

    fn style(color: skia_safe::Color, stroke: bool) -> TextStyle {
        let mut paint = Paint::default();
        paint.set_color(color);
        if stroke {
            paint.set_style(skia_safe::PaintStyle::Stroke);
            paint.set_stroke_width(0.5);
        }
        let mut style = TextStyle::default();
        let family = format!(
            "{}",
            crate::shapes::FontFamily::new(
                crate::Uuid::nil(),
                400,
                crate::shapes::FontStyle::Normal
            )
        );
        style.set_font_families(&[family]);
        style.set_font_size(18.0);
        style.set_height(1.6);
        style.set_height_override(true);
        style.set_half_leading(true);
        style.set_foreground_paint(&paint);
        style
    }

    #[test]
    fn fractional_wrapped_paint_matches_individual_lines_with_mixed_fills_and_strokes() {
        for stroke in [false, true] {
            let colors = [
                skia_safe::Color::RED,
                skia_safe::Color::GREEN,
                skia_safe::Color::BLUE,
            ];
            let mut builder = builder();
            for (index, color) in colors.into_iter().enumerate() {
                builder.push_style(&style(color, stroke));
                builder.add_text(if index < 2 { "한글\n" } else { "한글" });
                builder.pop();
            }
            let mut paragraph = LayoutParagraph::build(&mut builder);
            paragraph.layout(100.0);
            let mut actual = skia_safe::surfaces::raster_n32_premul((100, 100)).unwrap();
            paragraph.paint(actual.canvas(), (0.0, 0.0));
            let mut expected = skia_safe::surfaces::raster_n32_premul((100, 100)).unwrap();
            for (index, color) in colors.into_iter().enumerate() {
                let mut line_builder = self::builder();
                line_builder.push_style(&style(color, stroke));
                line_builder.add_text("한글");
                let mut line = line_builder.build();
                line.layout(100.0);
                line.paint(expected.canvas(), (0.0, index as f32 * 28.8));
            }
            let pixels = actual.peek_pixels().unwrap();
            assert!(
                pixels.bytes().unwrap().iter().any(|byte| *byte > 0),
                "the font paints visible ink"
            );
            assert_eq!(pixels.bytes(), expected.peek_pixels().unwrap().bytes());
        }
    }

    #[test]
    fn fractional_geometry_hit_testing_and_relayout_share_the_same_lines() {
        let mut builder = builder();
        builder.push_style(&style(skia_safe::Color::BLACK, false));
        builder.add_text("한글\n한글\n한글");
        let mut paragraph = LayoutParagraph::build(&mut builder);
        for width in [100.0, 50.0, 100.0] {
            paragraph.layout(width);
            assert!((paragraph.height() - 86.4).abs() < 0.001);
            let boxes: Vec<_> = [0, 3, 6]
                .into_iter()
                .map(|at| {
                    paragraph.get_rects_for_range(
                        at..at + 1,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    )[0]
                    .rect
                })
                .collect();
            for pair in boxes.windows(2) {
                assert!((pair[1].top - pair[0].top - 28.8).abs() < 0.001);
            }
            for (index, rect) in boxes.iter().enumerate() {
                let hit =
                    paragraph.get_glyph_position_at_coordinate((rect.left + 0.01, rect.center_y()));
                assert_eq!(hit.position as usize, index * 3);
            }
        }
    }
}
