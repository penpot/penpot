use super::fixtures::*;

use crate::shapes::{
    BlendMode, Fill, ImageFill, ImageFillTransform, SolidColor, StrokeCap, StrokeKind,
};
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
fn loads_svg_raw_dom_like_wasm_upload() {
    // Production paints svg-raw via Dom::render after set_shape_svg_raw_content.
    // Native SkSVGCanvas does not serialize those draws, so the export string
    // stays empty here; we assert Dom parse + that export does not panic.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_svg_raw(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 307.0, 243.0),
        concat!(
            r#"<svg xmlns="http://www.w3.org/2000/svg">"#,
            r#"<text x="10" y="24" fill="black">HOLA</text>"#,
            r#"</svg>"#,
        ),
    );

    let resources = crate::render::RenderResources::try_new_headless().expect("headless");
    let font_manager = skia::FontMgr::from(resources.fonts.font_provider().clone());
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.update_svg_raw_content(font_manager);
        assert!(shape.svg.is_some(), "Dom must parse like WASM upload");
    }

    let _svg = render(&pool, id);
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
fn exports_clipped_frame_with_solid_outer_stroke() {
    // Regression: frame content clip must not wrap strokes — outer strokes
    // sit outside the selrect and would be fully clipped away.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_frame(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 140.0, 100.0),
        skia::Color::from_rgb(0xee, 0xee, 0xee),
        true,
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_stroke(solid_stroke(
            StrokeKind::Outer,
            12.0,
            skia::Color::from_rgb(0x10, 0x40, 0xff),
        ));
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("clip-path=\"url(#"),
        "clipped frame must keep content clip: {svg}"
    );
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "outer stroke must emit an evenodd outline: {svg}"
    );
    let stroke_pos = svg.find("fill-rule=\"evenodd\"").expect("stroke outline");
    let clip_close = svg.find("</g>").expect("clip group close");
    assert!(
        stroke_pos > clip_close,
        "outer stroke must be outside the content clip group: {svg}"
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
fn exports_rect_with_per_side_solid_inner_stroke() {
    // Regression: solid Inner/Outer SVG expansion used stroke_to_path with a
    // uniform width and ignored stroke.widths. Per-side must use the GPU
    // evenodd band (top/right/bottom/left).
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = solid_stroke(
        StrokeKind::Inner,
        20.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([4.0, 12.0, 24.0, 40.0]); // top, right, bottom, left
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 140.0, 100.0), stroke);
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_fills(vec![Fill::Solid(SolidColor(skia::Color::from_rgb(
            0xff, 0xd4, 0x00,
        )))]);
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "per-side inner stroke must emit an evenodd band: {svg}"
    );
    // Inner hole for (0,0)-(140,100) with [4,12,24,40]: (40,4)-(128,76).
    // Uniform width=20 would incorrectly hole at (20,20)-(120,80).
    assert!(
        svg.contains("40") && svg.contains("128") && svg.contains("76"),
        "per-side hole must reflect left=40 / right=12 / bottom=24, got: {svg}"
    );
    assert!(
        !svg.contains("M20 20") && !svg.contains("L20 20"),
        "must not use uniform width=20 inset: {svg}"
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
fn exports_rotated_rect_with_solid_outer_stroke() {
    // Regression: outline strokes must use local selrect geometry. Baking
    // `centered_transform` into the path (via rect_segments) while the leaf
    // canvas also concatenates it double-rotates the stroke.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 140.0, 100.0),
        solid_stroke(
            StrokeKind::Outer,
            12.0,
            skia::Color::from_rgb(0x10, 0x40, 0xff),
        ),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        // 30° rotation (cos≈0.866, sin=0.5), matching a workspace export case.
        let c = 0.866_025_4_f32;
        let s = 0.5_f32;
        shape.set_transform(c, s, -s, c, 0.0, 0.0);
        shape.set_rotation(30.0);
        shape.set_fills(vec![Fill::Solid(SolidColor(skia::Color::from_rgb(
            0xff, 0xd4, 0x00,
        )))]);
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "rotated outer stroke must emit an evenodd outline: {svg}"
    );
    // Fill rect + stroke path should both carry the same leaf CTM (one rotation).
    assert!(
        svg.matches("matrix(0.866025").count() >= 2,
        "fill and stroke must each use the rotation matrix once: {svg}"
    );
    // Outline path data must stay in local selrect space (roughly [-stroke, w+stroke]).
    // Double rotation bakes world-space points into `d` before the CTM is applied.
    let d_attr = svg
        .split("d=\"")
        .nth(1)
        .and_then(|s| s.split('"').next())
        .unwrap_or("");
    let first_num = d_attr
        .trim_start_matches(|c: char| !c.is_ascii_digit() && c != '-' && c != '.')
        .split(|c: char| !c.is_ascii_digit() && c != '-' && c != '.')
        .find(|s| !s.is_empty())
        .and_then(|s| s.parse::<f32>().ok());
    assert!(
        matches!(first_num, Some(n) if (-40.0..180.0).contains(&n)),
        "stroke path d= should start in local coords, got {first_num:?} from {d_attr}: {svg}"
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
fn exports_rotated_closed_path_with_solid_inner_stroke() {
    // Regression: path content is stored in parent space; stroke outlines must
    // apply `to_path_transform` (like fills via get_skia_path) before drawing
    // under the leaf `centered_transform`, or the stroke double-rotates.
    use crate::shapes::Type;

    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 140.0, 100.0),
        solid_stroke(
            StrokeKind::Inner,
            12.0,
            skia::Color::from_rgb(0x10, 0x40, 0xff),
        ),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        let c = 0.866_025_4_f32;
        let s = 0.5_f32;
        shape.set_transform(c, s, -s, c, 0.0, 0.0);
        shape.set_rotation(30.0);
        shape.set_fills(vec![Fill::Solid(SolidColor(skia::Color::from_rgb(
            0xff, 0xd4, 0x00,
        )))]);

        // Bake rotation into path points (Penpot path storage model).
        let bake = shape.centered_transform();
        if let Type::Path(ref mut path) = shape.shape_type {
            path.transform(&bake);
            let b = path.bounds();
            shape.set_selrect(b.min_x(), b.min_y(), b.max_x(), b.max_y());
        }
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill-rule=\"evenodd\""),
        "rotated path inner stroke must emit an evenodd outline: {svg}"
    );
    assert!(
        svg.matches("matrix(0.866025").count() >= 2,
        "fill and stroke must each use the rotation matrix once: {svg}"
    );
    // Stroke outline `d` must stay in local (unrotated) space like the fill.
    let stroke_d = svg
        .split("fill-rule=\"evenodd\"")
        .next()
        .and_then(|before| before.rsplit("d=\"").next())
        .and_then(|s| s.split('"').next())
        .unwrap_or("");
    let first_num = stroke_d
        .trim_start_matches(|c: char| !c.is_ascii_digit() && c != '-' && c != '.')
        .split(|c: char| !c.is_ascii_digit() && c != '-' && c != '.')
        .find(|s| !s.is_empty())
        .and_then(|s| s.parse::<f32>().ok());
    assert!(
        matches!(first_num, Some(n) if (-40.0..180.0).contains(&n)),
        "stroke path d= should start in local coords, got {first_num:?} from {stroke_d}: {svg}"
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
fn exports_rect_with_dotted_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        dotted_stroke(StrokeKind::Inner, 10.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "dotted inner stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_dotted_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        dotted_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    // PathEffect does not serialize; dots expand to filled outline geometry.
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "dotted center stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_dotted_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (20.0, 20.0, 120.0, 100.0),
        dotted_stroke(StrokeKind::Outer, 10.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"red\"") || svg.to_ascii_lowercase().contains("fill=\"#ff0000\""),
        "dotted outer stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_dotted_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        dotted_stroke(StrokeKind::Inner, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "closed path dotted inner stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_dotted_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        dotted_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"green\"") || svg.to_ascii_lowercase().contains("fill=\"#008000\""),
        "closed path dotted center stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_dotted_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        dotted_stroke(StrokeKind::Outer, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"green\"") || svg.to_ascii_lowercase().contains("fill=\"#008000\""),
        "closed path dotted outer stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_dotted_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_open_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        dotted_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"red\"") || svg.to_ascii_lowercase().contains("fill=\"#ff0000\""),
        "open path dotted stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_dotted_stroke_and_caps() {
    // Regression: dotted/dashed SVG expansion used stroke_to_path and returned
    // before draw_stroke_geometry, so open-path caps (triangle/circle/…) were
    // dropped. `stroke_to_path` now unions them into the expanded outline.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = dotted_stroke(
        StrokeKind::Center,
        12.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.cap_start = Some(StrokeCap::TriangleArrow);
    stroke.cap_end = Some(StrokeCap::CircleMarker);
    add_stroked_open_path(&mut pool, id, Uuid::nil(), (0.0, 0.0, 140.0, 90.0), stroke);

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"#1040FF\"") || svg.to_ascii_lowercase().contains("fill=\"#1040ff\""),
        "dotted stroke with caps must emit filled geometry: {svg}"
    );
    // Caps are unioned into the expanded outline by `stroke_to_path`, so they
    // must not be emitted as extra draws: overlapping draws would double the
    // alpha of translucent strokes. The snapshot below covers the geometry.
    assert_eq!(
        svg.matches("<path ").count(),
        1,
        "caps must be part of the outline, not extra draws: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_dashed_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        dashed_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "dashed center stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_dashed_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (20.0, 20.0, 120.0, 100.0),
        dashed_stroke(StrokeKind::Outer, 10.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"red\"") || svg.to_ascii_lowercase().contains("fill=\"#ff0000\""),
        "dashed outer stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_dashed_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        dashed_stroke(StrokeKind::Inner, 8.0, skia::Color::from_rgb(0, 128, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"green\"") || svg.to_ascii_lowercase().contains("fill=\"#008000\""),
        "closed path dashed inner stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_mixed_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        mixed_stroke(StrokeKind::Center, 8.0, skia::Color::from_rgb(0, 0, 255)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"blue\"") || svg.to_ascii_lowercase().contains("fill=\"#0000ff\""),
        "mixed center stroke must emit filled geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_mixed_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        mixed_stroke(StrokeKind::Outer, 8.0, skia::Color::from_rgb(255, 0, 0)),
    );

    let svg = render(&pool, id);
    assert!(
        svg.contains("fill=\"red\"") || svg.to_ascii_lowercase().contains("fill=\"#ff0000\""),
        "closed path mixed outer stroke must emit filled geometry: {svg}"
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

#[test]
fn exports_image_fill_on_text() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_image_text(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        image_id,
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "text image fill must emit a linked <image>: {svg}"
    );
    assert!(
        svg.contains("clip-path=\"url(#"),
        "text image fill must be clipped to glyph silhouette: {svg}"
    );
    assert!(
        svg.contains("<clipPath ") && svg.contains("<text"),
        "clipPath must contain text glyphs: {svg}"
    );
    assert!(
        !svg.contains("data:image"),
        "must not base64-embed the image: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_image_fill_as_linked_image() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_image_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        image_id,
        true,
        255,
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("<image"),
        "image fill must emit an <image> element: {svg}"
    );
    assert!(
        svg.contains(TEST_IMAGE_URL),
        "image href must use the registered URL: {svg}"
    );
    assert!(
        svg.contains("preserveAspectRatio=\"xMidYMid slice\""),
        "keep-aspect image fill must slice: {svg}"
    );
    assert!(
        svg.contains("clip-path=\"url(#"),
        "image fill must be clipped to shape geometry: {svg}"
    );
    assert!(
        !svg.contains("data:image"),
        "must not base64-embed the image: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_image_fill_bounds_transform() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_rect_with_fills(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        vec![Fill::Image(ImageFill::new_with_transform(
            image_id,
            255,
            200,
            100,
            false,
            Some(ImageFillTransform {
                x: 0.25,
                y: 0.5,
                width: 0.5,
                height: 0.25,
            }),
        ))],
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains(r#"x="25" y="40" width="50" height="20""#),
        "linked image must keep the independent image bounds: {svg}"
    );
}

#[test]
fn exports_mixed_solid_and_image_fills_in_order() {
    // Image under a translucent solid; stretch (keep-aspect off); partial image
    // opacity; shape not at the page origin (page translate in CTM).
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_rect_with_fills(
        &mut pool,
        id,
        Uuid::nil(),
        (100.0, 50.0, 508.0, 178.0),
        vec![
            // fills[0] topmost — solid blue @ 50%
            Fill::Solid(SolidColor(skia::Color::from_argb(128, 0, 63, 255))),
            // fills[1] underneath — linked image, stretch, ~50% opacity
            Fill::Image(ImageFill::new(image_id, 128, 400, 300, false)),
        ],
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    let image_pos = svg.find("<image");
    let blue_pos = svg.to_ascii_lowercase().find("fill=\"#003fff\"");
    assert!(image_pos.is_some(), "missing image fill: {svg}");
    assert!(blue_pos.is_some(), "missing top solid fill: {svg}");
    assert!(
        image_pos.unwrap() < blue_pos.unwrap(),
        "image (bottom) must appear before solid (top): {svg}"
    );
    assert!(
        svg.contains("preserveAspectRatio=\"none\""),
        "mixed image fill should stretch when keep-aspect is off: {svg}"
    );
    // 128/255 → ~0.50196 as f32 (not a rounded "0.5").
    assert!(
        svg.contains("opacity=\"0.5019608\""),
        "image fill opacity must be emitted: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_image_fill_on_closed_path() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_image_path(&mut pool, id, Uuid::nil(), true, image_id);

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "closed path must emit a linked image fill: {svg}"
    );
    assert!(
        svg.contains("clip-path=\"url(#"),
        "image fill must be clipped to the path: {svg}"
    );
    // Clip geometry should be a path (triangle), not a plain rect.
    assert!(
        svg.contains("<path") || svg.contains(" d=\""),
        "closed-path clip must use path geometry: {svg}"
    );
    assert!(
        svg.contains("preserveAspectRatio=\"xMidYMid slice\""),
        "keep-aspect image fill on path: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_image_fill_on_open_path() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_image_path(&mut pool, id, Uuid::nil(), false, image_id);

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "open path must still emit a linked image fill: {svg}"
    );
    assert!(
        svg.contains("clip-path=\"url(#"),
        "image fill must be clipped to the open path geometry: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_image_fill_on_frame() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_image_frame(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        image_id,
        false,
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "frame background must emit a linked image fill: {svg}"
    );
    assert!(
        svg.contains("clip-path=\"url(#"),
        "frame image fill must be clipped to the frame: {svg}"
    );
    assert!(
        svg.contains("preserveAspectRatio=\"xMidYMid slice\""),
        "frame image fill should keep aspect: {svg}"
    );
    // No nested board clip when clip_content is off.
    assert!(
        !svg.contains("id=\"clip0\""),
        "unclipped frame should not wrap children in a board clip: {svg}"
    );
    insta::assert_snapshot!(svg);
}

fn assert_linked_image_stroke(svg: &str) {
    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "image stroke must emit a linked <image>: {svg}"
    );
    assert!(
        svg.contains("imgstrokeclip") && svg.contains("clip-path=\"url(#"),
        "image stroke must clip to the stroke outline: {svg}"
    );
    assert!(
        !svg.contains("data:image"),
        "must not base64-embed the stroke image: {svg}"
    );
}

fn assert_evenodd_stroke_clip(svg: &str) {
    assert!(
        svg.contains("clip-rule=\"evenodd\""),
        "stroke clip must use clip-rule=evenodd: {svg}"
    );
    assert!(
        !svg.contains("fill-rule=\"evenodd\""),
        "clipPath should rewrite fill-rule to clip-rule: {svg}"
    );
}

#[test]
fn exports_rect_with_solid_center_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        image_solid_stroke(StrokeKind::Center, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_solid_inner_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        image_solid_stroke(StrokeKind::Inner, 10.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_solid_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (20.0, 20.0, 120.0, 100.0),
        image_solid_stroke(StrokeKind::Outer, 10.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_rect_with_dotted_center_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (10.0, 10.0, 110.0, 90.0),
        image_dotted_stroke(StrokeKind::Center, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_solid_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        image_solid_stroke(StrokeKind::Outer, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_solid_center_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_open_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        image_solid_stroke(StrokeKind::Center, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_open_path_with_image_stroke_and_caps() {
    // Caps are part of the clip silhouette (unioned into the outline). Image
    // dest must grow past stroke.delta() so triangle/circle markers stay textured.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    let mut stroke = image_solid_stroke(StrokeKind::Center, 12.0, image_id);
    stroke.cap_start = Some(StrokeCap::TriangleArrow);
    stroke.cap_end = Some(StrokeCap::CircleMarker);
    add_stroked_open_path(&mut pool, id, Uuid::nil(), (0.0, 0.0, 140.0, 90.0), stroke);

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    let clip = svg
        .split("<clipPath")
        .nth(1)
        .and_then(|s| s.split("</clipPath>").next())
        .expect("imgstroke clipPath");
    // `stroke_to_path` unions the caps into the outline, so the clip is a
    // single path covering both.
    assert_eq!(
        clip.matches("<path ").count(),
        1,
        "clip must be the outline with the caps unioned in: {svg}"
    );
    // TriangleArrow margin is width*4 = 48.
    assert!(
        svg.contains(r#"x="-48""#)
            && svg.contains(r#"y="-48""#)
            && svg.contains(r#"width="236""#)
            && svg.contains(r#"height="186""#),
        "image dest must cover cap margin (48), not only stroke.delta: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_closed_path_with_dotted_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_stroked_closed_path(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        image_dotted_stroke(StrokeKind::Outer, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert_linked_image_stroke(&svg);
    assert_evenodd_stroke_clip(&svg);
    insta::assert_snapshot!(svg);
}
