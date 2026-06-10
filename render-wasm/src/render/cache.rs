use crate::error::{Error, Result};
use crate::state::ShapesPoolRef;
use crate::tiles;

pub fn apply_render_to_final_canvas(render_state: &mut crate::render::RenderState) -> Result<()> {
    // During interactive transforms we render tiles directly into Target; updating the cache
    // (snapshot -> atlas blit -> tiles.add) can force GPU stalls. Defer cache rebuild until
    // the interaction ends.
    if render_state.options.is_interactive_transform() {
        let tile_rect = render_state.get_current_aligned_tile_bounds()?;
        render_state.surfaces.draw_current_tile_into_backbuffer(
            &tile_rect,
            render_state.background_color,
            surfaces::DrawOnCache::No,
        );
        return Ok(());
    }

    // Viewer masked passes render a partial scene. Reusing the tile texture cache would
    // SrcOver-blend onto textures from the previous pass and leak pixels into the blob.
    if render_state.viewer_masked_pass() {
        // Use viewbox-aligned bounds (not grid-snapped) to match interactive-transform
        // compositing and avoid a visible offset vs the DOM canvas.
        let tile_rect = render_state.get_current_tile_bounds()?;
        render_state.surfaces.draw_current_tile_into_backbuffer(
            &tile_rect,
            render_state.background_color,
            surfaces::DrawOnCache::No,
        );
        return Ok(());
    }

    let fast_mode = render_state.options.is_fast_mode();
    // Decide *now* (at the first real cache blit) whether we need to clear Cache.
    // This avoids clearing Cache on renders that don't actually paint tiles (e.g. hover/UI),
    // while still preventing stale pixels from surviving across full-quality renders.
    if !fast_mode && !render_state.cache_cleared_this_render {
        render_state.surfaces.clear_cache(render_state.background_color);
        render_state.cache_cleared_this_render = true;
    }
    // In fast mode the viewport is moving (pan/zoom) so Cache surface
    // positions would be wrong — only save to the tile HashMap.
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

    Ok(())
}

pub fn render_from_cache(render_state: &mut crate::render::RenderState, shapes: ShapesPoolRef) {
    let _start = performance::begin_timed_log!("render_from_cache");
        performance::begin_measure!("render_from_cache");
        let bg_color = render_state.background_color;

        // During fast mode (pan/zoom), if a previous full-quality render still has pending tiles,
        // always prefer the persistent atlas. The atlas is incrementally updated as tiles finish,
        // and drawing from it avoids mixing a partially-updated Cache surface with missing tiles.
        if render_state.options.is_fast_mode() && !render_state.surfaces.atlas.is_empty() {
            render_state.surfaces
                .draw_atlas_to_backbuffer(render_state.viewbox, bg_color);

            render_state.present_frame(shapes);
            performance::end_measure!("render_from_cache");
            performance::end_timed_log!("render_from_cache", _start);
            return;
        }

        // Check if we have a valid cached viewbox (non-zero dimensions indicate valid cache)
        if render_state.cached_viewbox.area.width() > 0.0 {
            // Scale and translate the target according to the cached data
            let navigate_zoom = render_state.viewbox.zoom / render_state.cached_viewbox.zoom;

            let interest = render_state.options.dpr_viewport_interest_area_threshold;
            let TileRect(start_tile_x, start_tile_y, _, _) =
                tiles::get_tiles_for_viewbox_with_interest(&render_state.cached_viewbox, interest);
            let offset_x = render_state.viewbox.area.left * render_state.cached_viewbox.zoom * render_state.options.dpr;
            let offset_y = render_state.viewbox.area.top * render_state.cached_viewbox.zoom * render_state.options.dpr;
            let translate_x = (start_tile_x as f32 * tiles::TILE_SIZE) - offset_x;
            let translate_y = (start_tile_y as f32 * tiles::TILE_SIZE) - offset_y;

            // For zoom-out, prefer cache only if it fully covers the viewport.
            // Otherwise, atlas will provide a more correct full-viewport preview.
            let zooming_out = render_state.viewbox.zoom < render_state.cached_viewbox.zoom;
            if zooming_out {
                let cache_dim = render_state.surfaces.cache_dimensions();
                let cache_w = cache_dim.width as f32;
                let cache_h = cache_dim.height as f32;

                // Viewport in target pixels.
                let vw = render_state.viewbox.dpr_width().max(1.0);
                let vh = render_state.viewbox.dpr_height().max(1.0);

                // Inverse-map viewport corners into cache coordinates.
                // target = (cache * navigate_zoom) translated by (translate_x, translate_y) (in cache coords).
                // => cache = (target / navigate_zoom) - translate
                let inv = if navigate_zoom.abs() > f32::EPSILON {
                    1.0 / navigate_zoom
                } else {
                    0.0
                };

                // let cx0 = (0.0 * inv) - translate_x;
                // let cy0 = (0.0 * inv) - translate_y;
                // NOTA: 0.0 * inv => siempre 0
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
                if !cache_covers {
                    // Early return only if atlas exists; otherwise keep cache path.
                    if !render_state.surfaces.atlas.is_empty() {
                        render_state.surfaces
                            .draw_atlas_to_backbuffer(render_state.viewbox, bg_color);

                        render_state.present_frame(shapes);
                        performance::end_measure!("render_from_cache");
                        performance::end_timed_log!("render_from_cache", _start);
                        return;
                    }
                }
            }

            // Draw directly from cache surface, avoiding snapshot overhead
            render_state.surfaces.draw_cache_to_backbuffer();

            render_state.present_frame(shapes);
        }

        performance::end_measure!("render_from_cache");
        performance::end_timed_log!("render_from_cache", _start);
}
