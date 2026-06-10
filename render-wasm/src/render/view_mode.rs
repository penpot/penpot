use std::collections::HashSet;

use crate::render::RenderState;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

pub fn viewer_masked_pass(include_filter: &Option<HashSet<Uuid>>) -> bool {
    include_filter.is_some()
}

pub fn reset_viewer_masked_surfaces(render_state: &mut RenderState) {
    render_state
        .surfaces
        .clear_backbuffer(render_state.background_color);
    render_state.surfaces.clear_tile_atlas();
}

/// Precompute the set of all ancestor ids that are visible for the viewer
/// masked pass, avoiding recursive checks on the hot path.
pub fn precompute_viewer_visible_set(render_state: &mut RenderState, tree: ShapesPoolRef) {
    render_state.viewer_visible_set = None;
    let Some(ref include) = render_state.include_filter else {
        return;
    };
    let mut visible = include.clone();
    for id in include.iter() {
        let mut current_id = id;
        while let Some(raw) = tree.get_raw(current_id) {
            match raw.parent_id {
                Some(ref parent_id) => {
                    visible.insert(*parent_id);
                    current_id = parent_id;
                }
                None => break,
            }
        }
    }
    render_state.viewer_visible_set = Some(visible);
}

/// True when the shape or any descendant is whitelisted.
pub fn shape_visible_in_include_filter(
    viewer_visible_set: &Option<HashSet<Uuid>>,
    shape_id: &Uuid,
) -> bool {
    match viewer_visible_set {
        Some(visible) => visible.contains(shape_id),
        None => true,
    }
}

/// When an include whitelist is active, only those ids are painted.
pub fn shape_should_paint_for_viewer_layer(
    include_filter: &Option<HashSet<Uuid>>,
    shape_id: &Uuid,
) -> bool {
    match include_filter {
        Some(include) => include.contains(shape_id),
        None => true,
    }
}

/// Viewer layer mask: traverse whitelisted subtrees; paint only listed ids.
pub fn shape_visible_for_viewer_layer(
    viewer_render_root: &Option<Uuid>,
    viewer_visible_set: &Option<HashSet<Uuid>>,
    shape_id: &Uuid,
) -> bool {
    if viewer_render_root.as_ref() == Some(shape_id) {
        return true;
    }
    shape_visible_in_include_filter(viewer_visible_set, shape_id)
}
