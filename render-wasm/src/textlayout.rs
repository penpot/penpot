use skia_safe::{
    textlayout::{FontCollection, ParagraphBuilder},
    Paint,
};

pub fn layout_paragraph_with_style(
    builder: &mut ParagraphBuilder,
    paint: &Paint,
    width: f32,
    fonts: &FontCollection,
) -> skia_safe::textlayout::Paragraph {
    let text = builder.get_text().to_string();
    let mut paragraph_builder = ParagraphBuilder::new(&builder.get_paragraph_style(), fonts);
    let mut text_style = builder.peek_style();

    text_style.set_foreground_paint(paint);
    paragraph_builder.reset();
    paragraph_builder.push_style(&text_style);
    paragraph_builder.add_text(&text);

    let mut skia_paragraph = paragraph_builder.build();
    skia_paragraph.layout(width);

    skia_paragraph
}

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

fn get_built_paragraphs(
    paragraphs: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> Vec<Vec<skia_safe::textlayout::Paragraph>> {
    build_paragraphs_with_width(paragraphs, width)
}
