use crate::error::Result;
use crate::shapes::{Shadow, Shape};
use crate::state::ShapesPoolRef;

use super::document::{
    content_effect_attrs, opacity_blend_attrs, push_container_drop_filter,
    shape_with_selrect_outset, SvgLayerCanvas,
};
use super::images::{emit_fills, emit_strokes};
use super::render_tree;
use crate::render::RenderResources;

pub(super) fn render_frame(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    // Opacity/blend wrap silhouette + content (GPU opens the opacity save_layer
    // before the shadow composite).
    let composite = opacity_blend_attrs(element);
    if let Some(attrs) = &composite {
        builder.open_group(attrs);
    }

    // One silhouette pass per drop: geometric offset+spread in local space
    // (GPU), filter only blurs/tints — keeps stroke-ring width and rotation.
    if !builder.suppress_filters {
        let drops: Vec<Shadow> = element.drop_shadows_visible().copied().collect();
        for shadow in &drops {
            let id = push_container_drop_filter(builder, shadow);
            builder.open_group(&format!("filter=\"url(#{id})\""));
            let prev_suppress = builder.suppress_filters;
            let prev_spread = builder.silhouette_spread;
            let prev_offset = builder.silhouette_offset;
            builder.suppress_filters = true;
            builder.silhouette_spread = shadow.spread;
            builder.silhouette_offset = shadow.offset;
            render_frame_body(builder, shared, element, tree, scale)?;
            builder.silhouette_offset = prev_offset;
            builder.silhouette_spread = prev_spread;
            builder.suppress_filters = prev_suppress;
            builder.close_group();
        }
    }

    let effects = content_effect_attrs(builder, element);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    // Content pass uses builder.silhouette_spread (0 after this frame's own
    // drop; still set when nested inside a parent silhouette — matches
    // render_leaf so nested board fills outset with inherited spread).
    render_frame_body(builder, shared, element, tree, scale)?;

    if effects.is_some() {
        builder.close_group();
    }
    if composite.is_some() {
        builder.close_group();
    }
    Ok(())
}

fn render_frame_body(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let spread = builder.silhouette_spread;
    let clipped = element.clip_content;
    if clipped {
        let clip_id = builder.unique("clip");
        builder.push_clip_path(&clip_id, element, tree);
        builder.open_group(&format!("clip-path=\"url(#{clip_id})\""));
    }

    if !element.fills.is_empty() {
        // Fills: GPU `fills::render` outsets the rect for drop-shadow spread.
        let mask = shape_with_selrect_outset(element, spread);
        let matrix = builder.silhouette_draw_matrix(element);
        emit_fills(
            builder,
            shared,
            &mask,
            &mask.fills,
            tree,
            scale,
            Some(matrix),
        )?;
    }

    let children: Vec<_> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale)?;
    }

    if clipped {
        builder.close_group();
    }

    // Strokes: GPU ignores Rect/Frame stroke outset for drop spread. Use
    // emit_strokes (image + solid) under the silhouette/content CTM.
    let visible_strokes: Vec<_> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        let matrix = builder.silhouette_draw_matrix(element);
        emit_strokes(
            builder,
            shared,
            element,
            &visible_strokes,
            scale,
            Some(matrix),
        )?;
    }
    Ok(())
}
