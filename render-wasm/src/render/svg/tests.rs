use super::fixtures::*;

use crate::shapes::{BlendMode, Fill, SolidColor, StrokeKind};
use crate::state::ShapesPool;
use crate::uuid::Uuid;

use skia_safe as skia;

#[test]
fn exports_a_rect_with_multiple_solid_fills() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_rect_with_fills(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        vec![
            // fills[0] is topmost in Penpot (red 50%).
            Fill::Solid(SolidColor(skia::Color::from_argb(128, 245, 0, 0))),
            // fills[1] is underneath (blue 100%).
            Fill::Solid(SolidColor(skia::Color::from_rgb(0, 63, 255))),
        ],
    );

    let svg = render(&pool, id);
    assert!(
        svg.matches("fill=\"#").count() >= 2,
        "each solid fill must emit a fill attribute: {svg}"
    );
    let blue_pos = svg.to_ascii_lowercase().find("fill=\"#003fff\"");
    let red_pos = svg.to_ascii_lowercase().find("fill=\"#f50000\"");
    assert!(blue_pos.is_some(), "missing bottom blue fill: {svg}");
    assert!(red_pos.is_some(), "missing top red fill: {svg}");
    assert!(
        blue_pos.unwrap() < red_pos.unwrap(),
        "bottom fill must appear before top fill in SVG: {svg}"
    );
    insta::assert_snapshot!(svg);
}

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
fn exports_text_with_multiple_solid_fills() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text_with_fills(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        vec![
            Fill::Solid(SolidColor(skia::Color::from_argb(128, 245, 0, 0))),
            Fill::Solid(SolidColor(skia::Color::from_rgb(0, 63, 255))),
        ],
    );

    let svg = render(&pool, id);
    assert!(
        svg.matches("fill=\"#").count() >= 2,
        "each solid fill must emit a fill attribute: {svg}"
    );
    let blue_pos = svg.to_ascii_lowercase().find("fill=\"#003fff\"");
    let red_pos = svg.to_ascii_lowercase().find("fill=\"#f50000\"");
    assert!(blue_pos.is_some(), "missing bottom blue fill: {svg}");
    assert!(red_pos.is_some(), "missing top red fill: {svg}");
    assert!(
        blue_pos.unwrap() < red_pos.unwrap(),
        "bottom fill must appear before top fill in SVG: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        solid_stroke(StrokeKind::Inner, 10.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "inner stroke must emit a filled outline: {svg}"
    );
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "aligned stroke outline should use evenodd: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        solid_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke=\"blue\"") || svg.to_ascii_lowercase().contains("stroke=\"#0000ff\""),
        "center stroke must keep a stroke attribute: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "center stroke must not expand to an evenodd outline: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (20.0, 20.0, 120.0, 100.0),
        solid_stroke(StrokeKind::Outer, 10.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"red\"") || svg.to_ascii_lowercase().contains("fill=\"#ff0000\""),
        "outer stroke must emit a filled outline: {svg}"
    );
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "aligned stroke outline should use evenodd: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Inner, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "closed path inner stroke must emit a filled outline: {svg}"
    );
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "aligned stroke outline should use evenodd: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke=\"green\"") || svg.to_ascii_lowercase().contains("stroke=\"#008000\""),
        "closed path center stroke must keep a stroke attribute: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "center stroke must not expand to an evenodd outline: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Outer, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    // Path outer previously used save_layer+Clear (dropped by SkSVG). Must not
    // be a bare stroked path with no visible paint.
    assert!(
        svg.contains("fill=\"green\"") || svg.to_ascii_lowercase().contains("fill=\"#008000\""),
        "closed path outer stroke must emit a filled outline: {svg}"
    );
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "aligned stroke outline should use evenodd: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_solid_inner_stroke() {
    // Open paths force Center alignment regardless of the requested kind.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_open_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Inner, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke=\"blue\"") || svg.to_ascii_lowercase().contains("stroke=\"#0000ff\""),
        "open path inner stroke must render as center stroke: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "open path must not expand to an evenodd outline: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_open_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke=\"red\"") || svg.to_ascii_lowercase().contains("stroke=\"#ff0000\""),
        "open path center stroke must keep a stroke attribute: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "open path must not expand to an evenodd outline: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_solid_outer_stroke() {
    // Open paths force Center alignment regardless of the requested kind.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_open_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        solid_stroke(StrokeKind::Outer, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("stroke=\"green\"") || svg.to_ascii_lowercase().contains("stroke=\"#008000\""),
        "open path outer stroke must render as center stroke: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "open path must not expand to an evenodd outline: {svg}"
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
