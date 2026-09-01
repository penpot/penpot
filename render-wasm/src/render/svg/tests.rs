use super::fixtures::*;

use crate::shapes::BlendMode;
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
    assert!(
        svg.contains("opacity=\"0.5\""),
        "missing opacity wrapper: {svg}"
    );
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

    add_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 100.0),
        &[a, b],
    );
    {
        let group = pool.get_mut(&group_id).unwrap();
        group.set_opacity(0.7);
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

    let svg = render(&pool, group_id);
    assert!(
        svg.contains("opacity=\"0.7\""),
        "missing group opacity wrapper: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_a_clipped_frame_with_overflowing_child() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);

    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(240, 240, 240),
        true,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    // Child extends past the frame's right/bottom edge.
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (50.0, 50.0, 150.0, 150.0),
        skia::Color::from_rgb(255, 0, 0),
    );

    let svg = render(&pool, frame_id);
    assert!(
        svg.contains("clip-path=\"url(#"),
        "missing frame clip-path wrapper: {svg}"
    );
    assert!(svg.contains("<clipPath "), "missing clipPath def: {svg}");
    // Clipped boards keep the frame's own page size.
    assert!(
        svg.contains("width=\"100\" height=\"100\""),
        "clipped frame should export at selrect size: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_an_unclipped_frame_with_overflowing_child() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);

    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(240, 240, 240),
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    // Child extends past the frame's right/bottom edge.
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (50.0, 50.0, 150.0, 150.0),
        skia::Color::from_rgb(255, 0, 0),
    );

    let svg = render(&pool, frame_id);
    assert!(
        !svg.contains("clip-path=\"url(#"),
        "unclipped frame must not emit clip-path: {svg}"
    );
    // Page must grow to include the overflowing child (0..150).
    assert!(
        svg.contains("width=\"150\" height=\"150\""),
        "unclipped frame should export at extrect size: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_solid_text_with_font_face() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("@font-face") && svg.contains(TEST_FONT_URL),
        "missing @font-face for registered font URL: {svg}"
    );
    // Fixed-size text exports at the selrect, not tight glyph bounds.
    assert!(
        svg.contains("width=\"560\" height=\"240\""),
        "fixed text should export at selrect size: {svg}"
    );
    insta::assert_snapshot!(svg);
}
