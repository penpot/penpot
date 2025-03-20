use skia_safe::{self as skia, Matrix, RRect, Rect};

use std::collections::HashMap;
use uuid::Uuid;

use crate::view::Viewbox;
use crate::{run_script, run_script_int};

mod blend;
mod debug;
mod fills;
mod fonts;
mod gpu_state;
mod images;
mod options;
mod shadows;
mod strokes;
mod surfaces;
mod text;
mod tiles;

use crate::shapes::{Corners, Shape, Type};
use gpu_state::GpuState;
use options::RenderOptions;
use surfaces::{SurfaceId, Surfaces};

pub use blend::BlendMode;
pub use fonts::*;
pub use images::*;

const MAX_BLOCKING_TIME_MS: i32 = 32;
const NODE_BATCH_THRESHOLD: i32 = 10;

fn get_time() -> i32 {
    run_script_int!("performance.now()")
}

pub struct NodeRenderState {
    pub id: Uuid,
    // We use this bool to keep that we've traversed all the children inside this node.
    pub visited_children: bool,
    // This is used to clip the content of frames.
    pub clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    // This is a flag to indicate that we've already drawn the mask of a masked group.
    pub visited_mask: bool,
    // This bool indicates that we're drawing the mask shape.
    pub mask: bool,
}

impl NodeRenderState {
    pub fn get_children_clip_bounds(
        &self,
        element: &Shape,
        modifiers: Option<&Matrix>,
    ) -> Option<(Rect, Option<Corners>, Matrix)> {
        if self.id.is_nil() || !element.clip() {
            return self.clip_bounds;
        }

        let bounds = element.selrect();
        let mut transform = element.transform;
        transform.post_translate(bounds.center());
        transform.pre_translate(-bounds.center());

        if let Some(modifier) = modifiers {
            transform.post_concat(modifier);
        }

        let corners = match &element.shape_type {
            Type::Rect(data) => data.corners,
            Type::Frame(data) => data.corners,
            _ => None,
        };

        Some((bounds, corners, transform))
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub options: RenderOptions,
    pub surfaces: Surfaces,
    pub fonts: FontStore,
    pub viewbox: Viewbox,
    pub images: ImageStore,
    pub background_color: skia::Color,
    // Identifier of the current requestAnimationFrame call, if any.
    pub render_request_id: Option<i32>,
    // Indicates whether the rendering process has pending frames.
    pub render_in_progress: bool,
    // Stack of nodes pending to be rendered.
    pub pending_nodes: Vec<NodeRenderState>,
    pub current_tile: Option<tiles::Tile>,
    pub sampling_options: skia::SamplingOptions,
    pub render_area: Rect,
    pub tiles: tiles::TileHashMap,
    pub pending_tiles: Vec<tiles::Tile>,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);

        let fonts = FontStore::new();
        let surfaces = Surfaces::new(
            &mut gpu_state,
            (width, height),
            sampling_options,
            tiles::get_tile_dimensions(),
        );

        // This is used multiple times everywhere so instead of creating new instances every
        // time we reuse this one.

        let tiles = tiles::TileHashMap::new();

        RenderState {
            gpu_state,
            options: RenderOptions::default(),
            surfaces,
            fonts,
            viewbox: Viewbox::new(width as f32, height as f32),
            images: ImageStore::new(),
            background_color: skia::Color::TRANSPARENT,
            render_request_id: None,
            render_in_progress: false,
            pending_nodes: vec![],
            current_tile: None,
            sampling_options,
            render_area: Rect::new_empty(),
            tiles,
            pending_tiles: vec![],
        }
    }

    pub fn fonts(&self) -> &FontStore {
        &self.fonts
    }

    pub fn fonts_mut(&mut self) -> &mut FontStore {
        &mut self.fonts
    }

    pub fn add_image(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        self.images.add(id, image_data)
    }

    pub fn has_image(&mut self, id: &Uuid) -> bool {
        self.images.contains(id)
    }

    pub fn set_debug_flags(&mut self, debug: u32) {
        self.options.debug_flags = debug;
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        if Some(dpr) != self.options.dpr {
            self.options.dpr = Some(dpr);
            self.resize(
                self.viewbox.width.floor() as i32,
                self.viewbox.height.floor() as i32,
            );
        }
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.background_color = color;
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;

        self.surfaces
            .resize(&mut self.gpu_state, dpr_width, dpr_height);
        self.viewbox.set_wh(width as f32, height as f32);
    }

    pub fn flush(&mut self) {
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Target);
    }

    pub fn reset_canvas(&mut self) {
        self.surfaces.reset(self.background_color);
    }

    pub fn apply_render_to_final_canvas(&mut self, rect: skia::Rect) {
        let x = self.current_tile.unwrap().0;
        let y = self.current_tile.unwrap().1;

        // This caches the current surface into the corresponding tile.
        self.surfaces
            .cache_tile_surface((x, y), SurfaceId::Current, self.background_color);

        self.surfaces
            .draw_cached_tile_surface(self.current_tile.unwrap(), rect);

        if self.options.is_debug_visible() {
            debug::render_workspace_current_tile(
                self,
                "".to_string(),
                self.current_tile.unwrap(),
                rect,
            );
        }
    }

    pub fn apply_drawing_to_render_canvas(&mut self, shape: Option<&Shape>) {
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Fills);

        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::DropShadows);

        self.surfaces.draw_into(
            SurfaceId::DropShadows,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        self.surfaces.draw_into(
            SurfaceId::Fills,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        let mut render_overlay_below_strokes = false;
        if let Some(shape) = shape {
            render_overlay_below_strokes = shape.fills().len() > 0;
        }

        if render_overlay_below_strokes {
            self.surfaces
                .flush_and_submit(&mut self.gpu_state, SurfaceId::Overlay);
            self.surfaces.draw_into(
                SurfaceId::Overlay,
                SurfaceId::Current,
                Some(&skia::Paint::default()),
            );
        }

        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Strokes);
        self.surfaces.draw_into(
            SurfaceId::Strokes,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        if !render_overlay_below_strokes {
            self.surfaces
                .flush_and_submit(&mut self.gpu_state, SurfaceId::Overlay);
            self.surfaces.draw_into(
                SurfaceId::Overlay,
                SurfaceId::Current,
                Some(&skia::Paint::default()),
            );
        }

        self.surfaces
            .draw_into(SurfaceId::Overlay, SurfaceId::Current, None);
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Current);

        self.surfaces.apply_mut(
            &[
                SurfaceId::Shadow,
                SurfaceId::DropShadows,
                SurfaceId::Overlay,
                SurfaceId::Fills,
                SurfaceId::Strokes,
            ],
            |s| {
                s.canvas().clear(skia::Color::TRANSPARENT);
            },
        );
    }

    pub fn render_shape(
        &mut self,
        shape: &mut Shape,
        modifiers: Option<&Matrix>,
        clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    ) {
        let surface_ids = &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows];
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().save();
        });

        // set clipping
        if let Some((bounds, corners, transform)) = clip_bounds {
            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas().concat(&transform);
            });

            if let Some(corners) = corners {
                let rrect = RRect::new_rect_radii(bounds, &corners);
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().clip_rrect(rrect, skia::ClipOp::Intersect, true);
                });
            } else {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().clip_rect(bounds, skia::ClipOp::Intersect, true);
                });
            }

            // This renders a red line around clipped
            // shapes (frames).
            if self.options.is_debug_visible() {
                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_color(skia::Color::from_argb(255, 255, 0, 0));
                paint.set_stroke_width(4.);
                self.surfaces
                    .canvas(SurfaceId::Fills)
                    .draw_rect(bounds, &paint);
            }

            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas()
                    .concat(&transform.invert().unwrap_or(Matrix::default()));
            });
        }

        // Clone so we don't change the value in the global state
        let mut shape = shape.clone();

        if let Some(modifiers) = modifiers {
            shape.apply_transform(&modifiers);
        }

        let center = shape.center();
        let mut matrix = shape.transform;
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        match &shape.shape_type {
            Type::SVGRaw(sr) => {
                if let Some(modifiers) = modifiers {
                    self.surfaces.canvas(SurfaceId::Fills).concat(&modifiers);
                }
                self.surfaces.canvas(SurfaceId::Fills).concat(&matrix);
                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.canvas(SurfaceId::Fills))
                } else {
                    let font_manager = skia::FontMgr::from(self.fonts().font_provider().clone());
                    let dom_result = skia::svg::Dom::from_str(sr.content.to_string(), font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.canvas(SurfaceId::Fills));
                            shape.set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }
            Type::Text(text_content) => {
                text::render(self, text_content);
            }
            _ => {
                self.surfaces.apply_mut(
                    &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
                    |s| {
                        s.canvas().concat(&matrix);
                    },
                );

                for fill in shape.fills().rev() {
                    fills::render(self, &shape, fill);
                }

                for stroke in shape.strokes().rev() {
                    strokes::render(self, &shape, stroke);
                }

                for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
                    shadows::render_inner_shadow(
                        self,
                        shadow,
                        self.viewbox.zoom * self.options.dpr(),
                        shape.fills().len() > 0,
                    );
                }

                shadows::render_drop_shadows(self, &shape);
            }
        };

        self.apply_drawing_to_render_canvas(Some(&shape));
        self.surfaces.apply_mut(
            &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
            |s| {
                s.canvas().restore();
            },
        );
    }

    pub fn update_render_context(&mut self, tile: tiles::Tile) {
        self.current_tile = Some(tile);
        self.render_area = tiles::get_tile_rect(self.viewbox, tile);
        self.surfaces
            .update_render_context(self.render_area, self.viewbox);
    }

    pub fn start_render_loop(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if self.render_in_progress {
            if let Some(frame_id) = self.render_request_id {
                self.cancel_animation_frame(frame_id);
            }
        }
        self.reset_canvas();
        self.surfaces.apply_mut(
            &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
            |s| {
                s.canvas().scale((
                    self.viewbox.zoom * self.options.dpr(),
                    self.viewbox.zoom * self.options.dpr(),
                ));
            },
        );

        let (sx, sy, ex, ey) = tiles::get_tiles_for_viewbox(self.viewbox);
        debug::render_debug_tiles_for_viewbox(self, sx, sy, ex, ey);
        /*
        // TODO: Instead of rendering only the visible area
        // we could apply an offset to the viewbox to render
        // more tiles.
        sx - interest_delta
        sy - interest_delta
        ex + interest_delta
        ey + interest_delta
        */
        self.pending_tiles = vec![];
        for y in sy..=ey {
            for x in sx..=ex {
                let tile = (x, y);
                self.pending_tiles.push(tile);
            }
        }
        self.current_tile = None;
        self.render_in_progress = true;
        self.apply_drawing_to_render_canvas(None);
        self.process_animation_frame(tree, modifiers, timestamp)?;
        Ok(())
    }

    pub fn request_animation_frame(&mut self) -> i32 {
        run_script_int!("requestAnimationFrame(_process_animation_frame)")
    }

    pub fn cancel_animation_frame(&mut self, frame_id: i32) {
        run_script!(format!("cancelAnimationFrame({})", frame_id))
    }

    pub fn process_animation_frame(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if self.render_in_progress {
            self.render_shape_tree(tree, modifiers, timestamp)?;
            self.flush();

            if self.render_in_progress {
                if let Some(frame_id) = self.render_request_id {
                    self.cancel_animation_frame(frame_id);
                }
                self.render_request_id = Some(self.request_animation_frame());
            }
        }
        Ok(())
    }

    pub fn render_shape_enter(&mut self, element: &mut Shape, mask: bool) {
        // Masked groups needs two rendering passes, the first one rendering
        // the content and the second one rendering the mask so we need to do
        // an extra save_layer to keep all the masked group separate from other
        // already drawn elements.
        match element.shape_type {
            Type::Group(group) => {
                if group.masked {
                    let paint = skia::Paint::default();
                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                    self.surfaces
                        .canvas(SurfaceId::Current)
                        .save_layer(&layer_rec);
                }
            }
            _ => {}
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        // When we're rendering the mask shape we need to set a special blend mode
        // called 'destination-in' that keeps the drawn content within the mask.
        // @see https://skia.org/docs/user/api/skblendmode_overview/
        if mask {
            let mut mask_paint = skia::Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            self.surfaces
                .canvas(SurfaceId::Current)
                .save_layer(&mask_rec);
        }

        if let Some(image_filter) = element.image_filter(self.viewbox.zoom * self.options.dpr()) {
            paint.set_image_filter(image_filter);
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        self.surfaces
            .canvas(SurfaceId::Current)
            .save_layer(&layer_rec);
    }

    pub fn render_shape_exit(&mut self, element: &mut Shape, visited_mask: bool) {
        if visited_mask {
            // Because masked groups needs two rendering passes (first drawing
            // the content and then drawing the mask), we need to do an
            // extra restore.
            match element.shape_type {
                Type::Group(group) => {
                    if group.masked {
                        self.surfaces.canvas(SurfaceId::Current).restore();
                    }
                }
                _ => {}
            }
        }
        self.surfaces.canvas(SurfaceId::Current).restore();
    }

    pub fn get_current_tile_bounds(&mut self) -> Rect {
        let (tile_x, tile_y) = self.current_tile.unwrap();
        let zoom = self.viewbox.zoom * self.options.dpr();
        let offset_x = self.viewbox.area.left * zoom;
        let offset_y = self.viewbox.area.top * zoom;
        Rect::from_xywh(
            (tile_x as f32 * tiles::TILE_SIZE) - offset_x,
            (tile_y as f32 * tiles::TILE_SIZE) - offset_y,
            tiles::TILE_SIZE,
            tiles::TILE_SIZE,
        )
    }

    pub fn render_shape_tree(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if !self.render_in_progress {
            return Ok(());
        }

        let mut should_stop = false;
        while !should_stop {
            if let Some(current_tile) = self.current_tile {
                if self.surfaces.has_cached_tile_surface(current_tile) {
                    let tile_rect = self.get_current_tile_bounds();
                    self.surfaces
                        .draw_cached_tile_surface(current_tile, tile_rect);

                    if self.options.is_debug_visible() {
                        debug::render_workspace_current_tile(
                            self,
                            "Cached".to_string(),
                            current_tile,
                            tile_rect,
                        );
                    }
                } else {
                    let mut i = 0;
                    let mut is_empty = true;
                    while let Some(node_render_state) = self.pending_nodes.pop() {
                        let NodeRenderState {
                            id: node_id,
                            visited_children,
                            clip_bounds,
                            visited_mask,
                            mask,
                        } = node_render_state;
                        is_empty = false;
                        if let Some(element) = tree.get_mut(&node_id) {
                            let mut element = element.clone();

                            if visited_children {
                                if !visited_mask {
                                    match element.shape_type {
                                        Type::Group(group) => {
                                            // When we're dealing with masked groups we need to
                                            // do a separate extra step to draw the mask (the last
                                            // element of a masked group) and blend (using
                                            // the blend mode 'destination-in') the content
                                            // of the group and the mask.
                                            if group.masked {
                                                self.pending_nodes.push(NodeRenderState {
                                                    id: node_id,
                                                    visited_children: true,
                                                    clip_bounds: None,
                                                    visited_mask: true,
                                                    mask: false,
                                                });
                                                if let Some(&mask_id) = element.mask_id() {
                                                    self.pending_nodes.push(NodeRenderState {
                                                        id: mask_id,
                                                        visited_children: false,
                                                        clip_bounds: None,
                                                        visited_mask: false,
                                                        mask: true,
                                                    });
                                                }
                                            }
                                        }
                                        _ => {}
                                    }
                                }
                                self.render_shape_exit(&mut element, visited_mask);
                                continue;
                            }

                            if !node_render_state.id.is_nil() {
                                // If we didn't visited_children this shape, then we need to do
                                let mut transformed_element = element.clone();
                                if let Some(modifier) = modifiers.get(&node_id) {
                                    transformed_element.apply_transform(modifier);
                                }
                                if !transformed_element.extrect().intersects(self.render_area)
                                    || transformed_element.hidden()
                                {
                                    debug::render_debug_shape(self, &transformed_element, false);
                                    continue;
                                } else {
                                    debug::render_debug_shape(self, &transformed_element, true);
                                }
                            }

                            self.render_shape_enter(&mut element, mask);
                            if !node_render_state.id.is_nil() {
                                let element_id = element.id;
                                self.render_shape(
                                    &mut element,
                                    modifiers.get(&element_id),
                                    clip_bounds,
                                );
                            } else {
                                self.apply_drawing_to_render_canvas(Some(&element));
                            }

                            // Set the node as visited_children before processing children
                            self.pending_nodes.push(NodeRenderState {
                                id: node_id,
                                visited_children: true,
                                clip_bounds: None,
                                visited_mask: false,
                                mask: mask,
                            });

                            if element.is_recursive() {
                                let element_id = element.id;
                                let children_clip_bounds = node_render_state
                                    .get_children_clip_bounds(
                                        &mut element,
                                        modifiers.get(&element_id),
                                    );
                                for child_id in element.children_ids().iter().rev() {
                                    self.pending_nodes.push(NodeRenderState {
                                        id: *child_id,
                                        visited_children: false,
                                        clip_bounds: children_clip_bounds,
                                        visited_mask: false,
                                        mask: false,
                                    });
                                }
                            }
                        }
                        // We try to avoid doing too many calls to get_time
                        if i % NODE_BATCH_THRESHOLD == 0
                            && get_time() - timestamp > MAX_BLOCKING_TIME_MS
                        {
                            return Ok(());
                        }
                        i += 1;
                    }
                    let tile_rect = self.get_current_tile_bounds();
                    if !is_empty {
                        self.apply_render_to_final_canvas(tile_rect);
                    } else {
                        self.surfaces.apply_mut(&[SurfaceId::Target], |s| {
                            let mut paint = skia::Paint::default();
                            paint.set_color(self.background_color);
                            s.canvas().draw_rect(tile_rect, &paint);
                        });
                    }
                }
            }

            self.surfaces
                .canvas(SurfaceId::Current)
                .clear(self.background_color);

            // If we finish processing every node rendering is complete
            // let's check if there are more pending nodes
            if let Some(next_tile) = self.pending_tiles.pop() {
                self.update_render_context(next_tile);
                if !self.surfaces.has_cached_tile_surface(next_tile) {
                    if let Some(ids) = self.tiles.get_shapes_at(next_tile) {
                        for id in ids {
                            let element = tree.get_mut(&id).ok_or(
                                "Error: Element with root_id {id} not found in the tree."
                                    .to_string(),
                            )?;
                            if element.parent_id == Some(Uuid::nil()) {
                                println!("{:?} {:?}", next_tile, id);
                                self.pending_nodes.push(NodeRenderState {
                                    id: *id,
                                    visited_children: false,
                                    clip_bounds: None,
                                    visited_mask: false,
                                    mask: false,
                                });
                            }
                        }
                    }
                }
            } else {
                should_stop = true;
            }
        }
        self.render_in_progress = false;
        if self.options.is_debug_visible() {
            debug::render(self);
        }

        debug::render_wasm_label(self);
        self.flush();

        Ok(())
    }

    pub fn update_tile_for(&mut self, shape: &Shape) {
        let tile_size = tiles::get_tile_size(self.viewbox);
        let (rsx, rsy, rex, rey) = tiles::get_tiles_for_rect(shape.extrect(), tile_size);

        // Update tiles where the shape was
        if let Some(tiles) = self.tiles.get_tiles_of(shape.id) {
            for tile in tiles.iter() {
                self.surfaces.remove_cached_tile_surface(*tile);
            }
        }

        // Update tiles matching the actual selrect
        for x in rsx..=rex {
            for y in rsy..=rey {
                let tile = (x, y);
                self.tiles.add_shape_at(tile, shape.id);
                self.surfaces.remove_cached_tile_surface(tile);
            }
        }
    }

    pub fn rebuild_tiles(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
    ) {
        self.tiles.invalidate();
        self.surfaces.remove_cached_tiles();
        let mut nodes = vec![Uuid::nil()];
        while let Some(shape_id) = nodes.pop() {
            if let Some(shape) = tree.get(&shape_id) {
                let mut shape = shape.clone();
                if shape_id != Uuid::nil() {
                    if let Some(modifier) = modifiers.get(&shape_id) {
                        shape.apply_transform(modifier);
                    }
                    self.update_tile_for(&shape);
                }
                for child_id in shape.children_ids().iter() {
                    nodes.push(*child_id);
                }
            }
        }
    }
}
