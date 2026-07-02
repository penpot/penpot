use std::collections::HashSet;

use skia_safe::{self as skia, Paint};

use crate::error::Result;
use crate::shapes::{radius_to_sigma, BlurType, Shape, Stroke, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::shape_renderer::ShapeRenderer;
use super::vector::{
    children_paint_order, draw_shape_geometry, render_leaf_content, VectorRenderer, VectorTarget,
};
use super::RenderState;

/// Collects the registered font aliases used by every text span in the subtree
/// rooted at `id`, so the exporter can embed exactly those fonts.
fn collect_font_aliases(tree: ShapesPoolRef, id: &Uuid, out: &mut HashSet<String>) {
    let Some(shape) = tree.get(id) else {
        return;
    };

    if let Type::Text(_) = &shape.shape_type {
        for paragraph in shape.get_text_content().paragraphs() {
            for span in paragraph.children() {
                out.insert(format!("{}", span.font_family));
            }
        }
    }

    for child_id in shape.children_ids_iter_forward(true) {
        collect_font_aliases(tree, child_id, out);
    }
}

/// Renders a shape tree to an SVG document and returns the raw SVG bytes.
///
/// Dedicated vector-SVG render path. Leaf content (paths, text, fills, images)
/// is emitted as real SVG markup via short-lived Skia SVG canvases, while
/// composite effects — container/leaf opacity, blend mode, layer blur and masks
/// — are composed as native SVG `<g>` wrappers (`opacity`, `mix-blend-mode`,
/// `filter="feGaussianBlur"`, `clip-path`). This keeps everything vectorial and
/// faithful to the GPU/PDF output, sidestepping `SkSVGDevice`'s inability to
/// keep `save_layer` content.
///
/// Text is emitted as real `<text>` elements (no `CONVERT_TEXT_TO_PATHS`), so
/// the output stays selectable/editable. Skia's SVG backend does not embed
/// fonts, so we inject `@font-face` rules with the used fonts base64-embedded to
/// keep the document self-contained without relying on the viewer's fonts.
pub fn render_to_svg(
    shared: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    let shape = tree
        .get(id)
        .ok_or_else(|| crate::error::Error::CriticalError("Shape not found for SVG".to_string()))?;
    let bounds = shape.extrect(tree, scale);

    let page_w = bounds.width() * scale;
    let page_h = bounds.height() * scale;
    let rect = skia::Rect::from_xywh(0., 0., page_w, page_h);

    let (defs, body) =
        render_svg_body(shared, id, tree, scale, rect, -bounds.left(), -bounds.top())?;

    // Embed the fonts used by the subtree as `@font-face` rules so the SVG
    // renders faithfully without relying on the viewer having them installed.
    let mut aliases = HashSet::new();
    collect_font_aliases(tree, id, &mut aliases);
    let font_css = shared.fonts().font_face_css_for_aliases(&aliases);

    let mut out = String::with_capacity(body.len() + defs.len() + font_css.len() + 256);
    out.push_str("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
    out.push_str(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" \
         width=\"{page_w}\" height=\"{page_h}\">"
    ));

    if !font_css.is_empty() || !defs.is_empty() {
        out.push_str("<defs>");
        if !font_css.is_empty() {
            out.push_str(&format!(
                "<style type=\"text/css\"><![CDATA[{font_css}]]></style>"
            ));
        }
        out.push_str(&defs);
        out.push_str("</defs>");
    }

    out.push_str(&body);
    out.push_str("</svg>");

    Ok(out.into_bytes())
}

// ===========================================================================
// SVG compositor
// ===========================================================================
//
// Skia's SVG backend (`SkSVGDevice`) silently drops everything drawn inside a
// `save_layer`, so composite effects (container/leaf opacity, blend mode, layer
// blur, masks) rendered the PDF way (one canvas + `save_layer`) vanish in SVG.
//
// Instead of one canvas, the SVG path composes the document itself: leaf
// content is drawn into short-lived `skia::svg::Canvas` *fragments* (real
// `<path>`/`<text>`/… vector markup), and composite effects become native SVG
// `<g>` wrappers (`opacity`, `mix-blend-mode`, `filter="feGaussianBlur"`,
// `clip-path`). This keeps the output fully vectorial and matches the GPU/PDF
// result without rasterizing.

/// Accumulates the SVG document body while drawing.
///
/// Leaf draws land in the current `pending` fragment; opening/closing a group
/// flushes it so its markup nests correctly inside the `<g>`.
struct SvgLayerCanvas {
    scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
    /// Composed body (nested `<g>` + fragment markup), in document order.
    out: String,
    /// Global `<defs>` (clip paths, blur filters).
    defs: String,
    /// Fragment collecting consecutive leaf draws, if any.
    pending: Option<skia::svg::Canvas>,
    /// Counter for unique def ids (`blur0`, `clip1`, …).
    next_id: usize,
    /// Counter for per-fragment id prefixes (avoids id clashes across frags).
    frag_no: usize,
}

impl SvgLayerCanvas {
    fn new(scale: f32, page_rect: skia::Rect, tx: f32, ty: f32) -> Self {
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

    fn unique(&mut self, prefix: &str) -> String {
        let id = format!("{prefix}{}", self.next_id);
        self.next_id += 1;
        id
    }

    /// Creates a fragment canvas configured with the page transform
    /// (scale + translate to the export bounds), matching the GPU/PDF page.
    fn new_fragment(&self) -> skia::svg::Canvas {
        let canvas = skia::svg::Canvas::new(self.page_rect, None);
        {
            let cv: &skia::Canvas = &*canvas;
            cv.scale((self.scale, self.scale));
            cv.translate((self.tx, self.ty));
        }
        canvas
    }

    /// Returns the current leaf-drawing canvas, creating a fragment if needed.
    fn canvas(&mut self) -> &skia::Canvas {
        if self.pending.is_none() {
            self.pending = Some(self.new_fragment());
        }
        self.pending.as_deref().unwrap()
    }

    /// Finalizes the pending fragment and appends its markup to `out`.
    fn flush(&mut self) {
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
        self.out.push_str(&remap_ids(inner, &prefix));
    }

    fn open_group(&mut self, attrs: &str) {
        self.flush();
        self.out.push_str("<g ");
        self.out.push_str(attrs);
        self.out.push('>');
    }

    fn close_group(&mut self) {
        self.flush();
        self.out.push_str("</g>");
    }

    fn push_def(&mut self, def: &str) {
        self.defs.push_str(def);
    }

    /// Emits a `<clipPath>` from a shape's geometry (in device/page space) and
    /// returns nothing; the caller wraps content in `<g clip-path="url(#id)">`.
    fn push_clip_path(&mut self, id: &str, shape: &Shape) {
        let canvas = self.new_fragment();
        {
            let cv: &skia::Canvas = &*canvas;
            cv.concat(&shape.centered_transform());
            let mut paint = Paint::default();
            paint.set_anti_alias(true);
            paint.set_color(skia::Color::BLACK);
            draw_shape_geometry(cv, shape, &paint);
        }
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = remap_ids(inner, &prefix);
        self.defs.push_str(&format!(
            "<clipPath id=\"{id}\" clipPathUnits=\"userSpaceOnUse\">{geometry}</clipPath>"
        ));
    }
}

/// Renders `id`'s subtree to an SVG body, returning `(defs, body)`.
fn render_svg_body(
    shared: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
) -> Result<(String, String)> {
    let mut builder = SvgLayerCanvas::new(scale, page_rect, tx, ty);
    svg_render_tree(&mut builder, shared, id, tree, scale)?;
    builder.flush();
    Ok((builder.defs, builder.out))
}

fn svg_render_tree(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };
    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(group) => svg_render_group(builder, shared, element, group.masked, tree, scale),
        Type::Frame(_) => svg_render_frame(builder, shared, element, tree, scale),
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => svg_render_leaf(builder, shared, element, scale),
    }
}

fn svg_render_group(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderState,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let effects = svg_effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    let mask = masked
        .then(|| element.mask_id().and_then(|mid| tree.get(mid)))
        .flatten();
    if let Some(mask) = mask {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, mask);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }

    for child_id in &children_paint_order(tree, element) {
        svg_render_tree(builder, shared, child_id, tree, scale)?;
    }

    if mask.is_some() {
        builder.close_group();
    }
    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}

fn svg_render_frame(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderState,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let matrix = element.centered_transform();

    let effects = svg_effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    let clipped = element.clip_content;
    if clipped {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, element);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }

    // Frame background + inner shadows (frame space).
    if !element.fills.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        renderer.draw_fills(element, &element.fills)?;
        renderer.draw_fill_inner_shadows(element)?;
        canvas.restore();
    }

    // Children (absolute coords).
    for child_id in &children_paint_order(tree, element) {
        svg_render_tree(builder, shared, child_id, tree, scale)?;
    }

    // Strokes over children (frame space).
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        renderer.draw_strokes(element, &visible_strokes)?;
        canvas.restore();
    }

    if clipped {
        builder.close_group();
    }
    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}

fn svg_render_leaf(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let effects = svg_effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    {
        let matrix = element.centered_transform();
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        render_leaf_content(&mut renderer, element)?;
        canvas.restore();
    }

    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}

/// Builds the `<g>` attribute string for a shape's composite effects (opacity,
/// blend mode, layer blur), registering any needed `<defs>`. Returns `None`
/// when the shape needs no wrapper.
fn svg_effect_attrs(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    scale: f32,
) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();

    let opacity = element.opacity();
    if opacity < 1.0 {
        parts.push(format!("opacity=\"{opacity}\""));
    }

    if let Some(css) = blend_css(element.blend_mode().0) {
        parts.push(format!("style=\"mix-blend-mode:{css}\""));
    }

    if let Some(value) = layer_blur_value(element) {
        let sigma = radius_to_sigma(value * scale);
        let id = builder.unique("blur");
        builder.push_def(&format!(
            "<filter id=\"{id}\" x=\"-50%\" y=\"-50%\" width=\"200%\" height=\"200%\">\
             <feGaussianBlur stdDeviation=\"{sigma}\"/></filter>"
        ));
        parts.push(format!("filter=\"url(#{id})\""));
    }

    if parts.is_empty() {
        None
    } else {
        Some(parts.join(" "))
    }
}

/// Layer-blur radius of a shape, if it has a visible layer blur.
fn layer_blur_value(element: &Shape) -> Option<f32> {
    element
        .blur
        .and_then(|b| (!b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0).then_some(b.value))
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
    // Collect id definitions.
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
    // Longest-first so a shorter id can't collide inside a longer one; the
    // quote/paren delimiters below already prevent partial matches.
    ids.sort_by(|a, b| b.len().cmp(&a.len()));

    let mut out = body.to_string();
    for id in ids {
        let new_id = format!("{prefix}{id}");
        out = out.replace(&format!("id=\"{id}\""), &format!("id=\"{new_id}\""));
        out = out.replace(&format!("url(#{id})"), &format!("url(#{new_id})"));
        out = out.replace(&format!("=\"#{id}\""), &format!("=\"#{new_id}\""));
    }
    out
}
