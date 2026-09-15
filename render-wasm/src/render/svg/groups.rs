use crate::error::Result;
use crate::shapes::{Shadow, Shape};
use crate::state::ShapesPoolRef;

use super::document::{
    content_effect_attrs, opacity_blend_attrs, push_container_drop_filter, SvgLayerCanvas,
};
use super::render_tree;
use crate::render::RenderResources;

pub(super) fn render_group(
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
            render_group_children(builder, shared, element, tree, scale)?;
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

    // Masked groups are deferred: they need an alpha `<mask>` compositor that
    // will land in a later PR. For now we still emit the full child list
    // (including the mask shape as normal content) so basic group opacity
    // keeps working.
    render_group_children(builder, shared, element, tree, scale)?;

    if effects.is_some() {
        builder.close_group();
    }
    if composite.is_some() {
        builder.close_group();
    }
    Ok(())
}

fn render_group_children(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let children: Vec<_> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale)?;
    }
    Ok(())
}
