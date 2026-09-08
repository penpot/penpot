//! GPU-free scene builders and render helpers for SVG export tests.

use skia_safe as skia;

use crate::globals::TestRenderResourcesGuard;
use crate::render::{FontStore, RenderResources};
use crate::shapes::{
    Fill, FontFamily, FontStyle, Frame, Group, GrowType, ImageFill, Paragraph, Path, Rect, Segment,
    SolidColor, Stroke, StrokeKind, StrokeStyle, TextAlign, TextContent, TextDirection, TextSpan,
    Type,
};
use crate::state::ShapesPool;
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;

use super::render_tree_to_svg;

/// Font URL referenced in exported SVG `@font-face` rules.
pub(super) const TEST_FONT_URL: &str = "fonts/sourcesanspro-regular.ttf";

/// Media URL referenced by linked `<image href>` fills in SVG export tests.
/// Relative path so `./preview-snapshots` can resolve it under `target/svg-preview/`.
pub(super) const TEST_IMAGE_URL: &str = "images/test-fill.svg";

fn register_test_font_urls(fonts: &mut FontStore) {
    let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
    fonts.set_source_url(&family.alias(), TEST_FONT_URL.to_string());
}

/// Deterministic UUID from a small integer, keeping snapshots stable.
pub(super) fn uid(n: u32) -> Uuid {
    uuid_from_u32_quartet(0, 0, 0, n)
}

/// Adds a rectangle filled with a linked image (must call `render_with` / register URL).
pub(super) fn add_image_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    image_id: Uuid,
    keep_aspect_ratio: bool,
    opacity: u8,
) {
    add_rect_with_fills(
        pool,
        id,
        parent,
        (l, t, r, b),
        vec![Fill::Image(ImageFill::new(
            image_id,
            opacity,
            200,
            100,
            keep_aspect_ratio,
        ))],
    );
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
    add_frame_with_fills(
        pool,
        id,
        parent,
        (l, t, r, b),
        vec![Fill::Solid(SolidColor(color))],
        clip,
    );
}

fn add_frame_with_fills(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    fills: Vec<Fill>,
    clip: bool,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Frame(Frame::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(fills);
    shape.set_clip(clip);
}

/// Frame whose background is a linked image fill.
pub(super) fn add_image_frame(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    image_id: Uuid,
    clip: bool,
) {
    add_frame_with_fills(
        pool,
        id,
        parent,
        (l, t, r, b),
        vec![test_image_fill(image_id)],
        clip,
    );
}

fn triangle_segments(closed: bool) -> Vec<Segment> {
    let mut segments = vec![
        Segment::MoveTo((10.0, 90.0)),
        Segment::LineTo((50.0, 10.0)),
        Segment::LineTo((90.0, 90.0)),
    ];
    if closed {
        segments.push(Segment::Close);
    }
    segments
}

fn add_path_with_fills(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    segments: Vec<Segment>,
    fills: Vec<Fill>,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Path(Path::new(segments)));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(fills);
}

fn test_image_fill(image_id: Uuid) -> Fill {
    Fill::Image(ImageFill::new(image_id, 255, 200, 100, true))
}

/// Triangle path (open or closed) with a linked image fill.
pub(super) fn add_image_path(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    closed: bool,
    image_id: Uuid,
) {
    add_path_with_fills(
        pool,
        id,
        parent,
        (0.0, 0.0, 100.0, 100.0),
        triangle_segments(closed),
        vec![test_image_fill(image_id)],
    );
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

/// SVG-raw leaf with markup (same form `get-static-markup` uploads to WASM).
pub(super) fn add_svg_raw(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    content: &str,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_svg_raw_content(content.to_string());
    shape.set_selrect(l, t, r, b);
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

/// Adds a rectangle with a single solid stroke (no fill).
pub(super) fn add_stroked_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    stroke: Stroke,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![]);
    shape.add_stroke(stroke);
}

/// Adds a closed rectangular path with a single solid stroke (no fill).
pub(super) fn add_stroked_closed_path(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    bounds: (f32, f32, f32, f32),
    stroke: Stroke,
) {
    add_stroked_path(pool, id, parent, bounds, stroke, true);
}

/// Adds an open polyline path with a single solid stroke (no fill).
pub(super) fn add_stroked_open_path(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    bounds: (f32, f32, f32, f32),
    stroke: Stroke,
) {
    add_stroked_path(pool, id, parent, bounds, stroke, false);
}

fn add_stroked_path(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    stroke: Stroke,
    closed: bool,
) {
    let mut segments = vec![
        Segment::MoveTo((l, t)),
        Segment::LineTo((r, t)),
        Segment::LineTo((r, b)),
        Segment::LineTo((l, b)),
    ];
    if closed {
        segments.push(Segment::Close);
    }
    let path = Path::new(segments);
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Path(path));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![]);
    shape.add_stroke(stroke);
}

pub(super) fn solid_stroke(kind: StrokeKind, width: f32, color: skia::Color) -> Stroke {
    stroke_with_style(kind, StrokeStyle::Solid, width, color)
}

pub(super) fn dotted_stroke(kind: StrokeKind, width: f32, color: skia::Color) -> Stroke {
    stroke_with_style(kind, StrokeStyle::Dotted, width, color)
}

pub(super) fn dashed_stroke(kind: StrokeKind, width: f32, color: skia::Color) -> Stroke {
    stroke_with_style(kind, StrokeStyle::Dashed, width, color)
}

pub(super) fn mixed_stroke(kind: StrokeKind, width: f32, color: skia::Color) -> Stroke {
    stroke_with_style(kind, StrokeStyle::Mixed, width, color)
}

fn stroke_with_style(
    kind: StrokeKind,
    style: StrokeStyle,
    width: f32,
    color: skia::Color,
) -> Stroke {
    let mut stroke = match kind {
        StrokeKind::Inner => Stroke::new_inner_stroke(width, style, None, None, None, None),
        StrokeKind::Outer => Stroke::new_outer_stroke(width, style, None, None, None, None),
        StrokeKind::Center => Stroke::new_center_stroke(width, style, None, None, None, None),
    };
    stroke.fill = Fill::Solid(SolidColor(color));
    stroke
}

fn image_stroke(kind: StrokeKind, style: StrokeStyle, width: f32, image_id: Uuid) -> Stroke {
    let mut stroke = match kind {
        StrokeKind::Inner => Stroke::new_inner_stroke(width, style, None, None, None, None),
        StrokeKind::Outer => Stroke::new_outer_stroke(width, style, None, None, None, None),
        StrokeKind::Center => Stroke::new_center_stroke(width, style, None, None, None, None),
    };
    stroke.fill = test_image_fill(image_id);
    stroke
}

pub(super) fn image_solid_stroke(kind: StrokeKind, width: f32, image_id: Uuid) -> Stroke {
    image_stroke(kind, StrokeStyle::Solid, width, image_id)
}

pub(super) fn image_dotted_stroke(kind: StrokeKind, width: f32, image_id: Uuid) -> Stroke {
    image_stroke(kind, StrokeStyle::Dotted, width, image_id)
}

/// Text with a linked image fill (register URL via `render_with`).
pub(super) fn add_image_text(
    pool: &mut ShapesPool,
    id: Uuid,
    bounds: (f32, f32, f32, f32),
    text: &str,
    font_size: f32,
    image_id: Uuid,
) {
    add_text_with_fills(
        pool,
        id,
        bounds,
        text,
        font_size,
        vec![test_image_fill(image_id)],
    );
}

pub(super) fn render(pool: &ShapesPool, root: Uuid) -> String {
    render_with(pool, root, |_resources| {})
}

/// Like [`render`], but lets the test register extra resources (e.g. image URLs)
/// before export.
pub(super) fn render_with(
    pool: &ShapesPool,
    root: Uuid,
    setup: impl FnOnce(&mut RenderResources),
) -> String {
    let mut resources = RenderResources::try_new_headless().expect("headless resources");
    register_test_font_urls(&mut resources.fonts);
    setup(&mut resources);
    let _guard = TestRenderResourcesGuard::install(&mut resources);
    let bytes = render_tree_to_svg(&mut resources, &root, pool, 1.0).expect("svg export");
    String::from_utf8(bytes).expect("utf8 svg")
}
