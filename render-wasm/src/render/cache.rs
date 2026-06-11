use crate::error::{Error, Result};
use crate::state::ShapesPoolRef;
use crate::tiles;

pub fn apply_render_to_final_canvas(render_state: &mut crate::render::RenderState) -> Result<()> {
    if render_state.options.is_interactive_transform() {
        let tile_rect = render_state.get_current_aligned_tile_bounds()?;
        render_state
            .surfaces
            .draw_current_tile_into_backbuffer(
                &tile_rect,
                render_state.background_color,
                super::surfaces::DrawOnCache::No,
            );
        return Ok(());
    }

    if render_state.viewer_masked_pass() {
        let tile_rect = render_state.get_current_tile_bounds()?;
        render_state
            .surfaces
            .draw_current_tile_into_backbuffer(
                &tile_rect,
                render_state.background_color,
                super::surfaces::DrawOnCache::No,
            );
        return Ok(());
    }

    let fast_mode = render_state.options.is_fast_mode();
    if !fast_mode && !render_state.cache_cleared_this_render {
        render_state.surfaces.clear_cache(render_state.background_color);
        render_state.cache_cleared_this_render = true;
    }
    let tile_rect = render_state.get_current_aligned_tile_bounds()?;

    let current_tile = *render_state
        .current_tile
        .as_ref()
        .ok_or(Error::CriticalError("Current tile not found".to_string()))?;

    render_state.surfaces.draw_current_tile_into_tile_atlas(
        &render_state.tile_viewbox,
        &current_tile,
        &tile_rect,
        fast_mode,
        render_state.render_area,
    );

    let rect = render_state.get_current_tile_bounds()?;
    render_state
        .surfaces
        .draw_cached_tile_into_backbuffer(current_tile, &rect);

    Ok(())
}

pub fn render_from_cache(
    render_state: &mut crate::render::RenderState,
    shapes: ShapesPoolRef,
) {
    let _start = crate::performance::begin_timed_log!("render_from_cache");
    crate::performance::begin_measure!("render_from_cache");
    let bg_color = render_state.background_color;

    if render_state.options.is_fast_mode() && !render_state.surfaces.atlas.is_empty() {
        render_state
            .surfaces
            .draw_atlas_to_backbuffer(render_state.viewbox, bg_color);

        render_state.present_frame(shapes);
        crate::performance::end_measure!("render_from_cache");
        crate::performance::end_timed_log!("render_from_cache", _start);
        return;
    }

    if render_state.cached_viewbox.area.width() > 0.0 {
        let navigate_zoom = render_state.viewbox.zoom / render_state.cached_viewbox.zoom;

        let interest = render_state.options.dpr_viewport_interest_area_threshold;
        let tiles::TileRect(start_tile_x, start_tile_y, _, _) =
            tiles::get_tiles_for_viewbox_with_interest(&render_state.cached_viewbox, interest);
        let offset_x =
            render_state.viewbox.area.left * render_state.cached_viewbox.zoom * render_state.options.dpr;
        let offset_y =
            render_state.viewbox.area.top * render_state.cached_viewbox.zoom * render_state.options.dpr;
        let translate_x = (start_tile_x as f32 * tiles::TILE_SIZE) - offset_x;
        let translate_y = (start_tile_y as f32 * tiles::TILE_SIZE) - offset_y;

        let zooming_out = render_state.viewbox.zoom < render_state.cached_viewbox.zoom;
        if zooming_out {
            let cache_dim = render_state.surfaces.cache_dimensions();
            let cache_w = cache_dim.width as f32;
            let cache_h = cache_dim.height as f32;

            let vw = render_state.viewbox.dpr_width().max(1.0);
            let vh = render_state.viewbox.dpr_height().max(1.0);

            let inv = if navigate_zoom.abs() > f32::EPSILON {
                1.0 / navigate_zoom
            } else {
                0.0
            };

            let cx0 = -translate_x;
            let cy0 = -translate_y;
            let cx1 = (vw * inv) - translate_x;
            let cy1 = (vh * inv) - translate_y;

            let min_x = cx0.min(cx1);
            let min_y = cy0.min(cy1);
            let max_x = cx0.max(cx1);
            let max_y = cy0.max(cy1);

            let cache_covers =
                min_x >= 0.0 && min_y >= 0.0 && max_x <= cache_w && max_y <= cache_h;
            if !cache_covers && !render_state.surfaces.atlas.is_empty() {
                render_state
                    .surfaces
                    .draw_atlas_to_backbuffer(render_state.viewbox, bg_color);

                render_state.present_frame(shapes);
                crate::performance::end_measure!("render_from_cache");
                crate::performance::end_timed_log!("render_from_cache", _start);
                return;
            }
        }

        render_state.surfaces.draw_cache_to_backbuffer();

        if !render_state.zoom_changed() {
            let visible_rect = tiles::get_tiles_for_viewbox(&render_state.viewbox);
            let offset = render_state.viewbox.get_offset();
            for tx in visible_rect.x1()..=visible_rect.x2() {
                for ty in visible_rect.y1()..=visible_rect.y2() {
                    let tile = tiles::Tile::from(tx, ty);
                    if render_state.surfaces.has_cached_tile_surface(tile) {
                        let rect = tile.get_rect_with_offset(&offset);
                        render_state
                            .surfaces
                            .draw_cached_tile_into_backbuffer(tile, &rect);
                    }
                }
            }
        }

        render_state.present_frame(shapes);
    }

    crate::performance::end_measure!("render_from_cache");
    crate::performance::end_timed_log!("render_from_cache", _start);
}
