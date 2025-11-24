use super::{filters, RenderState, Shape, SurfaceId};
use crate::{
    math::Rect,
    shapes::{
        merge_fills, set_paint_fill, ParagraphBuilderGroup, Stroke, StrokeKind, TextContent,
        VerticalAlign,
    },
    utils::{get_fallback_fonts, get_font_collection},
};
use skia_safe::{
    self as skia,
    canvas::SaveLayerRec,
    textlayout::{
        LineMetrics, Paragraph, ParagraphBuilder, RectHeightStyle, RectWidthStyle, StyleMetrics,
        TextDecoration, TextStyle,
    },
    Canvas, ImageFilter, Paint, Path,
};

pub fn stroke_paragraph_builder_group_from_text(
    text_content: &TextContent,
    stroke: &Stroke,
    bounds: &Rect,
    count_inner_strokes: usize,
    use_shadow: Option<bool>,
) -> Vec<ParagraphBuilderGroup> {
    let fallback_fonts = get_fallback_fonts();
    let fonts = get_font_collection();
    let mut paragraph_group = Vec::new();
    let remove_stroke_alpha = use_shadow.unwrap_or(false) && !stroke.is_transparent();

    for paragraph in text_content.paragraphs() {
        let mut stroke_paragraphs_map: std::collections::HashMap<usize, ParagraphBuilder> =
            std::collections::HashMap::new();

        for span in paragraph.children().iter() {
            let text_paint: skia_safe::Handle<_> = merge_fills(span.fills(), *bounds);
            let stroke_paints = get_text_stroke_paints(
                stroke,
                bounds,
                &text_paint,
                count_inner_strokes,
                remove_stroke_alpha,
            );

            let text: String = span.apply_text_transform();

            for (paint_idx, stroke_paint) in stroke_paints.iter().enumerate() {
                let builder = stroke_paragraphs_map.entry(paint_idx).or_insert_with(|| {
                    let paragraph_style = paragraph.paragraph_to_style();
                    ParagraphBuilder::new(&paragraph_style, fonts)
                });
                let stroke_paint = stroke_paint.clone();
                let remove_alpha = use_shadow.unwrap_or(false) && !span.is_transparent();
                let stroke_style = span.to_stroke_style(
                    &stroke_paint,
                    fallback_fonts,
                    remove_alpha,
                    paragraph.line_height(),
                );
                builder.push_style(&stroke_style);
                builder.add_text(&text);
            }
        }

        let stroke_paragraphs: Vec<ParagraphBuilder> = (0..stroke_paragraphs_map.len())
            .map(|i| stroke_paragraphs_map.remove(&i).unwrap())
            .collect();

        paragraph_group.push(stroke_paragraphs);
    }

    paragraph_group
}

fn get_text_stroke_paints(
    stroke: &Stroke,
    bounds: &Rect,
    text_paint: &Paint,
    count_inner_strokes: usize,
    remove_stroke_alpha: bool,
) -> Vec<Paint> {
    let mut paints = Vec::new();

    match stroke.kind {
        StrokeKind::Inner => {
            let shader = text_paint.shader();
            let mut is_opaque = true;

            if let Some(shader) = shader {
                is_opaque = shader.is_opaque();
            }

            if is_opaque && count_inner_strokes == 1 {
                let mut paint = text_paint.clone();
                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(true);
                paints.push(paint);

                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_blend_mode(skia::BlendMode::SrcIn);
                paint.set_anti_alias(true);
                paint.set_stroke_width(stroke.width * 2.0);
                set_paint_fill(&mut paint, &stroke.fill, bounds, remove_stroke_alpha);
                paints.push(paint);
            } else {
                let mut paint = skia::Paint::default();
                if remove_stroke_alpha {
                    paint.set_color(skia::Color::BLACK);
                    paint.set_alpha(255);
                } else {
                    paint = text_paint.clone();
                    set_paint_fill(&mut paint, &stroke.fill, bounds, false);
                }

                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(false);
                paints.push(paint);

                let mut paint = skia::Paint::default();
                let image_filter =
                    skia_safe::image_filters::erode((stroke.width, stroke.width), None, None);

                paint.set_image_filter(image_filter);
                paint.set_anti_alias(false);
                paint.set_color(skia::Color::BLACK);
                paint.set_alpha(255);
                paint.set_blend_mode(skia::BlendMode::DstOut);
                paints.push(paint);
            }
        }
        StrokeKind::Center => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);
            set_paint_fill(&mut paint, &stroke.fill, bounds, remove_stroke_alpha);
            paints.push(paint);
        }
        StrokeKind::Outer => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_blend_mode(skia::BlendMode::DstOver);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);
            set_paint_fill(&mut paint, &stroke.fill, bounds, remove_stroke_alpha);
            paints.push(paint);

            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Fill);
            paint.set_blend_mode(skia::BlendMode::Clear);
            paint.set_color(skia::Color::TRANSPARENT);
            paint.set_anti_alias(true);
            paints.push(paint);
        }
    }

    paints
}

pub fn render(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
) {
    if let Some(render_state) = render_state {
        let target_surface = surface_id.unwrap_or(SurfaceId::Fills);

        if let Some(blur_filter) = blur {
            let bounds = blur_filter.compute_fast_bounds(shape.selrect);
            if bounds.is_finite() && bounds.width() > 0.0 && bounds.height() > 0.0 {
                let blur_filter_clone = blur_filter.clone();
                if filters::render_with_filter_surface(
                    render_state,
                    bounds,
                    target_surface,
                    |state, temp_surface| {
                        let temp_canvas = state.surfaces.canvas(temp_surface);
                        render_text_on_canvas(
                            temp_canvas,
                            shape,
                            paragraph_builders,
                            shadow,
                            Some(&blur_filter_clone),
                        );
                    },
                ) {
                    return;
                }
            }
        }

        let canvas = render_state.surfaces.canvas(target_surface);
        render_text_on_canvas(canvas, shape, paragraph_builders, shadow, blur);
        return;
    }

    if let Some(canvas) = canvas {
        render_text_on_canvas(canvas, shape, paragraph_builders, shadow, blur);
    }
}

fn render_text_on_canvas(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
) {
    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        let blur_layer = SaveLayerRec::default().paint(&blur_paint);
        canvas.save_layer(&blur_layer);
    }

    if let Some(shadow_paint) = shadow {
        let layer_rec = SaveLayerRec::default().paint(shadow_paint);
        canvas.save_layer(&layer_rec);
        draw_text(canvas, shape, paragraph_builders);
        canvas.restore();
    } else {
        draw_text(canvas, shape, paragraph_builders);
    }

    if blur.is_some() {
        canvas.restore();
    }

    canvas.restore();
}

fn draw_text(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
) {
    let text_content = shape.get_text_content();
    let selrect_width = shape.selrect().width();
    let text_width = text_content.get_width(selrect_width);
    let text_height = text_content.get_height(selrect_width);
    let selrect_height = shape.selrect().height();
    let mut global_offset_y = match shape.vertical_align() {
        VerticalAlign::Center => (selrect_height - text_height) / 2.0,
        VerticalAlign::Bottom => selrect_height - text_height,
        _ => 0.0,
    };

    let layer_rec = SaveLayerRec::default();
    canvas.save_layer(&layer_rec);
    let mut normalized_line_height = text_content.normalized_line_height();

    for paragraph_builder_group in paragraph_builder_groups {
        let mut group_offset_y = global_offset_y;
        let group_len = paragraph_builder_group.len();

        for (paragraph_index, paragraph_builder) in paragraph_builder_group.iter_mut().enumerate() {
            let mut paragraph = paragraph_builder.build();
            paragraph.layout(text_width);
            let xy = (shape.selrect().x(), shape.selrect().y() + group_offset_y);
            paragraph.paint(canvas, xy);

            let line_metrics = paragraph.get_line_metrics();
            if paragraph_index == group_len - 1 {
                if line_metrics.is_empty() {
                    group_offset_y += normalized_line_height;
                } else {
                    normalized_line_height = paragraph.ideographic_baseline();
                    group_offset_y += paragraph.ideographic_baseline() * line_metrics.len() as f32;
                }
            }

            for line_metrics in paragraph.get_line_metrics().iter() {
                render_text_decoration(canvas, &paragraph, paragraph_builder, line_metrics, xy);
            }
        }

        global_offset_y = group_offset_y;
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

        // Magic numbers for line thickness partially based on Chromium
        // (see https://source.chromium.org/chromium/chromium/src/+/main:ui/gfx/render_text.cc
        let raw_font_size = style_metric.text_style.font_size();
        let thickness_factor = raw_font_size.powf(0.4) * 6.0 / 18.0;

        let thickness = (font_metrics.underline_thickness().unwrap_or(1.0) * thickness_factor)
            .max(min_thickness);

        if style_metric.text_style.decoration().ty == TextDecoration::UNDERLINE {
            // Same gap from baseline to underline as in Chromium
            // (see https://source.chromium.org/chromium/chromium/src/+/main:ui/gfx/render_text.cc
            let gap_scaling = raw_font_size * 1.0 / 9.0;
            let y = line_baseline + gap_scaling;

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

    // Draw decorations per segment (text span)
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

#[allow(dead_code)]
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

#[allow(dead_code)]
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
