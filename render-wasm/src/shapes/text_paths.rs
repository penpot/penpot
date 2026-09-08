use crate::render::text::decoration_segments;
use crate::shapes::text::{vertical_align_offset, Paragraph, TextContent};
use crate::shapes::VerticalAlign;
use skia_safe::{
    self as skia,
    textlayout::{paragraph::VisitorInfo, Paragraph as SkiaParagraph},
    Point,
};
use std::ops::Deref;

pub struct TextPaths(TextContent);

struct LineStyle {
    start: usize,
    paint: skia::Paint,
}

impl TextPaths {
    pub fn new(text_content: TextContent) -> Self {
        Self(text_content)
    }
    
    pub fn get_paths(
        &self,
        antialias: bool,
        vertical_align: VerticalAlign,
    ) -> Vec<(skia::Path, skia::Paint)> {
        let mut paragraph_builders = self.0.paragraph_builder_group_from_text(None);
        let mut paragraphs = Vec::new();

        for (index, group) in paragraph_builders.iter_mut().enumerate() {
            let Some(paragraph_builder) = group.first_mut() else {
                continue;
            };
            let mut paragraph = paragraph_builder.build();
            paragraph.layout(self.bounds.width());
            paragraphs.push((index, paragraph));
        }

        let total_height: f32 = paragraphs.iter().map(|(_, p)| p.height()).sum();
        let vertical_offset =
            vertical_align_offset(self.bounds.height(), total_height, vertical_align);

        let mut paths = Vec::new();
        let mut offset_y = self.bounds.y() + vertical_offset;

        for (index, paragraph) in paragraphs.iter_mut() {
            let origin = Point::new(self.bounds.x(), offset_y);
            Self::collect_paragraph_paths(
                paragraph,
                self.0.paragraphs().get(*index),
                origin,
                antialias,
                &mut paths,
            );
            offset_y += paragraph.height();
        }

        paths
    }

    fn collect_paragraph_paths(
        paragraph: &mut SkiaParagraph,
        text_paragraph: Option<&Paragraph>,
        origin: Point,
        antialias: bool,
        paths: &mut Vec<(skia::Path, skia::Paint)>,
    ) {
        let line_styles = Self::line_styles(paragraph);

        if let Some(text_paragraph) = text_paragraph {
            for deco in decoration_segments(paragraph, text_paragraph, origin.x, origin.y) {
                let mut builder = skia::PathBuilder::new();
                builder.add_rect(deco.rect(), None, None);
                let mut paint = deco.text_style.foreground();
                paint.set_anti_alias(antialias);
                paths.push((builder.detach(), paint));
            }
        }

        paragraph.visit(|line_index: usize, info: Option<&VisitorInfo>| {
            let Some(info) = info else {
                return;
            };

            let font = info.font();
            let run_origin = origin + info.origin();
            let style = info
                .utf8_starts()
                .first()
                .and_then(|start| Self::style_at(&line_styles, line_index, *start as usize));

            let mut builder = skia::PathBuilder::new();
            let mut has_glyphs = false;

            for (glyph, position) in info.glyphs().iter().zip(info.positions().iter()) {
                let Some(glyph_path) = font.get_path(*glyph) else {
                    continue;
                };
                builder.add_path(&glyph_path.with_offset(run_origin + *position));
                has_glyphs = true;
            }

            if !has_glyphs {
                return;
            }

            let mut paint = style
                .map(|style| style.paint.clone())
                .unwrap_or_else(skia::Paint::default);
            paint.set_anti_alias(antialias);

            paths.push((builder.detach(), paint));
        });
    }

    fn line_styles(paragraph: &SkiaParagraph) -> Vec<Vec<LineStyle>> {
        paragraph
            .get_line_metrics()
            .iter()
            .map(|line| {
                line.get_style_metrics(line.start_index..line.end_index)
                    .into_iter()
                    .map(|(start, style_metric)| LineStyle {
                        start,
                        paint: style_metric.text_style.foreground(),
                    })
                    .collect()
            })
            .collect()
    }

    fn style_at(
        line_styles: &[Vec<LineStyle>],
        line_index: usize,
        start: usize,
    ) -> Option<&LineStyle> {
        let styles = line_styles.get(line_index)?;
        styles
            .iter()
            .rev()
            .find(|style| style.start <= start)
            .or_else(|| styles.first())
    }
}

impl Deref for TextPaths {
    type Target = TextContent;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
