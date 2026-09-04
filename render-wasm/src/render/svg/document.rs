use skia_safe::{self as skia, Paint};

use crate::shapes::{Shape, Type};
use crate::state::ShapesPoolRef;

use crate::render::vector::draw_shape_geometry;

// Skia's SVG backend (`SkSVGDevice`) silently drops everything drawn inside a
// `save_layer`, so composite effects rendered with `save_layer` (opacity,
// blend mode, …) vanish in SVG.
//
// Instead of one canvas, the SVG path composes the document itself: leaf
// content is drawn into short-lived `skia::svg::Canvas` fragments (real
// `<path>`/`<text>`/… vector markup), and composite effects become native SVG
// `<g>` wrappers (`opacity`, `mix-blend-mode`, `clip-path`).

/// Accumulates the SVG document body while drawing.
pub(crate) struct SvgLayerCanvas {
    pub(super) scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
    pub(super) out: String,
    pub(super) defs: String,
    pending: Option<skia::svg::Canvas>,
    next_id: usize,
    frag_no: usize,
}

impl SvgLayerCanvas {
    pub(super) fn new(scale: f32, page_rect: skia::Rect, tx: f32, ty: f32) -> Self {
        Self {
            scale,
            page_rect,
            tx,
            ty,
            out: String::new(),
            defs: String::new(),
            pending: None,
            next_id: 0,
            frag_no: 0,
        }
    }

    pub(super) fn unique(&mut self, prefix: &str) -> String {
        let id = format!("{prefix}{}", self.next_id);
        self.next_id += 1;
        id
    }

    /// Creates a fragment canvas configured with the page transform
    /// (scale + translate to the export bounds).
    pub(super) fn new_fragment(&self) -> skia::svg::Canvas {
        let canvas = skia::svg::Canvas::new(self.page_rect, None);
        {
            let cv: &skia::Canvas = &canvas;
            cv.scale((self.scale, self.scale));
            cv.translate((self.tx, self.ty));
        }
        canvas
    }

    /// Returns the current leaf-drawing canvas, creating a fragment if needed.
    pub(crate) fn canvas(&mut self) -> &skia::Canvas {
        if self.pending.is_none() {
            self.pending = Some(self.new_fragment());
        }
        self.pending.as_deref().unwrap()
    }

    /// Finalizes the pending fragment and appends its markup to `out`.
    pub(super) fn flush(&mut self) {
        let Some(canvas) = self.pending.take() else {
            return;
        };
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        if inner.trim().is_empty() {
            return;
        }
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        self.out
            .push_str(&sanitize_skia_svg_fragment(&remap_ids(inner, &prefix)));
    }

    pub(super) fn open_group(&mut self, attrs: &str) {
        self.flush();
        self.out.push_str("<g ");
        self.out.push_str(attrs);
        self.out.push('>');
    }

    pub(super) fn close_group(&mut self) {
        self.flush();
        self.out.push_str("</g>");
    }

    /// Emits a `<clipPath>` from a shape's geometry (in device/page space).
    ///
    /// A mask can be a group too. Since a group has no geometry of its own, we
    /// recurse into its descendants and accumulate their geometry.
    pub(super) fn push_clip_path(&mut self, id: &str, shape: &Shape, tree: ShapesPoolRef) {
        let canvas = self.new_fragment();
        {
            let cv: &skia::Canvas = &canvas;
            let mut paint = Paint::default();
            paint.set_anti_alias(true);
            paint.set_color(skia::Color::BLACK);
            draw_clip_geometry(cv, shape, tree, &paint);
        }
        self.finish_clip_path_fragment(id, canvas);
    }

    /// Finalizes a fragment canvas as a `<clipPath>` def.
    pub(super) fn finish_clip_path_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = sanitize_skia_svg_fragment(&remap_ids(inner, &prefix));
        self.defs.push_str(&format!(
            "<clipPath id=\"{id}\" clipPathUnits=\"userSpaceOnUse\">{geometry}</clipPath>"
        ));
    }
}

/// Draws a clip geometry into `cv` (already set up with the page transform).
fn draw_clip_geometry(cv: &skia::Canvas, shape: &Shape, tree: ShapesPoolRef, paint: &Paint) {
    if let Type::Group(_) = &shape.shape_type {
        for child_id in shape.children_ids_iter_forward(true) {
            if let Some(child) = tree.get(child_id) {
                draw_clip_geometry(cv, child, tree, paint);
            }
        }
        return;
    }

    cv.save();
    cv.concat(&shape.centered_transform());
    draw_shape_geometry(cv, shape, paint);
    cv.restore();
}

/// Builds the `<g>` attribute string for a shape's composite effects (opacity,
/// blend mode). Returns `None` when the shape needs no wrapper.
///
/// Layer blur / shadows are intentionally omitted here — they need native SVG
/// filter re-emission to survive `SkSVGDevice` and land in later PRs.
pub(super) fn effect_attrs(element: &Shape) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();

    let opacity = element.opacity();
    if opacity < 1.0 {
        parts.push(format!("opacity=\"{opacity}\""));
    }

    if let Some(css) = blend_css(element.blend_mode().0) {
        parts.push(format!("style=\"mix-blend-mode:{css}\""));
    }

    if parts.is_empty() {
        None
    } else {
        Some(parts.join(" "))
    }
}

/// Maps a Skia blend mode to its CSS `mix-blend-mode` keyword. Returns `None`
/// for `SrcOver` (normal) and modes without a CSS equivalent.
fn blend_css(mode: skia::BlendMode) -> Option<&'static str> {
    use skia::BlendMode::*;
    Some(match mode {
        Multiply => "multiply",
        Screen => "screen",
        Overlay => "overlay",
        Darken => "darken",
        Lighten => "lighten",
        ColorDodge => "color-dodge",
        ColorBurn => "color-burn",
        HardLight => "hard-light",
        SoftLight => "soft-light",
        Difference => "difference",
        Exclusion => "exclusion",
        Hue => "hue",
        Saturation => "saturation",
        Color => "color",
        Luminosity => "luminosity",
        _ => return None,
    })
}

/// Returns the inner body of a Skia SVG document (everything between the
/// opening `<svg …>` tag and the closing `</svg>`).
fn extract_inner_svg(doc: &str) -> &str {
    let start = doc
        .find("<svg")
        .and_then(|s| doc[s..].find('>').map(|e| s + e + 1));
    let end = doc.rfind("</svg>");
    match (start, end) {
        (Some(s), Some(e)) if s <= e => &doc[s..e],
        _ => "",
    }
}

/// Prefixes every id defined in a fragment (and its `url(#…)` / `#…`
/// references) so ids stay unique once fragments are merged into one document.
fn remap_ids(body: &str, prefix: &str) -> String {
    let needle = "id=\"";
    let mut ids: Vec<&str> = Vec::new();
    let mut offset = 0;
    while let Some(pos) = body[offset..].find(needle) {
        let start = offset + pos + needle.len();
        let Some(end_rel) = body[start..].find('"') else {
            break;
        };
        let id = &body[start..start + end_rel];
        if !id.is_empty() {
            ids.push(id);
        }
        offset = start + end_rel + 1;
    }

    ids.sort_unstable();
    ids.dedup();
    // Longest-first so a shorter id can't collide inside a longer one.
    ids.sort_by_key(|b| std::cmp::Reverse(b.len()));

    let mut out = body.to_string();
    for id in ids {
        let new_id = format!("{prefix}{id}");
        out = out.replace(&format!("id=\"{id}\""), &format!("id=\"{new_id}\""));
        out = out.replace(&format!("url(#{id})"), &format!("url(#{new_id})"));
        out = out.replace(&format!("=\"#{id}\""), &format!("=\"#{new_id}\""));
    }
    out
}

/// Skia's SVG backend appends a trailing comma to list-valued `<text>` attrs
/// (`x`, `y`, `dx`, `dy`). Firefox rejects the malformed list and drops the
/// glyph positioning (text vanishes or mis-renders).
fn sanitize_skia_svg_fragment(body: &str) -> String {
    const LIST_ATTRS: [&str; 4] = ["x=\"", "y=\"", "dx=\"", "dy=\""];
    let mut out = body.to_string();

    for attr in LIST_ATTRS {
        let mut search_from = 0;
        while let Some(rel) = out[search_from..].find(attr) {
            let value_start = search_from + rel + attr.len();
            let Some(end_rel) = out[value_start..].find('"') else {
                break;
            };
            let value_end = value_start + end_rel;
            let trimmed_len = out[value_start..value_end]
                .trim_end()
                .trim_end_matches(',')
                .len();
            if trimmed_len != value_end - value_start {
                let trimmed = out[value_start..value_start + trimmed_len].to_string();
                out.replace_range(value_start..value_end, &trimmed);
                search_from = value_start + trimmed_len + 1;
            } else {
                search_from = value_end + 1;
            }
        }
    }

    out
}

#[cfg(test)]
mod tests {
    use super::sanitize_skia_svg_fragment;

    #[test]
    fn strips_trailing_comma_from_text_position_lists() {
        let input = r#"<text x="1119, 1374.8594, 1584.332, " y="402, ">asd</text>"#;
        let out = sanitize_skia_svg_fragment(input);
        assert!(out.contains(r#"x="1119, 1374.8594, 1584.332""#));
        assert!(out.contains(r#"y="402""#));
        assert!(!out.contains("1584.332, \""));
        assert!(!out.contains("402, \""));
    }
}
