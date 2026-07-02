use crate::error::Result;
use crate::shapes::Shape;
use crate::state::ShapesPoolRef;

use super::document::{effect_attrs, SvgLayerCanvas};
use super::render_tree;
use super::shadows::render_container_drop_shadow;
use crate::render::vector::{children_paint_order, ExportState};

pub(super) fn render_group(
    builder: &mut SvgLayerCanvas,
    shared: &mut ExportState,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
    render_shadows: bool,
) -> Result<()> {
    let effects = effect_attrs(builder, element, scale);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    // Group drop shadow: the silhouette is the group's subtree (rendered
    // without its descendants' own shadows), drawn behind the content.
    if render_shadows {
        render_container_drop_shadow(builder, shared, element, tree, scale)?;
    }

    // A Penpot mask is an *alpha* mask: content is clipped to the mask shape's
    // rendered alpha (geometry AND fill alpha), not merely its outline. Mirror
    // the GPU/PDF `DstIn` compose with an SVG `<mask mask-type="alpha">` that
    // holds the fully-rendered mask subtree, so image/gradient/soft masks work
    // (a geometric `<clipPath>` can only capture the outline).
    let mask_id = masked
        .then(|| element.mask_id())
        .flatten()
        .filter(|mid| tree.get(mid).is_some())
        .copied();
    if let Some(mid) = mask_id {
        let mask_ref = builder.push_alpha_mask(shared, &mid, tree, scale)?;
        builder.open_group(&format!("mask=\"url(#{mask_ref})\""));
    }

    for child_id in &children_paint_order(tree, element) {
        render_tree(builder, shared, child_id, tree, scale, render_shadows)?;
    }

    if mask_id.is_some() {
        builder.close_group();
    }
    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}
