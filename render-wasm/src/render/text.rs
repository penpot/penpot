use super::{RenderState, Shape, SurfaceId};
use crate::shapes::VerticalAlign;
use skia_safe::{textlayout::ParagraphBuilder, FontMetrics, Paint, Path};

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [ParagraphBuilder],
    surface_id: Option<SurfaceId>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    let mut offset_y = 0.0;
    let container_height = shape.selrect().height();

    for builder in paragraphs {
        let mut skia_paragraph = builder.build();
        skia_paragraph.layout(shape.bounds().width());
        let paragraph_height: f32 = skia_paragraph.height();

        let paragraph_offset_y = match shape.vertical_align() {
            VerticalAlign::Center => (container_height - paragraph_height) / 2.0,
            VerticalAlign::Bottom => container_height - paragraph_height,
            _ => 0.0,
        };

        offset_y += paragraph_offset_y;

        let xy = (shape.selrect().x(), shape.selrect().y() + offset_y);
        skia_paragraph.paint(canvas, xy);

        offset_y += paragraph_height;

        for line_metrics in skia_paragraph.get_line_metrics().iter() {
            let style_metrics: Vec<_> = line_metrics
                .get_style_metrics(line_metrics.start_index..line_metrics.end_index)
                .into_iter()
                .collect();

            let mut current_x_offset = 0.0;
            let total_line_width = line_metrics.width as f32;
            let total_chars = line_metrics.end_index - line_metrics.start_index;

            // No text decoration for empty lines
            if total_chars == 0 || style_metrics.is_empty() {
                continue;
            }

            for (i, (index, style_metric)) in style_metrics.iter().enumerate() {
                let text_style = style_metric.text_style;
                let font_metrics = style_metric.font_metrics;
                let next_index = style_metrics
                    .get(i + 1)
                    .map(|(next_i, _)| *next_i)
                    .unwrap_or(line_metrics.end_index);
                let char_count = next_index - index;
                let segment_width = if total_chars > 0 {
                    (char_count as f32 / total_chars as f32) * total_line_width
                } else {
                    char_count as f32 * font_metrics.avg_char_width
                };

                if text_style.decoration().ty
                    != skia_safe::textlayout::TextDecoration::NO_DECORATION
                {
                    let decoration_type = text_style.decoration().ty;
                    let text_left = xy.0 + current_x_offset;
                    let text_top = xy.1 + line_metrics.baseline as f32 - line_metrics.ascent as f32;
                    let text_width = segment_width;
                    let line_height = line_metrics.height as f32;

                    let r = calculate_text_decoration_rect(
                        decoration_type,
                        font_metrics,
                        text_left,
                        text_top,
                        text_width,
                        line_height,
                    );

                    if let Some(decoration_rect) = r {
                        let decoration_paint = text_style.foreground();
                        canvas.draw_rect(decoration_rect, &decoration_paint);
                    }
                }

                current_x_offset += segment_width;
            }
        }
    }
}

pub fn calculate_text_decoration_rect(
    decoration: skia_safe::textlayout::TextDecoration,
    font_metrics: FontMetrics,
    blob_left: f32,
    blob_offset_y: f32,
    text_width: f32,
    blob_height: f32,
) -> Option<skia_safe::Rect> {
    let thickness = font_metrics.underline_thickness().unwrap_or(1.0);
    match decoration {
        skia_safe::textlayout::TextDecoration::LINE_THROUGH => {
            let line_position = blob_height / 2.0;
            Some(skia_safe::Rect::new(
                blob_left,
                blob_offset_y + line_position - thickness / 2.0,
                blob_left + text_width,
                blob_offset_y + line_position + thickness / 2.0,
            ))
        }
        skia_safe::textlayout::TextDecoration::UNDERLINE => {
            let underline_y = blob_offset_y + blob_height - thickness;
            Some(skia_safe::Rect::new(
                blob_left,
                underline_y,
                blob_left + text_width,
                underline_y + thickness,
            ))
        }
        _ => None,
    }
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_as_path(
    render_state: &mut RenderState,
    paths: &Vec<(Path, Paint)>,
    surface_id: Option<SurfaceId>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    for (path, paint) in paths {
        // Note: path can be empty
        canvas.draw_path(path, paint);
    }
}

// How to use it?
// Type::Text(text_content) => {
//     self.surfaces
//         .apply_mut(&[SurfaceId::Fills, SurfaceId::Strokes], |s| {
//             s.canvas().concat(&matrix);
//         });

//     let text_content = text_content.new_bounds(shape.selrect());
//     let paths = text_content.get_paths(antialias);

//     shadows::render_text_drop_shadows(self, &shape, &paths, antialias);
//     text::render(self, &paths, None, None);

//     for stroke in shape.strokes().rev() {
//         shadows::render_text_path_stroke_drop_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//         strokes::render_text_paths(self, &shape, stroke, &paths, None, None, antialias);
//         shadows::render_text_path_stroke_inner_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//     }

//     shadows::render_text_inner_shadows(self, &shape, &paths, antialias);
// }
