// Fixtures for the vertical layout tests: real shaping with bundled test
// fonts. Glyph coverage does not matter for offsets/columns; tofu still
// shapes with real advances.

use skia_safe::{textlayout::TypefaceFontProvider, FontMgr};

use crate::math::Rect;
use crate::shapes::{
    FontFamily, FontStyle, GrowType, Paragraph, TextAlign, TextContent, TextDecoration,
    TextDirection, TextOrientation, TextSpan, WritingMode,
};
use crate::Uuid;

use super::layout::{layout_vertical, VerticalLayout};

pub(crate) const TEST_FONT: &[u8] = include_bytes!("../../fonts/sourcesanspro-regular.ttf");

/// Noto Sans JP subset with `vmtx`/`vhea`: 〱 advances 2em vertically, あ/く 1em.
pub(super) const VMTX_TEST_FONT: &[u8] = include_bytes!("../../fonts/notosansjp-vmtx-test.ttf");

/// Noto Sans JP subset with GSUB `vert` and GPOS `vpal` for 、。「」あく.
pub(crate) const VPAL_TEST_FONT: &[u8] = include_bytes!("../../fonts/notosansjp-vpal-test.ttf");

/// Font size of every test span.
pub(crate) const EM: f32 = 20.0;

/// Provider with `font` registered under the family every test span uses
/// (spans serialize their family as "uuid-weight-style").
pub(crate) fn provider(font: &[u8]) -> TypefaceFontProvider {
    let typeface = FontMgr::new()
        .new_from_data(skia_safe::Data::new_copy(font), None)
        .expect("failed to load test font");
    let mut provider = TypefaceFontProvider::new();
    let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
    provider.register_typeface(typeface, Some(family.as_str()));
    provider
}

/// `provider(font)` plus `fallback` registered as the "fallback" family.
pub(super) fn provider_with_fallback(font: &[u8], fallback: &[u8]) -> TypefaceFontProvider {
    let mut provider = provider(font);
    let typeface = FontMgr::new()
        .new_from_data(skia_safe::Data::new_copy(fallback), None)
        .expect("failed to load fallback font");
    provider.register_typeface(typeface, Some("fallback"));
    provider
}

pub(super) fn make_span(text: &str) -> TextSpan {
    TextSpan {
        text: text.to_string(),
        font_size: EM,
        line_height: 1.0,
        ..TextSpan::default()
    }
}

pub(super) fn vertical_paragraph(
    spans: Vec<TextSpan>,
    align: TextAlign,
    line_height: f32,
) -> Paragraph {
    let mut paragraph = Paragraph::new(
        align,
        TextDirection::LTR,
        None,
        None,
        line_height,
        0.0,
        spans,
    );
    paragraph.set_writing_mode(WritingMode::VerticalRl);
    paragraph
}

/// A 200px wide box `height` tall holding `paragraphs`.
pub(super) fn content_of(
    paragraphs: Vec<Paragraph>,
    height: f32,
    grow_type: GrowType,
) -> TextContent {
    crate::globals::design_init();
    let mut content = TextContent::new(Rect::from_xywh(0.0, 0.0, 200.0, height), grow_type);
    for paragraph in paragraphs {
        content.add_paragraph(paragraph);
    }
    content
}

/// One left-aligned paragraph holding `spans`.
pub(super) fn spans_content(spans: Vec<TextSpan>, height: f32) -> TextContent {
    content_of(
        vec![vertical_paragraph(spans, TextAlign::Left, 1.0)],
        height,
        GrowType::Fixed,
    )
}

/// One paragraph per text, each a single span.
pub(super) fn make_content(texts: &[&str], height: f32) -> TextContent {
    let paragraphs = texts
        .iter()
        .map(|text| vertical_paragraph(vec![make_span(text)], TextAlign::Left, 1.0))
        .collect();
    content_of(paragraphs, height, GrowType::Fixed)
}

/// One paragraph with a span per text.
pub(super) fn make_content_with_spans(texts: &[&str], height: f32) -> TextContent {
    spans_content(texts.iter().map(|text| make_span(text)).collect(), height)
}

pub(super) fn ruby_content(base: &str, ruby: &str, height: f32) -> TextContent {
    spans_content(
        vec![TextSpan {
            ruby: ruby.to_string(),
            ..make_span(base)
        }],
        height,
    )
}

pub(super) fn warichu_content(base: &str, height: f32) -> TextContent {
    spans_content(
        vec![TextSpan {
            warichu: true,
            text_orientation: TextOrientation::Upright,
            ..make_span(base)
        }],
        height,
    )
}

pub(super) fn spaced_content(text: &str, letter_spacing: f32) -> TextContent {
    spans_content(
        vec![TextSpan {
            letter_spacing,
            ..make_span(text)
        }],
        1000.0,
    )
}

pub(super) fn decorated_content(text: &str, decoration: TextDecoration) -> TextContent {
    spans_content(
        vec![TextSpan {
            text_decoration: Some(decoration),
            ..make_span(text)
        }],
        1000.0,
    )
}

pub(super) fn layout_with_fallback(
    provider: &TypefaceFontProvider,
    content: &TextContent,
    max_height: f32,
    fallback_families: &[String],
) -> VerticalLayout {
    layout_vertical(
        content,
        max_height,
        provider,
        FontMgr::from(provider.clone()),
        fallback_families,
        content.bounds(),
    )
}

pub(super) fn layout_with_height(
    provider: &TypefaceFontProvider,
    content: &TextContent,
    max_height: f32,
) -> VerticalLayout {
    layout_with_fallback(provider, content, max_height, &[])
}

/// Layout with `provider` and a 1000px wrap budget.
pub(super) fn layout_with(
    provider: &TypefaceFontProvider,
    content: &TextContent,
) -> VerticalLayout {
    layout_with_height(provider, content, 1000.0)
}

/// Layout with the Latin test face.
pub(super) fn layout_content(content: &TextContent, max_height: f32) -> VerticalLayout {
    layout_with_height(&provider(TEST_FONT), content, max_height)
}
