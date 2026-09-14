use skia_safe::{self as skia, Paint};

use crate::shapes::{radius_to_sigma, Shadow, Shape, Type};
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
    scale: f32,
    page_rect: skia::Rect,
    tx: f32,
    ty: f32,
    pub(super) out: String,
    pub(super) defs: String,
    pending: Option<skia::svg::Canvas>,
    next_id: usize,
    frag_no: usize,
    /// When true, skip SVG `<filter>` effects so a parent drop-shadow pass can
    /// sample silhouettes without nested child shadows (shadow-of-shadow).
    pub(super) suppress_filters: bool,
    /// Design-space outset applied while drawing a container drop-shadow
    /// silhouette. Matches GPU geometric spread (avoids `feMorphology` fattening
    /// stroke rings on both edges).
    pub(super) silhouette_spread: f32,
    /// Design-space drop offset applied in local shape space while drawing a
    /// container silhouette (GPU `pre_translate` before rotation). The SVG
    /// filter itself uses a zero offset so rotated shadows stay correct.
    pub(super) silhouette_offset: (f32, f32),
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
            suppress_filters: false,
            silhouette_spread: 0.0,
            silhouette_offset: (0.0, 0.0),
        }
    }

    /// CTM for silhouette geometry: original centered transform, then local
    /// drop offset (spread is applied by outsetting selrect separately).
    pub(super) fn silhouette_draw_matrix(&self, element: &Shape) -> skia::Matrix {
        let mut matrix = element.centered_transform();
        let (dx, dy) = self.silhouette_offset;
        if dx != 0.0 || dy != 0.0 {
            matrix.pre_translate((dx, dy));
        }
        matrix
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

    /// Appends raw SVG markup to the body (flushes any pending Skia fragment first).
    pub(super) fn push_raw(&mut self, markup: &str) {
        self.flush();
        self.out.push_str(markup);
    }

    /// CTM for leaf content placed in page space: Scale * Translate * `draw_matrix`.
    pub(super) fn page_draw_matrix_attr(&self, draw_matrix: &skia::Matrix) -> String {
        let mut ctm = skia::Matrix::scale((self.scale, self.scale));
        ctm = ctm * skia::Matrix::translate((self.tx, self.ty));
        ctm = ctm * *draw_matrix;
        format!(
            "matrix({} {} {} {} {} {})",
            ctm.scale_x(),
            ctm.skew_y(),
            ctm.skew_x(),
            ctm.scale_y(),
            ctm.translate_x(),
            ctm.translate_y()
        )
    }

    /// Emits a `<clipPath>` from a shape's geometry (in device/page space).
    ///
    /// A mask can be a group too. Since a group has no geometry of its own, we
    /// recurse into its descendants and accumulate their geometry.
    ///
    /// Uses [`Self::silhouette_offset`] so clipped container drop silhouettes
    /// move their clip with the offset content (GPU parity).
    pub(super) fn push_clip_path(&mut self, id: &str, shape: &Shape, tree: ShapesPoolRef) {
        let canvas = self.new_fragment();
        {
            let cv: &skia::Canvas = &canvas;
            let mut paint = Paint::default();
            paint.set_anti_alias(true);
            paint.set_color(skia::Color::BLACK);
            draw_clip_geometry(cv, shape, tree, &paint, self.silhouette_offset);
        }
        self.finish_clip_path_fragment(id, canvas);
    }

    /// Finalizes a fragment canvas as a `<clipPath>` def.
    ///
    /// Rewrite fill-rule to clip-rule: clipPaths ignore fill-rule, so evenodd
    /// stroke rings would otherwise fill solid.
    pub(super) fn finish_clip_path_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = sanitize_skia_svg_fragment(&remap_ids(inner, &prefix))
            .replace("fill-rule=", "clip-rule=");
        self.defs.push_str(&format!(
            "<clipPath id=\"{id}\" clipPathUnits=\"userSpaceOnUse\">{geometry}</clipPath>"
        ));
    }

    /// Registers a composite effects `<filter>` (drop/inner shadows + optional
    /// layer blur) matching classic SVG filter order, and returns its id.
    ///
    /// Order: transparent flood → drop shadows → SourceGraphic → inner shadows
    /// → layer blur. Shadow blur uses canvas sigma (`radius_to_sigma`); offsets
    /// and spread are scaled by the export scale.
    ///
    /// When `blend_source_graphic` is false, the filter ends after the drop
    /// chain (used for container silhouette passes that must not re-emit
    /// content — content is drawn in a separate unfiltered group).
    pub(super) fn push_effects_filter(
        &mut self,
        drops: &[&Shadow],
        inners: &[&Shadow],
        layer_blur_sigma: Option<f32>,
        scale: f32,
        blend_source_graphic: bool,
    ) -> String {
        let id = self.unique("fx");
        let mut body = String::new();
        body.push_str(r#"<feFlood flood-opacity="0" result="bg"/>"#);
        let mut prev = "bg".to_string();

        for (i, shadow) in drops.iter().enumerate() {
            let result = format!("drop{i}");
            append_drop_shadow_primitives(
                &mut body, shadow, scale, &prev, &result, /* morph_spread */ true,
            );
            prev = result;
        }

        if blend_source_graphic {
            body.push_str(&format!(
                r#"<feBlend mode="normal" in="SourceGraphic" in2="{prev}" result="shape"/>"#
            ));
            prev = "shape".to_string();

            for (i, shadow) in inners.iter().enumerate() {
                let result = format!("inner{i}");
                append_inner_shadow_primitives(&mut body, shadow, scale, &prev, &result);
                prev = result;
            }

            if let Some(sigma) = layer_blur_sigma {
                body.push_str(&format!(
                    r#"<feGaussianBlur in="{prev}" stdDeviation="{sigma}"/>"#
                ));
            }
        }

        self.defs.push_str(&format!(
            concat!(
                "<filter id=\"{id}\" {region} color-interpolation-filters=\"sRGB\">",
                "{body}</filter>"
            ),
            id = id,
            region = filter_region_attrs(self),
            body = body
        ));
        id
    }
}

fn color_matrix_values(color: skia::Color) -> String {
    let r = f32::from(color.r()) / 255.0;
    let g = f32::from(color.g()) / 255.0;
    let b = f32::from(color.b()) / 255.0;
    let a = f32::from(color.a()) / 255.0;
    format!("0 0 0 0 {r} 0 0 0 0 {g} 0 0 0 0 {b} 0 0 0 {a} 0")
}

/// Filter subregion covering the export page in user space.
///
/// Default SVG `objectBoundingBox` + `x/y=-50% width/height=200%` is a percent
/// of the *shape* bbox. Shadow reach (offset + blur sigma + spread) is absolute
/// pixels, so small/thin shapes crop the halo. Page bounds already include
/// shadow/blur via `extrect`; sizing the filter to the page matches that.
fn filter_region_attrs(builder: &SvgLayerCanvas) -> String {
    let w = builder.page_rect.width();
    let h = builder.page_rect.height();
    format!(r#"filterUnits="userSpaceOnUse" x="0" y="0" width="{w}" height="{h}""#)
}

fn append_drop_shadow_primitives(
    body: &mut String,
    shadow: &Shadow,
    scale: f32,
    in2: &str,
    result: &str,
    morph_spread: bool,
) {
    let sigma = radius_to_sigma(shadow.blur * scale);
    // `shadow.offset` must already be in the SVG filter user space (parent of
    // the transformed leaf). Callers map local design offsets through
    // `centered_transform().map_vector` for rotated/flipped leaves.
    let dx = shadow.offset.0 * scale;
    let dy = shadow.offset.1 * scale;
    let spread = shadow.spread * scale;
    let color = color_matrix_values(shadow.color);

    body.push_str(
        r#"<feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="alpha"/>"#,
    );
    // Container silhouettes apply spread geometrically (GPU). Morphology on a
    // stroke ring expands both edges and makes border shadows look too thick.
    let after_morph = if morph_spread && spread > 0.0 {
        body.push_str(&format!(
            r#"<feMorphology in="alpha" operator="dilate" radius="{spread}" result="spread"/>"#
        ));
        "spread"
    } else if morph_spread && spread < 0.0 {
        body.push_str(&format!(
            r#"<feMorphology in="alpha" operator="erode" radius="{}" result="spread"/>"#,
            -spread
        ));
        "spread"
    } else {
        "alpha"
    };
    body.push_str(&format!(
        r#"<feOffset in="{after_morph}" dx="{dx}" dy="{dy}" result="off"/>"#
    ));
    body.push_str(&format!(
        r#"<feGaussianBlur in="off" stdDeviation="{sigma}" result="blurred"/>"#
    ));
    body.push_str(&format!(
        r#"<feColorMatrix in="blurred" type="matrix" values="{color}" result="colored"/>"#
    ));
    body.push_str(&format!(
        r#"<feBlend mode="normal" in="colored" in2="{in2}" result="{result}"/>"#
    ));
}

fn append_inner_shadow_primitives(
    body: &mut String,
    shadow: &Shadow,
    scale: f32,
    in2: &str,
    result: &str,
) {
    let sigma = radius_to_sigma(shadow.blur * scale);
    let dx = shadow.offset.0 * scale;
    let dy = shadow.offset.1 * scale;
    let spread = shadow.spread * scale;
    let color = color_matrix_values(shadow.color);

    // Classic inner-shadow graph: hard alpha, optional erode for +spread,
    // offset+blur, subtract from hard alpha, tint, blend over prior result.
    body.push_str(
        r#"<feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="hardAlpha"/>"#,
    );
    let morph_in = if spread > 0.0 {
        body.push_str(&format!(
            r#"<feMorphology in="hardAlpha" operator="erode" radius="{spread}" result="spread"/>"#
        ));
        "spread"
    } else {
        "hardAlpha"
    };
    body.push_str(&format!(
        r#"<feOffset in="{morph_in}" dx="{dx}" dy="{dy}" result="off"/>"#
    ));
    body.push_str(&format!(
        r#"<feGaussianBlur in="off" stdDeviation="{sigma}" result="blurred"/>"#
    ));
    body.push_str(
        r#"<feComposite in="blurred" in2="hardAlpha" operator="arithmetic" k2="-1" k3="1" result="shadow"/>"#,
    );
    body.push_str(&format!(
        r#"<feColorMatrix in="shadow" type="matrix" values="{color}" result="colored"/>"#
    ));
    body.push_str(&format!(
        r#"<feBlend mode="normal" in="colored" in2="{in2}" result="{result}"/>"#
    ));
}

/// Draws a clip geometry into `cv` (already set up with the page transform).
///
/// `silhouette_offset` is applied in local space (same as
/// [`SvgLayerCanvas::silhouette_draw_matrix`]) so board `clip content` during a
/// container drop pass tracks the shifted silhouette.
fn draw_clip_geometry(
    cv: &skia::Canvas,
    shape: &Shape,
    tree: ShapesPoolRef,
    paint: &Paint,
    silhouette_offset: (f32, f32),
) {
    if let Type::Group(_) = &shape.shape_type {
        for child_id in shape.children_ids_iter_forward(true) {
            if let Some(child) = tree.get(child_id) {
                draw_clip_geometry(cv, child, tree, paint, silhouette_offset);
            }
        }
        return;
    }

    cv.save();
    let mut matrix = shape.centered_transform();
    let (dx, dy) = silhouette_offset;
    if dx != 0.0 || dy != 0.0 {
        matrix.pre_translate((dx, dy));
    }
    cv.concat(&matrix);
    draw_shape_geometry(cv, shape, paint);
    cv.restore();
}

/// Returns a shape whose `selrect` is expanded/shrunk by `outset` (design space).
/// Used for GPU-matching geometric drop-shadow spread.
pub(super) fn shape_with_selrect_outset(shape: &Shape, outset: f32) -> Shape {
    let mut out = shape.clone();
    if outset > 0.0 {
        out.selrect.outset((outset, outset));
    } else if outset < 0.0 {
        out.selrect.inset((-outset, -outset));
    }
    out
}

/// Builds the `<g>` attribute string for a leaf shape's composite effects
/// (opacity, blend mode, drop/inner shadows, layer blur). Returns `None` when
/// the shape needs no wrapper.
///
/// Shadows and layer blur are native SVG `<filter>`s — `SkSVGDevice` drops the
/// GPU `save_layer` image-filter path. When `suppress_filters` is set (parent
/// drop-shadow silhouette pass), only opacity/blend are emitted.
pub(super) fn effect_attrs(builder: &mut SvgLayerCanvas, element: &Shape) -> Option<String> {
    wrapper_attrs(builder, element, EffectFilterMode::LeafComposite)
}

/// Opacity / blend for a container, wrapping both drop silhouettes and content.
///
/// Matches GPU `render_shape_enter` (opacity save_layer before shadow composite)
/// so container drop shadows inherit the board's opacity.
pub(super) fn opacity_blend_attrs(element: &Shape) -> Option<String> {
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

/// Inner shadows / layer blur for a container's real content group.
///
/// Opacity and blend are applied by [`opacity_blend_attrs`] around silhouette +
/// content. Drop shadows are a separate silhouette pass.
pub(super) fn content_effect_attrs(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
) -> Option<String> {
    wrapper_attrs(builder, element, EffectFilterMode::ContentWithoutDrops)
}

/// Drop-shadow-only filter for one container silhouette pass.
///
/// Offset and spread are applied geometrically while drawing (local space,
/// matching GPU). The filter only hardens alpha, blurs, and tints.
pub(super) fn push_container_drop_filter(builder: &mut SvgLayerCanvas, shadow: &Shadow) -> String {
    let scale = builder.scale;
    let id = builder.unique("fx");
    let mut body = String::from(r#"<feFlood flood-opacity="0" result="bg"/>"#);
    // Zero offset: geometric `pre_translate` already moved the silhouette.
    let mut filter_shadow = *shadow;
    filter_shadow.offset = (0.0, 0.0);
    append_drop_shadow_primitives(
        &mut body,
        &filter_shadow,
        scale,
        "bg",
        "drop0",
        /* morph_spread */ false,
    );
    builder.defs.push_str(&format!(
        concat!(
            "<filter id=\"{id}\" {region} color-interpolation-filters=\"sRGB\">",
            "{body}</filter>"
        ),
        id = id,
        region = filter_region_attrs(builder),
        body = body
    ));
    id
}

/// Dilates/erodes glyph alpha for text drawn inside a container drop silhouette.
///
/// Container drop filters omit `feMorphology` so stroke-ring silhouettes stay
/// thin (morph fattens both edges). Text children still need spread: GPU paints
/// them with `Shadow::get_drop_shadow_filter`, which wraps `drop_shadow_only`
/// in `dilate(spread)` (thicken the already blurred shadow).
///
/// Approximation: we nest `feMorphology` on the glyph alpha *before* the
/// parent container blur. That is morph-then-blur, not dilate-after-drop like
/// Skia. Close enough for export parity; halo softness can differ slightly.
pub(super) fn push_text_silhouette_spread_filter(
    builder: &mut SvgLayerCanvas,
    spread: f32,
) -> Option<String> {
    let radius = spread * builder.scale;
    if radius == 0.0 {
        return None;
    }
    let id = builder.unique("txmorph");
    let (op, r) = if radius > 0.0 {
        ("dilate", radius)
    } else {
        ("erode", -radius)
    };
    builder.defs.push_str(&format!(
        concat!(
            "<filter id=\"{id}\" {region} color-interpolation-filters=\"sRGB\">",
            "<feMorphology in=\"SourceAlpha\" operator=\"{op}\" radius=\"{r}\" result=\"m\"/>",
            "<feFlood flood-color=\"#000000\" flood-opacity=\"1\" result=\"f\"/>",
            "<feComposite in=\"f\" in2=\"m\" operator=\"in\"/>",
            "</filter>"
        ),
        id = id,
        region = filter_region_attrs(builder),
        op = op,
        r = r
    ));
    Some(id)
}

#[derive(Clone, Copy)]
enum EffectFilterMode {
    /// Flood → drops → SourceGraphic → inners → blur (leaves).
    LeafComposite,
    /// Flood → SourceGraphic → inners → blur (container content; drops separate).
    ContentWithoutDrops,
}

/// Maps a design-space shadow offset into SVG filter user space.
///
/// Leaf geometry is drawn with `centered_transform` (rotation / flip). GPU
/// `drop_shadow_only` offsets in that local space, then the CTM maps it. SVG
/// `feOffset` runs after the leaf is painted into the parent group, so the
/// offset must be `map_vector` of the local offset or rotated shadows drift.
fn shadow_with_user_space_offset(shape: &Shape, shadow: &Shadow) -> Shadow {
    let mut out = *shadow;
    let mapped = shape.centered_transform().map_vector(shadow.offset);
    out.offset = (mapped.x, mapped.y);
    out
}

fn wrapper_attrs(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    mode: EffectFilterMode,
) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();

    // Leaves keep opacity/blend on the same wrapper as their filter. Containers
    // use [`opacity_blend_attrs`] outside silhouette + content instead.
    if matches!(mode, EffectFilterMode::LeafComposite) {
        let opacity = element.opacity();
        if opacity < 1.0 {
            parts.push(format!("opacity=\"{opacity}\""));
        }

        if let Some(css) = blend_css(element.blend_mode().0) {
            parts.push(format!("style=\"mix-blend-mode:{css}\""));
        }
    }

    if !builder.suppress_filters {
        let scale = builder.scale;
        let mapped_inners: Vec<Shadow> = element
            .inner_shadows_visible()
            .map(|s| shadow_with_user_space_offset(element, s))
            .collect();
        let inners: Vec<&Shadow> = mapped_inners.iter().collect();
        let layer_blur_sigma = element
            .visible_layer_blur()
            .map(|blur| radius_to_sigma(blur.value * scale));

        match mode {
            EffectFilterMode::LeafComposite => {
                let mapped_drops: Vec<Shadow> = element
                    .drop_shadows_visible()
                    .map(|s| shadow_with_user_space_offset(element, s))
                    .collect();
                let drops: Vec<&Shadow> = mapped_drops.iter().collect();
                if !drops.is_empty() || !inners.is_empty() || layer_blur_sigma.is_some() {
                    let id =
                        builder.push_effects_filter(&drops, &inners, layer_blur_sigma, scale, true);
                    parts.push(format!("filter=\"url(#{id})\""));
                }
            }
            EffectFilterMode::ContentWithoutDrops => {
                if !inners.is_empty() || layer_blur_sigma.is_some() {
                    let id =
                        builder.push_effects_filter(&[], &inners, layer_blur_sigma, scale, true);
                    parts.push(format!("filter=\"url(#{id})\""));
                }
            }
        }
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
