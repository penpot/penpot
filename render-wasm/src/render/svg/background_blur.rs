//! Background blur for WASM SVG export.
//!
//! Emits an empty XHTML `foreignObject` with CSS `backdrop-filter`, clipped to
//! the shape silhouette via `clip-path: path(...)` on the inner HTML div, then
//! paints fills/strokes on top. Text clips use glyph outline paths. Skia SVG
//! has no native backdrop filter; SVG clip/mask on the FO empties the browser
//! backdrop sample, so the clip stays on the div.

use skia_safe as skia;

use crate::error::Result;
use crate::render::RenderState;
use crate::shapes::text_paths::TextPaths;
use crate::shapes::{radius_to_sigma, Path, Segment, Shape, Stroke, Type};

use super::document::SvgLayerCanvas;

/// Emits background-blur markup when the shape has a visible background blur.
/// No-op for `SVGRaw`, silhouette passes (`suppress_filters`), hidden/zero
/// blur, empty clip paths, or shapes without background blur.
///
/// Call before opacity / blend / layer-blur wrappers so the glass ignores
/// shape opacity (same order as the GPU path).
pub(super) fn emit_background_blur(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    if builder.suppress_filters {
        return Ok(());
    }
    if matches!(element.shape_type, Type::SVGRaw(_)) {
        return Ok(());
    }
    let Some(blur) = element.visible_background_blur() else {
        return Ok(());
    };
    if blur.value <= 0.0 {
        return Ok(());
    }

    let sigma = radius_to_sigma(blur.value * scale);
    let draw_matrix = builder.silhouette_draw_matrix(element);
    let (clip, stroke_outset) = blur_clip_and_outset(element);
    if clip.is_empty() {
        return Ok(());
    }
    emit_foreign_object(
        builder,
        element,
        &draw_matrix,
        &clip,
        stroke_outset,
        blur.value,
        sigma,
    );
    Ok(())
}

/// Local-space silhouette path and stroke reach used to size/clip the FO.
fn blur_clip_and_outset(element: &Shape) -> (skia::Path, f32) {
    match &element.shape_type {
        Type::Text(_) => {
            let outset = Stroke::max_bounds_width(element.visible_strokes(), false);
            (text_background_blur_clip_path(element, outset), outset)
        }
        Type::Group(_) => (skia::Path::rect(element.selrect(), None), 0.0),
        _ => {
            let outset = Stroke::max_bounds_width(element.visible_strokes(), element.is_open());
            (
                RenderState::background_blur_clip_path(element, outset),
                outset,
            )
        }
    }
}

/// Union of glyph outlines, optionally expanded by stroke reach.
fn text_background_blur_clip_path(element: &Shape, stroke_outset: f32) -> skia::Path {
    let Type::Text(text) = &element.shape_type else {
        return skia::Path::default();
    };

    let text_paths = TextPaths::new(text.new_bounds(element.selrect()));
    let mut combined: Option<skia::Path> = None;
    for path in text_paths.get_paths(element.vertical_align()) {
        combined = Some(match combined {
            None => path,
            Some(acc) => acc.op(&path, skia::PathOp::Union).unwrap_or(acc),
        });
    }

    let Some(base) = combined else {
        return skia::Path::default();
    };

    RenderState::union_stroke_outset(base, stroke_outset)
}

/// Emits a page-space `foreignObject` whose div applies backdrop blur and a
/// FO-local CSS path clip. The FO is outset by stroke reach plus ~2× the
/// design blur radius so the filter has margin to sample.
fn emit_foreign_object(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    draw_matrix: &skia::Matrix,
    clip_path_local: &skia::Path,
    stroke_outset: f32,
    blur_value: f32,
    sigma: f32,
) {
    let margin = stroke_outset + 2.0 * blur_value;
    let mut local = element.selrect();
    if margin > 0.0 {
        local.outset((margin, margin));
    }

    let page = builder.map_selrect_to_page(&local, draw_matrix);
    let x = page.left();
    let y = page.top();
    let w = page.width();
    let h = page.height();

    let local_clip = builder
        .map_path_to_page(clip_path_local, draw_matrix)
        .make_offset((-x, -y));
    let fill_rule = css_path_fill_rule(local_clip.fill_type());
    let d = skia_path_to_svg_d(&local_clip);

    builder.push_raw(&format!(
        concat!(
            r#"<foreignObject x="{x}" y="{y}" width="{w}" height="{h}">"#,
            r#"<div xmlns="http://www.w3.org/1999/xhtml" style="backdrop-filter:blur({sigma}px);-webkit-backdrop-filter:blur({sigma}px);clip-path:path({fill_rule},'{d}');height:100%;width:100%"></div>"#,
            r#"</foreignObject>"#
        ),
        x = x,
        y = y,
        w = w,
        h = h,
        sigma = sigma,
        fill_rule = fill_rule,
        d = d,
    ));
}

/// CSS `clip-path: path(<fill-rule>, …)` keyword for a Skia path fill type.
fn css_path_fill_rule(fill_type: skia::PathFillType) -> &'static str {
    match fill_type {
        skia::PathFillType::EvenOdd | skia::PathFillType::InverseEvenOdd => "evenodd",
        _ => "nonzero",
    }
}

/// Serializes a Skia path to an SVG `d` string for CSS `clip-path: path(...)`.
fn skia_path_to_svg_d(path: &skia::Path) -> String {
    let converted = Path::from_skia_path_accurate(path.clone());
    let mut out = String::new();
    for segment in converted.segments() {
        match *segment {
            Segment::MoveTo((px, py)) => out.push_str(&format!("M{px} {py}")),
            Segment::LineTo((px, py)) => out.push_str(&format!("L{px} {py}")),
            Segment::CurveTo(((c1x, c1y), (c2x, c2y), (px, py))) => {
                out.push_str(&format!("C{c1x} {c1y} {c2x} {c2y} {px} {py}"))
            }
            Segment::Close => out.push('Z'),
        }
    }
    out.replace('\'', "")
}
