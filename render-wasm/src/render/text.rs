use super::{RenderState, Shape, SurfaceId};
use crate::shapes::VerticalAlign;
use skia_safe::{
    canvas::SaveLayerRec,
    textlayout::{
        LineMetrics, Paragraph, ParagraphBuilder, RectHeightStyle, RectWidthStyle, StyleMetrics,
        TextDecoration, TextStyle,
    },
    Canvas, ImageFilter, Paint, Path,
};

pub fn render(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
) {
    let render_canvas = if let Some(rs) = render_state {
        rs.surfaces.canvas(surface_id.unwrap_or(SurfaceId::Fills))
    } else if let Some(c) = canvas {
        c
    } else {
        return;
    };

    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        let blur_layer = SaveLayerRec::default().paint(&blur_paint);
        render_canvas.save_layer(&blur_layer);
    }

    if let Some(shadow_paint) = shadow {
        let layer_rec = SaveLayerRec::default().paint(shadow_paint);
        render_canvas.save_layer(&layer_rec);
        draw_text(render_canvas, shape, paragraphs);
        render_canvas.restore();
    } else {
        draw_text(render_canvas, shape, paragraphs);
    }

    if blur.is_some() {
        render_canvas.restore();
    }

    render_canvas.restore();
}

fn draw_text(canvas: &Canvas, shape: &Shape, paragraphs: &mut [Vec<ParagraphBuilder>]) {
    // Width
    let paragraph_width = if let crate::shapes::Type::Text(text_content) = &shape.shape_type {
        text_content.width()
    } else {
        shape.width()
    };

    // Height
    let container_height = shape.selrect().height();
    let total_content_height = calculate_all_paragraphs_height(paragraphs, paragraph_width);
    let mut global_offset_y = match shape.vertical_align() {
        VerticalAlign::Center => (container_height - total_content_height) / 2.0,
        VerticalAlign::Bottom => container_height - total_content_height,
        _ => 0.0,
    };

    let layer_rec = SaveLayerRec::default();
    canvas.save_layer(&layer_rec);
    for group in paragraphs {
        let mut group_offset_y = global_offset_y;
        let group_len = group.len();

        for builder in group.iter_mut() {
            let mut skia_paragraph = builder.build();
            skia_paragraph.layout(paragraph_width);
            let paragraph_height = skia_paragraph.height();
            let xy = (shape.selrect().x(), shape.selrect().y() + group_offset_y);
            skia_paragraph.paint(canvas, xy);

            for line_metrics in skia_paragraph.get_line_metrics().iter() {
                render_text_decoration(canvas, &skia_paragraph, builder, line_metrics, xy);
            }

            if group_len == 1 {
                group_offset_y += paragraph_height;
            }
        }

        if group_len > 1 {
            let mut first_paragraph = group[0].build();
            first_paragraph.layout(paragraph_width);
            global_offset_y += first_paragraph.height();
        } else {
            global_offset_y = group_offset_y;
        }
    }
}

fn draw_text_decorations(
    canvas: &Canvas,
    text_style: &TextStyle,
    y: Option<f32>,
    thickness: f32,
    text_left: f32,
    text_width: f32,
) {
    if let Some(y) = y {
        let r = skia_safe::Rect::new(
            text_left,
            y - thickness / 2.0,
            text_left + text_width,
            y + thickness / 2.0,
        );
        let mut decoration_paint = text_style.foreground();
        decoration_paint.set_anti_alias(true);
        canvas.draw_rect(r, &decoration_paint);
    }
}

fn calculate_decoration_metrics(
    style_metrics: &Vec<(usize, &StyleMetrics)>,
    line_baseline: f32,
) -> (f32, Option<f32>, f32, Option<f32>) {
    let mut max_underline_thickness: f32 = 0.0;
    let mut underline_y = None;
    let mut max_strike_thickness: f32 = 0.0;
    let mut strike_y = None;
    for (_style_start, style_metric) in style_metrics.iter() {
        let font_metrics = style_metric.font_metrics;
        let font_size = font_metrics
            .cap_height
            .abs()
            .max(font_metrics.x_height.abs());
        let min_thickness = (font_size * 0.06).max(1.0);
        let thickness = font_metrics
            .underline_thickness()
            .unwrap_or(1.0)
            .max(min_thickness);
        if style_metric.text_style.decoration().ty == TextDecoration::UNDERLINE {
            let y = line_baseline + font_metrics.underline_position().unwrap_or(thickness);
            max_underline_thickness = max_underline_thickness.max(thickness);
            underline_y = Some(y);
        }
        if style_metric.text_style.decoration().ty == TextDecoration::LINE_THROUGH {
            let y = line_baseline
                + font_metrics
                    .strikeout_position()
                    .unwrap_or(-font_metrics.cap_height / 2.0);
            max_strike_thickness = max_strike_thickness.max(thickness);
            strike_y = Some(y);
        }
    }
    (
        max_underline_thickness,
        underline_y,
        max_strike_thickness,
        strike_y,
    )
}

fn render_text_decoration(
    canvas: &Canvas,
    skia_paragraph: &Paragraph,
    builder: &mut ParagraphBuilder,
    line_metrics: &LineMetrics,
    xy: (f32, f32),
) {
    let style_metrics: Vec<_> = line_metrics
        .get_style_metrics(line_metrics.start_index..line_metrics.end_index)
        .into_iter()
        .collect();

    let mut current_x_offset = 0.0;
    let total_chars = line_metrics.end_index - line_metrics.start_index;
    let line_start_offset = line_metrics.left as f32;

    if total_chars == 0 || style_metrics.is_empty() {
        return;
    }

    let line_baseline = xy.1 + line_metrics.baseline as f32;
    let full_text = builder.get_text();

    // Calculate decoration metrics
    let (max_underline_thickness, underline_y, max_strike_thickness, strike_y) =
        calculate_decoration_metrics(&style_metrics, line_baseline);

    // Draw decorations per segment (text leaf)
    for (i, (style_start, style_metric)) in style_metrics.iter().enumerate() {
        let text_style = &style_metric.text_style;
        let style_end = style_metrics
            .get(i + 1)
            .map(|(next_i, _)| *next_i)
            .unwrap_or(line_metrics.end_index);

        let seg_start = (*style_start).max(line_metrics.start_index);
        let seg_end = style_end.min(line_metrics.end_index);
        if seg_start >= seg_end {
            continue;
        }

        let start_byte = full_text
            .char_indices()
            .nth(seg_start)
            .map(|(i, _)| i)
            .unwrap_or(0);
        let end_byte = full_text
            .char_indices()
            .nth(seg_end)
            .map(|(i, _)| i)
            .unwrap_or(full_text.len());
        let segment_text = &full_text[start_byte..end_byte];

        let rects = skia_paragraph.get_rects_for_range(
            seg_start..seg_end,
            RectHeightStyle::Tight,
            RectWidthStyle::Tight,
        );
        let (segment_width, actual_x_offset) = if !rects.is_empty() {
            let total_width: f32 = rects.iter().map(|r| r.rect.width()).sum();
            let skia_x_offset = rects
                .first()
                .map(|r| r.rect.left - line_start_offset)
                .unwrap_or(0.0);
            (total_width, skia_x_offset)
        } else {
            let font = skia_paragraph.get_font_at(seg_start);
            let measured_width = font.measure_text(segment_text, None).0;
            (measured_width, current_x_offset)
        };

        let text_left = xy.0 + line_start_offset + actual_x_offset;
        let text_width = segment_width;

        // Underline
        if text_style.decoration().ty == TextDecoration::UNDERLINE {
            draw_text_decorations(
                canvas,
                text_style,
                underline_y,
                max_underline_thickness,
                text_left,
                text_width,
            );
        }
        // Strikethrough
        if text_style.decoration().ty == TextDecoration::LINE_THROUGH {
            draw_text_decorations(
                canvas,
                text_style,
                strike_y,
                max_strike_thickness,
                text_left,
                text_width,
            );
        }
        current_x_offset += segment_width;
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

//     shadows::render_text_shadows(self, &shape, &paths, antialias);
//     text::render(self, &paths, None, None);

//     for stroke in shape.visible_strokes().rev() {
//         shadows::render_text_path_stroke_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//         strokes::render_text_paths(self, &shape, stroke, &paths, None, None, antialias);
//         shadows::render_text_path_stroke_inner_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//     }

//     shadows::render_text_inner_shadows(self, &shape, &paths, antialias);
// }
