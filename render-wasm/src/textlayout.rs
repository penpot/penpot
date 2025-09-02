use skia_safe::{self as skia, textlayout::ParagraphBuilder, ImageFilter, MaskFilter, Paint, Rect};

use crate::{
    render::filters::compose_filters,
    shapes::{merge_fills, set_paint_fill, Stroke, StrokeKind, TextContent},
    utils::{get_fallback_fonts, get_font_collection},
};

pub fn auto_width(paragraphs: &mut [Vec<ParagraphBuilder>], width: f32) -> f32 {
    let built_paragraphs = get_built_paragraphs(paragraphs, width);

    built_paragraphs
        .iter()
        .flatten()
        .fold(0.0, |auto_width, p| {
            f32::max(p.max_intrinsic_width(), auto_width)
        })
}

pub fn auto_height(paragraphs: &mut [Vec<ParagraphBuilder>], width: f32) -> f32 {
    paragraphs.iter_mut().fold(0.0, |auto_height, p| {
        p.iter_mut().fold(auto_height, |auto_height, paragraph| {
            let mut paragraph = paragraph.build();
            paragraph.layout(width);
            auto_height + paragraph.height()
        })
    })
}

pub fn build_paragraphs_with_width(
    paragraphs: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> Vec<Vec<skia_safe::textlayout::Paragraph>> {
    paragraphs
        .iter_mut()
        .map(|builders| {
            builders
                .iter_mut()
                .map(|builder| {
                    let mut paragraph = builder.build();
                    // For auto-width, always layout with infinite width first to get intrinsic width
                    paragraph.layout(f32::MAX);
                    let intrinsic_width = paragraph.max_intrinsic_width().ceil();
                    // Use the larger of the requested width or intrinsic width to prevent line breaks
                    let final_width = f32::max(width, intrinsic_width);
                    paragraph.layout(final_width);
                    paragraph
                })
                .collect()
        })
        .collect()
}

type ParagraphBuilderGroup = Vec<ParagraphBuilder>;

pub fn paragraph_builder_group_from_text(
    text_content: &TextContent,
    blur: Option<&ImageFilter>,
    blur_mask: Option<&MaskFilter>,
    shadow: Option<&Paint>,
) -> Vec<ParagraphBuilderGroup> {
    let fonts = get_font_collection();
    let fallback_fonts = get_fallback_fonts();
    let mut paragraph_group = Vec::new();

    for paragraph in text_content.paragraphs() {
        let paragraph_style = paragraph.paragraph_to_style();
        let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
        for leaf in paragraph.children() {
            let text_style = leaf.to_style(
                &text_content.bounds(),
                fallback_fonts,
                blur,
                blur_mask,
                shadow,
            );
            let text = leaf.apply_text_transform();
            builder.push_style(&text_style);
            builder.add_text(&text);
        }
        paragraph_group.push(vec![builder]);
    }

    paragraph_group
}

pub fn stroke_paragraph_builder_group_from_text(
    text_content: &TextContent,
    stroke: &Stroke,
    bounds: &Rect,
    blur: Option<&ImageFilter>,
    blur_mask: Option<&MaskFilter>,
    shadow: Option<&Paint>,
    count_inner_strokes: usize,
) -> Vec<ParagraphBuilderGroup> {
    let fallback_fonts = get_fallback_fonts();
    let fonts = get_font_collection();
    let mut paragraph_group = Vec::new();

    for paragraph in text_content.paragraphs() {
        let mut stroke_paragraphs_map: std::collections::HashMap<usize, ParagraphBuilder> =
            std::collections::HashMap::new();

        for leaf in paragraph.children().iter() {
            let mut text_paint = merge_fills(leaf.fills(), *bounds);
            if let Some(blur_mask) = blur_mask {
                text_paint.set_mask_filter(blur_mask.clone());
            }

            let stroke_paints = if shadow.is_some() {
                get_text_stroke_paints_with_shadows(
                    stroke,
                    blur,
                    blur_mask,
                    shadow,
                    leaf.is_transparent(),
                )
            } else {
                get_text_stroke_paints(
                    stroke,
                    bounds,
                    &text_paint,
                    blur,
                    blur_mask,
                    count_inner_strokes,
                )
            };

            let text: String = leaf.apply_text_transform();

            for (paint_idx, stroke_paint) in stroke_paints.iter().enumerate() {
                let builder = stroke_paragraphs_map.entry(paint_idx).or_insert_with(|| {
                    let paragraph_style = paragraph.paragraph_to_style();
                    ParagraphBuilder::new(&paragraph_style, fonts)
                });
                let stroke_paint = stroke_paint.clone();
                let stroke_style =
                    leaf.to_stroke_style(&stroke_paint, fallback_fonts, blur, blur_mask, None);
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

fn get_built_paragraphs(
    paragraphs: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> Vec<Vec<skia_safe::textlayout::Paragraph>> {
    build_paragraphs_with_width(paragraphs, width)
}

fn get_text_stroke_paints_with_shadows(
    stroke: &Stroke,
    blur: Option<&ImageFilter>,
    blur_mask: Option<&MaskFilter>,
    shadow: Option<&Paint>,
    is_transparent: bool,
) -> Vec<Paint> {
    let mut paints = Vec::new();

    match stroke.kind {
        StrokeKind::Inner => {
            let mut paint = Paint::default();
            paint.set_style(skia::PaintStyle::Fill);
            paint.set_anti_alias(true);

            if let Some(blur) = blur {
                paint.set_image_filter(blur.clone());
            }

            if let Some(shadow) = shadow {
                paint.set_image_filter(shadow.image_filter());
            }

            paints.push(paint.clone());

            if is_transparent {
                let image_filter = skia_safe::image_filters::erode(
                    (stroke.width, stroke.width),
                    paint.image_filter(),
                    None,
                );
                paint.set_image_filter(image_filter);
                paint.set_blend_mode(skia::BlendMode::DstOut);
                paints.push(paint.clone());
            }
        }
        StrokeKind::Center => {
            let mut paint = skia_safe::Paint::default();
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);

            if let Some(blur) = blur {
                paint.set_image_filter(blur.clone());
            }

            if let Some(shadow) = shadow {
                paint.set_image_filter(shadow.image_filter());
            }

            if is_transparent {
                paint.set_style(skia::PaintStyle::Stroke);
            } else {
                paint.set_style(skia::PaintStyle::StrokeAndFill);
            }

            paints.push(paint);
        }
        StrokeKind::Outer => {
            let mut paint = skia_safe::Paint::default();
            paint.set_style(skia::PaintStyle::StrokeAndFill);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);

            if let Some(blur_mask) = blur_mask {
                paint.set_mask_filter(blur_mask.clone());
            }

            if let Some(shadow) = shadow {
                paint.set_image_filter(shadow.image_filter());
            }

            paints.push(paint.clone());

            if is_transparent {
                let image_filter = skia_safe::image_filters::erode(
                    (stroke.width, stroke.width),
                    paint.image_filter(),
                    None,
                );
                paint.set_image_filter(image_filter);
                paint.set_blend_mode(skia::BlendMode::DstOut);
                paints.push(paint.clone());
            }
        }
    }
    paints
}

fn get_text_stroke_paints(
    stroke: &Stroke,
    bounds: &Rect,
    text_paint: &Paint,
    blur: Option<&ImageFilter>,
    blur_mask: Option<&MaskFilter>,
    count_inner_strokes: usize,
) -> Vec<Paint> {
    let mut paints = Vec::new();

    match stroke.kind {
        StrokeKind::Inner => {
            let shader = text_paint.shader();
            let mut is_opaque = true;

            if shader.is_some() {
                is_opaque = shader.unwrap().is_opaque();
            }

            if is_opaque && count_inner_strokes == 1 {
                let mut paint = text_paint.clone();
                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(true);
                if let Some(blur) = blur {
                    paint.set_image_filter(blur.clone());
                }
                paints.push(paint);
                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_blend_mode(skia::BlendMode::SrcIn);
                paint.set_anti_alias(true);
                paint.set_stroke_width(stroke.width * 2.0);
                set_paint_fill(&mut paint, &stroke.fill, bounds);
                if let Some(blur) = blur {
                    paint.set_image_filter(blur.clone());
                }
                paints.push(paint);
            } else {
                let mut paint = text_paint.clone();
                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(false);
                set_paint_fill(&mut paint, &stroke.fill, bounds);
                paints.push(paint);

                let mut paint = skia::Paint::default();
                let image_filter =
                    skia_safe::image_filters::erode((stroke.width, stroke.width), None, None);

                let filter = compose_filters(blur, image_filter.as_ref());
                paint.set_image_filter(filter);
                paint.set_anti_alias(false);
                paint.set_blend_mode(skia::BlendMode::DstOut);
                paints.push(paint);
            }
        }
        StrokeKind::Center => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);

            set_paint_fill(&mut paint, &stroke.fill, bounds);
            if let Some(blur) = blur {
                paint.set_image_filter(blur.clone());
            }

            paints.push(paint);
        }
        StrokeKind::Outer => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_blend_mode(skia::BlendMode::DstOver);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);
            set_paint_fill(&mut paint, &stroke.fill, bounds);
            if let Some(blur_mask) = blur_mask {
                paint.set_mask_filter(blur_mask.clone());
            }
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
