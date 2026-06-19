use crate::error::Result;
use crate::get_gpu_state;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;
use skia_safe as skia;

use super::{NodeRenderState, RenderState, SurfaceId};

pub fn render_shape_pixels(
    render_state: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    timestamp: i32,
) -> Result<(Vec<u8>, i32, i32)> {
    let target_surface = SurfaceId::Export;

    let saved_focus_mode = render_state.focus_mode.clone();
    let saved_export_context = render_state.export_context;
    let saved_render_area = render_state.render_area;
    let saved_render_area_with_margins = render_state.render_area_with_margins;
    let saved_current_tile = render_state.tile.current_tile;
    let saved_pending_nodes = std::mem::take(&mut render_state.pending_nodes);
    let saved_nested_fills = std::mem::take(&mut render_state.nested_fills);
    let saved_nested_blurs = std::mem::take(&mut render_state.nested_blurs);
    let saved_nested_shadows = std::mem::take(&mut render_state.nested_shadows);
    let saved_ignore_nested_blurs = render_state.ignore_nested_blurs;
    let saved_preview_mode = render_state.preview_mode;

    render_state.focus_mode.clear();

    render_state
        .surfaces
        .canvas(target_surface)
        .clear(skia::Color::TRANSPARENT);

    if tree.len() != 0 {
        let Some(shape) = tree.get(id) else {
            return Ok((Vec::new(), 0, 0));
        };
        let mut extrect = shape.extrect(tree, scale);
        render_state.export_context = Some((extrect, scale));
        let margins = render_state.surfaces.margins;
        extrect.offset((margins.width as f32 / scale, margins.height as f32 / scale));

        render_state.surfaces.resize_export_surface(scale, extrect);
        render_state.render_area = extrect;
        render_state.render_area_with_margins = extrect;
        render_state.surfaces.update_render_context(extrect, scale);

        render_state.pending_nodes.push(NodeRenderState {
            id: *id,
            visited_children: false,
            clip_bounds: None,
            visited_mask: false,
            mask: false,
            flattened: false,
        });
        render_state.render_shape_tree_tile(tree, timestamp, false, true)?;
    }

    render_state.export_context = None;

    render_state.surfaces.flush_and_submit(target_surface);

    let image = render_state.surfaces.snapshot(target_surface);
    let data = image
        .encode(
            Some(&mut get_gpu_state().context),
            skia::EncodedImageFormat::PNG,
            100,
        )
        .expect("PNG encode failed");
    let skia::ISize { width, height } = image.dimensions();

    render_state.focus_mode = saved_focus_mode;
    render_state.export_context = saved_export_context;
    render_state.render_area = saved_render_area;
    render_state.render_area_with_margins = saved_render_area_with_margins;
    render_state.tile.current_tile = saved_current_tile;
    render_state.pending_nodes = saved_pending_nodes;
    render_state.nested_fills = saved_nested_fills;
    render_state.nested_blurs = saved_nested_blurs;
    render_state.nested_shadows = saved_nested_shadows;
    render_state.ignore_nested_blurs = saved_ignore_nested_blurs;
    render_state.preview_mode = saved_preview_mode;

    let workspace_scale = render_state.get_scale();
    if let Some(tile) = render_state.tile.current_tile {
        render_state.update_render_context(tile);
    } else if !render_state.render_area.is_empty() {
        render_state
            .surfaces
            .update_render_context(render_state.render_area, workspace_scale);
    }

    Ok((data.as_bytes().to_vec(), width, height))
}
