use super::{RenderState, Shape, SurfaceId};
use crate::shapes::VerticalAlign;
use crate::utils::get_font_collection;
use skia_safe::{textlayout::ParagraphBuilder, FontMetrics, Paint, Path};

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    paint: Option<&Paint>,
) {
    let fonts = get_font_collection();
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));
    let container_height = shape.selrect().height();

    // Calculate total height for vertical alignment
    let total_content_height = calculate_all_paragraphs_height(paragraphs, shape.bounds().width());
    let mut global_offset_y = match shape.vertical_align() {
        VerticalAlign::Center => (container_height - total_content_height) / 2.0,
        VerticalAlign::Bottom => container_height - total_content_height,
        _ => 0.0,
    };

    let layer_rec = skia_safe::canvas::SaveLayerRec::default();
    canvas.save_layer(&layer_rec);

    for group in paragraphs {
        let mut group_offset_y = global_offset_y;
        let group_len = group.len();

        for (index, builder) in group.iter_mut().enumerate() {
            let mut skia_paragraph = builder.build();

            if paint.is_some() && index == 0 {
                let text = builder.get_text().to_string();
                let mut paragraph_builder =
                    ParagraphBuilder::new(&builder.get_paragraph_style(), fonts);
                let mut text_style: skia_safe::Handle<_> = builder.peek_style();
                text_style.set_foreground_paint(paint.unwrap());
                paragraph_builder.reset();
                paragraph_builder.push_style(&text_style);
                paragraph_builder.add_text(&text);
                skia_paragraph = paragraph_builder.build();
            } else if paint.is_some() && index > 0 {
                continue;
            }

            skia_paragraph.layout(shape.bounds().width());

            let paragraph_height = skia_paragraph.height();
            let xy = (shape.selrect().x(), shape.selrect().y() + group_offset_y);
            skia_paragraph.paint(canvas, xy);

            for line_metrics in skia_paragraph.get_line_metrics().iter() {
                let style_metrics: Vec<_> = line_metrics
                    .get_style_metrics(line_metrics.start_index..line_metrics.end_index)
                    .into_iter()
                    .collect();

                let mut current_x_offset = 0.0;
                let total_line_width = line_metrics.width as f32;
                let total_chars = line_metrics.end_index - line_metrics.start_index;

                // Calculate line's actual start position considering text alignment
                // let paragraph_width = shape.bounds().width();
                let line_start_offset = line_metrics.left as f32;

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
                        let text_left = xy.0 + line_start_offset + current_x_offset;
                        let text_top =
                            xy.1 + line_metrics.baseline as f32 - line_metrics.ascent as f32;
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

            // Only increment group_offset_y for regular paragraphs (single element groups)
            // For stroke groups (multiple elements), keep same offset for blending
            if group_len == 1 {
                group_offset_y += paragraph_height;
            }
            // For stroke groups (group_len > 1), don't increment group_offset_y within the group
            // This ensures all stroke variants render at the same position for proper blending
        }

        // For stroke groups (multiple elements), increment global_offset_y once per group
        if group_len > 1 {
            let mut first_paragraph = group[0].build();
            first_paragraph.layout(shape.bounds().width());
            global_offset_y += first_paragraph.height();
        } else {
            // For regular paragraphs, global_offset_y was already incremented inside the loop
            global_offset_y = group_offset_y;
        }
    }

    canvas.restore();
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

fn calculate_total_paragraphs_height(paragraphs: &mut [ParagraphBuilder], width: f32) -> f32 {
    paragraphs
        .iter_mut()
        .map(|p| {
            let mut paragraph = p.build();
            paragraph.layout(width);
            paragraph.height()
        })
        .sum()
}

fn calculate_all_paragraphs_height(
    paragraph_groups: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> f32 {
    paragraph_groups
        .iter_mut()
        .map(|group| {
            // For stroke groups, only count the first paragraph to avoid double-counting
            if group.len() > 1 {
                let mut paragraph = group[0].build();
                paragraph.layout(width);
                paragraph.height()
            } else {
                calculate_total_paragraphs_height(group, width)
            }
        })
        .sum()
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

//     for stroke in shape.visible_strokes().rev() {
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
