use skia_safe::{self as skia, Paint};

use crate::error::Result;
use crate::render::shape_renderer::ShapeRenderer;
use crate::shapes::{radius_to_sigma, Shadow, Shape, Stroke, Type};
use crate::state::ShapesPoolRef;

use super::document::SvgLayerCanvas;
use super::render_tree;
use crate::render::vector::{children_paint_order, draw_shape_geometry, ExportState, VectorRenderer, VectorTarget};

/// Adaptive `<filter>` region as `objectBoundingBox` percentages.
struct FilterRegion {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
}

fn filter_region(bbox: skia::Rect, scale: f32, margin_x: f32, margin_y: f32) -> FilterRegion {
    let w = (bbox.width() * scale).abs().max(1.0);
    let h = (bbox.height() * scale).abs().max(1.0);
    FilterRegion {
        x: -margin_x / w * 100.0,
        y: -margin_y / h * 100.0,
        width: (1.0 + 2.0 * margin_x / w) * 100.0,
        height: (1.0 + 2.0 * margin_y / h) * 100.0,
    }
}

fn track_shadow_reach(
    margin_x: f32,
    margin_y: f32,
    sigma: f32,
    spread: f32,
    scale: f32,
    dx: f32,
    dy: f32,
) -> (f32, f32) {
    let reach = 3.0 * sigma + spread * scale;
    (
        margin_x.max(reach + dx.abs()),
        margin_y.max(reach + dy.abs()),
    )
}

fn shadow_tint(shadow: &Shadow) -> (String, f32) {
    let color = shadow.color;
    (
        format!("#{:02X}{:02X}{:02X}", color.r(), color.g(), color.b()),
        color.a() as f32 / 255.0,
    )
}

fn intern_filter_attr(
    builder: &mut SvgLayerCanvas,
    prefix: &str,
    region: &FilterRegion,
    prims: &str,
    merges: &str,
) -> String {
    let body = format!(
        "x=\"{:.4}%\" y=\"{:.4}%\" width=\"{:.4}%\" height=\"{:.4}%\" \
         primitiveUnits=\"userSpaceOnUse\" color-interpolation-filters=\"sRGB\">\
         {prims}<feMerge>{merges}</feMerge>",
        region.x, region.y, region.width, region.height,
    );
    let id = builder.intern_def(prefix, &body, |id| format!("<filter id=\"{id}\" {body}</filter>"));
    format!("filter=\"url(#{id})\"")
}

/// Emits a container's (frame/group) drop shadows as a shadow-only `<g filter>`
/// group drawn *behind* the content. The group holds the container's silhouette:
/// its own fills/strokes plus its descendants rendered with `render_shadows =
/// false`, so the filter's `SourceAlpha` is the shape silhouette *without* the
/// descendants' own drop shadows (which would otherwise cast a second, offset
/// green copy: the "doubled shadow"). The filter merges only the tinted/offset
/// shadow (no `SourceGraphic`), since the real content is drawn separately on
/// top.
///
/// For a frame, the descendants' silhouette is clipped to the frame geometry,
/// mirroring the GPU (`get_nested_shadow_clip_bounds` clips each child's shadow
/// to the frame selrect). The frame's *own* fills/strokes are left unclipped
/// (the GPU renders them with `clip_content = false`). The clip is applied
/// pre-offset; the filter's `feOffset` then shifts the clipped silhouette,
/// matching the GPU's selrect-shifted-by-offset clip.
pub(super) fn render_container_drop_shadow(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let Some(attrs) = drop_shadow_attr(builder, element, scale, false) else {
        return Ok(());
    };

    let matrix = element.centered_transform();
    builder.open_group(&attrs);

    // Frame background silhouette (frame space, unclipped).
    if !element.fills.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        renderer.draw_fills(element, &element.fills)?;
        canvas.restore();
    }

    // Descendants (absolute coords), with their own shadows suppressed. Clipped
    // to the frame geometry to match the GPU nested-shadow clip.
    let children = children_paint_order(tree, element);
    let clip_children = !children.is_empty() && matches!(element.shape_type, Type::Frame(_));
    if clip_children {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, element, tree);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale, false)?;
    }
    if clip_children {
        builder.close_group();
    }

    // Frame strokes silhouette (frame space, unclipped).
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        renderer.draw_strokes(element, &visible_strokes)?;
        canvas.restore();
    }

    builder.close_group();
    Ok(())
}

/// Registers an SVG `<filter>` reproducing the shape's visible drop shadows
/// (Penpot paints them behind the shape) and returns the `<g filter=…>`
/// attribute referencing it, or `None` when there are none.
pub(super) fn drop_shadow_attr(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    scale: f32,
    include_source: bool,
) -> Option<String> {
    let shadows: Vec<&Shadow> = element.drop_shadows_visible().collect();
    if shadows.is_empty() {
        return None;
    }

    let mut margin_x = 0.0_f32;
    let mut margin_y = 0.0_f32;
    let mut prims = String::new();
    let mut merges = String::new();

    for (i, shadow) in shadows.iter().enumerate() {
        let sigma = radius_to_sigma(shadow.blur * scale);
        let dx = shadow.offset.0 * scale;
        let dy = shadow.offset.1 * scale;
        (margin_x, margin_y) = track_shadow_reach(margin_x, margin_y, sigma, shadow.spread, scale, dx, dy);
        let (hex, opacity) = shadow_tint(shadow);

        let mut input = "SourceAlpha".to_string();
        if shadow.spread > 0.0 {
            let dilated = format!("shsp{i}");
            prims.push_str(&format!(
                "<feMorphology in=\"{input}\" operator=\"dilate\" \
                 radius=\"{}\" result=\"{dilated}\"/>",
                shadow.spread * scale
            ));
            input = dilated;
        }
        let blurred = format!("shbl{i}");
        prims.push_str(&format!(
            "<feGaussianBlur in=\"{input}\" stdDeviation=\"{sigma}\" result=\"{blurred}\"/>"
        ));
        let offset = format!("shof{i}");
        prims.push_str(&format!(
            "<feOffset in=\"{blurred}\" dx=\"{dx}\" dy=\"{dy}\" result=\"{offset}\"/>"
        ));
        let flood = format!("shfl{i}");
        prims.push_str(&format!(
            "<feFlood flood-color=\"{hex}\" flood-opacity=\"{opacity}\" result=\"{flood}\"/>"
        ));
        let tinted = format!("shad{i}");
        prims.push_str(&format!(
            "<feComposite in=\"{flood}\" in2=\"{offset}\" \
             operator=\"in\" result=\"{tinted}\"/>"
        ));
        merges.push_str(&format!("<feMergeNode in=\"{tinted}\"/>"));
    }
    if include_source {
        merges.push_str("<feMergeNode in=\"SourceGraphic\"/>");
    }

    let region = filter_region(element.selrect(), scale, margin_x, margin_y);
    Some(intern_filter_attr(builder, "shadow", &region, &prims, &merges))
}

/// Builds an `filter="url(#…)"` attribute for a shape's *inner* shadows.
fn inner_shadow_attr(
    builder: &mut SvgLayerCanvas,
    element: &Shape,
    scale: f32,
) -> Option<String> {
    let shadows: Vec<&Shadow> = element.inner_shadows_visible().collect();
    if shadows.is_empty() {
        return None;
    }

    let mut margin_x = 0.0_f32;
    let mut margin_y = 0.0_f32;
    let mut prims = String::new();
    let mut merges = String::new();

    for (i, shadow) in shadows.iter().enumerate() {
        let sigma = radius_to_sigma(shadow.blur * scale);
        let dx = shadow.offset.0 * scale;
        let dy = shadow.offset.1 * scale;
        (margin_x, margin_y) = track_shadow_reach(margin_x, margin_y, sigma, shadow.spread, scale, dx, dy);
        let (hex, opacity) = shadow_tint(shadow);

        let blurred = format!("isbl{i}");
        prims.push_str(&format!(
            "<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\"{sigma}\" result=\"{blurred}\"/>"
        ));
        let occluder = format!("isof{i}");
        prims.push_str(&format!(
            "<feOffset in=\"{blurred}\" dx=\"{dx}\" dy=\"{dy}\" result=\"{occluder}\"/>"
        ));
        let band = format!("isbd{i}");
        prims.push_str(&format!(
            "<feComposite in=\"SourceAlpha\" in2=\"{occluder}\" \
             operator=\"out\" result=\"{band}\"/>"
        ));
        let flood = format!("isfl{i}");
        prims.push_str(&format!(
            "<feFlood flood-color=\"{hex}\" flood-opacity=\"{opacity}\" result=\"{flood}\"/>"
        ));
        let tinted = format!("istn{i}");
        prims.push_str(&format!(
            "<feComposite in=\"{flood}\" in2=\"{band}\" \
             operator=\"in\" result=\"{tinted}\"/>"
        ));
        let mut clip_input = tinted.clone();
        if shadow.spread > 0.0 {
            let dilated = format!("issp{i}");
            prims.push_str(&format!(
                "<feMorphology in=\"{clip_input}\" operator=\"dilate\" \
                 radius=\"{}\" result=\"{dilated}\"/>",
                shadow.spread * scale
            ));
            clip_input = dilated;
        }
        let shade = format!("issh{i}");
        prims.push_str(&format!(
            "<feComposite in=\"{clip_input}\" in2=\"SourceAlpha\" \
             operator=\"in\" result=\"{shade}\"/>"
        ));
        merges.push_str(&format!("<feMergeNode in=\"{shade}\"/>"));
    }

    let region = filter_region(element.selrect(), scale, margin_x, margin_y);
    Some(intern_filter_attr(builder, "inshadow", &region, &prims, &merges))
}

/// Emits a shape's inner shadows as a `<g filter>` wrapping an opaque silhouette
/// (the shape geometry, or the glyph silhouette for text) drawn over the fill.
pub(super) fn render_inner_shadows(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let is_text = matches!(element.shape_type, Type::Text(_));
    if !is_text && !element.has_fills() {
        return Ok(());
    }
    let Some(attrs) = inner_shadow_attr(builder, element, scale) else {
        return Ok(());
    };

    builder.open_group(&attrs);
    {
        let matrix = element.centered_transform();
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        if is_text {
            let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
            renderer.draw_text_glyph_silhouette(element)?;
        } else {
            let mut paint = Paint::default();
            paint.set_anti_alias(true);
            paint.set_color(skia::Color::BLACK);
            draw_shape_geometry(canvas, element, &paint);
        }
        canvas.restore();
    }
    builder.close_group();
    Ok(())
}
