use crate::error::Result;
use crate::shapes::Shape;
use crate::state::ShapesPoolRef;

use super::document::{effect_attrs, SvgLayerCanvas};
use super::render_tree;
use crate::render::RenderResources;

pub(super) fn render_group(
    builder: &mut SvgLayerCanvas,
    shared: &mut RenderResources,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<()> {
    let effects = effect_attrs(element);
    if let Some(attrs) = &effects {
        builder.open_group(attrs);
    }

    // Masked groups are deferred: they need an alpha `<mask>` compositor that
    // will land in a later PR. For now we still emit the full child list
    // (including the mask shape as normal content) so basic group opacity
    // keeps working.

    let children: Vec<_> = element.children_ids_iter_forward(false).copied().collect();
    for child_id in &children {
        render_tree(builder, shared, child_id, tree, scale)?;
    }

    if effects.is_some() {
        builder.close_group();
    }
    Ok(())
}
