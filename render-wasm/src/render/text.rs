use super::{filters, RenderState, Shape, SurfaceId, DEFAULT_EMOJI_FONT};
use crate::{
    error::Result,
    math::Rect,
    shapes::{
        calculate_text_layout_data, set_paint_fill, ParagraphBuilderGroup, ParagraphLayout, Stroke,
        StrokeKind, TextContent,
    },
    utils::{get_fallback_fonts, get_font_collection},
};
use skia_safe::{
    self as skia,
    canvas::SaveLayerRec,
    textlayout::{ParagraphBuilder, StyleMetrics, TextDecoration, TextStyle},
    Canvas, ImageFilter, Paint,
};

pub fn stroke_paragraph_builder_group_from_text(
    text_content: &TextContent,
    stroke: &Stroke,
    bounds: &Rect,
    use_shadow: Option<bool>,
) -> (Vec<ParagraphBuilderGroup>, Option<f32>) {
    let fallback_fonts = get_fallback_fonts();
    let fonts = get_font_collection();
    let mut paragraph_group = Vec::new();
    let remove_stroke_alpha = use_shadow.unwrap_or(false) && !stroke.is_transparent();
    let mut group_layer_opacity: Option<f32> = None;

    for paragraph in text_content.paragraphs() {
        let mut stroke_paragraphs_map: std::collections::HashMap<usize, ParagraphBuilder> =
            std::collections::HashMap::new();

        for span in paragraph.children().iter() {
            let (stroke_paints, stroke_layer_opacity) =
                get_text_stroke_paints(stroke, bounds, remove_stroke_alpha);

            if group_layer_opacity.is_none() {
                group_layer_opacity = stroke_layer_opacity;
            }

            let text: String = span.apply_text_transform();

            for (paint_idx, stroke_paint) in stroke_paints.iter().enumerate() {
                let builder = stroke_paragraphs_map.entry(paint_idx).or_insert_with(|| {
                    let paragraph_style = paragraph.paragraph_to_style();
                    let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
                    paragraph.push_list_marker_placeholder(&mut builder);
                    builder
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
            .filter_map(|i| stroke_paragraphs_map.remove(&i))
            .collect();

        paragraph_group.push(stroke_paragraphs);
    }

    (paragraph_group, group_layer_opacity)
}

fn get_text_stroke_paints(
    stroke: &Stroke,
    bounds: &Rect,
    remove_stroke_alpha: bool,
) -> (Vec<Paint>, Option<f32>) {
    let mut paints = Vec::new();
    let mut layer_opacity: Option<f32> = None;

    let stroke_opacity = stroke.fill.opacity();
    let needs_opacity_layer = stroke_opacity < 1.0 && !remove_stroke_alpha;

    let fill_for_paint = |paint: &mut Paint| {
        if needs_opacity_layer {
            let opaque_fill = stroke.fill.with_full_opacity();
            set_paint_fill(paint, &opaque_fill, bounds, remove_stroke_alpha);
        } else {
            set_paint_fill(paint, &stroke.fill, bounds, remove_stroke_alpha);
        }
    };

    if needs_opacity_layer {
        layer_opacity = Some(stroke_opacity);
    }

    match stroke.kind {
        StrokeKind::Inner => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);
            if remove_stroke_alpha {
                paint.set_color(skia::Color::BLACK);
                paint.set_alpha(255);
            } else {
                fill_for_paint(&mut paint);
            }
            paints.push(paint);
        }
        StrokeKind::Center => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);
            fill_for_paint(&mut paint);
            paints.push(paint);
        }
        StrokeKind::Outer => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);
            if remove_stroke_alpha {
                paint.set_color(skia::Color::BLACK);
                paint.set_alpha(255);
            } else {
                fill_for_paint(&mut paint);
            }
            paints.push(paint);
        }
    }

    (paints, layer_opacity)
}

#[allow(clippy::too_many_arguments)]
pub fn render_with_bounds_outset(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
) -> Result<()> {
    render_with_bounds_outset_inner(
        render_state,
        canvas,
        shape,
        paragraph_builders,
        surface_id,
        shadow,
        blur,
        stroke_bounds_outset,
        fill_inset,
        layer_opacity,
        false,
    )
}

/// Like [`render_with_bounds_outset`] but with emoji bitmap overlay for PDF/vector export.
#[allow(clippy::too_many_arguments)]
pub fn render_with_bounds_outset_overlay_emoji(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
) -> Result<()> {
    render_with_bounds_outset_inner(
        None,
        Some(canvas),
        shape,
        paragraph_builders,
        None,
        shadow,
        blur,
        stroke_bounds_outset,
        fill_inset,
        layer_opacity,
        true,
    )
}

#[allow(clippy::too_many_arguments)]
fn render_with_bounds_outset_inner(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
    overlay_emoji: bool,
) -> Result<()> {
    if let Some(render_state) = render_state {
        let target_surface = surface_id.unwrap_or(SurfaceId::Fills);

        if let Some(blur_filter) = blur {
            let mut text_bounds = shape
                .get_text_content()
                .calculate_bounds(shape, false)
                .to_rect();
            if stroke_bounds_outset > 0.0 {
                text_bounds.inset((-stroke_bounds_outset, -stroke_bounds_outset));
            }
            let bounds = blur_filter.compute_fast_bounds(text_bounds);
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
                            fill_inset,
                            layer_opacity,
                            false,
                        );
                        Ok(())
                    },
                )? {
                    return Ok(());
                }
            }
        }

        let canvas = render_state.surfaces.canvas_and_mark_dirty(target_surface);
        render_text_on_canvas(
            canvas,
            shape,
            paragraph_builders,
            shadow,
            blur,
            fill_inset,
            layer_opacity,
            false,
        );
        return Ok(());
    }

    if let Some(canvas) = canvas {
        render_text_on_canvas(
            canvas,
            shape,
            paragraph_builders,
            shadow,
            blur,
            fill_inset,
            layer_opacity,
            overlay_emoji,
        );
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn render(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
) -> Result<()> {
    render_with_bounds_outset(
        render_state,
        canvas,
        shape,
        paragraph_builders,
        surface_id,
        shadow,
        blur,
        0.0,
        fill_inset,
        layer_opacity,
    )
}

/// Like [`render`] but rasterizes color emoji as bitmap overlays for PDF/vector export.
#[allow(clippy::too_many_arguments)]
pub fn render_overlay_emoji(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
) -> Result<()> {
    render_with_bounds_outset_overlay_emoji(
        canvas,
        shape,
        paragraph_builders,
        shadow,
        blur,
        0.0,
        fill_inset,
        layer_opacity,
    )
}

#[allow(clippy::too_many_arguments)]
fn render_text_on_canvas(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builders: &mut [Vec<ParagraphBuilder>],
    shadow: Option<&Paint>,
    blur: Option<&ImageFilter>,
    fill_inset: Option<f32>,
    layer_opacity: Option<f32>,
    overlay_emoji: bool,
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
        draw_text(
            canvas,
            shape,
            paragraph_builders,
            layer_opacity,
            overlay_emoji,
        );
        canvas.restore();
    } else if let Some(eps) = fill_inset.filter(|&e| e > 0.0) {
        if let Some(erode) = skia_safe::image_filters::erode((eps, eps), None, None) {
            let mut layer_paint = Paint::default();
            layer_paint.set_image_filter(erode);
            let layer_rec = SaveLayerRec::default().paint(&layer_paint);
            canvas.save_layer(&layer_rec);
            draw_text(
                canvas,
                shape,
                paragraph_builders,
                layer_opacity,
                overlay_emoji,
            );
            canvas.restore();
        } else {
            draw_text(
                canvas,
                shape,
                paragraph_builders,
                layer_opacity,
                overlay_emoji,
            );
        }
    } else {
        draw_text(
            canvas,
            shape,
            paragraph_builders,
            layer_opacity,
            overlay_emoji,
        );
    }

    if blur.is_some() {
        canvas.restore();
    }

    canvas.restore();
}

/// Lays out and paints paragraph builders without any layer management.
fn paint_text(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
) {
    paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, false);
}

fn paint_text_with_emoji_overlay(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
    overlay_emoji: bool,
) {
    let text_content = shape.get_text_content();
    let mut layout_info =
        calculate_text_layout_data(shape, text_content, paragraph_builder_groups, true);

    // paragraph_builder_groups is 1:1 with content paragraphs; each group may
    // expand to several laid-out ParagraphLayout entries (e.g. stroke paints).
    let mut layout_idx = 0usize;
    for (para_idx, group) in paragraph_builder_groups.iter().enumerate() {
        let group_len = group.len().max(1);
        for builder_idx in 0..group_len {
            if let Some(para) = layout_info.paragraphs.get_mut(layout_idx) {
                para.paragraph.paint(canvas, (para.x, para.y));

                if overlay_emoji {
                    paint_emoji_overlay(canvas, para);
                }

                for deco in &para.decorations {
                    draw_text_decorations(
                        canvas,
                        &deco.text_style,
                        Some(deco.y),
                        deco.thickness,
                        deco.left,
                        deco.width,
                    );
                }

                // Draw list markers once per content paragraph (first builder).
                if builder_idx == 0 {
                    if let Some(content_para) = text_content.paragraphs().get(para_idx) {
                        paint_list_marker(canvas, text_content, para_idx, content_para, para);
                    }
                }
            }
            layout_idx += 1;
        }
    }
}

fn list_item_index(text_content: &TextContent, para_idx: usize) -> u32 {
    let paragraphs = text_content.paragraphs();
    let Some(para) = paragraphs.get(para_idx) else {
        return 1;
    };
    let indent = para.list_indent();
    let mut count = 1u32;
    for prev in paragraphs.iter().take(para_idx).rev() {
        if prev.list_style() != crate::shapes::ListStyle::Numbered {
            break;
        }
        if prev.list_indent() < indent {
            break;
        }
        if prev.list_indent() == indent {
            count += 1;
        }
    }
    count
}

/// CSS-style lower-alpha: 1 -> a, 26 -> z, 27 -> aa.
fn number_to_lower_alpha(mut n: u32) -> String {
    if n == 0 {
        return "a".to_string();
    }
    let mut result = String::new();
    while n > 0 {
        n -= 1;
        result.insert(0, (b'a' + (n % 26) as u8) as char);
        n /= 26;
    }
    result
}

/// CSS-style lower-roman: 1 -> i, 4 -> iv, 9 -> ix.
fn number_to_lower_roman(mut n: u32) -> String {
    if n == 0 {
        return "i".to_string();
    }
    const VALUES: [(u32, &str); 13] = [
        (1000, "m"),
        (900, "cm"),
        (500, "d"),
        (400, "cd"),
        (100, "c"),
        (90, "xc"),
        (50, "l"),
        (40, "xl"),
        (10, "x"),
        (9, "ix"),
        (5, "v"),
        (4, "iv"),
        (1, "i"),
    ];
    let mut result = String::new();
    for (value, symbol) in VALUES {
        while n >= value {
            result.push_str(symbol);
            n -= value;
        }
    }
    result
}

/// Numbered list marker text by indent level: decimal, lower-alpha, lower-roman (cycles).
fn numbered_marker_label(count: u32, indent: u8) -> String {
    let marker = match indent % 3 {
        0 => count.to_string(),
        1 => number_to_lower_alpha(count),
        _ => number_to_lower_roman(count),
    };
    format!("{marker}.")
}

fn list_marker_label(text_content: &TextContent, para_idx: usize) -> Option<String> {
    let paragraphs = text_content.paragraphs();
    let para = paragraphs.get(para_idx)?;
    if para.list_style() != crate::shapes::ListStyle::Numbered {
        return None;
    }
    let count = list_item_index(text_content, para_idx);
    Some(numbered_marker_label(count, para.list_indent()))
}

fn marker_font(para_layout: &mut crate::shapes::ParagraphLayout, font_size: f32) -> skia::Font {
    // Prefer the typeface Skia already resolved for this paragraph's text.
    // Empty paragraphs still return a usable default from the paragraph.
    let mut font = para_layout.paragraph.get_font_at_utf16_offset(0);
    if font.typeface().family_name().is_empty() {
        font = crate::globals::get_render_state()
            .fonts()
            .debug_font()
            .clone();
    }
    font.set_size(font_size);
    font
}

fn paint_list_marker(
    canvas: &Canvas,
    text_content: &TextContent,
    para_idx: usize,
    content_para: &crate::shapes::Paragraph,
    para_layout: &mut crate::shapes::ParagraphLayout,
) {
    let list_style = content_para.list_style();
    if !list_style.is_active() {
        return;
    }

    let font_size = content_para
        .children()
        .first()
        .map(|span| span.font_size)
        .unwrap_or(14.0);

    let fill_color = content_para
        .children()
        .first()
        .and_then(|span| span.fills.first())
        .map(|fill| match fill {
            crate::shapes::Fill::Solid(crate::shapes::SolidColor(color)) => *color,
            _ => skia::Color::BLACK,
        })
        .unwrap_or(skia::Color::BLACK);

    let mut paint = Paint::default();
    paint.set_anti_alias(true);
    paint.set_color(fill_color);

    let marker_column = content_para.list_marker_column();
    let marker_center_x = if content_para.list_style_position().is_inside() {
        // Marker sits in the first-line placeholder inside the text box.
        para_layout.x + (marker_column * 0.5)
    } else {
        // Marker lives in the outside gutter to the left of the text box.
        para_layout.x - (marker_column * 0.5)
    };

    let baseline = para_layout
        .paragraph
        .get_line_metrics()
        .first()
        .map(|line| para_layout.y + line.baseline as f32)
        .unwrap_or(para_layout.y + font_size);

    let center_y = baseline - (font_size * 0.35);

    match list_style {
        crate::shapes::ListStyle::Bullet => {
            paint.set_style(skia::PaintStyle::Fill);
            let radius = (font_size * 0.18).max(1.5);
            canvas.draw_circle((marker_center_x, center_y), radius, &paint);
        }
        crate::shapes::ListStyle::Numbered => {
            paint.set_style(skia::PaintStyle::Fill);
            let Some(label) = list_marker_label(text_content, para_idx) else {
                return;
            };
            let font = marker_font(para_layout, font_size);
            let width = font.measure_str(&label, Some(&paint)).0;
            let marker_x = if content_para.list_style_position().is_inside() {
                para_layout.x + ((marker_column - width) * 0.5).max(0.0)
            } else {
                para_layout.x - width - 4.0
            };
            canvas.draw_str(label, (marker_x, baseline), &font, &paint);
        }
        crate::shapes::ListStyle::None => {}
    }
}

fn paragraph_has_emoji(paragraph: &mut skia::textlayout::Paragraph) -> bool {
    let line_bounds: Vec<(usize, usize)> = paragraph
        .get_line_metrics()
        .iter()
        .map(|l| (l.start_index, l.end_index))
        .collect();
    line_bounds
        .into_iter()
        .any(|(start, end)| !line_emoji_ranges(paragraph, start, end).is_empty())
}

fn line_emoji_ranges(
    paragraph: &mut skia::textlayout::Paragraph,
    line_start: usize,
    line_end: usize,
) -> Vec<(usize, usize)> {
    let mut merged: Vec<(usize, usize)> = Vec::new();
    for idx in line_start..line_end {
        let font = paragraph.get_font_at_utf16_offset(idx);
        let normalized = font
            .typeface()
            .family_name()
            .to_lowercase()
            .replace(' ', "-");
        if !normalized.contains(DEFAULT_EMOJI_FONT) {
            continue;
        }

        match merged.last_mut() {
            Some(last) if last.1 == idx => last.1 = idx + 1,
            _ => merged.push((idx, idx + 1)),
        }
    }

    merged
}

/// Rasterizes color emoji runs as bitmap overlays. Skia's PDF backend can't
/// embed COLR/CBDT color glyphs, so each emoji is drawn to a raster surface and
/// blitted; `paragraph.paint()` already wrote placeholder glyphs (keeps text
/// selectable).
fn paint_emoji_overlay(canvas: &Canvas, para: &mut ParagraphLayout) {
    let line_metrics = para.paragraph.get_line_metrics();

    // Rasterize at TARGET_DPI relative to the emoji's on-page size (72 user
    // units = 1 inch), capped at MAX_RASTER_PX so a huge font can't allocate
    // an unbounded surface.
    const TARGET_DPI: f32 = 600.0;
    const PDF_POINTS_PER_INCH: f32 = 72.0;
    const MAX_RASTER_PX: f32 = 2048.0;

    let ctm = canvas.local_to_device_as_3x3();
    let sx = (ctm.scale_x().powi(2) + ctm.skew_y().powi(2)).sqrt();
    let sy = (ctm.skew_x().powi(2) + ctm.scale_y().powi(2)).sqrt();
    let output_scale = sx.max(sy).max(1.0);

    let line_bounds: Vec<(usize, usize)> = line_metrics
        .iter()
        .map(|l| (l.start_index, l.end_index))
        .collect();
    drop(line_metrics);

    for (line_start, line_end) in line_bounds {
        for (range_start, range_end) in line_emoji_ranges(&mut para.paragraph, line_start, line_end)
        {
            // Get the bounding rects for this (possibly merged) emoji run
            let rects = para.paragraph.get_rects_for_range(
                range_start..range_end,
                skia::textlayout::RectHeightStyle::Tight,
                skia::textlayout::RectWidthStyle::Tight,
            );

            for text_box in &rects {
                let r = &text_box.rect;
                let w = r.width();
                let h = r.height();
                if w <= 0.0 || h <= 0.0 {
                    continue;
                }

                // Render at TARGET_DPI relative to the emoji's final on-page
                // size, clamped so the surface stays within MAX_RASTER_PX.
                let mut raster_scale = output_scale * (TARGET_DPI / PDF_POINTS_PER_INCH);
                let max_dim = w.max(h) * raster_scale;
                if max_dim > MAX_RASTER_PX {
                    raster_scale *= MAX_RASTER_PX / max_dim;
                }
                let raster_w = (w * raster_scale).ceil() as i32;
                let raster_h = (h * raster_scale).ceil() as i32;

                let info = skia::ImageInfo::new_n32_premul((raster_w, raster_h), None);
                let Some(mut raster) = skia::surfaces::raster(&info, None, None) else {
                    continue;
                };

                let rc = raster.canvas();
                rc.clear(skia::Color::TRANSPARENT);
                rc.scale((raster_scale, raster_scale));
                // Translate so the emoji rect origin maps to (0,0)
                rc.translate((-r.left, -r.top));
                para.paragraph.paint(rc, (0.0, 0.0));

                let image = raster.image_snapshot();

                // Draw the rasterized emoji onto the PDF canvas at the
                // correct position (paragraph offset + emoji rect origin).
                let dest = skia::Rect::from_xywh(para.x + r.left, para.y + r.top, w, h);

                let sampling = skia::SamplingOptions::from(skia::CubicResampler::mitchell());
                canvas.draw_image_rect_with_sampling_options(
                    &image,
                    None,
                    dest,
                    sampling,
                    &Paint::default(),
                );
            }
        }
    }
}

fn draw_decoration_stroke(
    canvas: &Canvas,
    kind: StrokeKind,
    stroke_paint: &Paint,
    bar: skia::Rect,
) {
    if kind == StrokeKind::Center {
        canvas.draw_rect(bar, stroke_paint);
        return;
    }

    let blend = if kind == StrokeKind::Inner {
        skia::BlendMode::SrcIn
    } else {
        skia::BlendMode::SrcOut
    };

    canvas.save_layer(&SaveLayerRec::default());
    let mut mask_paint = Paint::default();
    mask_paint.set_color(skia::Color::BLACK);
    mask_paint.set_anti_alias(true);
    canvas.draw_rect(bar, &mask_paint);

    let mut blend_paint = Paint::default();
    blend_paint.set_blend_mode(blend);
    canvas.save_layer(&SaveLayerRec::default().paint(&blend_paint));
    canvas.draw_rect(bar, stroke_paint);
    canvas.restore();
    canvas.restore();
}

fn paint_emoji_opaque(
    canvas: &Canvas,
    emoji_para: &mut ParagraphLayout,
    deco_para: &ParagraphLayout,
    stroke_decos: &[(StrokeKind, Paint)],
) {
    let line_bounds: Vec<(usize, usize)> = emoji_para
        .paragraph
        .get_line_metrics()
        .iter()
        .map(|l| (l.start_index, l.end_index))
        .collect();

    let mut clip = skia::PathBuilder::new();
    let mut has_emoji = false;
    for (line_start, line_end) in line_bounds {
        for (range_start, range_end) in
            line_emoji_ranges(&mut emoji_para.paragraph, line_start, line_end)
        {
            let rects = emoji_para.paragraph.get_rects_for_range(
                range_start..range_end,
                skia::textlayout::RectHeightStyle::Tight,
                skia::textlayout::RectWidthStyle::Tight,
            );

            for text_box in &rects {
                let r = &text_box.rect;
                if r.width() <= 0.0 || r.height() <= 0.0 {
                    continue;
                }
                clip.add_rect(
                    skia::Rect::from_xywh(
                        emoji_para.x + r.left,
                        emoji_para.y + r.top,
                        r.width(),
                        r.height(),
                    ),
                    None,
                    None,
                );
                has_emoji = true;
            }
        }
    }

    if !has_emoji {
        return;
    }

    canvas.save();
    canvas.clip_path(&clip.detach(), skia::ClipOp::Intersect, true);
    emoji_para
        .paragraph
        .paint(canvas, (emoji_para.x, emoji_para.y));

    for deco in &deco_para.decorations {
        draw_text_decorations(
            canvas,
            &deco.text_style,
            Some(deco.y),
            deco.thickness,
            deco.left,
            deco.width,
        );
        let r = decoration_rect(deco.y, deco.thickness, deco.left, deco.width);
        for (kind, paint) in stroke_decos {
            draw_decoration_stroke(canvas, *kind, paint, r);
        }
    }
    canvas.restore();
}

pub fn render_emoji_overlay(
    render_state: &mut RenderState,
    shape: &Shape,
    emoji_builders: &mut [Vec<ParagraphBuilder>],
    deco_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: SurfaceId,
    blur: Option<&ImageFilter>,
) {
    let text_content = shape.get_text_content();
    let mut emoji_layout = calculate_text_layout_data(shape, text_content, emoji_builders, true);

    if !emoji_layout
        .paragraphs
        .iter_mut()
        .any(|para| paragraph_has_emoji(&mut para.paragraph))
    {
        return;
    }

    let selrect = shape.selrect();
    let mut stroke_decos: Vec<(StrokeKind, Paint)> = Vec::new();
    for stroke in shape.visible_strokes().rev() {
        let (paints, opacity) = get_text_stroke_paints(stroke, &selrect, false);
        for mut paint in paints {
            if let Some(opacity) = opacity {
                paint.set_alpha_f(opacity);
            }
            stroke_decos.push((stroke.kind, paint));
        }
    }

    let deco_layout = calculate_text_layout_data(shape, text_content, deco_builders, true);
    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);

    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        canvas.save_layer(&SaveLayerRec::default().paint(&blur_paint));
    }

    for (emoji_para, deco_para) in emoji_layout
        .paragraphs
        .iter_mut()
        .zip(deco_layout.paragraphs.iter())
    {
        paint_emoji_opaque(canvas, emoji_para, deco_para, &stroke_decos);
    }

    if blur.is_some() {
        canvas.restore();
    }
}

fn draw_text(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
    layer_opacity: Option<f32>,
    overlay_emoji: bool,
) {
    if let Some(opacity) = layer_opacity {
        let mut opacity_paint = Paint::default();
        opacity_paint.set_alpha_f(opacity);
        let layer_rec = SaveLayerRec::default().paint(&opacity_paint);
        canvas.save_layer(&layer_rec);
    } else {
        canvas.save_layer(&SaveLayerRec::default());
    }

    paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, overlay_emoji);
}

/// Renders a text stroke masked to the glyph shape.
///
/// `stroke_mask_blend` selects which side of the glyph the stroke keeps:
/// `SrcIn` for inner strokes (stroke clipped to the glyph), `SrcOut` for outer
/// strokes (stroke kept outside the glyph). When `fill_builders` is provided
/// (inner strokes) the fill is composited with `DstOver` *inside* the masked
/// layer so its anti-aliased edge aligns with the stroke — no seam at the glyph
/// boundary. Outer strokes pass `None` (fill is drawn separately).
#[allow(clippy::too_many_arguments)]
fn render_masked_stroke_on_canvas(
    canvas: &Canvas,
    shape: &Shape,
    mask_builders: &mut [Vec<ParagraphBuilder>],
    stroke_builders: &mut [Vec<ParagraphBuilder>],
    fill_builders: Option<&mut [Vec<ParagraphBuilder>]>,
    stroke_mask_blend: skia::BlendMode,
    blur: Option<&ImageFilter>,
    layer_opacity: Option<f32>,
) {
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

    canvas.save_layer(&SaveLayerRec::default());

    canvas.save_layer(&SaveLayerRec::default());

    paint_text(canvas, shape, mask_builders);

    let mut stroke_paint = Paint::default();
    stroke_paint.set_blend_mode(stroke_mask_blend);
    canvas.save_layer(&SaveLayerRec::default().paint(&stroke_paint));

    paint_text(canvas, shape, stroke_builders);

    // Fill with DstOver behind the stroke, inside the masked layer so the fill's
    // anti-aliased edge aligns with the stroke (no seam at the glyph edge).
    // Outer strokes have no fill here (`None`).
    if let Some(fill_builders) = fill_builders {
        let mut dst_over_paint = Paint::default();
        dst_over_paint.set_blend_mode(skia::BlendMode::DstOver);
        canvas.save_layer(&SaveLayerRec::default().paint(&dst_over_paint));

        paint_text(canvas, shape, fill_builders);

        canvas.restore(); // DstOver layer
    }

    canvas.restore(); // SrcIn / SrcOut layer
    canvas.restore(); // mask group layer
    canvas.restore(); // outer layer

    if layer_opacity.is_some() {
        canvas.restore();
    }

    if blur.is_some() {
        canvas.restore();
    }
}

#[allow(clippy::too_many_arguments)]
fn render_masked_stroke(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    mask_builders: &mut [Vec<ParagraphBuilder>],
    stroke_builders: &mut [Vec<ParagraphBuilder>],
    mut fill_builders: Option<&mut [Vec<ParagraphBuilder>]>,
    stroke_mask_blend: skia::BlendMode,
    surface_id: Option<SurfaceId>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    layer_opacity: Option<f32>,
) -> Result<()> {
    if let Some(render_state) = render_state {
        let target_surface = surface_id.unwrap_or(SurfaceId::Fills);

        if let Some(blur_filter) = blur {
            let mut text_bounds = shape
                .get_text_content()
                .calculate_bounds(shape, false)
                .to_rect();
            if stroke_bounds_outset > 0.0 {
                text_bounds.inset((-stroke_bounds_outset, -stroke_bounds_outset));
            }
            let bounds = blur_filter.compute_fast_bounds(text_bounds);
            if bounds.is_finite() && bounds.width() > 0.0 && bounds.height() > 0.0 {
                let blur_filter_clone = blur_filter.clone();
                let fill_builders = &mut fill_builders;
                if filters::render_with_filter_surface(
                    render_state,
                    bounds,
                    target_surface,
                    |state, temp_surface| {
                        let temp_canvas = state.surfaces.canvas(temp_surface);
                        render_masked_stroke_on_canvas(
                            temp_canvas,
                            shape,
                            mask_builders,
                            stroke_builders,
                            fill_builders.as_deref_mut(),
                            stroke_mask_blend,
                            Some(&blur_filter_clone),
                            layer_opacity,
                        );
                        Ok(())
                    },
                )? {
                    return Ok(());
                }
            }
        }

        let canvas = render_state.surfaces.canvas_and_mark_dirty(target_surface);
        render_masked_stroke_on_canvas(
            canvas,
            shape,
            mask_builders,
            stroke_builders,
            fill_builders.as_deref_mut(),
            stroke_mask_blend,
            blur,
            layer_opacity,
        );
        return Ok(());
    }

    if let Some(canvas) = canvas {
        render_masked_stroke_on_canvas(
            canvas,
            shape,
            mask_builders,
            stroke_builders,
            fill_builders,
            stroke_mask_blend,
            blur,
            layer_opacity,
        );
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn render_inner_stroke(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    stroke_builders: &mut [Vec<ParagraphBuilder>],
    fill_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    layer_opacity: Option<f32>,
) -> Result<()> {
    let mut mask_builders = shape.get_text_content().paragraph_builder_group_opaque();
    render_masked_stroke(
        render_state,
        canvas,
        shape,
        &mut mask_builders,
        stroke_builders,
        Some(fill_builders),
        skia::BlendMode::SrcIn,
        surface_id,
        blur,
        stroke_bounds_outset,
        layer_opacity,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn render_outer_stroke(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    stroke_builders: &mut [Vec<ParagraphBuilder>],
    surface_id: Option<SurfaceId>,
    blur: Option<&ImageFilter>,
    stroke_bounds_outset: f32,
    layer_opacity: Option<f32>,
) -> Result<()> {
    let mut mask_builders = shape.get_text_content().paragraph_builder_group_opaque();
    render_masked_stroke(
        render_state,
        canvas,
        shape,
        &mut mask_builders,
        stroke_builders,
        None,
        skia::BlendMode::SrcOut,
        surface_id,
        blur,
        stroke_bounds_outset,
        layer_opacity,
    )
}

fn decoration_rect(y: f32, thickness: f32, text_left: f32, text_width: f32) -> skia_safe::Rect {
    skia_safe::Rect::new(
        text_left,
        y - thickness / 2.0,
        text_left + text_width,
        y + thickness / 2.0,
    )
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
        let r = decoration_rect(y, thickness, text_left, text_width);
        let mut decoration_paint = text_style.foreground();
        decoration_paint.set_anti_alias(true);
        canvas.draw_rect(r, &decoration_paint);
    }
}

pub fn calculate_decoration_metrics(
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

/// Loose index overlap between a span and a line, with list-placeholder end fix.
pub fn span_overlaps_line(
    skia_start: usize,
    skia_end: usize,
    line_start: usize,
    line_end: usize,
    list_index_offset: usize,
) -> bool {
    let line_end = line_end.saturating_add(list_index_offset.min(1));
    skia_start < line_end && skia_end > line_start
}

/// Horizontal bounds for a styled run on one line.
/// Filters glyph rects by vertical overlap so list-marker placeholders do not
/// shrink underline/strikethrough at the line edges.
pub fn segment_advance_bounds_for_line(
    paragraph: &skia::textlayout::Paragraph,
    seg_start: usize,
    seg_end: usize,
    line_left: f32,
    line_top: f32,
    line_bottom: f32,
) -> Option<(f32, f32)> {
    if seg_start >= seg_end {
        return None;
    }

    let rects = paragraph.get_rects_for_range(
        seg_start..seg_end,
        skia::textlayout::RectHeightStyle::Tight,
        skia::textlayout::RectWidthStyle::Tight,
    );

    if rects.is_empty() {
        return None;
    }

    const Y_EPS: f32 = 0.5;
    let mut min_left: Option<f32> = None;
    let mut max_right: Option<f32> = None;

    for text_box in rects {
        let r = text_box.rect;
        if r.bottom() <= line_top - Y_EPS || r.top() >= line_bottom + Y_EPS {
            continue;
        }
        min_left = Some(min_left.map_or(r.left, |v| v.min(r.left)));
        max_right = Some(max_right.map_or(r.right(), |v| v.max(r.right())));
    }

    let (left, right) = (min_left?, max_right?);
    let width = (right - left).max(0.0);
    if width <= 0.0 {
        return None;
    }
    Some((width, left - line_left))
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

#[cfg(test)]
mod list_marker_tests {
    use super::*;

    #[test]
    fn lower_alpha_sequence() {
        assert_eq!(number_to_lower_alpha(1), "a");
        assert_eq!(number_to_lower_alpha(2), "b");
        assert_eq!(number_to_lower_alpha(26), "z");
        assert_eq!(number_to_lower_alpha(27), "aa");
    }

    #[test]
    fn lower_roman_sequence() {
        assert_eq!(number_to_lower_roman(1), "i");
        assert_eq!(number_to_lower_roman(4), "iv");
        assert_eq!(number_to_lower_roman(9), "ix");
        assert_eq!(number_to_lower_roman(14), "xiv");
    }

    #[test]
    fn numbered_marker_cycles_by_indent() {
        assert_eq!(numbered_marker_label(1, 0), "1.");
        assert_eq!(numbered_marker_label(2, 1), "b.");
        assert_eq!(numbered_marker_label(3, 2), "iii.");
        assert_eq!(numbered_marker_label(1, 3), "1.");
        assert_eq!(numbered_marker_label(1, 4), "a.");
    }
}
