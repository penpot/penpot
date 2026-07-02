use super::fixtures::*;

use crate::shapes::{
    BlendMode, Blur, BlurType, Fill, Frame, Group, Rect, Shadow, ShadowStyle, SolidColor, Stroke,
    StrokeKind, StrokeStyle, Type,
};
use crate::state::ShapesPool;
use crate::uuid::Uuid;

use skia_safe as skia;

#[test]
fn exports_a_solid_rect() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );

    insta::assert_snapshot!(render(&pool, id));
}

#[test]
fn exports_leaf_opacity_and_blend_mode_as_group_wrappers() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(0, 128, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_opacity(0.5);
        shape.set_blend_mode(BlendMode(skia::BlendMode::Multiply));
    }

    let svg = render(&pool, id);
    // Composite effects must become native SVG group wrappers, not dropped
    // `save_layer`s.
    assert!(svg.contains("opacity=\"0.5\""), "missing opacity wrapper: {svg}");
    assert!(
        svg.contains("mix-blend-mode:multiply"),
        "missing blend-mode wrapper: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_a_group_with_two_rects_and_group_opacity() {
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let a = uid(2);
    let b = uid(3);

    {
        let group = pool.add_shape(group_id);
        group.set_parent(Uuid::nil());
        group.set_shape_type(Type::Group(Group { masked: false }));
        group.set_selrect(0.0, 0.0, 200.0, 100.0);
        group.set_opacity(0.7);
        group.add_child(a);
        group.add_child(b);
    }

    add_solid_rect(
        &mut pool,
        a,
        group_id,
        (0.0, 0.0, 90.0, 100.0),
        skia::Color::from_rgb(0, 0, 255),
    );
    add_solid_rect(
        &mut pool,
        b,
        group_id,
        (110.0, 0.0, 200.0, 100.0),
        skia::Color::from_rgb(0, 200, 0),
    );

    insta::assert_snapshot!(render(&pool, group_id));
}

/// A minimal masked group: the first child is a solid rectangle acting as
/// the mask, clipping a single solid-filled rectangle to its rendered
/// alpha. A Penpot mask is an alpha mask, so the mask needs a fill; an
/// opaque solid fill clips exactly to the mask's geometry.
#[test]
fn exports_a_masked_group() {
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask = uid(2); // first child, the mask
    let content = uid(3);

    {
        let group = pool.add_shape(group_id);
        group.set_parent(Uuid::nil());
        group.set_shape_type(Type::Group(Group { masked: true }));
        group.set_selrect(0.0, 0.0, 100.0, 100.0);
        // First child is the mask; the rest is the masked content.
        group.add_child(mask);
        group.add_child(content);
    }

    // Mask: a solid rectangle covering the left half of the group. Its
    // opaque alpha defines the clip region.
    add_solid_rect(
        &mut pool,
        mask,
        group_id,
        (0.0, 0.0, 50.0, 100.0),
        skia::Color::from_rgb(0, 0, 0),
    );

    // Masked content: a solid rectangle spanning the whole group.
    add_solid_rect(
        &mut pool,
        content,
        group_id,
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(255, 0, 0),
    );

    insta::assert_snapshot!(render(&pool, group_id));
}

/// A masked group whose *mask is itself a group* of shapes (a very common
/// Penpot pattern). The mask region is the union of the mask group's
/// descendants (here a horizontal + vertical band forming a plus sign)
/// and the masked content is an ellipse.
///
/// Regression test: a group carries no geometry of its own, so the SVG
/// exporter must render the whole mask subtree into the `<mask>`. Otherwise
/// the mask comes out empty and the content renders unmasked (or fully
/// hidden).
///
/// Mirrors the transit shape (with small, round coordinates):
///   group (masked-group=true) with children
///     [ group (the mask) with [Rectangle #B1B2B5, Rectangle #B1B2B5],
///       Ellipse #1a4de5 (content) ]
#[test]
fn exports_a_masked_group_whose_mask_is_a_group() {
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_group = uid(2); // first child, the mask (a group)
    let ellipse = uid(3); // masked content
    let band_h = uid(4); // horizontal band (mask geometry)
    let band_v = uid(5); // vertical band (mask geometry)

    {
        let group = pool.add_shape(group_id);
        group.set_parent(Uuid::nil());
        group.set_shape_type(Type::Group(Group { masked: true }));
        group.set_selrect(0.0, 0.0, 100.0, 100.0);
        // First child is the mask group; the rest is the masked content.
        group.add_child(mask_group);
        group.add_child(ellipse);
    }

    // Mask: a group of two rectangles forming a plus sign. No geometry of
    // its own; its mask region comes from its children.
    {
        let group = pool.add_shape(mask_group);
        group.set_parent(group_id);
        group.set_shape_type(Type::Group(Group { masked: false }));
        group.set_selrect(0.0, 0.0, 100.0, 100.0);
        group.add_child(band_h);
        group.add_child(band_v);
    }
    add_solid_rect(
        &mut pool,
        band_h,
        mask_group,
        (0.0, 40.0, 100.0, 60.0),
        skia::Color::from_rgb(0xB1, 0xB2, 0xB5),
    );
    add_solid_rect(
        &mut pool,
        band_v,
        mask_group,
        (40.0, 0.0, 60.0, 100.0),
        skia::Color::from_rgb(0xB1, 0xB2, 0xB5),
    );

    // Masked content: an ellipse (oval from its selrect).
    {
        let shape = pool.add_shape(ellipse);
        shape.set_parent(group_id);
        shape.set_shape_type(Type::Circle);
        shape.set_selrect(0.0, 0.0, 100.0, 100.0);
        shape.set_fills(vec![Fill::Solid(SolidColor(skia::Color::from_rgb(
            0x1A, 0x4D, 0xE5,
        )))]);
    }

    let svg = render(&pool, group_id);
    // The mask is a group, so the `<mask>` must contain the descendants'
    // rendered content: the content group references a real mask and the
    // mask def is not empty.
    assert!(
        svg.contains("mask=\"url(#"),
        "content must reference a mask: {svg}"
    );
    assert!(
        !svg.contains("mask-type=\"alpha\"></mask>"),
        "mask from a group must not be empty: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple leaf shape with a drop shadow: a solid-filled ellipse with one
/// visible drop shadow (offset, blur, tint). The GPU/PDF path draws the
/// shadow via a `save_layer` image filter, which `SkSVGDevice` drops, so
/// the SVG backend must emit a native `<filter>` (blur + offset + flood)
/// producing the shadow behind the shape.
///
/// Mirrors the transit shape (with small, round coordinates):
///   Ellipse #ffbbbb with drop-shadow
///     { color #380000, opacity 1, offset (10, 4), blur 4, spread 0 }
#[test]
fn exports_a_shape_with_a_drop_shadow() {
    let mut pool = ShapesPool::new();
    let id = uid(1);

    {
        let shape = pool.add_shape(id);
        shape.set_parent(Uuid::nil());
        shape.set_shape_type(Type::Circle);
        shape.set_selrect(0.0, 0.0, 100.0, 80.0);
        shape.set_fills(vec![Fill::Solid(SolidColor(skia::Color::from_rgb(
            0xFF, 0xBB, 0xBB,
        )))]);
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(0xFF, 0x38, 0x00, 0x00),
            4.0,         // blur
            0.0,         // spread
            (10.0, 4.0), // offset (x, y)
            ShadowStyle::Drop,
            false, // hidden
        ));
    }

    let svg = render(&pool, id);
    // The drop shadow must survive as a native SVG filter (the GPU/PDF
    // `save_layer` shadow is dropped by `SkSVGDevice`).
    assert!(
        svg.contains("<filter") && svg.contains("feOffset") && svg.contains("feGaussianBlur"),
        "drop shadow must be emitted as an SVG filter: {svg}"
    );
    assert!(
        svg.contains("flood-color=\"#380000\""),
        "shadow tint must be preserved: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple leaf shape with a layer blur: a solid rectangle with a visible
/// `LayerBlur`. The GPU/PDF path blurs via a `save_layer` image filter,
/// which `SkSVGDevice` drops, so the SVG backend emits a native
/// `<filter><feGaussianBlur>` wrapper (`stdDeviation` from `radius_to_sigma`).
#[test]
fn exports_a_shape_with_a_layer_blur() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(0, 128, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_blur(Some(Blur::new(BlurType::LayerBlur, false, 8.0)));
    }

    let svg = render(&pool, id);
    // The layer blur must survive as a native SVG filter (the GPU/PDF
    // `save_layer` blur is dropped by `SkSVGDevice`).
    assert!(
        svg.contains("<filter") && svg.contains("feGaussianBlur"),
        "layer blur must be emitted as an SVG feGaussianBlur filter: {svg}"
    );
    assert!(
        svg.contains("filter=\"url(#blur"),
        "shape must reference the blur filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A frame ("Board") with no fill, an inner solid stroke and a drop shadow.
/// The GPU/PDF path casts the frame shadow from its rendered silhouette
/// (including the stroke) via a `save_layer` image filter that `SkSVGDevice`
/// drops, so the SVG backend must emit a native `<filter>` wrapping the
/// frame content, sitting outside the content clip so the offset shadow is
/// not clipped to the frame bounds.
///
/// Mirrors the transit shape: a 354x204 board, inner 10px black stroke, drop
/// shadow { color #000000 opacity 0.2, offset (40, 40), blur 4, spread 0 }.
#[test]
fn exports_a_frame_with_a_drop_shadow() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    {
        let shape = pool.add_shape(id);
        shape.set_parent(Uuid::nil());
        shape.set_shape_type(Type::Frame(Frame {
            corners: None,
            layout: None,
        }));
        shape.set_selrect(0.0, 0.0, 354.0, 204.0);
        let mut stroke = Stroke::new_inner_stroke(10.0, StrokeStyle::Solid, None, None, None, None);
        stroke.fill = Fill::Solid(SolidColor(skia::Color::from_rgb(0, 0, 0)));
        shape.add_stroke(stroke);
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(0x33, 0x00, 0x00, 0x00), // #000000 @ ~0.2
            4.0,                                            // blur
            0.0,                                            // spread
            (40.0, 40.0),                                   // offset (x, y)
            ShadowStyle::Drop,
            false, // hidden
        ));
    }

    let svg = render(&pool, id);
    // The frame drop shadow must survive as a native SVG filter (the GPU/PDF
    // `save_layer` shadow is dropped by `SkSVGDevice`).
    assert!(
        svg.contains("<filter") && svg.contains("feOffset") && svg.contains("feGaussianBlur"),
        "frame drop shadow must be emitted as an SVG filter: {svg}"
    );
    assert!(
        svg.contains("filter=\"url(#shadow"),
        "frame content must reference the shadow filter: {svg}"
    );
    // The stroke (shadow silhouette source) must still be present.
    assert!(
        svg.contains("stroke-width=\"10\""),
        "frame stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn frame_drop_shadow_does_not_double_child_shadows() {
    // A frame with a drop shadow, containing a child that has its *own* drop
    // shadow. The frame shadow silhouette must trace the child's shape only,
    // never the child's shadow; otherwise the frame shadow is painted twice
    // (a second copy offset by the child shadow). The silhouette pass renders
    // descendants with their shadows suppressed, so only one frame shadow
    // filter and one child shadow filter exist, each used once.
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child_id = uid(2);
    {
        let frame = pool.add_shape(frame_id);
        frame.set_parent(Uuid::nil());
        frame.set_shape_type(Type::Frame(Frame {
            corners: None,
            layout: None,
        }));
        frame.set_selrect(0.0, 0.0, 300.0, 200.0);
        frame.add_shadow(Shadow::new(
            skia::Color::from_rgb(0x58, 0xEA, 0x66), // green frame shadow
            0.0,
            0.0,
            (50.0, 50.0),
            ShadowStyle::Drop,
            false,
        ));
        frame.add_child(child_id);
    }
    {
        let child = pool.add_shape(child_id);
        child.set_parent(frame_id);
        child.set_shape_type(Type::Rect(Rect::default()));
        child.set_selrect(40.0, 40.0, 160.0, 120.0);
        child.add_fill(Fill::Solid(SolidColor(skia::Color::from_rgb(
            0xE1, 0x7F, 0xDA,
        ))));
        child.add_shadow(Shadow::new(
            skia::Color::from_rgb(0x19, 0x00, 0xFF), // blue child shadow
            0.0,
            0.0,
            (20.0, 20.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, frame_id);

    // Both shadows are emitted as distinct filters, each defined exactly once
    // (no redundant duplicated `<filter>`s, no doubled frame silhouette).
    assert_eq!(
        svg.matches("flood-color=\"#58EA66\"").count(),
        1,
        "frame (green) shadow filter must be defined once: {svg}"
    );
    assert_eq!(
        svg.matches("flood-color=\"#1900FF\"").count(),
        1,
        "child (blue) shadow filter must be defined once: {svg}"
    );
    // The child's own shadow must not leak into the frame silhouette: it is
    // referenced by exactly one `<g filter>` (the real content), while the
    // silhouette pass renders the child with shadows suppressed.
    assert_eq!(
        svg.matches("filter=\"url(#shadow").count(),
        2,
        "exactly the frame-silhouette + child-content shadow refs: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple rectangle with a solid *inner* stroke: the stroke sits fully
/// inside the shape's geometry.
#[test]
fn exports_a_shape_with_a_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Inner,
        10.0,
        skia::Color::from_rgb(0xFF, 0x00, 0x00),
    );

    let svg = render(&pool, id);
    // Skia's SVG backend serializes colors as CSS names / short hex, so
    // assert on the emitted stroke rather than an exact hex string.
    assert!(
        svg.contains("stroke-width=\"10\"") && svg.contains("stroke=\"red\""),
        "inner stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple rectangle with a solid *center* stroke: the stroke straddles the
/// geometry (half inside, half outside).
#[test]
fn exports_a_shape_with_a_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Center,
        10.0,
        skia::Color::from_rgb(0x00, 0x88, 0x00),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke-width=\"10\"") && svg.contains("stroke=\"#080\""),
        "center stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple rectangle with a solid *outer* stroke: the stroke sits fully
/// outside the shape's geometry.
#[test]
fn exports_a_shape_with_a_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        10.0,
        skia::Color::from_rgb(0x00, 0x00, 0xFF),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke-width=\"10\"") && svg.contains("stroke=\"blue\""),
        "outer stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

// -- image-filled strokes ------------------------------------------------
//
// Image-filled strokes are the trickiest SVG case: the GPU/PDF path paints
// the texture inside a `save_layer` + `SrcIn` composition that `SkSVGDevice`
// drops. The SVG compositor instead emits the raw `<image>` confined by an
// alpha `<mask>` of the stroke silhouette (solid/dotted-center) or, for
// dotted inner/outer, an interior/exterior restriction plus a dotted-ring
// alpha mask. These tests exercise both branches with a GPU-free
// [`ImageProvider`] so they stay fast and headless.

/// A rectangle with a solid, image-filled *center* stroke: the texture is
/// re-emitted as an `<image>` confined by an alpha `<mask>` of the stroke
/// silhouette (no dropped `save_layer`).
#[test]
fn exports_a_shape_with_a_solid_center_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Center,
        StrokeStyle::Solid,
        16.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    assert!(
        svg.contains("mask-type=\"alpha\""),
        "stroke silhouette alpha mask must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A rectangle with a *dotted inner* image-filled stroke: the texture is
/// confined to the dot ring (alpha mask) and to the shape interior
/// (`clip-path`).
#[test]
fn exports_a_shape_with_a_dotted_inner_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Inner,
        StrokeStyle::Dotted,
        16.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    // Dotted inner: interior restriction is a clip-path, the dot ring is an
    // alpha mask.
    assert!(
        svg.contains("clip-path=\"url(#") && svg.contains("mask-type=\"alpha\""),
        "dotted-inner image stroke needs a clip-path + alpha ring mask: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A rectangle with a *dotted outer* image-filled stroke: the texture is
/// confined to the dot ring (alpha mask) and to the shape exterior
/// (inverse-of-shape luminance mask, since SVG `<clipPath>` cannot subtract).
#[test]
fn exports_a_shape_with_a_dotted_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        StrokeStyle::Dotted,
        16.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    // Dotted outer: exterior restriction + dot-ring both via <mask>.
    assert!(
        svg.contains("mask=\"url(#") && svg.contains("mask-type=\"alpha\""),
        "dotted-outer image stroke needs restriction + alpha ring masks: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A rectangle with a solid, image-filled *inner* stroke: like the center
/// case, the texture is confined by an alpha `<mask>` of the stroke
/// silhouette, but the silhouette sits fully inside the shape geometry.
#[test]
fn exports_a_shape_with_a_solid_inner_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Inner,
        StrokeStyle::Solid,
        16.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    assert!(
        svg.contains("mask-type=\"alpha\""),
        "inner stroke silhouette alpha mask must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A closed path with a solid, image-filled *outer* stroke: SkSVGDevice drops
/// the save_layer+Clear used to build an outer-stroke alpha mask on paths, so
/// the image must be confined to the shape exterior via a luminance mask.
#[test]
fn exports_a_path_with_a_solid_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_path(
        &mut pool,
        id,
        image_id,
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        10.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    assert!(
        svg.contains("mask=\"url(#smask") && svg.contains("mask=\"url(#simask"),
        "outer path image stroke needs exterior + ring masks: {svg}"
    );
    assert!(
        svg.contains("mask-type=\"alpha\""),
        "ring mask must be alpha: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A rectangle with a solid, image-filled *outer* stroke: the silhouette
/// sits fully outside the shape geometry, again confined by an alpha
/// `<mask>`.
#[test]
fn exports_a_shape_with_a_solid_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        StrokeStyle::Solid,
        16.0,
    );

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    assert!(
        svg.contains("mask-type=\"alpha\""),
        "outer stroke silhouette alpha mask must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

// -- rotated image-filled strokes ----------------------------------------
//
// Rotation is the main regression surface for image strokes: the leaf
// transform on the emitted `<image>` must stay aligned with the alpha masks
// built from `centered_transform()`. Paths with outer strokes additionally
// need the exterior + ring mask pair (no `stroke_to_path` even-odd holes).
// Stroke image opacity at 50% exercises the `opacity` attribute on `<image>`.

const ROTATION_45: f32 = 45.0;
const HALF_IMAGE_STROKE_OPACITY: u8 = 128;

fn assert_solid_image_stroke_exports(svg: &str, kind: StrokeKind, is_path: bool, rotated: bool) {
    assert!(svg.contains("<image"), "stroke image must be emitted: {svg}");
    assert!(
        svg.contains("opacity=\"0.5"),
        "semi-transparent image stroke must carry opacity: {svg}"
    );
    if kind == StrokeKind::Outer && is_path {
        assert!(
            svg.contains("mask=\"url(#smask") && svg.contains("mask=\"url(#simask"),
            "outer path image stroke needs exterior + ring masks: {svg}"
        );
    }
    assert!(
        svg.contains("mask-type=\"alpha\"") || svg.contains("mask=\"url(#smask"),
        "image stroke must be confined by a mask: {svg}"
    );
    if rotated {
        assert!(
            svg.contains("transform=\"matrix(") && svg.contains("0.707"),
            "rotated stroke image must use a 45° leaf transform: {svg}"
        );
        if is_path {
            assert!(
                svg.contains("<path fill=\"#CCC\" transform=\"matrix("),
                "rotated path fill must carry centered_transform: {svg}"
            );
            if kind == StrokeKind::Inner {
                assert!(
                    svg.contains("clip-path=\"url(#dclip"),
                    "rotated inner path image stroke needs a rotated clip-path: {svg}"
                );
                assert!(
                    !svg.contains("<clipPath id=\"f0_cl_"),
                    "inner path stroke mask must not use an axis-aligned canvas clip: {svg}"
                );
            }
            if kind == StrokeKind::Outer {
                assert!(
                    svg.contains("mask=\"url(#smask") && svg.contains("mask=\"url(#simask"),
                    "outer path image stroke needs exterior + ring masks: {svg}"
                );
            }
        }
    }
}

fn render_rotated_rect_image_stroke(kind: StrokeKind) -> String {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_stroked_rect(
        &mut pool,
        id,
        image_id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        kind,
        StrokeStyle::Solid,
        16.0,
    );
    rotate_shape(&mut pool, id, ROTATION_45);
    set_image_stroke_opacity(&mut pool, id, HALF_IMAGE_STROKE_OPACITY);
    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    render_with_images(&pool, id, &mut images)
}

#[test]
fn exports_a_rotated_rect_with_a_solid_inner_image_stroke() {
    let svg = render_rotated_rect_image_stroke(StrokeKind::Inner);
    assert_solid_image_stroke_exports(&svg, StrokeKind::Inner, false, true);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_a_rotated_rect_with_a_solid_center_image_stroke() {
    let svg = render_rotated_rect_image_stroke(StrokeKind::Center);
    assert_solid_image_stroke_exports(&svg, StrokeKind::Center, false, true);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_a_rotated_rect_with_a_solid_outer_image_stroke() {
    let svg = render_rotated_rect_image_stroke(StrokeKind::Outer);
    assert_solid_image_stroke_exports(&svg, StrokeKind::Outer, false, true);
    insta::assert_snapshot!(svg);
}

/// A rectangle filled with an image: the texture is emitted as an `<image>`
/// clipped to the shape geometry (no dropped `save_layer`).
#[test]
fn exports_a_shape_with_an_image_fill() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_filled_rect(&mut pool, id, image_id, (0.0, 0.0, 100.0, 80.0));

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: TEST_IMAGE_URL,
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(svg.contains("<image"), "image fill must be emitted: {svg}");
    insta::assert_snapshot!(svg);
}

/// Image fills on paths must clip to the actual path geometry (not the bounding rect).
#[test]
fn exports_a_path_with_an_image_fill_clipped_to_path() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_filled_path(&mut pool, id, image_id);

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: "https://example.com/image.png",
    };
    let svg = render_with_images(&pool, id, &mut images);

    assert!(
        svg.contains("<clipPath"),
        "path image fill should emit a clipPath: {svg}"
    );
    assert!(
        svg.contains("<path"),
        "clipPath for path image fill must contain a <path> geometry: {svg}"
    );
    assert!(
        !svg.contains("<clipPath") || !svg.contains("<rect width=\"100\" height=\"80\""),
        "clipPath should not fall back to a simple bounding rect: {svg}"
    );
}

/// Linked images must apply the export page offset so they line up with other
/// SVG content (Skia fragments already scale/translate to the export bounds).
#[test]
fn exports_a_linked_image_at_page_offset() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(9);
    add_image_filled_rect(&mut pool, id, image_id, (100.0, 50.0, 200.0, 150.0));

    let mut images = FakeImages {
        id: image_id,
        image: tiny_image(),
        url: "https://example.com/image.png",
    };
    let svg = render_with_images(&pool, id, &mut images);
    assert!(
        svg.contains("xlink:href=\"https://example.com/image.png\""),
        "linked image must reference the source URL: {svg}"
    );
    assert!(
        svg.contains("matrix(1 0 0 1 -100 -50)"),
        "linked image wrapper must include the page offset: {svg}"
    );
    assert!(
        !svg.contains("<use "),
        "linked images should be a single <image>, not <use>: {svg}"
    );
}

/// A simple closed path (triangle) with a solid *inner* stroke.
#[test]
fn exports_a_path_with_a_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_path(
        &mut pool,
        id,
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Inner,
        10.0,
        skia::Color::from_rgb(0xFF, 0x00, 0x00),
    );

    insta::assert_snapshot!(render(&pool, id));
}

/// A simple closed path (triangle) with a solid *center* stroke.
#[test]
fn exports_a_path_with_a_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_path(
        &mut pool,
        id,
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Center,
        10.0,
        skia::Color::from_rgb(0x00, 0x88, 0x00),
    );

    insta::assert_snapshot!(render(&pool, id));
}

/// A simple closed path (triangle) with a solid *outer* stroke.
#[test]
fn exports_a_path_with_a_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_path(
        &mut pool,
        id,
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        10.0,
        skia::Color::from_rgb(0x00, 0x00, 0xFF),
    );

    let svg = render(&pool, id);
    // The outer stroke is composed as a nested `<g>` masked to the shape's
    // exterior (double-width stroke = 2 * 10). It must not vanish.
    assert!(
        svg.contains("<mask") && svg.contains("mask=\"url(#smask"),
        "outer path stroke must be composed via an exterior mask: {svg}"
    );
    assert!(
        svg.contains("stroke-width=\"20\""),
        "outer path stroke must emit the double-width stroke: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple rectangle with a *dotted inner* stroke. The GPU/PDF path clips a
/// boundary ring of dots to the shape interior inside a `save_layer` that
/// `SkSVGDevice` drops; the compositor re-emits it as a `<g clip-path>`.
#[test]
fn exports_a_shape_with_a_dotted_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_styled_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Inner,
        StrokeStyle::Dotted,
        10.0,
        skia::Color::from_rgb(0xFF, 0x00, 0x00),
    );

    let svg = render(&pool, id);
    // The dotted inner stroke must be composed as a nested `<g>` clipped to
    // the shape interior (it must not vanish with the dropped `save_layer`).
    assert!(
        svg.contains("<clipPath") && svg.contains("clip-path=\"url(#dclip"),
        "dotted inner stroke must be composed via an interior clip: {svg}"
    );
    // Dots are emitted as filled paths (not a dashed stroke).
    assert!(
        svg.contains("fill=\"red\""),
        "dotted inner stroke color must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A simple rectangle with a *dotted center* stroke.
#[test]
fn exports_a_shape_with_a_dotted_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_styled_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Center,
        StrokeStyle::Dotted,
        10.0,
        skia::Color::from_rgb(0x00, 0x88, 0x00),
    );

    insta::assert_snapshot!(render(&pool, id));
}

/// A simple rectangle with a *dotted outer* stroke. The GPU/PDF path clips a
/// boundary ring of dots to the shape exterior inside a `save_layer` that
/// `SkSVGDevice` drops; the compositor re-emits it masked to the exterior
/// (an inverse-of-shape luminance mask, since `<clipPath>` cannot subtract).
#[test]
fn exports_a_shape_with_a_dotted_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_styled_stroked_rect(
        &mut pool,
        id,
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0xCC, 0xCC, 0xCC),
        StrokeKind::Outer,
        StrokeStyle::Dotted,
        10.0,
        skia::Color::from_rgb(0x00, 0x00, 0xFF),
    );

    let svg = render(&pool, id);
    // The dotted outer stroke must be composed as a nested `<g>` masked to
    // the shape exterior (it must not vanish with the dropped `save_layer`).
    assert!(
        svg.contains("<mask") && svg.contains("mask=\"url(#dmask"),
        "dotted outer stroke must be composed via an exterior mask: {svg}"
    );
    // Dots are emitted as filled paths (not a dashed stroke).
    assert!(
        svg.contains("fill=\"blue\""),
        "dotted outer stroke color must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Simple text with a solid *inner* stroke. `SkSVGDevice` drops the
/// glyph-fill mask the shared renderer uses, so the compositor re-emits the
/// inner stroke clipped to the glyph silhouette (`<clipPath>`).
#[test]
fn exports_text_with_a_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,                                  // large font + thin stroke so the fill shows inside the glyph stems
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA), // pink fill
        Some((StrokeKind::Inner, 2.0, skia::Color::from_rgb(0x00, 0x00, 0xFF))), // blue stroke
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("<clipPath") && svg.contains("clip-path=\"url(#tclip"),
        "inner text stroke must be composed via a glyph-silhouette clip: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Simple text with a solid *center* stroke: drawn inline by the shared
/// renderer (a stroked `<text>` straddling the glyph outline).
#[test]
fn exports_text_with_a_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,                                  // large font + thin stroke so the fill shows inside the glyph stems
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA), // pink fill
        Some((StrokeKind::Center, 2.0, skia::Color::from_rgb(0x00, 0x00, 0xFF))), // blue stroke
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("stroke-width=\"2\"") && svg.contains("stroke=\"blue\""),
        "center text stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Simple text with a solid *outer* stroke.
#[test]
fn exports_text_with_a_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA), // pink fill
        Some((StrokeKind::Outer, 6.0, skia::Color::from_rgb(0x00, 0x00, 0xFF))), // blue stroke
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    // The outer stroke must be masked to the glyph exterior (keeping only its
    // outer half), not painted at full double width over the fill.
    assert!(
        svg.contains("<mask") && svg.contains("mask=\"url(#tmask"),
        "outer text stroke must be composed via an exterior mask: {svg}"
    );
    assert!(
        svg.contains("stroke=\"blue\""),
        "outer text stroke color must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// A filled shape with an *inner* shadow. The GPU/PDF path composes it inside
/// a `save_layer` (+ image filter) that `SkSVGDevice` drops, so the SVG
/// compositor must re-emit it as a native `<filter>` that darkens the shape
/// interior (blur+offset occluder, `operator=\"out\"`, then clipped back in).
#[test]
fn exports_a_shape_with_an_inner_shadow() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 120.0, 90.0),
        skia::Color::from_rgb(0xB1, 0xB2, 0xB5),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(0x66, 0x00, 0x00, 0x00), // #000000 @ ~0.4
            12.0,                                          // blur
            0.0,                                           // spread
            (8.0, 6.0),                                    // offset (x, y)
            ShadowStyle::Inner,
            false, // hidden
        ));
    }

    let svg = render(&pool, id);
    // The inner shadow must survive as a native SVG filter, not a dropped
    // `save_layer`, and must be clipped to the shape interior.
    assert!(
        svg.contains("filter=\"url(#inshadow"),
        "inner shadow must be emitted as an SVG filter: {svg}"
    );
    assert!(
        svg.contains("operator=\"out\"") && svg.contains("flood-color=\"#000000\""),
        "inner shadow must build a tinted interior band: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Text with an *inner* shadow: the shared renderer paints it via an
/// image-filter overlay dropped by `SkSVGDevice`, so the compositor re-emits
/// it over the glyph silhouette.
#[test]
fn exports_text_with_an_inner_shadow() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xEE, 0x19, 0x19), // red fill
        None,
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(0x99, 0x00, 0x00, 0x00),
            6.0,
            0.0,
            (4.0, 4.0),
            ShadowStyle::Inner,
            false,
        ));
    }

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("filter=\"url(#inshadow"),
        "text inner shadow must be emitted as an SVG filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

