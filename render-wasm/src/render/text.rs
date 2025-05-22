use super::{RenderState, SurfaceId};
use crate::render::strokes::draw_stroke_on_text_path;
use crate::shapes::{Shape, Stroke};
use skia_safe::{self as skia, canvas::SaveLayerRec, textlayout::Paragraph, Point, Rect};

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [Vec<Paragraph>],
    surface_id: Option<SurfaceId>,
    stroke: Option<&Stroke>,
    paint: Option<skia::Paint>,
) {
    let mask_paint = paint.unwrap_or_default();
    let mask = SaveLayerRec::default().paint(&mask_paint);
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    canvas.save_layer(&mask);

    let center = shape.center();
    let mut matrix = shape.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);
    canvas.concat(&matrix);

    for group in paragraphs.iter_mut() {
        let mut offset_y = 0.0;
        for skia_paragraph in group.iter_mut() {
            let paragraph_width = shape.selrect().width();
            skia_paragraph.layout(paragraph_width);

            let line_data: Vec<_> = skia_paragraph
                .get_line_metrics()
                .iter()
                .map(|line| {
                    (
                        line.line_number,
                        line.start_index,
                        line.end_index,
                        line.baseline,
                    )
                })
                .collect();

            for (line_number, start, end, baseline) in line_data {
                let (_, path) = skia_paragraph.get_path_at(line_number);
                let line_baseline = baseline as f32;
                let line_offset_y = shape.selrect().y() + offset_y;
                let d = Point::new(shape.selrect().x(), line_offset_y);
                let offset_path = path.with_offset(d);
                let line_metrics = skia_paragraph.get_line_metrics_at(line_number).unwrap();
                let style_metrics = line_metrics.get_style_metrics(start..end);
                for (_, style_metric) in style_metrics {
                    let mut paint = style_metric.text_style.foreground();
                    paint.set_anti_alias(true);

                    let mut combined_path = offset_path.clone();
                    let decoration = style_metric.text_style.decoration();
                    let font_metrics = style_metric.font_metrics;

                    let line_left = shape.selrect().x() + line_metrics.left as f32;
                    let line_right = line_left + line_metrics.width as f32;

                    match decoration.ty {
                        skia::textlayout::TextDecoration::UNDERLINE => {
                            let underline_thickness =
                                font_metrics.underline_thickness().unwrap_or(0.0);
                            let underline_position =
                                font_metrics.underline_position().unwrap_or(0.0);
                            let underline_rect = Rect::new(
                                line_left,
                                line_offset_y + line_baseline + underline_position,
                                line_right,
                                line_offset_y
                                    + line_baseline
                                    + underline_position
                                    + underline_thickness,
                            );
                            combined_path.add_rect(underline_rect, None);
                        }
                        skia::textlayout::TextDecoration::LINE_THROUGH => {
                            let strikeout_thickness =
                                font_metrics.strikeout_thickness().unwrap_or(0.0);
                            let strikeout_position =
                                font_metrics.strikeout_position().unwrap_or(0.0);
                            let strikethrough_rect = Rect::new(
                                line_left,
                                line_offset_y + line_baseline + strikeout_position,
                                line_right,
                                line_offset_y
                                    + line_baseline
                                    + strikeout_position
                                    + strikeout_thickness,
                            );
                            combined_path.add_rect(strikethrough_rect, None);
                        }
                        _ => {}
                    }

                    canvas.draw_path(&combined_path, &paint);

                    if let Some(stroke) = stroke {
                        draw_stroke_on_text_path(
                            canvas,
                            stroke,
                            combined_path,
                            &shape.selrect(),
                            &shape.svg_attrs,
                            1.0, // FIXME dpr?
                            true,
                        );
                    }
                }
            }

            offset_y += skia_paragraph.height();
        }
    }
    canvas.restore();
}
