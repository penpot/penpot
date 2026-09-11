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

impl TextPaths {
    pub fn new(text_content: TextContent) -> Self {
        Self(text_content)
    }

    pub fn get_paths(&self, vertical_align: VerticalAlign) -> Vec<skia::Path> {
        let layout_width = self.0.get_width(self.bounds.width());
        let mut paragraph_builders = self.0.paragraph_builder_group_from_text(None);
        let mut paragraphs: Vec<SkiaParagraph> = paragraph_builders
            .iter_mut()
            .filter_map(|group| group.first_mut())
            .map(|paragraph_builder| {
                let mut paragraph = paragraph_builder.build();
                paragraph.layout(layout_width);
                paragraph
            })
            .collect();

        let total_height: f32 = paragraphs.iter().map(|p| p.height()).sum();
        let mut offset_y = self.bounds.y()
            + vertical_align_offset(self.bounds.height(), total_height, vertical_align);

        let mut paths = Vec::new();
        for (paragraph, text_paragraph) in paragraphs.iter_mut().zip(self.0.paragraphs()) {
            let origin = Point::new(self.bounds.x(), offset_y);
            Self::collect_paragraph_paths(paragraph, text_paragraph, origin, &mut paths);
            offset_y += paragraph.height();
        }

        paths
    }

    fn collect_paragraph_paths(
        paragraph: &mut SkiaParagraph,
        text_paragraph: &Paragraph,
        origin: Point,
        paths: &mut Vec<skia::Path>,
    ) {
        for deco in decoration_segments(paragraph, text_paragraph, origin.x, origin.y) {
            let mut builder = skia::PathBuilder::new();
            builder.add_rect(deco.rect(), None, None);
            paths.push(builder.detach());
        }

        paragraph.visit(|_: usize, info: Option<&VisitorInfo>| {
            let Some(info) = info else {
                return;
            };

            let font = info.font();
            let run_origin = origin + info.origin();
            let mut builder = skia::PathBuilder::new();
            let mut has_glyphs = false;

            for (glyph, position) in info.glyphs().iter().zip(info.positions().iter()) {
                let Some(glyph_path) = font.get_path(*glyph) else {
                    continue;
                };
                builder.add_path(&glyph_path.with_offset(run_origin + *position));
                has_glyphs = true;
            }

            if has_glyphs {
                paths.push(builder.detach());
            }
        });
    }
}

impl Deref for TextPaths {
    type Target = TextContent;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
