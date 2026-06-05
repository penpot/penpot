//! GPU-free font-provisioning tests: `fonts_used_by_shape` enumerates exactly
//! the families a text tree uses, and `FontStore` provisions them with no GPU.

use super::*;
use crate::render::FontStore;
use crate::shapes::{
    Fill, FontFamily, FontStyle, GrowType, Paragraph, SolidColor, TextAlign, TextContent,
    TextDirection, TextSpan, Type,
};

const TTF: &[u8] = include_bytes!("fonts/sourcesanspro-regular.ttf");

fn text_span(family: FontFamily) -> TextSpan {
    TextSpan::new(
        "Hello".to_string(),
        family,
        24.0,
        1.2,
        0.0,
        None,
        None,
        TextDirection::LTR,
        family.weight() as i32,
        Uuid::nil(),
        vec![Fill::Solid(SolidColor(skia::Color::BLACK))],
    )
}

fn text_shape(state: &mut State, id: Uuid, families: &[FontFamily]) {
    let shape = state.shapes.add_shape(id);
    shape.selrect = skia::Rect::from_xywh(0.0, 0.0, 200.0, 50.0);

    let spans = families.iter().copied().map(text_span).collect();
    let paragraph = Paragraph::new(
        TextAlign::default(),
        TextDirection::LTR,
        None,
        None,
        1.2,
        0.0,
        spans,
    );
    let mut content =
        TextContent::new(skia::Rect::from_xywh(0.0, 0.0, 200.0, 50.0), GrowType::Fixed);
    content.add_paragraph(paragraph);
    shape.shape_type = Type::Text(content);
}

#[test]
fn enumerates_distinct_fonts_used_by_text() {
    let mut state = State::new();
    let id = Uuid::new_v4();
    let a = FontFamily::new(Uuid::new_v4(), 700, FontStyle::Italic);
    let b = FontFamily::new(Uuid::new_v4(), 400, FontStyle::Normal);

    // `a` appears twice; reported once, in first-seen order.
    text_shape(&mut state, id, &[a, b, a]);

    assert_eq!(state.fonts_used_by_shape(&id), vec![a, b]);
}

#[test]
fn non_text_shape_needs_no_fonts() {
    let mut state = State::new();
    let id = Uuid::new_v4();
    state.shapes.add_shape(id).selrect = skia::Rect::from_xywh(0.0, 0.0, 10.0, 10.0);

    assert!(state.fonts_used_by_shape(&id).is_empty());
}

#[test]
fn font_store_provisions_family_on_demand_without_gpu() {
    let family = FontFamily::new(Uuid::new_v4(), 700, FontStyle::Italic);
    let mut store = FontStore::try_new().expect("font store");

    assert!(!store.has_family(&family, false), "not present before upload");
    store.add(family, TTF, false, false).expect("provision font");
    assert!(store.has_family(&family, false), "present after upload");
}
