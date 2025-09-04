use crate::shapes::text::TextContent;
use skia_safe::{
    self as skia, textlayout::Paragraph as SkiaParagraph, FontMetrics, Point, Rect, TextBlob,
};
use std::ops::Deref;

use crate::{with_state_mut, STATE};

pub struct TextPaths(TextContent);

// Note: This class is not being currently used.
// It's an example of how to convert texts to paths
#[allow(dead_code)]
impl TextPaths {
    pub fn new(text_content: TextContent) -> Self {
        Self(text_content)
    }

    pub fn get_paths(&self, antialias: bool) -> Vec<(skia::Path, skia::Paint)> {
        let mut paths = Vec::new();
        let mut offset_y = self.bounds.y();
        let mut paragraph_builders = self.0.paragraph_builder_group_from_text(None);

        for paragraphs in paragraph_builders.iter_mut() {
            for paragraph_builder in paragraphs.iter_mut() {
                // 1. Get paragraph and set the width layout
                let mut skia_paragraph = paragraph_builder.build();
                let text = paragraph_builder.get_text();
                let paragraph_width = self.bounds.width();
                skia_paragraph.layout(paragraph_width);

                let mut line_offset_y = offset_y;

                // 2. Iterate through each line in the paragraph
                for line_metrics in skia_paragraph.get_line_metrics() {
                    let line_baseline = line_metrics.baseline as f32;
                    let start = line_metrics.start_index;
                    let end = line_metrics.end_index;

                    // 3. Get styles present in line for each text leaf
                    let style_metrics = line_metrics.get_style_metrics(start..end);

                    let mut offset_x = 0.0;

                    for (i, (start_index, style_metric)) in style_metrics.iter().enumerate() {
                        let end_index = style_metrics.get(i + 1).map_or(end, |next| next.0);

                        let start_byte = text
                            .char_indices()
                            .nth(*start_index)
                            .map(|(i, _)| i)
                            .unwrap_or(0);
                        let end_byte = text
                            .char_indices()
                            .nth(end_index)
                            .map(|(i, _)| i)
                            .unwrap_or(text.len());

                        let leaf_text = &text[start_byte..end_byte];

                        let font = skia_paragraph.get_font_at(*start_index);

                        let blob_offset_x = self.bounds.x() + line_metrics.left as f32 + offset_x;
                        let blob_offset_y = line_offset_y;

                        // 4. Get the path for each text leaf
                        if let Some((text_path, paint)) = self.generate_text_path(
                            leaf_text,
                            &font,
                            blob_offset_x,
                            blob_offset_y,
                            style_metric,
                            antialias,
                        ) {
                            let text_width = font.measure_text(leaf_text, None).0;
                            offset_x += text_width;
                            paths.push((text_path, paint));
                        }
                    }
                    line_offset_y = offset_y + line_baseline;
                }
                offset_y += skia_paragraph.height();
            }
        }
        paths
    }

    fn generate_text_path(
        &self,
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
        style_metric: &skia::textlayout::StyleMetrics,
        antialias: bool,
    ) -> Option<(skia::Path, skia::Paint)> {
        // Convert text to path, including text decoration
        // TextBlob might be empty and, in this case, we return None
        // This is used to avoid rendering empty paths, but we can
        // revisit this logic later
        if let Some((text_blob_path, text_blob_bounds)) =
            Self::get_text_blob_path(leaf_text, font, blob_offset_x, blob_offset_y)
        {
            let mut text_path = text_blob_path.clone();
            let text_width = font.measure_text(leaf_text, None).0;

            let decoration = style_metric.text_style.decoration();
            let font_metrics = style_metric.font_metrics;

            let blob_left = blob_offset_x;
            let blob_top = blob_offset_y;
            let blob_height = text_blob_bounds.height();

            if let Some(decoration_rect) = self.calculate_text_decoration_rect(
                decoration.ty,
                font_metrics,
                blob_left,
                blob_top,
                text_width,
                blob_height,
            ) {
                text_path.add_rect(decoration_rect, None);
            }

            let mut paint = style_metric.text_style.foreground();
            paint.set_anti_alias(antialias);

            return Some((text_path, paint));
        }
        None
    }

    fn calculate_text_decoration_rect(
        &self,
        decoration: skia::textlayout::TextDecoration,
        font_metrics: FontMetrics,
        blob_left: f32,
        blob_offset_y: f32,
        text_width: f32,
        blob_height: f32,
    ) -> Option<Rect> {
        match decoration {
            skia::textlayout::TextDecoration::LINE_THROUGH => {
                let underline_thickness = font_metrics.underline_thickness().unwrap_or(0.0);
                let underline_position = blob_height / 2.0;
                Some(Rect::new(
                    blob_left,
                    blob_offset_y + underline_position - underline_thickness / 2.0,
                    blob_left + text_width,
                    blob_offset_y + underline_position + underline_thickness / 2.0,
                ))
            }
            skia::textlayout::TextDecoration::UNDERLINE => {
                let underline_thickness = font_metrics.underline_thickness().unwrap_or(0.0);
                let underline_position = blob_height - underline_thickness;
                Some(Rect::new(
                    blob_left,
                    blob_offset_y + underline_position - underline_thickness / 2.0,
                    blob_left + text_width,
                    blob_offset_y + underline_position + underline_thickness / 2.0,
                ))
            }
            _ => None,
        }
    }

    fn get_text_blob_path(
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
    ) -> Option<(skia::Path, skia::Rect)> {
        with_state_mut!(state, {
            let utf16_text = leaf_text.encode_utf16().collect::<Vec<u16>>();
            let text = unsafe { skia_safe::as_utf16_unchecked(&utf16_text) };
            let emoji_font = state.render_state.fonts().get_emoji_font(font.size());
            let use_font = emoji_font.as_ref().unwrap_or(font);

            if let Some(mut text_blob) = TextBlob::from_text(text, use_font) {
                let path = SkiaParagraph::get_path(&mut text_blob);
                let d = Point::new(blob_offset_x, blob_offset_y);
                let offset_path = path.with_offset(d);
                let bounds = text_blob.bounds();
                return Some((offset_path, *bounds));
            }
        });
        None
    }
}

impl Deref for TextPaths {
    type Target = TextContent;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
