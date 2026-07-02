use skia_safe::Canvas;

use crate::error::Result;
use crate::shapes::{Shape, StrokeKind};

use super::document::SvgLayerCanvas;
use super::masks::push_inverse_glyph_mask;
use crate::render::vector::{ExportState, VectorRenderer, VectorTarget};

/// Emits each semi-transparent *center* text stroke as a `<g opacity>` wrapper
/// around the fully-opaque stroke geometry, matching the GPU/PDF opacity-layer
/// result without a `save_layer` (which `SkSVGDevice` would drop). Inner strokes
/// are handled by `render_text_inner_strokes` and outer strokes (any
/// opacity) by `render_text_outer_strokes`.
pub(super) fn render_text_alpha_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let matrix = element.centered_transform();
    for stroke in element.visible_strokes() {
        if stroke.render_kind(false) != StrokeKind::Center {
            continue;
        }
        let opacity = stroke.fill.opacity();
        if opacity >= 1.0 {
            // Opaque strokes were already drawn by `render_leaf_content`.
            continue;
        }

        builder.open_group(&format!("opacity=\"{opacity}\""));
        {
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
            renderer.draw_text_stroke_opaque(element, stroke)?;
            canvas.restore();
        }
        builder.close_group();
    }
    Ok(())
}

/// Emits each inner text stroke as a `<g clip-path>` (glyph-silhouette clip)
/// wrapping the fully-opaque double-width stroke, plus a `<g opacity>` when the
/// stroke is semi-transparent. Reproduces the GPU/PDF mask + `SrcIn` + `DstOver`
/// inner-stroke composition, which `SkSVGDevice` drops (it lives inside
/// `save_layer`s). Inner strokes vanish from SVG regardless of opacity, so all
/// of them are handled here.
pub(super) fn render_text_inner_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let matrix = element.centered_transform();
    for stroke in element.visible_strokes() {
        if stroke.render_kind(false) != StrokeKind::Inner {
            continue;
        }

        // Clip path from the opaque glyph silhouette: clipping the double-width
        // stroke to the glyph interior keeps only its inner half.
        let clip_id = builder.unique("tclip");
        {
            let canvas = builder.new_fragment();
            {
                let cv: &Canvas = &*canvas;
                cv.concat(&matrix);
                let mut renderer = VectorRenderer::new(cv, shared, scale, VectorTarget::Svg);
                renderer.draw_text_glyph_silhouette(element)?;
            }
            builder.finish_clip_path_fragment(&clip_id, canvas);
        }

        let opacity = stroke.fill.opacity();
        let mut attrs = format!("clip-path=\"url(#{clip_id})\"");
        if opacity < 1.0 {
            attrs.push_str(&format!(" opacity=\"{opacity}\""));
        }
        builder.open_group(&attrs);
        {
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
            renderer.draw_text_stroke_opaque(element, stroke)?;
            canvas.restore();
        }
        builder.close_group();
    }
    Ok(())
}

/// Emits each *outer* text stroke as a `<g mask>` (glyph-exterior mask) wrapping
/// the fully-opaque double-width stroke, plus a `<g opacity>` when the stroke is
/// semi-transparent. The shared renderer draws the outer stroke at double width
/// centered on the glyph outline and relies on a `save_layer` + `Clear` (keep
/// only the outer half) that `SkSVGDevice` drops, so on SVG it is skipped there
/// and re-emitted here masked to the glyph exterior (an inverse-of-glyph
/// luminance mask: white canvas minus the glyphs, since `<clipPath>` cannot
/// subtract), keeping only the outer half and matching the GPU/PDF width.
pub(super) fn render_text_outer_strokes(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    scale: f32,
) -> Result<()> {
    let matrix = element.centered_transform();
    for stroke in element.visible_strokes() {
        if stroke.render_kind(false) != StrokeKind::Outer {
            continue;
        }

        let mask_id = push_inverse_glyph_mask(builder, shared, element, &matrix, scale)?;

        let opacity = stroke.fill.opacity();
        let mut attrs = format!("mask=\"url(#{mask_id})\"");
        if opacity < 1.0 {
            attrs.push_str(&format!(" opacity=\"{opacity}\""));
        }
        builder.open_group(&attrs);
        {
            let canvas = builder.canvas();
            canvas.save();
            canvas.concat(&matrix);
            let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
            renderer.draw_text_stroke_opaque(element, stroke)?;
            canvas.restore();
        }
        builder.close_group();
    }
    Ok(())
}
