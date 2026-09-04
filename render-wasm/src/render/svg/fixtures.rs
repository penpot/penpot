//! GPU-free scene builders and render helpers for SVG export tests.

use skia_safe as skia;

use crate::globals::TestRenderResourcesGuard;
use crate::render::{FontStore, RenderResources};
use crate::shapes::{
    Fill, FontFamily, FontStyle, Frame, Group, GrowType, Paragraph, Rect, SolidColor, TextAlign,
    TextContent, TextDirection, TextSpan, Type,
};
use crate::state::ShapesPool;
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;

use super::render_tree_to_svg;

/// Font URL referenced in exported SVG `@font-face` rules.
pub(super) const TEST_FONT_URL: &str = "fonts/sourcesanspro-regular.ttf";

fn register_test_font_urls(fonts: &mut FontStore) {
    let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
    fonts.set_source_url(&family.alias(), TEST_FONT_URL.to_string());
}

/// Deterministic UUID from a small integer, keeping snapshots stable.
pub(super) fn uid(n: u32) -> Uuid {
    uuid_from_u32_quartet(0, 0, 0, n)
}

/// Adds a solid-filled rectangle to the pool.
pub(super) fn add_solid_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    color: skia::Color,
) {
    add_rect_with_fills(
        pool,
        id,
        parent,
        (l, t, r, b),
        vec![Fill::Solid(SolidColor(color))],
    );
}

/// Adds a rectangle with the given fill stack (bottom → top).
pub(super) fn add_rect_with_fills(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    fills: Vec<Fill>,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(fills);
}

/// Adds a solid-filled frame (board) to the pool.
pub(super) fn add_frame(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    color: skia::Color,
    clip: bool,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Frame(Frame::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(color))]);
    shape.set_clip(clip);
}

/// Adds an empty (unmasked) group.
pub(super) fn add_group(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    children: &[Uuid],
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Group(Group { masked: false }));
    shape.set_selrect(l, t, r, b);
    for child in children {
        shape.add_child(*child);
    }
}

/// Adds a single-line text shape using the embedded default font.
pub(super) fn add_solid_text(
    pool: &mut ShapesPool,
    id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    text: &str,
    font_size: f32,
    fill: skia::Color,
) {
    add_text_with_fills(
        pool,
        id,
        (l, t, r, b),
        text,
        font_size,
        vec![Fill::Solid(SolidColor(fill))],
    );
}

/// Adds a single-line text shape with the given fill stack (top → bottom).
pub(super) fn add_text_with_fills(
    pool: &mut ShapesPool,
    id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    text: &str,
    font_size: f32,
    fills: Vec<Fill>,
) {
    let bounds = skia::Rect::from_ltrb(l, t, r, b);
    let mut content = TextContent::new(bounds, GrowType::Fixed);
    let line_height = 1.2;
    let span = TextSpan::new(
        text.to_string(),
        FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
        font_size,
        line_height,
        0.0,
        None,
        None,
        TextDirection::LTR,
        400,
        Uuid::nil(),
        fills,
    );
    content.add_paragraph(Paragraph::new(
        TextAlign::Left,
        TextDirection::LTR,
        None,
        None,
        line_height,
        0.0,
        vec![span],
    ));

    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_selrect(l, t, r, b);
    shape.set_shape_type(Type::Text(content));
}

pub(super) fn render(pool: &ShapesPool, root: Uuid) -> String {
    let mut resources = RenderResources::try_new_headless().expect("headless resources");
    register_test_font_urls(&mut resources.fonts);
    let _guard = TestRenderResourcesGuard::install(&mut resources);
    let bytes = render_tree_to_svg(&mut resources, &root, pool, 1.0).expect("svg export");
    String::from_utf8(bytes).expect("utf8 svg")
}
