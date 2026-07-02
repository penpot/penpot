//! GPU-free scene builders and render helpers for SVG export tests.

use skia_safe as skia;

use crate::render::vector::ExportState;
use crate::render::{FontStore, ImageProvider};
use crate::shapes::{
    Fill, FontFamily, FontStyle, GrowType, ImageFill, Paragraph, Path, Rect, Segment, SolidColor,
    Stroke, StrokeKind, StrokeStyle, TextAlign, TextContent, TextDirection, TextSpan, Type,
};
use crate::state::ShapesPool;
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;

use super::render_tree_to_svg;

/// Default image URL used by SVG export tests.
pub(super) const TEST_IMAGE_URL: &str = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAABHNCSVQICAgIfAhkiAAAABtJREFUCJlj/G/M8J8x7T8DA8PM////GzP8BwBGtwhh9BmShgAAAABJRU5ErkJggg==";

/// Font URL referenced in exported SVG `@font-face` rules.
pub(super) const TEST_FONT_URL: &str = "fonts/sourcesanspro-regular.ttf";

fn register_test_font_urls(fonts: &mut FontStore) {
    let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
    fonts.set_source_url(&family.alias(), TEST_FONT_URL.to_string());
}

/// Minimal [`ExportState`] for tests without image draws. Image tests build
/// their own context with [`render_with_images`].
pub(super) fn export_state(fonts: &FontStore) -> ExportState<'_> {
    ExportState {
        fonts,
        images: None,
        sampling_options: skia::SamplingOptions::new(
            skia::FilterMode::Linear,
            skia::MipmapMode::Nearest,
        ),
    }
}

/// Deterministic UUID from a small integer, keeping snapshots stable.
pub(super) fn uid(n: u32) -> Uuid {
    uuid_from_u32_quartet(0, 0, 0, n)
}

/// Adds a solid-filled rectangle to the pool and returns nothing; callers
/// tweak the returned shape via `pool.get_mut` when they need effects.
pub(super) fn add_solid_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    color: skia::Color,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(color))]);
}

pub(super) fn render(pool: &ShapesPool, root: Uuid) -> String {
    let mut fonts = FontStore::try_new().expect("font store");
    register_test_font_urls(&mut fonts);
    let mut ctx = export_state(&fonts);
    let bytes = render_tree_to_svg(&mut ctx, &root, pool, 1.0).expect("svg export");
    String::from_utf8(bytes).expect("utf8 svg")
}

/// Adds a solid-filled rectangle with a single solid stroke of the given
/// kind/width/color.
pub(super) fn add_stroked_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    rect: (f32, f32, f32, f32),
    fill: skia::Color,
    kind: StrokeKind,
    width: f32,
    stroke_color: skia::Color,
) {
    add_styled_stroked_rect(
        pool,
        id,
        rect,
        fill,
        kind,
        StrokeStyle::Solid,
        width,
        stroke_color,
    );
}

/// Adds a solid-filled rectangle with a single stroke of the given
/// kind/style/width/color.
pub(super) fn add_styled_stroked_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    fill: skia::Color,
    kind: StrokeKind,
    style: StrokeStyle,
    width: f32,
    stroke_color: skia::Color,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(fill))]);

    let mut stroke = match kind {
        StrokeKind::Inner => Stroke::new_inner_stroke(width, style, None, None, None, None),
        StrokeKind::Center => Stroke::new_center_stroke(width, style, None, None, None, None),
        StrokeKind::Outer => Stroke::new_outer_stroke(width, style, None, None, None, None),
    };
    stroke.fill = Fill::Solid(SolidColor(stroke_color));
    shape.add_stroke(stroke);
}

/// A CPU-only [`ImageProvider`] returning one tiny raster image and its source
/// URL for a fixed id, so image-stroke tests need no GPU-backed `ImageStore`.
pub(super) struct FakeImages {
    pub id: Uuid,
    pub image: skia::Image,
    pub url: &'static str,
}

impl ImageProvider for FakeImages {
    fn get_cpu_image(&mut self, id: &Uuid) -> Option<skia::Image> {
        (*id == self.id).then(|| self.image.clone())
    }

    fn source_url(&self, id: &Uuid) -> Option<&str> {
        (*id == self.id).then_some(self.url)
    }
}

/// A 2×2 non-uniform raster image (kept tiny so its base64 stays small and
/// deterministic in snapshots).
pub(super) fn tiny_image() -> skia::Image {
    let info = skia::ImageInfo::new_n32_premul((2, 2), None);
    let mut surface = skia::surfaces::raster(&info, None, None).expect("raster surface");
    let canvas = surface.canvas();
    canvas.clear(skia::Color::from_rgb(0x00, 0x99, 0xFF));
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::from_rgb(0xFF, 0x33, 0x00));
    canvas.draw_rect(skia::Rect::from_xywh(0.0, 0.0, 1.0, 1.0), &paint);
    canvas.draw_rect(skia::Rect::from_xywh(1.0, 1.0, 1.0, 1.0), &paint);
    surface.image_snapshot()
}

/// Renders `root` with an image provider available (image draws enabled).
pub(super) fn render_with_images(
    pool: &ShapesPool,
    root: Uuid,
    images: &mut dyn ImageProvider,
) -> String {
    let mut fonts = FontStore::try_new().expect("font store");
    register_test_font_urls(&mut fonts);
    let mut ctx = ExportState {
        fonts: &fonts,
        images: Some(images),
        sampling_options: skia::SamplingOptions::new(
            skia::FilterMode::Linear,
            skia::MipmapMode::Nearest,
        ),
    };
    let bytes = render_tree_to_svg(&mut ctx, &root, pool, 1.0).expect("svg export");
    String::from_utf8(bytes).expect("utf8 svg")
}

/// Applies a rotation (degrees) around the shape's center.
///
/// SVG export composes geometry via `Shape::centered_transform()`, which reads
/// `transform` rather than the standalone `rotation` field. Tests therefore
/// need to keep both values in sync.
pub(super) fn rotate_shape(pool: &mut ShapesPool, id: Uuid, degrees: f32) {
    let shape = pool.get_mut(&id).unwrap();
    shape.set_rotation(degrees);

    let mut transform = skia::Matrix::new_identity();
    transform.set_rotate(degrees, None);
    shape.set_transform(
        transform[0],
        transform[3],
        transform[1],
        transform[4],
        transform[2],
        transform[5],
    );
}

/// Sets the opacity of an image-filled stroke (0-255).
pub(super) fn set_image_stroke_opacity(pool: &mut ShapesPool, id: Uuid, opacity: u8) {
    let shape = pool.get_mut(&id).unwrap();
    let Some(stroke) = shape.strokes.first_mut() else {
        return;
    };
    let Fill::Image(image_fill) = &stroke.fill else {
        return;
    };

    stroke.fill = Fill::Image(ImageFill::new(
        image_fill.id(),
        opacity,
        image_fill.width(),
        image_fill.height(),
        image_fill.keep_aspect_ratio(),
    ));
}

/// Adds a solid-filled rectangle whose single stroke is *image-filled* (the
/// stroke references `image_id` via an [`ImageFill`]).
pub(super) fn add_image_stroked_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    image_id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    fill: skia::Color,
    kind: StrokeKind,
    style: StrokeStyle,
    width: f32,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(fill))]);

    let mut stroke = match kind {
        StrokeKind::Inner => Stroke::new_inner_stroke(width, style, None, None, None, None),
        StrokeKind::Center => Stroke::new_center_stroke(width, style, None, None, None, None),
        StrokeKind::Outer => Stroke::new_outer_stroke(width, style, None, None, None, None),
    };
    stroke.fill = Fill::Image(ImageFill::new(image_id, 255, 2, 2, false));
    shape.add_stroke(stroke);
}

/// Adds a rectangle whose *fill* is an image (references `image_id` via an
/// [`ImageFill`]).
pub(super) fn add_image_filled_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    image_id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Image(ImageFill::new(image_id, 255, 2, 2, false))]);
}

/// Adds a closed triangular path whose *fill* is an image (references `image_id`
/// via an [`ImageFill`]).
pub(super) fn add_image_filled_path(
    pool: &mut ShapesPool,
    id: Uuid,
    image_id: Uuid,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Path(Path::new(vec![
        Segment::MoveTo((50.0, 0.0)),
        Segment::LineTo((100.0, 80.0)),
        Segment::LineTo((0.0, 80.0)),
        Segment::Close,
    ])));
    shape.set_selrect(0.0, 0.0, 100.0, 80.0);
    shape.set_fills(vec![Fill::Image(ImageFill::new(image_id, 255, 2, 2, false))]);
}

/// Adds a solid-filled closed triangular path whose single stroke is
/// *image-filled* (references `image_id` via an [`ImageFill`]).
pub(super) fn add_image_stroked_path(
    pool: &mut ShapesPool,
    id: Uuid,
    image_id: Uuid,
    fill: skia::Color,
    kind: StrokeKind,
    width: f32,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Path(Path::new(vec![
        Segment::MoveTo((50.0, 0.0)),
        Segment::LineTo((100.0, 80.0)),
        Segment::LineTo((0.0, 80.0)),
        Segment::Close,
    ])));
    shape.set_selrect(0.0, 0.0, 100.0, 80.0);
    shape.set_fills(vec![Fill::Solid(SolidColor(fill))]);

    let mut stroke = match kind {
        StrokeKind::Inner => {
            Stroke::new_inner_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
        StrokeKind::Center => {
            Stroke::new_center_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
        StrokeKind::Outer => {
            Stroke::new_outer_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
    };
    stroke.fill = Fill::Image(ImageFill::new(image_id, 255, 2, 2, false));
    shape.add_stroke(stroke);
}

/// Adds a solid-filled closed triangular path with a single solid stroke of
/// the given kind/width/color. Closed paths honor the stroke kind (open
/// paths always render centered).
pub(super) fn add_stroked_path(
    pool: &mut ShapesPool,
    id: Uuid,
    fill: skia::Color,
    kind: StrokeKind,
    width: f32,
    stroke_color: skia::Color,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(Uuid::nil());
    shape.set_shape_type(Type::Path(Path::new(vec![
        Segment::MoveTo((50.0, 0.0)),
        Segment::LineTo((100.0, 80.0)),
        Segment::LineTo((0.0, 80.0)),
        Segment::Close,
    ])));
    shape.set_selrect(0.0, 0.0, 100.0, 80.0);
    shape.set_fills(vec![Fill::Solid(SolidColor(fill))]);

    let mut stroke = match kind {
        StrokeKind::Inner => {
            Stroke::new_inner_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
        StrokeKind::Center => {
            Stroke::new_center_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
        StrokeKind::Outer => {
            Stroke::new_outer_stroke(width, StrokeStyle::Solid, None, None, None, None)
        }
    };
    stroke.fill = Fill::Solid(SolidColor(stroke_color));
    shape.add_stroke(stroke);
}

/// Adds a single-line text shape ("HOLA"-style) using the embedded default
/// font (registered under the nil-UUID family, weight 400), optionally with
/// a single solid stroke of the given kind. Font shaping resolves against the
/// export `FontStore` (installed for the render), so no global state or GPU
/// is needed.
pub(super) fn add_text(
    pool: &mut ShapesPool,
    id: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    text: &str,
    font_size: f32,
    fill: skia::Color,
    stroke: Option<(StrokeKind, f32, skia::Color)>,
) {
    let bounds = skia::Rect::from_ltrb(l, t, r, b);
    let mut content = TextContent::new(bounds, GrowType::Fixed);
    // `line_height` is a multiplier of the font size (Skia `set_height` with
    // height override), NOT an absolute pixel value.
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
        vec![Fill::Solid(SolidColor(fill))],
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
    // Set the selrect *before* the text type: `set_selrect` on a text shape
    // eagerly relayouts (needing the font collection), which isn't available
    // until the export installs it. The render recomputes text layout from
    // the selrect anyway.
    shape.set_selrect(l, t, r, b);
    shape.set_shape_type(Type::Text(content));
    if let Some((kind, width, color)) = stroke {
        let mut stroke = match kind {
            StrokeKind::Inner => {
                Stroke::new_inner_stroke(width, StrokeStyle::Solid, None, None, None, None)
            }
            StrokeKind::Center => {
                Stroke::new_center_stroke(width, StrokeStyle::Solid, None, None, None, None)
            }
            StrokeKind::Outer => {
                Stroke::new_outer_stroke(width, StrokeStyle::Solid, None, None, None, None)
            }
        };
        stroke.fill = Fill::Solid(SolidColor(color));
        shape.add_stroke(stroke);
    }
}
