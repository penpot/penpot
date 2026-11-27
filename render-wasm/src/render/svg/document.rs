use skia_safe::{self as skia, Paint};

use crate::error::Result;
use crate::shapes::{radius_to_sigma, Fill, Shadow, Shape, Type};
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
    /// Stack of group fills inherited by empty-fill children (GPU `nested_fills`).
    /// Frames push an empty vec to break inheritance. SVG-imported groups often
    /// carry a default black fill that paths without own fills must paint.
    pub(super) nested_fills: Vec<Vec<Fill>>,
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
            nested_fills: Vec::new(),
        }
    }

    /// Fills to paint for a leaf: own fills, else inherited group fills.
    /// Owned so callers can still mutably borrow `self` afterward.
    pub(super) fn effective_fills_owned(&self, element: &Shape) -> Vec<Fill> {
        if element.inherits_fills() {
            self.nested_fills.last().cloned().unwrap_or_default()
        } else {
            element.fills.clone()
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

    /// Runs `f` while diverting body markup into a temporary buffer.
    ///
    /// Pending Skia fragments are flushed before/after. Defs (filters, clips,
    /// nested masks) still append to `self.defs`. Used to build `<mask>` bodies
    /// from a full mask subtree render.
    pub(super) fn capture_body<F>(&mut self, f: F) -> Result<String>
    where
        F: FnOnce(&mut Self) -> Result<()>,
    {
        self.flush();
        let saved = std::mem::take(&mut self.out);
        let result = f(self);
        self.flush();
        let captured = std::mem::replace(&mut self.out, saved);
        result?;
        Ok(captured)
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

    /// Maps a shape-local rect through the page CTM into SVG user space.
    pub(super) fn map_selrect_to_page(
        &self,
        local: &skia::Rect,
        draw_matrix: &skia::Matrix,
    ) -> skia::Rect {
        self.page_ctm(draw_matrix).map_rect(*local).0
    }

    /// Maps a shape-local path through the page CTM into SVG user space.
    pub(super) fn map_path_to_page(
        &self,
        path: &skia::Path,
        draw_matrix: &skia::Matrix,
    ) -> skia::Path {
        path.make_transform(&self.page_ctm(draw_matrix))
    }

    /// Scale × translate × `draw_matrix` (same CTM as SVG fragment canvases).
    fn page_ctm(&self, draw_matrix: &skia::Matrix) -> skia::Matrix {
        let mut ctm = skia::Matrix::scale((self.scale, self.scale));
        ctm = ctm * skia::Matrix::translate((self.tx, self.ty));
        ctm * *draw_matrix
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

    /// Finalizes a fragment canvas as a luminance `<mask>` def (white shows,
    /// black hides). Used for outer text strokes: white canvas minus black
    /// glyphs keeps only the exterior half of a double-width stroke.
    pub(super) fn finish_mask_fragment(&mut self, id: &str, canvas: skia::svg::Canvas) {
        let data = canvas.end();
        let doc = String::from_utf8_lossy(data.as_bytes());
        let inner = extract_inner_svg(&doc);
        let prefix = format!("f{}_", self.frag_no);
        self.frag_no += 1;
        let geometry = sanitize_skia_svg_fragment(&remap_ids(inner, &prefix));
        self.defs.push_str(&format!(
            "<mask id=\"{id}\" maskUnits=\"userSpaceOnUse\">{geometry}</mask>"
        ));
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
/// Unified ideograph of each Kangxi radical, U+2F00 to U+2FD5: their
/// compatibility (NFKC) mappings.
const KANGXI_IDEOGRAPHS: [u32; 214] = [
    0x4E00, 0x4E28, 0x4E36, 0x4E3F, 0x4E59, 0x4E85, 0x4E8C, 0x4EA0, 0x4EBA, 0x513F, 0x5165, 0x516B,
    0x5182, 0x5196, 0x51AB, 0x51E0, 0x51F5, 0x5200, 0x529B, 0x52F9, 0x5315, 0x531A, 0x5338, 0x5341,
    0x535C, 0x5369, 0x5382, 0x53B6, 0x53C8, 0x53E3, 0x56D7, 0x571F, 0x58EB, 0x5902, 0x590A, 0x5915,
    0x5927, 0x5973, 0x5B50, 0x5B80, 0x5BF8, 0x5C0F, 0x5C22, 0x5C38, 0x5C6E, 0x5C71, 0x5DDB, 0x5DE5,
    0x5DF1, 0x5DFE, 0x5E72, 0x5E7A, 0x5E7F, 0x5EF4, 0x5EFE, 0x5F0B, 0x5F13, 0x5F50, 0x5F61, 0x5F73,
    0x5FC3, 0x6208, 0x6236, 0x624B, 0x652F, 0x6534, 0x6587, 0x6597, 0x65A4, 0x65B9, 0x65E0, 0x65E5,
    0x66F0, 0x6708, 0x6728, 0x6B20, 0x6B62, 0x6B79, 0x6BB3, 0x6BCB, 0x6BD4, 0x6BDB, 0x6C0F, 0x6C14,
    0x6C34, 0x706B, 0x722A, 0x7236, 0x723B, 0x723F, 0x7247, 0x7259, 0x725B, 0x72AC, 0x7384, 0x7389,
    0x74DC, 0x74E6, 0x7518, 0x751F, 0x7528, 0x7530, 0x758B, 0x7592, 0x7676, 0x767D, 0x76AE, 0x76BF,
    0x76EE, 0x77DB, 0x77E2, 0x77F3, 0x793A, 0x79B8, 0x79BE, 0x7A74, 0x7ACB, 0x7AF9, 0x7C73, 0x7CF8,
    0x7F36, 0x7F51, 0x7F8A, 0x7FBD, 0x8001, 0x800C, 0x8012, 0x8033, 0x807F, 0x8089, 0x81E3, 0x81EA,
    0x81F3, 0x81FC, 0x820C, 0x821B, 0x821F, 0x826E, 0x8272, 0x8278, 0x864D, 0x866B, 0x8840, 0x884C,
    0x8863, 0x897E, 0x898B, 0x89D2, 0x8A00, 0x8C37, 0x8C46, 0x8C55, 0x8C78, 0x8C9D, 0x8D64, 0x8D70,
    0x8DB3, 0x8EAB, 0x8ECA, 0x8F9B, 0x8FB0, 0x8FB5, 0x9091, 0x9149, 0x91C6, 0x91CC, 0x91D1, 0x9577,
    0x9580, 0x961C, 0x96B6, 0x96B9, 0x96E8, 0x9751, 0x975E, 0x9762, 0x9769, 0x97CB, 0x97ED, 0x97F3,
    0x9801, 0x98A8, 0x98DB, 0x98DF, 0x9996, 0x9999, 0x99AC, 0x9AA8, 0x9AD8, 0x9ADF, 0x9B25, 0x9B2F,
    0x9B32, 0x9B3C, 0x9B5A, 0x9CE5, 0x9E75, 0x9E7F, 0x9EA5, 0x9EBB, 0x9EC3, 0x9ECD, 0x9ED1, 0x9EF9,
    0x9EFD, 0x9F0E, 0x9F13, 0x9F20, 0x9F3B, 0x9F4A, 0x9F52, 0x9F8D, 0x9F9C, 0x9FA0,
];

/// The character Skia's SVG device should have written for `ch`. It writes a
/// glyph back as the first code point the font maps to it, and CJK fonts map a
/// radical and its unified ideograph to one glyph: 山 comes out as the radical
/// ⼭ (U+2F2D), which search, copy and screen readers take for another
/// character. Texts hardly ever hold the radicals themselves.
fn unified_ideograph(ch: char) -> char {
    let cp = ch as u32;
    let mapped = match cp {
        0x2F00..=0x2FD5 => KANGXI_IDEOGRAPHS[(cp - 0x2F00) as usize],
        0x2E9F => 0x6BCD,
        0x2EF3 => 0x9F9F,
        _ => cp,
    };
    char::from_u32(mapped).unwrap_or(ch)
}

/// Removes `<text>` elements with no character, which Skia writes for runs of
/// zero-width controls such as a WORD JOINER.
fn drop_empty_text_elements(out: &mut String) {
    let mut search_from = 0;
    while let Some(rel) = out[search_from..].find("<text") {
        let start = search_from + rel;
        // Not `<textPath`: the tag name ends there.
        if !matches!(out[start + "<text".len()..].chars().next(), Some(' ' | '>')) {
            search_from = start + "<text".len();
            continue;
        }
        let Some(open_end) = out[start..].find('>').map(|i| start + i + 1) else {
            break;
        };
        let Some(close) = out[open_end..].find("</text>").map(|i| open_end + i) else {
            break;
        };
        let content = &out[open_end..close];
        if !out[start..open_end].ends_with("/>") && content.trim().is_empty() {
            out.replace_range(start..close + "</text>".len(), "");
            search_from = start;
        } else {
            search_from = open_end;
        }
    }
}

fn sanitize_skia_svg_fragment(body: &str) -> String {
    const LIST_ATTRS: [&str; 4] = ["x=\"", "y=\"", "dx=\"", "dy=\""];
    let mut out: String = body.chars().map(unified_ideograph).collect();
    drop_empty_text_elements(&mut out);

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
    fn writes_unified_ideographs_instead_of_radicals() {
        let out = sanitize_skia_svg_fragment(
            "<text x=\"0\" y=\"0\">\u{2F2D}\u{2F0A}\u{2FBB}\u{2E9F}の</text>",
        );
        assert!(out.contains("山入骨母の"), "{out}");
    }

    #[test]
    fn drops_text_elements_without_characters() {
        let out = sanitize_skia_svg_fragment(
            "<g><text x=\"\" y=\"\">\n\t\t</text><text x=\"1\" y=\"2\">紅</text></g>",
        );
        assert_eq!(out, "<g><text x=\"1\" y=\"2\">紅</text></g>");
    }

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
