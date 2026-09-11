use crate::error::Result;
use crate::shapes::{Shadow, Shape, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

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
            // Drop silhouettes stay unmasked (matches vector container drops).
            render_group_content_children(builder, shared, element, tree, scale)?;
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

    render_group_content(builder, shared, element, tree, scale)?;

    if effects.is_some() {
        builder.close_group();
    }
    if composite.is_some() {
        builder.close_group();
    }
    Ok(())
}

fn render_group_content(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let masked = matches!(element.shape_type, Type::Group(g) if g.masked);
    if !masked {
        return render_group_content_children(builder, shared, element, tree, scale);
    }

    let Some(mask_id) = element.mask_id().copied() else {
        return render_group_content_children(builder, shared, element, tree, scale);
    };

    // Paint the mask subtree into an alpha <mask> (GPU DstIn uses rendered
    // alpha — geometry, fills, soft opacity — not a bare clip outline).
    let mask_elem_id = push_alpha_mask(builder, shared, &mask_id, tree, scale)?;
    builder.open_group(&format!("mask=\"url(#{mask_elem_id})\""));
    render_group_content_children(builder, shared, element, tree, scale)?;
    builder.close_group();
    Ok(())
}

fn render_group_content_children(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    // Masked groups: skip the first child (the mask); content only.
    let children: Vec<_> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale)?;
    }
    Ok(())
}

/// Renders `mask_id`'s subtree into a `<mask mask-type="alpha">` def.
fn push_alpha_mask(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    mask_id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<String> {
    let id = builder.unique("mask");
    let body = builder.capture_body(|b| render_tree(b, shared, mask_id, tree, scale))?;
    builder.defs.push_str(&format!(
        r#"<mask id="{id}" maskUnits="userSpaceOnUse" mask-type="alpha">{body}</mask>"#
    ));
    Ok(id)
}
