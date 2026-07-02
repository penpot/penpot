use std::collections::HashMap;

use skia_safe::{self as skia, Paint};

use crate::error::Result;
use crate::shapes::{radius_to_sigma, BlurType, Shape, Stroke, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use crate::render::vector::{draw_shape_geometry, ExportState, VectorRenderer, VectorTarget};

// Skia's SVG backend (`SkSVGDevice`) silently drops everything drawn inside a
// `save_layer`, so composite effects (container/leaf opacity, blend mode, layer
// blur, masks) rendered using `save_layer` vanish in SVG.
//
// Instead of one canvas, the SVG path composes the document itself: leaf
// content is drawn into short-lived `skia::svg::Canvas` *fragments* (real
// `<path>`/`<text>`/… vector markup), and composite effects become native SVG
// `<g>` wrappers (`opacity`, `mix-blend-mode`, `filter="feGaussianBlur"`,
// `clip-path`). This keeps the output fully vectorial and matches the GPU
// result without rasterizing.

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
    def_cache: HashMap<String, String>,
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
            def_cache: std::collections::HashMap::new(),
        }
    }

    pub(super) fn unique(&mut self, prefix: &str) -> String {
        let id = format!("{prefix}{}", self.next_id);
        self.next_id += 1;
        id
    }

    /// Page transform applied to every Skia SVG fragment (`scale` then `translate`).
    pub(crate) fn page_transform(&self) -> skia::Matrix {
        let mut matrix = skia::Matrix::new_identity();
        matrix.set_scale((self.scale, self.scale), None);
        matrix.post_translate((self.tx, self.ty));
        matrix
    }

    /// Transform for leaf content in export space: page offset/scale plus the
    /// shape's `centered_transform`, matching `render_leaf`'s Skia canvas setup.
    pub(crate) fn leaf_transform(&self, shape: &Shape) -> skia::Matrix {
        let mut matrix = self.page_transform();
        // Keep linked-image placement aligned with Skia's fragment output.
        // `pre_concat` here matches the effective transform observed in emitted
        // SVG from Skia fragments for rotated shapes.
        matrix.pre_concat(&shape.centered_transform());
        matrix
    }

    /// Creates a fragment canvas configured with the page transform
    /// (scale + translate to the export bounds).
    pub(super) fn new_fragment(&self) -> skia::svg::Canvas {
        let canvas = skia::svg::Canvas::new(self.page_rect, None);
        {
            let cv: &skia::Canvas = &*canvas;
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
        self.out.push_str(&remap_ids(inner, &prefix));
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

    pub(super) fn push_def(&mut self, def: &str) {
        self.defs.push_str(def);
    }

    /// Interns a reusable `<def>` keyed by its (id-independent) body. If an
    /// identical def was already emitted, its id is returned; otherwise a fresh
    /// id is minted, the def emitted via `render(id)` and cached. Lets several
    /// shapes share one filter instead of duplicating identical `<filter>`s.
    pub(super) fn intern_def(&mut self, prefix: &str, key: &str, render: impl Fn(&str) -> String) -> String {
        if let Some(id) = self.def_cache.get(key) {
            return id.clone();
        }
        let id = self.unique(prefix);
        let def = render(&id);
        self.push_def(&def);
        self.def_cache.insert(key.to_string(), id.clone());
        id
    }

    /// Emits a `<clipPath>` from a mask shape's geometry (in device/page space)
    /// and returns nothing; the caller wraps content in
    /// `<g clip-path="url(#id)">`.
    ///
    /// A mask can be a group too. 
    /// Since a group has no geometry of its own, we recurse into
    /// its descendants and accumulate their geometry.
    pub(super) fn push_clip_path(&mut self, id: &str, shape: &Shape, tree: ShapesPoolRef) {
        let canvas = self.new_fragment();
        {
            let cv: &skia::Canvas = &*canvas;
            let mut paint = Paint::default();
            paint.set_anti_alias(true);
            paint.set_color(skia::Color::BLACK);
            draw_clip_geometry(cv, shape, tree, &paint);
        }
        self.finish_clip_path_fragment(id, canvas);
    }

    /// Renders the mask subtree rooted at `mask_id` into an isolated fragment
    /// and registers it as an alpha `<mask>` def, returning the def id for the
    /// caller to reference via `mask="url(#id)"`.
    ///
    /// Unlike a geometric `<clipPath>`, this captures the mask's rendered
    /// *alpha* (fills included, so images/gradients/soft masks compose
    /// faithfully). The subtree is rendered
    /// with a fresh [`SvgLayerCanvas`] and its ids are namespaced so they can't
    /// collide with the surrounding document.
    pub(super) fn push_alpha_mask(
        &mut self,
        shared: &mut ExportState,
        mask_id: &Uuid,
        tree: ShapesPoolRef,
        scale: f32,
    ) -> Result<String> {
        let mut sub = SvgLayerCanvas::new(self.scale, self.page_rect, self.tx, self.ty);
        super::render_tree(&mut sub, shared, mask_id, tree, scale, true)?;
        sub.flush();

        let prefix = format!("{}_", self.unique("m"));
        let mask_ref = self.unique("mask");
        let sub_defs = remap_ids(&sub.defs, &prefix);
        let sub_body = remap_ids(&sub.out, &prefix);

        if !sub_defs.is_empty() {
            self.defs.push_str(&sub_defs);
        }
        self.defs.push_str(&format!(
            "<mask id=\"{mask_ref}\" maskUnits=\"userSpaceOnUse\" \
             mask-type=\"alpha\">{sub_body}</mask>"
        ));
        Ok(mask_ref)
    }

    /// Builds an alpha `<mask>` from a stroke's opaque silhouette and returns its
    /// id, for confining an image-filled stroke to the stroke region. The
    /// silhouette is drawn opaque (alpha 1 in the stroke area), so `mask-type:
    /// alpha` shows the image exactly where the stroke paints.
    pub(super) fn push_stroke_alpha_mask(
        &mut self,
        shared: &mut ExportState,
        element: &Shape,
        stroke: &Stroke,
        scale: f32,
        inner_clip: bool,
    ) -> Result<String> {
        let canvas = self.new_fragment();
        {
            let cv: &skia::Canvas = &*canvas;
            cv.save();
            cv.concat(&element.centered_transform());
            let mut renderer = VectorRenderer::new(cv, shared, scale, VectorTarget::Svg);
            renderer.draw_stroke_silhouette(element, stroke, inner_clip)?;
            cv.restore();
        }

        let id = self.unique("simask");
        self.finish_alpha_mask_fragment(&id, canvas);
        Ok(id)
    }

    /// Finalizes a fragment canvas as an alpha `<mask>` def: its emitted markup's
    /// *alpha* becomes the mask (opaque shows, transparent hides), independent of
    /// color, unlike a luminance mask, so an opaque black silhouette works.
    pub(super) fn finish_alpha_mask_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = remap_ids(inner, &prefix);
        self.defs.push_str(&format!(
            "<mask id=\"{id}\" maskUnits=\"userSpaceOnUse\" \
             mask-type=\"alpha\">{geometry}</mask>"
        ));
    }

    /// Finalizes a fragment canvas as a `<clipPath>` def: its emitted markup
    /// (shape geometry or text glyph silhouette) becomes the clip geometry.
    pub(super) fn finish_clip_path_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
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

    /// Finalizes a fragment canvas as a luminance `<mask>` def: its emitted
    /// markup becomes the mask (white shows, black hides).
    pub(super) fn finish_mask_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = remap_ids(inner, &prefix);
        self.defs.push_str(&format!(
            "<mask id=\"{id}\" maskUnits=\"userSpaceOnUse\">{geometry}</mask>"
        ));
    }
}

/// Draws a mask's clip geometry into `cv` (already set up with the page
/// transform). Leaf shapes contribute their own geometry under their
/// `centered_transform`; groups contribute nothing themselves but recurse into
/// their children (a group carries no geometry, and its children hold absolute
/// coordinates with their own transforms, the group transform is not
/// propagated.
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
/// blend mode, layer blur), registering any needed `<defs>`. Returns `None`
/// when the shape needs no wrapper.
pub(super) fn effect_attrs(
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
