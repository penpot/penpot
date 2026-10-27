use super::{filters, RenderState, Shape, SurfaceId, DEFAULT_EMOJI_FONT};
use crate::{
    error::Result,
    math::{Point, Rect},
    shapes::{
        add_text_with_tabs, calculate_text_layout_data, set_paint_fill, vertical_align_offset,
        ListMarker, ListStyleType, Paragraph as TextParagraph, ParagraphBuilderGroup,
        ParagraphLayout, Stroke, StrokeKind, TextContent, TextDecorationSegment, TextDirection,
        LIST_MARKER_CENTER_FACTOR, LIST_MARKER_GAP_FACTOR, LIST_MARKER_MIN_RADIUS,
        LIST_MARKER_RADIUS_FACTOR,
    },
    utils::{get_fallback_fonts, get_font_collection},
};
use skia_safe::{
    self as skia,
    canvas::SaveLayerRec,
    textlayout::{ParagraphBuilder, StyleMetrics, TextDecoration},
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
                add_text_with_tabs(builder, &text, span.font_size);
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

/// Paint fill glyphs from `TextContent.layout` when the cache is valid.
///
/// Avoids rebuilding ParagraphBuilders and re-running Skia layout on every
/// paint. Only safe for the plain fill pass (no stroke/shadow-specific builders).
/// Returns `true` when painting was done from cache.
pub fn try_paint_from_layout_cache(
    render_state: Option<&mut RenderState>,
    canvas: Option<&Canvas>,
    shape: &Shape,
    surface_id: Option<SurfaceId>,
    layout_cache_rotation_only: bool,
) -> Result<bool> {
    let text_content = shape.get_text_content();
    let cache_usable = if layout_cache_rotation_only {
        text_content.layout_cache_versions_match()
    } else {
        text_content.has_usable_paint_layout(shape)
    };
    if !cache_usable {
        return Ok(false);
    }

    if let Some(render_state) = render_state {
        let target_surface = surface_id.unwrap_or(SurfaceId::Fills);
        let canvas = render_state.surfaces.canvas_and_mark_dirty(target_surface);
        paint_from_cached_layout(canvas, shape, text_content);
        return Ok(true);
    }

    if let Some(canvas) = canvas {
        paint_from_cached_layout(canvas, shape, text_content);
        return Ok(true);
    }

    Ok(false)
}

fn paint_from_cached_layout(canvas: &Canvas, shape: &Shape, text_content: &TextContent) {
    let selrect = shape.selrect();
    // Absolute image/gradient shaders were baked at `layout_paint_origin`. Paint
    // glyphs in that space and translate the canvas so both track selrect moves
    // without rebuilding Skia paragraphs.
    let anchor = text_content.cached_layout_paint_anchor(&selrect);
    let offset = text_content.cached_layout_paint_offset(&selrect);
    let needs_translate = offset.x.abs() > f32::EPSILON || offset.y.abs() > f32::EPSILON;
    if needs_translate {
        canvas.save();
        canvas.translate((offset.x, offset.y));
    }

    let x = anchor.x;
    let base_y = anchor.y;
    let layout_width = text_content.get_width(selrect.width());
    let paragraphs = &text_content.layout.paragraphs;
    let draw_decorations = text_content.has_text_decorations();

    let total_text_height: f32 = paragraphs
        .iter()
        .filter_map(|group| group.first())
        .map(|p| p.height())
        .sum();
    let vertical_offset =
        vertical_align_offset(selrect.height(), total_text_height, shape.vertical_align());

    let top = base_y + vertical_offset;
    let mut y_accum = top;
    for (index, group) in paragraphs.iter().enumerate() {
        let Some(paragraph) = group.first() else {
            continue;
        };
        paragraph.paint(canvas, (x, y_accum));
        if draw_decorations {
            if let Some(text_paragraph) = text_content.paragraphs().get(index) {
                for deco in decoration_segments(paragraph, text_paragraph, x, y_accum) {
                    draw_decoration_segment(canvas, &deco);
                }
            }
        }
        y_accum += paragraph.height();
    }

    for marker in list_markers(
        paragraphs.iter().map(|group| group.first()),
        text_content.paragraphs(),
        x,
        top,
        layout_width,
    ) {
        draw_list_marker(canvas, &marker);
    }

    if needs_translate {
        canvas.restore();
    }
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
    let layer_bounds = shape.layer_bounds();

    // Layer stack is managed here (blur / shadow / inset). `draw_text` is
    // self-contained and only opens a layer when stroke-group opacity needs it.
    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        let blur_layer = SaveLayerRec::default()
            .bounds(&layer_bounds)
            .paint(&blur_paint);
        canvas.save_layer(&blur_layer);
    }

    if let Some(shadow_paint) = shadow {
        let layer_rec = SaveLayerRec::default()
            .bounds(&layer_bounds)
            .paint(shadow_paint);
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
            let layer_rec = SaveLayerRec::default()
                .bounds(&layer_bounds)
                .paint(&layer_paint);
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
}

/// Paints pre-built paragraph groups (SVG export path for selective fill layers).
pub fn paint_text_paragraphs(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
) {
    paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, false);
}

/// Lays out and paints paragraph builders without any layer management.
fn paint_text(
    canvas: &Canvas,
    shape: &Shape,
    paragraph_builder_groups: &mut [Vec<ParagraphBuilder>],
) {
    paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, false);
}

/// Alpha mask for background blur coverage
pub fn paint_text_mask(canvas: &Canvas, shape: &Shape) {
    let text_content = shape.get_text_content();
    let mut mask_builders = text_content.paragraph_builder_group_opaque();
    paint_text(canvas, shape, &mut mask_builders);

    // take strokes into account for the bblur mask
    for stroke in shape.visible_strokes() {
        let (mut stroke_builders, _) = stroke_paragraph_builder_group_from_text(
            text_content,
            stroke,
            &shape.selrect(),
            Some(true),
        );
        paint_text(canvas, shape, &mut stroke_builders);
    }
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

    for para in &mut layout_info.paragraphs {
        para.paragraph.paint(canvas, (para.x, para.y));

        if overlay_emoji {
            paint_emoji_overlay(canvas, para);
        }

        if let Some(marker) = &para.marker {
            draw_list_marker(canvas, marker);
        }

        for deco in &para.decorations {
            draw_decoration_segment(canvas, deco);
        }
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

    let outset = stroke_paint.stroke_width().max(0.0);
    let layer_bounds = bar.with_outset((outset, outset));

    canvas.save_layer(&SaveLayerRec::default().bounds(&layer_bounds));
    let mut mask_paint = Paint::default();
    mask_paint.set_color(skia::Color::BLACK);
    mask_paint.set_anti_alias(true);
    canvas.draw_rect(bar, &mask_paint);

    let mut blend_paint = Paint::default();
    blend_paint.set_blend_mode(blend);
    canvas.save_layer(
        &SaveLayerRec::default()
            .bounds(&layer_bounds)
            .paint(&blend_paint),
    );
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
        draw_decoration_segment(canvas, deco);
        for (kind, paint) in stroke_decos {
            draw_decoration_stroke(canvas, *kind, paint, deco.rect());
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
        let layer_bounds = shape.layer_bounds();
        canvas.save_layer(
            &SaveLayerRec::default()
                .bounds(&layer_bounds)
                .paint(&blur_paint),
        );
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
    // Multi-style spans are already encoded in each ParagraphBuilder's
    // TextStyles; paragraph.paint handles them without an isolation layer.
    // Only open a save_layer when stroke-group opacity must composite as one.
    if let Some(opacity) = layer_opacity {
        let layer_bounds = shape.layer_bounds();
        let mut opacity_paint = Paint::default();
        opacity_paint.set_alpha_f(opacity);
        let layer_rec = SaveLayerRec::default()
            .bounds(&layer_bounds)
            .paint(&opacity_paint);
        canvas.save_layer(&layer_rec);
        paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, overlay_emoji);
        canvas.restore();
    } else {
        paint_text_with_emoji_overlay(canvas, shape, paragraph_builder_groups, overlay_emoji);
    }
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
    let layer_bounds = shape.layer_bounds();

    if let Some(blur_filter) = blur {
        let mut blur_paint = Paint::default();
        blur_paint.set_image_filter(blur_filter.clone());
        canvas.save_layer(
            &SaveLayerRec::default()
                .bounds(&layer_bounds)
                .paint(&blur_paint),
        );
    }

    if let Some(opacity) = layer_opacity {
        let mut opacity_paint = Paint::default();
        opacity_paint.set_alpha_f(opacity);
        canvas.save_layer(
            &SaveLayerRec::default()
                .bounds(&layer_bounds)
                .paint(&opacity_paint),
        );
    }

    canvas.save_layer(&SaveLayerRec::default().bounds(&layer_bounds));

    canvas.save_layer(&SaveLayerRec::default().bounds(&layer_bounds));

    paint_text(canvas, shape, mask_builders);

    let mut stroke_paint = Paint::default();
    stroke_paint.set_blend_mode(stroke_mask_blend);
    canvas.save_layer(
        &SaveLayerRec::default()
            .bounds(&layer_bounds)
            .paint(&stroke_paint),
    );

    paint_text(canvas, shape, stroke_builders);

    // Fill with DstOver behind the stroke, inside the masked layer so the fill's
    // anti-aliased edge aligns with the stroke (no seam at the glyph edge).
    // Outer strokes have no fill here (`None`).
    if let Some(fill_builders) = fill_builders {
        let mut dst_over_paint = Paint::default();
        dst_over_paint.set_blend_mode(skia::BlendMode::DstOver);
        canvas.save_layer(
            &SaveLayerRec::default()
                .bounds(&layer_bounds)
                .paint(&dst_over_paint),
        );

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

fn draw_list_marker(canvas: &Canvas, marker: &ListMarker) {
    canvas.draw_circle(marker.center, marker.radius, &marker.paint);
}

/// Bullets of paragraphs laid out one under another, from `top` down. `None`
/// stands for a paragraph with no laid-out Skia paragraph; it keeps the
/// paragraphs in step with `text_paragraphs`.
pub fn list_markers<'a>(
    skia_paragraphs: impl IntoIterator<Item = Option<&'a skia::textlayout::Paragraph>>,
    text_paragraphs: &[TextParagraph],
    x: f32,
    top: f32,
    layout_width: f32,
) -> Vec<ListMarker> {
    let mut markers: Vec<ListMarker> = Vec::new();
    let mut y = top;
    for (skia_paragraph, text_paragraph) in skia_paragraphs.into_iter().zip(text_paragraphs) {
        let Some(skia_paragraph) = skia_paragraph else {
            continue;
        };
        if let Some(marker) = list_marker(
            skia_paragraph,
            text_paragraph,
            x,
            y,
            layout_width,
            markers.last(),
        ) {
            markers.push(marker);
        }
        y += skia_paragraph.height();
    }
    markers
}

/// The bullet of a list paragraph, in shape coordinates. It hangs before the
/// text box's start edge, level with the first line, a gap away that grows
/// with the font size.
///
/// An empty paragraph has no style run, so it takes the paint of `previous`
/// (the last list item painted in the same pass) and its own span's size.
pub fn list_marker(
    skia_paragraph: &skia::textlayout::Paragraph,
    text_paragraph: &TextParagraph,
    x: f32,
    y_accum: f32,
    layout_width: f32,
    previous: Option<&ListMarker>,
) -> Option<ListMarker> {
    if text_paragraph.list_style_type() == ListStyleType::None {
        return None;
    }

    let line_metrics = skia_paragraph.get_line_metrics();
    let line = line_metrics.first()?;
    let style_metrics = line.get_style_metrics(line.start_index..line.end_index);
    let (paint, font_size) = match style_metrics.first() {
        Some((_, metrics)) => {
            let mut paint = metrics.text_style.foreground();
            paint.set_anti_alias(true);
            (paint, metrics.text_style.font_size())
        }
        None => {
            let font_size = text_paragraph.children().first()?.font_size;
            (previous?.paint.clone(), font_size)
        }
    };

    let radius = (font_size * LIST_MARKER_RADIUS_FACTOR).max(LIST_MARKER_MIN_RADIUS);
    let offset = radius + font_size * LIST_MARKER_GAP_FACTOR;
    let center_x = if text_paragraph.text_direction() == TextDirection::RTL {
        x + layout_width + offset
    } else {
        x - offset
    };
    let baseline = y_accum + line.baseline as f32;

    Some(ListMarker {
        paint,
        center: Point::new(center_x, baseline - font_size * LIST_MARKER_CENTER_FACTOR),
        radius,
    })
}

fn draw_decoration_segment(canvas: &Canvas, deco: &TextDecorationSegment) {
    let mut decoration_paint = deco.text_style.foreground();
    decoration_paint.set_anti_alias(true);
    canvas.draw_rect(deco.rect(), &decoration_paint);
}

/// One decorated span clipped to a line: UTF-16 range, decoration and the
/// Skia style run it falls in (paint + font metrics).
type LineDecoration<'a> = (usize, usize, TextDecoration, &'a StyleMetrics<'a>);

/// UTF-16 ranges of the spans that ask for a decoration we draw.
fn decorated_span_ranges(text_paragraph: &TextParagraph) -> Vec<(usize, usize, TextDecoration)> {
    let mut ranges = Vec::new();
    let mut offset = 0;
    for span in text_paragraph.children() {
        let len = span.apply_text_transform().encode_utf16().count();
        match span.text_decoration {
            Some(kind)
                if kind == TextDecoration::UNDERLINE || kind == TextDecoration::LINE_THROUGH =>
            {
                ranges.push((offset, offset + len, kind))
            }
            _ => {}
        }
        offset += len;
    }
    ranges
}

/// Style run covering `offset`; runs are keyed by their start index.
fn style_metric_at<'a>(
    style_metrics: &[(usize, &'a StyleMetrics<'a>)],
    offset: usize,
) -> Option<&'a StyleMetrics<'a>> {
    style_metrics
        .iter()
        .rev()
        .find(|(start, _)| *start <= offset)
        .map(|(_, metrics)| *metrics)
}

/// Decoration bars for one laid out paragraph, in shape coordinates.
///
/// Segmented by the model's spans, so which spans get a bar never depends on
/// how Skia grouped the line into style runs; the runs only supply the paint
/// and font metrics covering each segment.
pub fn decoration_segments(
    skia_paragraph: &skia::textlayout::Paragraph,
    text_paragraph: &TextParagraph,
    x: f32,
    y_accum: f32,
) -> Vec<TextDecorationSegment> {
    let decorated = decorated_span_ranges(text_paragraph);
    if decorated.is_empty() {
        return Vec::new();
    }

    let mut segments = Vec::new();
    for line in &skia_paragraph.get_line_metrics() {
        let style_metrics: Vec<_> = line
            .get_style_metrics(line.start_index..line.end_index)
            .into_iter()
            .collect();
        let line_baseline = y_accum + line.baseline as f32;

        let line_decorations: Vec<LineDecoration<'_>> = decorated
            .iter()
            .filter_map(|&(start, end, kind)| {
                let seg_start = start.max(line.start_index);
                let seg_end = end.min(line.end_index);
                if seg_start >= seg_end {
                    return None;
                }
                let metrics = style_metric_at(&style_metrics, seg_start)?;
                Some((seg_start, seg_end, kind, metrics))
            })
            .collect();

        let (max_underline_thickness, underline_y, max_strike_thickness, strike_y) =
            calculate_decoration_metrics(&line_decorations, line_baseline);

        for (seg_start, seg_end, kind, metrics) in line_decorations {
            let rects = skia_paragraph.get_rects_for_range(
                seg_start..seg_end,
                skia::textlayout::RectHeightStyle::Tight,
                skia::textlayout::RectWidthStyle::Tight,
            );
            let (width, x_offset) = match rects.first() {
                Some(first) => {
                    let total_width: f32 = rects.iter().map(|r| r.rect.width()).sum();
                    (total_width, first.rect.left - line.left as f32)
                }
                None => (0.0, 0.0),
            };
            let (y, thickness) = if kind == TextDecoration::LINE_THROUGH {
                (strike_y, max_strike_thickness)
            } else {
                (underline_y, max_underline_thickness)
            };
            segments.push(TextDecorationSegment {
                kind,
                text_style: (*metrics.text_style).clone(),
                y: y.unwrap_or(line_baseline),
                thickness,
                left: x + line.left as f32 + x_offset,
                width,
            });
        }
    }

    segments
}

fn calculate_decoration_metrics(
    line_decorations: &[LineDecoration<'_>],
    line_baseline: f32,
) -> (f32, Option<f32>, f32, Option<f32>) {
    let mut max_underline_thickness: f32 = 0.0;
    let mut underline_y = None;
    let mut max_strike_thickness: f32 = 0.0;
    let mut strike_y = None;
    for (_seg_start, _seg_end, kind, style_metric) in line_decorations.iter() {
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

        if *kind == TextDecoration::UNDERLINE {
            // Same gap from baseline to underline as in Chromium
            // (see https://source.chromium.org/chromium/chromium/src/+/main:ui/gfx/render_text.cc
            let gap_scaling = raw_font_size * 1.0 / 9.0;
            let y = line_baseline + gap_scaling;

            max_underline_thickness = max_underline_thickness.max(thickness);
            underline_y = Some(y);
        }
        if *kind == TextDecoration::LINE_THROUGH {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::globals::TestRenderResourcesGuard;
    use crate::render::RenderResources;
    use crate::shapes::{
        Fill, FontFamily, FontStyle, GrowType, ListStyleType, SolidColor, TextAlign, TextDirection,
        TextSpan, TextTransform, Type,
    };
    use crate::uuid::Uuid;

    fn span(
        text: &str,
        decoration: Option<TextDecoration>,
        transform: Option<TextTransform>,
    ) -> TextSpan {
        TextSpan::new(
            text.to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            14.0,
            1.2,
            0.0,
            decoration,
            transform,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            vec![],
        )
    }

    fn paragraph(spans: Vec<TextSpan>) -> TextParagraph {
        TextParagraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            spans,
        )
    }

    #[test]
    fn decorated_ranges_follow_spans_not_paint_runs() {
        let para = paragraph(vec![
            span("plain ", None, None),
            span("under", Some(TextDecoration::UNDERLINE), None),
            span(" plain ", None, None),
            span("struck", Some(TextDecoration::LINE_THROUGH), None),
        ]);

        assert_eq!(
            decorated_span_ranges(&para),
            vec![
                (6, 11, TextDecoration::UNDERLINE),
                (18, 24, TextDecoration::LINE_THROUGH),
            ]
        );
    }

    #[test]
    fn decorated_ranges_are_utf16_offsets_of_the_transformed_text() {
        let para = paragraph(vec![
            span("🎉", None, None),
            span(
                "straße",
                Some(TextDecoration::UNDERLINE),
                Some(TextTransform::Uppercase),
            ),
            span("x", Some(TextDecoration::UNDERLINE), None),
        ]);

        // The emoji takes two UTF-16 units and `ß` uppercases to `SS`.
        assert_eq!(
            decorated_span_ranges(&para),
            vec![
                (2, 9, TextDecoration::UNDERLINE),
                (9, 10, TextDecoration::UNDERLINE),
            ]
        );
    }

    #[test]
    fn undecorated_paragraphs_have_no_ranges() {
        let para = paragraph(vec![
            span("plain", None, None),
            span("none", Some(TextDecoration::NO_DECORATION), None),
        ]);

        assert!(decorated_span_ranges(&para).is_empty());
    }

    fn sized_span(text: &str, font_size: f32, fills: Vec<Fill>) -> TextSpan {
        TextSpan::new(
            text.to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            font_size,
            1.2,
            0.0,
            None,
            None,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            fills,
        )
    }

    fn list_paragraph(
        text: &str,
        font_size: f32,
        direction: TextDirection,
        list_style_type: ListStyleType,
    ) -> TextParagraph {
        TextParagraph::new(
            TextAlign::Left,
            direction,
            None,
            None,
            1.2,
            0.0,
            vec![sized_span(text, font_size, vec![])],
        )
        .with_list_style_type(list_style_type)
    }

    fn filled_list_item(text: &str, font_size: f32, color: skia::Color) -> TextParagraph {
        TextParagraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![sized_span(
                text,
                font_size,
                vec![Fill::Solid(SolidColor(color))],
            )],
        )
        .with_list_style_type(ListStyleType::Disc)
    }

    /// A fixed text at (10, 20), `width` wide, laid out into paragraph layouts.
    fn layout_paragraphs(paragraphs: Vec<TextParagraph>, width: f32) -> Vec<ParagraphLayout> {
        let selrect = crate::math::Rect::from_xywh(10.0, 20.0, width, 200.0);
        let mut content = TextContent::new(selrect, GrowType::Fixed);
        for paragraph in paragraphs {
            content.add_paragraph(paragraph);
        }
        let mut shape = Shape::new(Uuid::nil());
        shape.set_shape_type(Type::Text(content.clone()));
        shape.set_selrect(10.0, 20.0, 10.0 + width, 220.0);

        let mut builders = content.paragraph_builder_group_from_text(None);
        calculate_text_layout_data(&shape, &content, &mut builders, true).paragraphs
    }

    fn first_baseline(layout: &ParagraphLayout) -> f32 {
        layout.y + layout.paragraph.get_line_metrics()[0].baseline as f32
    }

    #[test]
    fn list_marker_hangs_before_the_first_line() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "item",
                20.0,
                TextDirection::LTR,
                ListStyleType::Disc,
            )],
            300.0,
        );

        let marker = layouts[0]
            .marker
            .as_ref()
            .expect("disc paragraph has a marker");
        // Font size 20: radius 3.2, gap 6.6, center 6.4 above the baseline.
        assert!((marker.center.x - (10.0 - 9.8)).abs() < 0.01, "{marker:?}");
        assert!(
            (marker.center.y - (first_baseline(&layouts[0]) - 6.4)).abs() < 0.01,
            "{marker:?}"
        );
        assert!((marker.radius - 3.2).abs() < 0.01, "{marker:?}");
    }

    #[test]
    fn rtl_list_marker_hangs_after_the_text_box() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "item",
                20.0,
                TextDirection::RTL,
                ListStyleType::Disc,
            )],
            300.0,
        );

        let marker = layouts[0]
            .marker
            .as_ref()
            .expect("disc paragraph has a marker");
        assert!(
            (marker.center.x - (10.0 + 300.0 + 9.8)).abs() < 0.01,
            "{marker:?}"
        );
    }

    #[test]
    fn wrapped_list_paragraph_has_one_marker_on_its_first_line() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "one two three four five six",
                20.0,
                TextDirection::LTR,
                ListStyleType::Disc,
            )],
            60.0,
        );

        assert!(layouts[0].paragraph.get_line_metrics().len() > 1);
        let marker = layouts[0]
            .marker
            .as_ref()
            .expect("disc paragraph has a marker");
        assert!(
            (marker.center.y - (first_baseline(&layouts[0]) - 6.4)).abs() < 0.01,
            "{marker:?}"
        );
    }

    #[test]
    fn list_marker_keeps_a_gap_before_the_text_at_any_size() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        for font_size in [4.0, 14.0, 72.0, 300.0] {
            let layouts = layout_paragraphs(
                vec![list_paragraph(
                    "item",
                    font_size,
                    TextDirection::LTR,
                    ListStyleType::Disc,
                )],
                2000.0,
            );

            let marker = layouts[0]
                .marker
                .as_ref()
                .expect("disc paragraph has a marker");
            // The text starts at x = 10; the gap grows with the font size.
            assert!(
                marker.rect().right() <= 10.0 - font_size * 0.32,
                "font size {font_size}: {marker:?}"
            );
        }
    }

    /// The color painted at `point` when `paragraph` is painted through the
    /// cached layout of a 200x100 text at (40, 40).
    fn cached_paint_color_at(
        paragraph: TextParagraph,
        point: impl Fn(&ParagraphLayout) -> Point,
    ) -> skia::Color {
        let selrect = crate::math::Rect::from_xywh(40.0, 40.0, 200.0, 100.0);
        let mut content = TextContent::new(selrect, GrowType::Fixed);
        content.add_paragraph(paragraph);
        let mut shape = Shape::new(Uuid::nil());
        shape.set_shape_type(Type::Text(content));
        shape.set_selrect(40.0, 40.0, 240.0, 140.0);

        let content = shape.get_text_content().clone();
        let mut builders = content.paragraph_builder_group_from_text(None);
        let layouts = calculate_text_layout_data(&shape, &content, &mut builders, true);
        let at = point(&layouts.paragraphs[0]);

        let mut surface = skia::surfaces::raster_n32_premul((300, 200)).expect("raster surface");
        surface.canvas().clear(skia::Color::TRANSPARENT);
        let painted =
            try_paint_from_layout_cache(None, Some(surface.canvas()), &shape, None, false)
                .expect("cached paint");
        assert!(painted, "the layout cache should be usable");

        let info = skia::ImageInfo::new(
            (1, 1),
            skia::ColorType::RGBA8888,
            skia::AlphaType::Unpremul,
            None,
        );
        let mut px = [0u8; 4];
        assert!(surface.image_snapshot().read_pixels(
            &info,
            &mut px,
            4,
            (at.x as i32, at.y as i32),
            skia::image::CachingHint::Disallow
        ));
        skia::Color::from_argb(px[3], px[0], px[1], px[2])
    }

    #[test]
    fn cached_paint_draws_the_list_marker() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let color =
            cached_paint_color_at(filled_list_item("item", 30.0, skia::Color::RED), |layout| {
                layout
                    .marker
                    .as_ref()
                    .expect("disc paragraph has a marker")
                    .center
            });
        assert_eq!(color, skia::Color::RED);
    }

    #[test]
    fn cached_paint_draws_no_marker_for_plain_text() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        // Where a 30px bullet would be: radius 4.8 plus a 9.9 gap before x = 40.
        let paragraph = filled_list_item("item", 30.0, skia::Color::RED)
            .with_list_style_type(ListStyleType::None);
        let color = cached_paint_color_at(paragraph, |layout| {
            Point::new(40.0 - 14.7, first_baseline(layout) - 9.6)
        });
        assert_eq!(color.a(), 0, "{color:?}");
    }

    #[test]
    fn plain_paragraph_has_no_marker() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "item",
                20.0,
                TextDirection::LTR,
                ListStyleType::None,
            )],
            300.0,
        );

        assert!(layouts[0].marker.is_none());
    }

    #[test]
    fn small_text_marker_keeps_a_minimum_radius() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "item",
                4.0,
                TextDirection::LTR,
                ListStyleType::Disc,
            )],
            300.0,
        );

        let marker = layouts[0]
            .marker
            .as_ref()
            .expect("disc paragraph has a marker");
        assert!((marker.radius - 1.5).abs() < 0.01, "{marker:?}");
    }

    #[test]
    fn empty_list_paragraph_borrows_the_previous_item_paint() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![
                filled_list_item("item", 20.0, skia::Color::RED),
                filled_list_item("", 10.0, skia::Color::BLUE),
            ],
            300.0,
        );

        let empty = layouts[1].marker.as_ref().expect("empty item has a marker");
        // Its own size, the previous item's paint.
        assert!((empty.radius - 1.6).abs() < 0.01, "{empty:?}");
        assert_eq!(empty.paint.color(), skia::Color::RED);
    }

    #[test]
    fn empty_first_list_paragraph_has_no_marker() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let layouts = layout_paragraphs(
            vec![list_paragraph(
                "",
                20.0,
                TextDirection::LTR,
                ListStyleType::Disc,
            )],
            300.0,
        );

        assert!(layouts[0].marker.is_none());
    }
}
