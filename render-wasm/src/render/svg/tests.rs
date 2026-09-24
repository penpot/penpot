use super::fixtures::*;

use crate::shapes::{
    radius_to_sigma, BlendMode, Blur, BlurType, Fill, ImageFill, ImageFillTransform, Shadow,
    ShadowStyle, SolidColor, StrokeCap, StrokeKind,
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
fn exports_leaf_background_blur_as_foreign_object() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_argb(180, 255, 0, 0),
    );
    let blur_value = 10.0;
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, false, blur_value)));
    }

    let svg = render(&pool, id);
    let expected_sigma = radius_to_sigma(blur_value * 1.0);
    assert!(
        svg.contains("<foreignObject") && svg.contains("backdrop-filter:blur("),
        "background blur must emit foreignObject + backdrop-filter: {svg}"
    );
    assert!(
        svg.contains(&format!("backdrop-filter:blur({expected_sigma}px)")),
        "blur sigma must match radius_to_sigma(value * scale): {svg}"
    );
    assert!(
        svg.contains("clip-path:path(")
            && (svg.contains("path(nonzero,'") || svg.contains("path(evenodd,'")),
        "FO div must use CSS path clip with a fill rule: {svg}"
    );
    // FO before fill paint.
    let fo_pos = svg.find("<foreignObject").expect("foreignObject");
    let fill_pos = svg.find("fill=").expect("fill");
    assert!(
        fo_pos < fill_pos,
        "background blur FO must precede fills: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn skips_hidden_background_blur() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, true, 10.0)));
    }

    let svg = render(&pool, id);
    assert!(
        !svg.contains("foreignObject") && !svg.contains("backdrop-filter"),
        "hidden background blur must not emit FO: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn background_blur_coexists_with_layer_blur_outside_filter() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_argb(180, 0, 128, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, false, 8.0)));
        shape.set_blur(Some(Blur::new(BlurType::LayerBlur, false, 4.0)));
    }

    let svg = render(&pool, id);
    let fo_pos = svg.find("<foreignObject").expect("foreignObject");
    let filter_pos = svg.find("filter=\"url(#fx").expect("layer blur filter");
    assert!(
        fo_pos < filter_pos,
        "FO must sit outside the layer-blur filter group: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_frame_background_blur_before_children() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_argb(100, 255, 255, 255),
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, false, 6.0)));
    }
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (20.0, 20.0, 80.0, 80.0),
        skia::Color::from_rgb(0, 0, 255),
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    let svg = render(&pool, frame_id);
    let expected_sigma = radius_to_sigma(6.0);
    assert!(
        svg.contains(&format!("backdrop-filter:blur({expected_sigma}px)")),
        "frame background blur: {svg}"
    );
    let fo_pos = svg.find("<foreignObject").expect("foreignObject");
    // Child rect is painted after the frame FO (and after any frame fill).
    let child_marker = "width=\"60\" height=\"60\"";
    let child_pos = svg.find(child_marker).expect("child rect size");
    assert!(fo_pos < child_pos, "frame FO must precede children: {svg}");
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_group_background_blur_selrect_clip() {
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let a = uid(2);
    add_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 100.0),
        &[a],
    );
    {
        let group = pool.get_mut(&group_id).unwrap();
        group.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, false, 5.0)));
    }
    add_solid_rect(
        &mut pool,
        a,
        group_id,
        (10.0, 10.0, 90.0, 90.0),
        skia::Color::from_rgb(0, 200, 0),
    );

    let svg = render(&pool, group_id);
    assert!(
        svg.contains("foreignObject") && svg.contains("clip-path:path("),
        "group background blur: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_text_background_blur_with_glyph_clip() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_text(
        &mut pool,
        id,
        (0.0, 0.0, 200.0, 80.0),
        "Glass",
        40.0,
        skia::Color::from_argb(200, 255, 255, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_background_blur(Some(Blur::new(BlurType::BackgroundBlur, false, 8.0)));
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("foreignObject")
            && svg.contains("backdrop-filter:blur(")
            && svg.contains("clip-path:path("),
        "text background blur needs FO with CSS glyph path clip: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_leaf_layer_blur_as_fe_gaussian_blur() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    let blur_value = 10.0;
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_blur(Some(Blur::new(BlurType::LayerBlur, false, blur_value)));
    }

    let svg = render(&pool, id);
    let expected_sigma = radius_to_sigma(blur_value * 1.0);
    assert!(
        svg.contains("<filter") && svg.contains("feGaussianBlur"),
        "layer blur must emit an SVG filter: {svg}"
    );
    assert!(
        svg.contains(&format!("stdDeviation=\"{expected_sigma}\"")),
        "stdDeviation must match canvas radius_to_sigma(value * scale): {svg}"
    );
    assert!(
        svg.contains("filter=\"url(#fx"),
        "shape group must reference the effects filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn skips_hidden_layer_blur() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.set_blur(Some(Blur::new(BlurType::LayerBlur, true, 10.0)));
    }

    let svg = render(&pool, id);
    assert!(
        !svg.contains("feGaussianBlur") && !svg.contains("filter=\"url(#fx"),
        "hidden layer blur must not emit a filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_group_layer_blur_wrapping_children() {
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
        group.set_blur(Some(Blur::new(BlurType::LayerBlur, false, 6.0)));
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
    let expected_sigma = radius_to_sigma(6.0);
    assert!(
        svg.contains(&format!("stdDeviation=\"{expected_sigma}\"")),
        "group layer blur stdDeviation: {svg}"
    );
    // Filter wrapper must open before child geometry.
    let filter_pos = svg.find("filter=\"url(#fx").expect("group filter wrapper");
    let child_pos = svg.find("fill=\"#").expect("child fill");
    assert!(
        filter_pos < child_pos,
        "group blur must wrap children: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_leaf_drop_shadow_as_svg_filter() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(128, 0, 0, 0),
            8.0,
            0.0,
            (4.0, 6.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, id);
    let expected_sigma = radius_to_sigma(8.0);
    // Geometry leaves: offset is geometric (silhouette), filter only blurs/tints
    // (matches GPU — no feMorphology, no SourceGraphic in the drop filter).
    // Page padding shifts both passes; silhouette is padding+(4,6).
    assert!(
        svg.contains(r#"translate(15.3564 15.3564)"#),
        "drop silhouette must include geometric offset (4,6) over page pad: {svg}"
    );
    assert!(
        svg.contains(r#"translate(11.3564 9.35641)"#),
        "content pass must stay at page pad without drop offset: {svg}"
    );
    assert!(
        svg.contains(&format!("stdDeviation=\"{expected_sigma}\"")),
        "drop blur sigma must match canvas: {svg}"
    );
    assert!(
        svg.contains("filter=\"url(#fx"),
        "drop shadow must emit a filter: {svg}"
    );
    let filter_open = svg.find("<filter id=\"fx").expect("filter def");
    let filter_close = svg[filter_open..]
        .find("</filter>")
        .map(|i| filter_open + i)
        .expect("filter close");
    let filter = &svg[filter_open..filter_close];
    assert!(
        !filter.contains("SourceGraphic"),
        "leaf drop silhouette filter must not blend SourceGraphic: {filter}"
    );
    assert!(
        !filter.contains("feMorphology"),
        "leaf drop must not use feMorphology for spread: {filter}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn leaf_drop_offset_follows_rotation_in_user_space() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        // 90° CCW: local (+10, 0) → user-space (0, 10).
        let (c, s) = (0.0_f32, 1.0_f32);
        shape.set_transform(c, s, -s, c, 0.0, 0.0);
        shape.set_rotation(90.0);
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(128, 56, 0, 238),
            0.0,
            0.0,
            (10.0, 0.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, id);
    // Geometric silhouette: local offset is pre_translated before rotation.
    let filter_open = svg.find("filter=\"url(#fx").expect("drop filter");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("silhouette group close");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        silhouette.contains("matrix(") || silhouette.contains("translate("),
        "rotated leaf drop silhouette must carry a CTM: {silhouette}"
    );
    // Filter itself keeps zero offset (geometry moved instead).
    let def_start = svg.find("<filter id=\"fx").expect("filter def");
    let def_end = svg[def_start..]
        .find("</filter>")
        .map(|i| def_start + i)
        .expect("filter close");
    let filter = &svg[def_start..def_end];
    assert!(
        filter.contains(r#"dx="0""#) && filter.contains(r#"dy="0""#),
        "rotated leaf drop filter offset must stay zero: {filter}"
    );
    assert!(
        !filter.contains(r#"dx="10""#),
        "must not keep unmapped local dx in the filter: {filter}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn small_leaf_drop_shadow_filter_covers_page_not_bbox_percent() {
    // 16×16 + offset(4,4) blur 4: objectBoundingBox ±50% only leaves 8px margin,
    // but reach is ~|offset|+3σ ≈ 12px — corners crop unless the filter is
    // sized in userSpaceOnUse to the page (already padded via extrect).
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 16.0, 16.0),
        skia::Color::from_rgb(61, 123, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(153, 0, 0, 0),
            4.0,
            0.0,
            (4.0, 4.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, id);
    assert_filter_covers_page(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn large_blur_leaf_drop_shadow_filter_covers_page_not_bbox_percent() {
    // 100×100 blur 40: ±50% of bbox = 50px, 3σ≈71px — halo crops with
    // objectBoundingBox percentages.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 100.0),
        skia::Color::from_rgb(61, 123, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(178, 0, 0, 0),
            40.0,
            0.0,
            (0.0, 0.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, id);
    assert_filter_covers_page(&svg);
    let sigma = radius_to_sigma(40.0);
    assert!(
        svg.contains(&format!("stdDeviation=\"{sigma}\"")),
        "large blur sigma must remain in the filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn sliver_leaf_drop_shadow_filter_covers_page_not_bbox_percent() {
    // 200×2 + offset(0,12) blur 6: objectBoundingBox height is only 4px —
    // the shadow disappears. userSpaceOnUse page region keeps it.
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 2.0),
        skia::Color::from_rgb(61, 123, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(204, 0, 0, 0),
            6.0,
            0.0,
            (0.0, 12.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, id);
    assert_filter_covers_page(&svg);
    // Geometric offset (0,12): silhouette y = page_pad_y + 12, content y = page_pad_y.
    assert!(
        svg.contains(r#"translate(11.8923 12)"#) && svg.contains(r#"translate(11.8923 0)"#),
        "sliver drop must apply geometric offset (0,12): {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_leaf_inner_shadow_as_svg_filter() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(0, 128, 255),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_argb(180, 0, 0, 0),
            6.0,
            0.0,
            (2.0, 3.0),
            ShadowStyle::Inner,
            false,
        ));
    }

    let svg = render(&pool, id);
    assert!(
        svg.contains("feComposite") && svg.contains("hardAlpha"),
        "inner shadow must use classic composite graph: {svg}"
    );
    assert!(
        svg.contains("filter=\"url(#fx"),
        "inner shadow must wrap the shape: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn skips_hidden_shadows() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_solid_rect(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 100.0, 80.0),
        skia::Color::from_rgb(255, 0, 0),
    );
    {
        let shape = pool.get_mut(&id).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::BLACK,
            8.0,
            0.0,
            (4.0, 4.0),
            ShadowStyle::Drop,
            true,
        ));
    }

    let svg = render(&pool, id);
    assert!(
        !svg.contains("feOffset") && !svg.contains("filter=\"url(#fx"),
        "hidden shadow must not emit a filter: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_frame_drop_shadow_wrapping_children() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_rgb(240, 240, 240),
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_shadow(Shadow::new(
            skia::Color::from_argb(100, 0, 0, 0),
            10.0,
            0.0,
            (0.0, 8.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (20.0, 20.0, 100.0, 80.0),
        skia::Color::from_rgb(0, 200, 0),
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    let svg = render(&pool, frame_id);
    assert!(
        svg.contains("filter=\"url(#fx"),
        "frame drop shadow must emit a filter: {svg}"
    );
    assert!(
        !svg.contains("SourceGraphic"),
        "container drop filter must be shadow-only (no SourceGraphic): {svg}"
    );
    // Silhouette under the filter, then real content without nesting the filter.
    let filter_pos = svg.find("filter=\"url(#fx").expect("frame filter");
    let child_pos = svg.find("fill=\"#").expect("child fill");
    assert!(
        filter_pos < child_pos,
        "frame shadow silhouette must precede content: {svg}"
    );
    let fill_count = svg.matches("fill=\"#").count();
    assert!(
        fill_count >= 2,
        "silhouette + content must both draw fills: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn clipped_frame_drop_shadow_silhouette_skips_content_clip() {
    // clip=ON + drop offset: GPU paints the drop outside the content clip, so
    // the silhouette must not use clipPath (F1a). Otherwise an unshifted clip
    // truncates the offset shadow.
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_rgb(240, 240, 240),
        true,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_shadow(Shadow::new(
            skia::Color::from_argb(140, 0, 0, 0),
            0.0,
            0.0,
            (0.0, 24.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (20.0, 20.0, 180.0, 100.0),
        skia::Color::from_rgb(0, 200, 0),
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    let svg = render(&pool, frame_id);
    assert!(
        svg.contains("filter=\"url(#fx"),
        "clipped frame drop shadow must emit a filter: {svg}"
    );

    let filter_open = svg.find("filter=\"url(#fx").expect("frame filter");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("silhouette group close");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        !silhouette.contains("clip-path="),
        "drop silhouette must not use content clip: {silhouette}"
    );
    assert!(
        silhouette.contains(r#"translate(0 24)"#),
        "silhouette fills must still apply drop offset: {silhouette}"
    );
    assert!(
        svg.contains("<clipPath") && svg.contains("clip-path=\"url(#"),
        "content pass must still clip: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn clipped_frame_drop_spread_is_not_truncated_by_content_clip() {
    // clip=ON + spread 24, offset 0: fills outset in the silhouette, but if the
    // content clipPath stays at the true selrect the red ring is cut away.
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_rgb(240, 240, 240),
        true,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_shadow(Shadow::new(
            skia::Color::from_rgb(229, 16, 35),
            0.0,
            24.0,
            (0.0, 0.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (20.0, 20.0, 180.0, 100.0),
        skia::Color::from_rgb(0, 200, 0),
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    let svg = render(&pool, frame_id);
    let filter_open = svg.find("filter=\"url(#fx").expect("frame filter");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("silhouette group close");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        !silhouette.contains("clip-path="),
        "spread silhouette must not be content-clipped: {silhouette}"
    );
    // 200×120 + 2×24 spread, and child 160×80 + 2×24.
    assert!(
        silhouette.contains(r#"width="248""#) && silhouette.contains(r#"height="168""#),
        "frame fill must outset by spread 24: {silhouette}"
    );
    assert!(
        silhouette.contains(r#"width="208""#) && silhouette.contains(r#"height="128""#),
        "child fill must outset by inherited spread 24: {silhouette}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn group_drop_silhouette_shifts_image_fill_children() {
    // Container drop silhouettes must offset linked <image> fills the same way
    // as solid fills (F3). Clip already follows silhouette_offset; the image
    // CTM must use draw_matrix too.
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let image_child = uid(2);
    let solid_child = uid(3);
    let image_id = uid(42);

    add_image_rect(
        &mut pool,
        image_child,
        group_id,
        (0.0, 0.0, 120.0, 120.0),
        image_id,
        true,
        255,
    );
    add_solid_rect(
        &mut pool,
        solid_child,
        group_id,
        (170.0, 0.0, 290.0, 120.0),
        skia::Color::from_rgb(61, 123, 255),
    );
    add_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 290.0, 120.0),
        &[image_child, solid_child],
    );
    {
        let group = pool.get_mut(&group_id).unwrap();
        group.add_shadow(Shadow::new(
            skia::Color::from_rgb(229, 16, 35),
            0.0,
            0.0,
            (40.0, 40.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render_with(&pool, group_id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });

    assert!(
        svg.contains("filter=\"url(#fx"),
        "group drop shadow must emit a filter: {svg}"
    );

    let filter_open = svg.find("filter=\"url(#fx").expect("group filter");
    let silhouette = &svg[filter_open..];
    // Content pass repeats the image without the silhouette offset matrix.
    let content_image = silhouette
        .match_indices("<image")
        .nth(1)
        .map(|(i, _)| filter_open + i);
    let silhouette = match content_image {
        Some(end) => &svg[filter_open..end],
        None => silhouette,
    };

    assert!(
        silhouette.contains("<image") && silhouette.contains(TEST_IMAGE_URL),
        "silhouette must include the image-fill child: {silhouette}\nfull: {svg}"
    );
    assert!(
        silhouette.contains("fill=\"#3D7BFF\"") || silhouette.contains("fill=\"#3d7bff\""),
        "silhouette must include the solid child: {silhouette}\nfull: {svg}"
    );
    assert!(
        silhouette.contains(r#"translate(40 40)"#),
        "solid silhouette child must apply group drop offset: {silhouette}\nfull: {svg}"
    );
    assert!(
        silhouette.contains("matrix(1 0 0 1 40 40)"),
        "image silhouette child must apply the same local offset via draw_matrix: {silhouette}\nfull: {svg}"
    );

    insta::assert_snapshot!(svg);
}

#[test]
fn nested_frame_fill_outsets_under_parent_drop_spread() {
    // Outer board: no fill, drop spread 24. Nested board fill must outset by
    // the inherited silhouette_spread (F5) — same as leaf children via
    // render_leaf. Hardcoding 0.0 on the nested frame content pass left the
    // nested board hugging its true edge while the leaf got the red ring.
    let mut pool = ShapesPool::new();
    let outer = uid(1);
    let nested = uid(2);
    let leaf = uid(3);

    add_frame(
        &mut pool,
        outer,
        Uuid::nil(),
        (0.0, 0.0, 340.0, 220.0),
        skia::Color::TRANSPARENT,
        false,
    );
    {
        let frame = pool.get_mut(&outer).unwrap();
        frame.clear_fills();
        frame.add_shadow(Shadow::new(
            skia::Color::from_rgb(229, 16, 35),
            0.0,
            24.0,
            (0.0, 0.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_frame(
        &mut pool,
        nested,
        outer,
        (20.0, 30.0, 200.0, 190.0),
        skia::Color::from_rgb(61, 123, 255),
        false,
    );
    add_solid_rect(
        &mut pool,
        leaf,
        outer,
        (250.0, 70.0, 310.0, 130.0),
        skia::Color::from_rgb(0, 200, 0),
    );
    {
        let frame = pool.get_mut(&outer).unwrap();
        frame.add_child(nested);
        frame.add_child(leaf);
    }

    let svg = render(&pool, outer);
    assert!(
        svg.contains("filter=\"url(#fx"),
        "outer drop shadow must emit a filter: {svg}"
    );

    let filter_open = svg.find("filter=\"url(#fx").expect("outer filter");
    let after = &svg[filter_open..];
    // Content pass redraws the nested board at true size (180×160); silhouette
    // must use the spread-outset size (180+48)×(160+48).
    let silhouette_end = after
        .find("width=\"180\"")
        .map(|i| filter_open + i)
        .expect("content nested board at true size");
    let silhouette = &svg[filter_open..silhouette_end];

    assert!(
        silhouette.contains("width=\"228\"") && silhouette.contains("height=\"208\""),
        "nested board fill must outset by parent spread 24 (180+48, 160+48): {silhouette}\nfull: {svg}"
    );
    assert!(
        silhouette.contains("width=\"108\"") && silhouette.contains("height=\"108\""),
        "leaf fill must also outset by parent spread 24 (60+48): {silhouette}\nfull: {svg}"
    );

    insta::assert_snapshot!(svg);
}

#[test]
fn frame_drop_silhouette_inherits_board_opacity() {
    // GPU opens the opacity save_layer before the shadow composite, so a board
    // at opacity 0.5 casts a half-strength drop. The silhouette filter group
    // must sit inside the opacity wrapper (F6a), not beside it.
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_rgb(61, 123, 255),
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.set_opacity(0.5);
        frame.add_shadow(Shadow::new(
            skia::Color::BLACK,
            0.0,
            0.0,
            (0.0, 28.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, frame_id);
    let opacity_pos = svg.find(r#"opacity="0.5""#).expect("board opacity wrapper");
    let filter_pos = svg
        .find("filter=\"url(#fx")
        .expect("drop silhouette filter");
    assert!(
        opacity_pos < filter_pos,
        "opacity must wrap the drop silhouette (GPU order): {svg}"
    );

    // Silhouette group is nested inside the opacity group — closing opacity
    // after the filter group means the shadow is attenuated.
    let after_opacity = &svg[opacity_pos..];
    assert!(
        after_opacity.contains("filter=\"url(#fx"),
        "drop silhouette must be inside the opacity wrapper: {svg}"
    );

    insta::assert_snapshot!(svg);
}

#[test]
fn nested_child_drop_shadow_is_not_refiltered_by_frame() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let child = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 260.0, 140.0),
        skia::Color::TRANSPARENT,
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.clear_fills();
        frame.add_stroke(solid_stroke(StrokeKind::Inner, 5.0, skia::Color::BLACK));
        frame.add_shadow(Shadow::new(
            skia::Color::from_rgb(251, 243, 0),
            0.0,
            0.0,
            (20.0, 20.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_solid_rect(
        &mut pool,
        child,
        frame_id,
        (40.0, 30.0, 120.0, 70.0),
        skia::Color::from_rgb(239, 83, 80),
    );
    {
        let shape = pool.get_mut(&child).unwrap();
        shape.add_shadow(Shadow::new(
            skia::Color::from_rgb(56, 0, 238),
            0.0,
            0.0,
            (10.0, 10.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(child);
    }

    let svg = render(&pool, frame_id);
    let filter_attrs: Vec<_> = svg.match_indices("filter=\"url(#fx").collect();
    assert_eq!(
        filter_attrs.len(),
        2,
        "expect frame silhouette filter + child content filter only: {svg}"
    );
    // Child's filter must not sit inside the frame's filtered group.
    let frame_filter_open = svg.find("<g filter=\"url(#fx").expect("frame filter group");
    let frame_filter_close = svg[frame_filter_open..]
        .find("</g>")
        .map(|i| frame_filter_open + i)
        .expect("close frame filter group");
    let child_filter = svg.rfind("<g filter=\"url(#fx").expect("child filter");
    assert!(
        child_filter > frame_filter_close,
        "child drop filter must be outside frame drop group to avoid shadow-of-shadow: {svg}"
    );
    // Child leaf drop offset is geometric (translate), not feOffset.
    assert!(
        svg.contains(r#"translate(10 10)"#),
        "child leaf drop must keep geometric offset: {svg}"
    );
    // Frame container drops apply offset geometrically (filter dx=0).
    assert!(
        svg.contains(r#"dx="0""#) || svg.matches(r#"dx=""#).count() >= 1,
        "frame drop filter must not re-offset in user space: {svg}"
    );
    // Stroke-ring silhouette (border shadow), not a solid board fill.
    let silhouette = &svg[frame_filter_open..=frame_filter_close];
    assert!(
        silhouette.contains("fill-rule=\"evenodd\"") || silhouette.contains("<path"),
        "frame stroke must be in the drop-shadow silhouette: {silhouette}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn frame_drop_silhouette_offsets_child_text() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    let text_id = uid(2);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 300.0, 180.0),
        skia::Color::TRANSPARENT,
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.clear_fills();
        frame.add_stroke(solid_stroke(StrokeKind::Inner, 5.0, skia::Color::BLACK));
        frame.add_shadow(Shadow::new(
            skia::Color::from_argb(128, 229, 16, 35),
            4.0,
            4.0,
            (20.0, 20.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    add_solid_text(
        &mut pool,
        text_id,
        (40.0, 40.0, 200.0, 120.0),
        "HOLA",
        40.0,
        skia::Color::BLACK,
    );
    {
        let text = pool.get_mut(&text_id).unwrap();
        text.set_parent(frame_id);
        text.add_shadow(Shadow::new(
            skia::Color::from_argb(128, 56, 0, 238),
            4.0,
            4.0,
            (10.0, 10.0),
            ShadowStyle::Drop,
            false,
        ));
    }
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.add_child(text_id);
    }

    let svg = render(&pool, frame_id);
    let frame_filter_open = svg.find("<g filter=\"url(#fx").expect("frame filter group");
    let frame_filter_close = svg[frame_filter_open..]
        .find("</g>")
        .map(|i| frame_filter_open + i)
        .expect("close frame filter group");
    let silhouette = &svg[frame_filter_open..=frame_filter_close];
    assert!(
        silhouette.contains("<text"),
        "frame drop silhouette must include child text: {silhouette}"
    );
    assert!(
        silhouette.contains(r#"transform="translate(20 20)""#),
        "silhouette text must apply frame drop offset in local space: {silhouette}"
    );
    assert!(
        silhouette.contains("feMorphology") || svg.contains("txmorph"),
        "silhouette text must dilate for frame drop spread: {svg}"
    );
    assert!(
        svg.contains(r#"operator="dilate""#) && svg.contains(r#"radius="4""#),
        "text silhouette spread must dilate by frame shadow spread: {svg}"
    );
    // Content text (outside silhouette) must stay unshifted.
    let content = &svg[frame_filter_close..];
    let content_text_start = content.find("<text").expect("content text");
    let content_text_end = content[content_text_start..]
        .find("</text>")
        .map(|i| content_text_start + i)
        .expect("content text end");
    let content_text = &content[content_text_start..=content_text_end];
    assert!(
        !content_text.contains("translate(20 20)"),
        "content text must not carry silhouette offset: {content_text}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn fill_less_frame_drop_shadow_ignores_stroke_spread_outset() {
    let mut pool = ShapesPool::new();
    let frame_id = uid(1);
    add_frame(
        &mut pool,
        frame_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::TRANSPARENT,
        false,
    );
    {
        let frame = pool.get_mut(&frame_id).unwrap();
        frame.clear_fills();
        frame.add_stroke(solid_stroke(StrokeKind::Inner, 5.0, skia::Color::BLACK));
        frame.add_shadow(Shadow::new(
            skia::Color::from_argb(128, 229, 16, 35),
            4.0,
            4.0,
            (20.0, 20.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, frame_id);
    assert!(
        !svg.contains("feMorphology"),
        "container drop must not use feMorphology: {svg}"
    );
    assert!(
        svg.contains(r#"dx="0""#) && svg.contains("feGaussianBlur"),
        "container drop filter must blur only (offset is geometric): {svg}"
    );
    let filter_open = svg.find("<g filter=\"url(#fx").expect("drop group");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("close drop group");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        silhouette.contains("fill-rule=\"evenodd\""),
        "silhouette must stay a stroke ring: {silhouette}"
    );
    // GPU ignores Rect/Frame stroke outset — ring must match content selrect
    // (200×120), not an expanded 208×128 path.
    assert!(
        silhouette.contains("M200 ") || silhouette.contains("L200 "),
        "stroke silhouette must not grow with spread: {silhouette}"
    );
    assert!(
        !silhouette.contains("M204 ") && !silhouette.contains("L204 "),
        "spread must not outset frame stroke geometry: {silhouette}"
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
fn exports_masked_group_as_alpha_mask() {
    // First child is the mask; content must be under mask="url(#…)" and the
    // mask shape must not appear as unmasked sibling content (Closes #11378).
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_id = uid(2);
    let content_id = uid(3);

    add_masked_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 200.0, 120.0),
        &[mask_id, content_id],
    );
    add_solid_rect(
        &mut pool,
        mask_id,
        group_id,
        (40.0, 20.0, 160.0, 100.0),
        skia::Color::BLACK,
    );
    add_solid_rect(
        &mut pool,
        content_id,
        group_id,
        (0.0, 0.0, 200.0, 120.0),
        skia::Color::from_rgb(61, 123, 255),
    );

    let svg = render(&pool, group_id);
    assert!(
        svg.contains("mask-type=\"alpha\"") && svg.contains("maskUnits=\"userSpaceOnUse\""),
        "masked group must emit an alpha <mask>: {svg}"
    );
    assert!(
        svg.contains("mask=\"url(#mask"),
        "content must reference the alpha mask: {svg}"
    );

    // Mask body lives in <defs>; content fill appears under the mask group.
    let defs_end = svg.find("</defs>").expect("defs");
    let mask_def = svg[..defs_end]
        .find("<mask ")
        .map(|i| &svg[i..defs_end])
        .expect("mask def");
    assert!(
        mask_def.contains(r#"width="120""#) && mask_def.contains(r#"height="80""#),
        "mask def must paint the mask shape geometry: {mask_def}"
    );

    let body = &svg[defs_end..];
    assert!(
        body.contains("fill=\"#3D7BFF\"") || body.contains("fill=\"#3d7bff\""),
        "content fill must appear in the body: {body}"
    );
    // Mask geometry must not also paint as a sibling outside the mask wrapper.
    let mask_wrapper = body.find("mask=\"url(#mask").expect("mask wrapper");
    let before_masked_content = &body[..mask_wrapper];
    assert!(
        !before_masked_content.contains(r#"width="120""#)
            || !before_masked_content.contains(r#"height="80""#),
        "mask shape must not paint as unmasked content: {svg}"
    );

    insta::assert_snapshot!(svg);
}

#[test]
fn masked_leaf_drop_spread_uses_geometric_silhouette() {
    // Case 11 style: masked group + circle content with drop spread 16 blur 0.
    // GPU paints geometric outset (not feMorphology); the ring must stay circular.
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_id = uid(2);
    let content_id = uid(3);

    add_masked_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 160.0, 130.0),
        &[mask_id, content_id],
    );
    add_solid_rect(
        &mut pool,
        mask_id,
        group_id,
        (0.0, 0.0, 160.0, 130.0),
        skia::Color::BLACK,
    );
    add_solid_circle(
        &mut pool,
        content_id,
        group_id,
        (30.0, 20.0, 130.0, 120.0),
        skia::Color::from_rgb(255, 209, 102),
    );
    {
        let content = pool.get_mut(&content_id).unwrap();
        content.add_shadow(Shadow::new(
            skia::Color::from_rgb(229, 16, 35),
            0.0,
            16.0,
            (0.0, 0.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, group_id);
    assert!(
        svg.contains("mask=\"url(#mask"),
        "masked group must wrap content: {svg}"
    );
    assert!(
        !svg.contains("feMorphology"),
        "leaf drop spread must not use feMorphology: {svg}"
    );
    // 100×100 circle + 2×16 spread → 132×132 silhouette ellipse.
    let filter_open = svg.find("filter=\"url(#fx").expect("drop filter");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("silhouette group close");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        silhouette.contains("<ellipse")
            && (silhouette.contains(r#"rx="66""#) || silhouette.contains(r#"rx=\"66\""#)),
        "drop silhouette must be a geometrically outset ellipse (rx=66): {silhouette}"
    );
    // True-size content ellipse remains under the mask.
    assert!(
        svg.contains(r#"rx="50""#) || svg.matches("rx=\"50\"").count() >= 1,
        "content ellipse must keep true size rx=50: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn masked_group_drop_silhouette_is_masked() {
    // Circle mask + oversized rect content + group drop: silhouette must be
    // masked (circular), not the raw rect. Offset stays geometric.
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_id = uid(2);
    let content_id = uid(3);

    add_masked_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (80.0, 60.0, 200.0, 180.0),
        &[mask_id, content_id],
    );
    add_solid_circle(
        &mut pool,
        mask_id,
        group_id,
        (80.0, 60.0, 200.0, 180.0),
        skia::Color::BLACK,
    );
    add_solid_rect(
        &mut pool,
        content_id,
        group_id,
        (40.0, 45.0, 240.0, 185.0),
        skia::Color::from_rgb(255, 0, 110),
    );
    {
        let group = pool.get_mut(&group_id).unwrap();
        group.add_shadow(Shadow::new(
            skia::Color::from_argb(140, 0, 0, 0),
            10.0,
            0.0,
            (8.0, 12.0),
            ShadowStyle::Drop,
            false,
        ));
    }

    let svg = render(&pool, group_id);
    let filter_open = svg.find("filter=\"url(#fx").expect("drop filter");
    let filter_close = svg[filter_open..]
        .find("</g>")
        .map(|i| filter_open + i)
        .expect("silhouette group close");
    let silhouette = &svg[filter_open..=filter_close];
    assert!(
        silhouette.contains("mask=\"url(#mask"),
        "group drop silhouette must be masked to the mask shape: {silhouette}\nfull: {svg}"
    );
    // Silhouette mask body is a circle (ellipse), not only the rect content.
    let defs_end = svg.find("</defs>").expect("defs");
    let defs = &svg[..defs_end];
    assert!(
        defs.contains("<ellipse") && (defs.contains(r#"rx="60""#) || defs.contains("rx=\"60\"")),
        "mask def must include the circle mask geometry: {defs}"
    );
    // Geometric offset: silhouette rect translate differs from content by (8,12).
    assert!(
        silhouette.contains("<rect"),
        "silhouette still paints content geometry under the mask: {silhouette}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn mask_group_nested_fills_paint_empty_path() {
    // SVG-imported mask group: resolve-shape-fills gives the group a black
    // fill; the path child has empty fills and must inherit via nested_fills
    // (GPU parity). Without inheritance the <mask> body is empty.
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_group = uid(2);
    let path_id = uid(3);
    let content_id = uid(4);

    add_masked_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (60.0, 55.0, 220.0, 175.0),
        &[mask_group, content_id],
    );
    add_group(
        &mut pool,
        mask_group,
        group_id,
        (60.0, 55.0, 220.0, 175.0),
        &[path_id],
    );
    {
        let g = pool.get_mut(&mask_group).unwrap();
        // Mirrors apply-svg-derived / resolve-shape-fills for groups with :svg-attrs.
        g.set_fills(vec![Fill::Solid(SolidColor(skia::Color::BLACK))]);
        g.svg_attrs = Some(crate::shapes::SvgAttrs::default());
    }
    add_empty_fill_closed_path(&mut pool, path_id, mask_group, (70.0, 65.0, 210.0, 165.0));
    add_solid_rect(
        &mut pool,
        content_id,
        group_id,
        (30.0, 40.0, 250.0, 190.0),
        skia::Color::from_rgb(6, 214, 160),
    );

    let svg = render(&pool, group_id);
    let defs_end = svg.find("</defs>").expect("defs");
    let mask_def = &svg[svg.find("<mask ").expect("mask")..defs_end];
    assert!(
        !mask_def.contains("<mask") || mask_def.matches("<mask").count() == 1,
        "mask def present: {mask_def}"
    );
    // Closing `</mask>` with no children means inheritance failed.
    assert!(
        !mask_def.contains(
            "<mask id=\"mask0\" maskUnits=\"userSpaceOnUse\" mask-type=\"alpha\"></mask>"
        ) && (mask_def.contains("<path") || mask_def.contains("<rect")),
        "mask must paint inherited-fill path geometry, not be empty: {mask_def}\nfull: {svg}"
    );
    assert!(
        svg.contains("mask=\"url(#mask"),
        "content must reference the mask: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_masked_group_when_mask_is_itself_a_group() {
    // Issue #11378: mask subtree can be a group of shapes.
    let mut pool = ShapesPool::new();
    let group_id = uid(1);
    let mask_group = uid(2);
    let mask_a = uid(3);
    let mask_b = uid(4);
    let content_id = uid(5);

    add_masked_group(
        &mut pool,
        group_id,
        Uuid::nil(),
        (0.0, 0.0, 220.0, 100.0),
        &[mask_group, content_id],
    );
    add_group(
        &mut pool,
        mask_group,
        group_id,
        (10.0, 10.0, 110.0, 90.0),
        &[mask_a, mask_b],
    );
    add_solid_rect(
        &mut pool,
        mask_a,
        mask_group,
        (10.0, 10.0, 50.0, 90.0),
        skia::Color::BLACK,
    );
    add_solid_rect(
        &mut pool,
        mask_b,
        mask_group,
        (70.0, 10.0, 110.0, 90.0),
        skia::Color::from_argb(128, 0, 0, 0),
    );
    add_solid_rect(
        &mut pool,
        content_id,
        group_id,
        (0.0, 0.0, 220.0, 100.0),
        skia::Color::from_rgb(0, 200, 0),
    );

    let svg = render(&pool, group_id);
    assert!(
        svg.contains("mask-type=\"alpha\"") && svg.contains("mask=\"url(#mask"),
        "nested group mask must still emit alpha <mask>: {svg}"
    );
    let defs_end = svg.find("</defs>").expect("defs");
    let mask_def = &svg[svg.find("<mask ").expect("mask")..defs_end];
    assert!(
        mask_def.matches("<rect").count() >= 2,
        "mask group must paint both children into the mask: {mask_def}"
    );
    assert!(
        mask_def.contains("fill-opacity"),
        "soft mask child alpha must reach the <mask>: {mask_def}"
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

/// Center text stroke: stroked `<text>` (SkSVGDevice keeps stroke style).
#[test]
fn exports_text_with_solid_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text_with_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        Some((StrokeKind::Center, 2.0, skia::Color::BLUE)),
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("stroke-width=\"2\"") && svg.contains("stroke=\"blue\""),
        "center text stroke must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Semi-transparent center stroke: paint is opaque; alpha is `<g opacity>`.
#[test]
fn exports_text_with_alpha_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text_with_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        Some((
            StrokeKind::Center,
            2.0,
            skia::Color::from_argb(128, 0, 0, 255),
        )),
    );

    let svg = render(&pool, id);
    // 128/255 → Skia emits ~0.5019608, not a rounded 0.5.
    assert!(
        svg.contains("opacity=\"0.5"),
        "alpha center stroke must wrap stroke in <g opacity>: {svg}"
    );
    assert!(
        svg.contains("stroke=\"blue\"") || svg.contains("stroke=\"#0000ff\""),
        "stroke paint must stay fully opaque: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Inner text stroke: double-width stroke clipped to the glyph silhouette.
#[test]
fn exports_text_with_solid_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text_with_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        Some((StrokeKind::Inner, 2.0, skia::Color::BLUE)),
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("<clipPath") && svg.contains("clip-path=\"url(#tclip"),
        "inner text stroke must use a glyph-silhouette clip: {svg}"
    );
    insta::assert_snapshot!(svg);
}

/// Outer text stroke: double-width stroke under an inverse-glyph luminance mask.
#[test]
fn exports_text_with_solid_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_text_with_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        Some((StrokeKind::Outer, 6.0, skia::Color::BLUE)),
    );

    let svg = render(&pool, id);
    assert!(svg.contains("<text"), "text glyphs must be present: {svg}");
    assert!(
        svg.contains("<mask") && svg.contains("mask=\"url(#tmask"),
        "outer text stroke must use an exterior glyph mask: {svg}"
    );
    assert!(
        svg.contains("stroke=\"blue\""),
        "outer text stroke color must be present: {svg}"
    );
    insta::assert_snapshot!(svg);
}

fn assert_linked_text_image_stroke(svg: &str) {
    assert!(
        svg.contains("<image") && svg.contains(TEST_IMAGE_URL),
        "text image stroke must emit a linked <image>: {svg}"
    );
    // SVG clipPath ignores strokes; the silhouette must be a luminance mask.
    assert!(
        svg.contains("<mask") && svg.contains("mask=\"url(#txtstrokemask"),
        "text image stroke must mask to the stroke silhouette: {svg}"
    );
    assert!(
        !svg.contains("data:image"),
        "must not base64-embed the stroke image: {svg}"
    );
}

#[test]
fn exports_text_with_solid_center_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_text_with_image_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        image_solid_stroke(StrokeKind::Center, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });
    assert_linked_text_image_stroke(&svg);
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_text_with_solid_inner_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_text_with_image_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        image_solid_stroke(StrokeKind::Inner, 8.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });
    assert_linked_text_image_stroke(&svg);
    assert!(
        svg.contains("clip-path=\"url(#tclip"),
        "inner text image stroke must also clip to glyphs: {svg}"
    );
    insta::assert_snapshot!(svg);
}

#[test]
fn exports_text_with_solid_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_text_with_image_stroke(
        &mut pool,
        id,
        (0.0, 0.0, 560.0, 240.0),
        "HOLA",
        200.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        image_solid_stroke(StrokeKind::Outer, 10.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });
    assert_linked_text_image_stroke(&svg);
    assert!(
        svg.contains("mask=\"url(#tmask"),
        "outer image stroke must nest inverse-glyph mask like solid outer: {svg}"
    );
    insta::assert_snapshot!(svg);
}
/// Regression: outer image stroke must remain aligned when the text is not at
/// the page origin (page translate in CTM). Uses a luminance mask because SVG
/// clipPath ignores strokes.
#[test]
fn exports_offset_text_with_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    add_text_with_image_stroke(
        &mut pool,
        id,
        (165.0, 604.0, 246.0, 690.0),
        "Aa",
        72.0,
        skia::Color::from_rgb(0xE1, 0x7F, 0xDA),
        image_solid_stroke(StrokeKind::Outer, 10.0, image_id),
    );

    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });
    assert_linked_text_image_stroke(&svg);
    // Cover-map onto selrect (GPU get_fill_shader), not selrect+delta stretch.
    // ImageFill fixture is 200×100; selrect 81×86 → scale 0.86 → dest 172×86 at x=119.5.
    assert!(
        svg.contains(r#"x="119.5""#) && svg.contains(r#"y="604""#),
        "image dest must cover-map onto selrect: {svg}"
    );
    assert!(
        svg.contains("preserveAspectRatio=\"xMidYMid slice\""),
        "text stroke images must cover like GPU shaders: {svg}"
    );
    insta::assert_snapshot!(svg);
}
#[test]
fn exports_nofill_text_with_outer_image_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let image_id = uid(42);
    // No solid fill — only an outer image stroke (matches user report).
    add_text_with_fills(
        &mut pool,
        id,
        (165.0, 604.0, 246.0, 690.0),
        "Aa",
        72.0,
        vec![],
    );
    {
        let shape = pool.get_mut(&id).expect("text");
        shape.add_stroke(image_solid_stroke(StrokeKind::Outer, 10.0, image_id));
    }
    let svg = render_with(&pool, id, |resources| {
        resources
            .images
            .set_source_url(image_id, TEST_IMAGE_URL.to_string());
    });
    assert_linked_text_image_stroke(&svg);
    assert!(
        svg.contains("mask=\"url(#tmask"),
        "nofill outer must nest inverse-glyph mask: {svg}"
    );
    // No fill <text> in the body — only the masked image.
    let body = svg.split("</defs>").last().unwrap_or("");
    assert!(
        !body.contains("fill=\"#"),
        "nofill text must not emit a solid fill: {svg}"
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

fn assert_filter_covers_page(svg: &str) {
    let width = svg
        .split_once("width=\"")
        .and_then(|(_, rest)| rest.split_once('"').map(|(w, _)| w))
        .expect("svg width");
    let height = svg
        .split_once("height=\"")
        .and_then(|(_, rest)| rest.split_once('"').map(|(h, _)| h))
        .expect("svg height");
    assert!(
        svg.contains("filterUnits=\"userSpaceOnUse\""),
        "shadow/blur filters must use userSpaceOnUse (not objectBoundingBox %): {svg}"
    );
    assert!(
        !svg.contains("x=\"-50%\"") && !svg.contains("width=\"200%\""),
        "must not size filters as a percent of the shape bbox: {svg}"
    );
    assert!(
        svg.contains(&format!("width=\"{width}\""))
            && svg.contains(&format!("height=\"{height}\""))
            && svg.contains(r#"x="0""#)
            && svg.contains(r#"y="0""#),
        "filter region must cover the export page ({width}×{height}): {svg}"
    );
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

/// A per-side dash or dot pattern is drawn as its own filled path, so the
/// exported SVG carries far more than the two contours of a solid band.
fn assert_per_side_pattern(svg: &str, fill: &str) {
    let needle = format!("<path fill=\"{fill}\"");
    let from = svg
        .find(&needle)
        .unwrap_or_else(|| panic!("no stroke path filled with {fill}: {svg}"));
    let d_start = svg[from..].find(" d=\"").expect("stroke path has no d") + from + 4;
    let d_end = svg[d_start..].find('"').expect("unterminated d") + d_start;
    assert!(
        svg[d_start..d_end].matches('M').count() > 2,
        "pattern must leave more than the two band contours: {svg}"
    );
}

#[test]
fn exports_rect_with_per_side_dashed_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = dashed_stroke(
        StrokeKind::Center,
        20.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([4.0, 12.0, 24.0, 40.0]); // top, right, bottom, left
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 240.0, 180.0), stroke);

    let svg = render(&pool, id);
    assert_per_side_pattern(&svg, "#1040FF");
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

#[test]
fn exports_rect_with_per_side_dotted_center_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = dotted_stroke(
        StrokeKind::Center,
        16.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([6.0, 16.0, 6.0, 16.0]);
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 240.0, 180.0), stroke);

    let svg = render(&pool, id);
    assert_per_side_pattern(&svg, "#1040FF");
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

#[test]
fn exports_rect_with_per_side_dotted_inner_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    // Inner dots have radius = width against an advance of width + 5, so they
    // only stay discrete below width 5.
    let mut stroke = dotted_stroke(
        StrokeKind::Inner,
        4.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([2.0, 4.0, 2.0, 4.0]);
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 60.0, 40.0), stroke);

    let svg = render(&pool, id);
    assert_per_side_pattern(&svg, "#1040FF");
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

#[test]
fn exports_rounded_rect_with_per_side_dashed_outer_stroke() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = dashed_stroke(
        StrokeKind::Outer,
        12.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([6.0, 12.0, 18.0, 24.0]);
    add_stroked_rect_with_radius(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 240.0, 180.0),
        24.0,
        stroke,
    );

    let svg = render(&pool, id);
    assert_per_side_pattern(&svg, "#1040FF");
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

#[test]
fn per_side_dashed_stroke_skips_zero_width_sides() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = dashed_stroke(
        StrokeKind::Inner,
        16.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    // Only the top and bottom sides are drawn.
    stroke.widths = Some([16.0, 0.0, 16.0, 0.0]);
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 240.0, 180.0), stroke);

    let svg = render(&pool, id);
    assert_per_side_pattern(&svg, "#1040FF");
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

/// Widths and colours for a rect whose four sides are four separate strokes.
fn per_side_colour_sides() -> [(f32, skia::Color); 4] {
    [
        (10.0, skia::Color::from_rgb(0xe1, 0x1d, 0x48)),
        (20.0, skia::Color::from_rgb(0x05, 0x96, 0x69)),
        (15.0, skia::Color::from_rgb(0x25, 0x63, 0xeb)),
        (5.0, skia::Color::from_rgb(0xf5, 0x9e, 0x0b)),
    ]
}

#[test]
fn miters_a_rect_whose_sides_are_separate_strokes() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_rect_with_per_side_strokes(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 220.0, 140.0),
        0.0,
        per_side_colour_sides(),
    );

    let svg = render(&pool, id);
    // Each side is clipped to its wedge, so every stroke carries a clip path.
    assert_eq!(
        svg.matches("<clipPath").count(),
        4,
        "each side needs its own miter clip: {svg}"
    );
    insta::assert_snapshot!(with_stable_clip_ids(&svg));
}

#[test]
fn miters_a_rounded_rect_whose_sides_are_separate_strokes() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    add_rect_with_per_side_strokes(
        &mut pool,
        id,
        Uuid::nil(),
        (0.0, 0.0, 220.0, 140.0),
        20.0,
        per_side_colour_sides(),
    );

    insta::assert_snapshot!(with_stable_clip_ids(&render(&pool, id)));
}

#[test]
fn a_lone_per_side_stroke_is_not_mitered() {
    let mut pool = ShapesPool::new();
    let id = uid(1);
    let mut stroke = solid_stroke(
        StrokeKind::Inner,
        10.0,
        skia::Color::from_rgb(0x10, 0x40, 0xff),
    );
    stroke.widths = Some([10.0, 20.0, 15.0, 5.0]);
    add_stroked_rect(&mut pool, id, Uuid::nil(), (0.0, 0.0, 220.0, 140.0), stroke);

    let svg = render(&pool, id);
    assert!(
        !svg.contains("<clipPath"),
        "one stroke has nothing to miter against, so it stays a plain band: {svg}"
    );
}
