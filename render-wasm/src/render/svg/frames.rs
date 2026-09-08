use crate::error::Result;
use crate::render::shape_renderer::ShapeRenderer;
use crate::render::vector::VectorRenderer;
use crate::shapes::{Shape, Stroke};
use crate::state::ShapesPoolRef;

use super::document::{effect_attrs, SvgLayerCanvas};
use super::images::emit_fills;
use super::render_tree;
use crate::render::RenderResources;

pub(super) fn render_frame(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let matrix = element.centered_transform();

    let effects = effect_attrs(element);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    let clipped = element.clip_content;
    if clipped {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, element, tree);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }

    // Frame background (frame space), with linked `<image>` for image fills.
    if !element.fills.is_empty() {
        emit_fills(builder, shared, element, &element.fills, tree, scale)?;
    }

    // Children (absolute coords).
    let children: Vec<_> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale)?;
    }

    // Close content clip before strokes. Outer (and half of center) strokes
    // extend past the frame bounds; keeping them under clip-path hides them.
    // Matches GPU: clipped-frame strokes render in exit without the frame clip.
    if clipped {
        builder.close_group();
    }

    // Strokes over children (frame space), outside the content clip.
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let canvas = builder.canvas();
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, false);
        renderer.draw_strokes(element, &visible_strokes)?;
        canvas.restore();
    }

    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}
