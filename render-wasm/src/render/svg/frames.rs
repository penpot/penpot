use crate::error::Result;
use crate::render::shape_renderer::ShapeRenderer;
use crate::shapes::{Shape, Stroke};
use crate::state::ShapesPoolRef;

use super::document::{effect_attrs, SvgLayerCanvas};
use super::render_tree;
use super::shadows::{render_container_drop_shadow, render_inner_shadows};
use super::strokes::render_image_strokes;
use crate::render::vector::{children_paint_order, ExportState, VectorRenderer, VectorTarget};

pub(super) fn render_frame(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    render_shadows: bool,
) -> Result<()> {
    let matrix = element.centered_transform();

    let effects = effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    // Frame drop shadow: like the GPU/PDF path, the silhouette is the frame's
    // rendered content (background + strokes + children), but the descendants'
    // *own* drop shadows must not bleed into it (that would trace a second,
    // doubled silhouette). It is emitted as a separate shadow-only pass drawn
    // behind the content, sitting outside the content clip so the offset shadow
    // is not clipped to the frame bounds.
    if render_shadows {
        render_container_drop_shadow(builder, shared, element, tree, scale)?;
    }

    let clipped = element.clip_content;
    if clipped {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, element, tree);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }

    // Frame background + inner shadows (frame space).
    if !element.fills.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, VectorTarget::Svg);
        renderer.draw_fills(element, &element.fills)?;
        canvas.restore();
    }

    // Frame-background inner shadows (native `<g filter>`; the shared renderer's
    // `save_layer` version is dropped by `SkSVGDevice`). Over the background,
    // under the children, matching the GPU order.
    render_inner_shadows(builder, shared, element, scale)?;

    // Children (absolute coords).
    for child_id in &children_paint_order(tree, element) {
        render_tree(builder, shared, child_id, tree, scale, render_shadows)?;
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

        // Image-filled frame strokes are deferred by the shared renderer; re-emit
        // the texture masked to the stroke region.
        render_image_strokes(builder, shared, element, scale)?;
    }

    if clipped {
        builder.close_group();
    }
    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}
