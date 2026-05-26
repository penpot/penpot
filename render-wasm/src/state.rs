use skia_safe::{self as skia, textlayout::FontCollection, Path, Point};
use std::collections::HashMap;

mod shapes_pool;
mod text_editor;
pub use shapes_pool::{ShapesPool, ShapesPoolMutRef, ShapesPoolRef};
pub use text_editor::*;

use crate::error::{Error, Result};
use crate::shapes::{grid_layout::grid_cell_data, Shape};
use crate::uuid::Uuid;
use crate::{get_render_state, tiles};

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State {
    pub current_id: Option<Uuid>,
    pub current_browser: u8,
    pub shapes: ShapesPool,
    pub saved_shapes: Option<ShapesPool>,
    /// True while the first bulk load of shapes is in progress.
    pub loading: bool,
}

impl State {
    pub fn new() -> Self {
        Self {
            current_id: None,
            current_browser: 0,
            shapes: ShapesPool::new(),
            saved_shapes: None,
            loading: false,
        }
    }

    // Creates a new temporary shapes pool.
    // Will panic if a previous temporary pool exists.
    pub fn start_temp_objects(&mut self) -> Result<()> {
        if self.saved_shapes.is_some() {
            return Err(Error::CriticalError(
                "Tried to start a temp objects while the previous have not been restored"
                    .to_string(),
            ));
        }
        self.saved_shapes = Some(self.shapes.clone());
        self.shapes = ShapesPool::new();
        Ok(())
    }

    // Disposes of the temporary shapes pool restoring the normal pool
    // Will panic if a there is no temporary pool.
    pub fn end_temp_objects(&mut self) -> Result<()> {
        self.shapes = self.saved_shapes.clone().ok_or(Error::CriticalError(
            "Tried to end temp objects but not content to be restored is present".to_string(),
        ))?;
        self.saved_shapes = None;
        Ok(())
    }

    pub fn render_from_cache(&mut self) {
        get_render_state().render_from_cache(&self.shapes);
    }

    pub fn render_sync(&mut self, timestamp: i32) -> Result<()> {
        get_render_state().start_render_loop(None, &self.shapes, timestamp, true)
    }

    pub fn render_sync_shape(&mut self, id: &Uuid, timestamp: i32) -> Result<()> {
        get_render_state().start_render_loop(Some(id), &self.shapes, timestamp, true)
    }

    pub fn render_shape_pixels(
        &mut self,
        id: &Uuid,
        scale: f32,
        timestamp: i32,
    ) -> Result<(Vec<u8>, i32, i32)> {
        get_render_state().render_shape_pixels(id, &self.shapes, scale, timestamp)
    }

    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<()> {
        let render_state = get_render_state();
        // If zoom changed (e.g. interrupted zoom render followed by pan), the
        // tile index may be stale for the new viewport position. Rebuild the
        // index so shapes are mapped to the correct tiles. We use
        // rebuild_tile_index (NOT rebuild_tiles_shallow) to preserve the tile
        // texture cache — otherwise cached tiles with shadows/blur would be
        // cleared and re-rendered in fast mode without effects.
        if render_state.zoom_changed() {
            render_state.rebuild_tile_index(&self.shapes);
        }

        render_state.start_render_loop(None, &self.shapes, timestamp, false)
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<()> {
        get_render_state().process_animation_frame(None, &self.shapes, timestamp)
    }

    pub fn clear_focus_mode(&mut self) {
        get_render_state().clear_focus_mode();
    }

    pub fn set_focus_mode(&mut self, shapes: Vec<Uuid>) {
        get_render_state().set_focus_mode(shapes);
    }

    pub fn init_shapes_pool(&mut self, capacity: usize) {
        self.shapes.initialize(capacity);
    }

    pub fn use_shape(&mut self, id: Uuid) {
        if !self.shapes.has(&id) {
            self.shapes.add_shape(id);
        }
        self.current_id = Some(id);
    }

    pub fn has_shape(&mut self, id: Uuid) -> bool {
        self.shapes.has(&id)
    }

    pub fn delete_shape_children(&mut self, parent_id: Uuid, id: Uuid) {
        let render_state = get_render_state();

        // We don't really do a self.shapes.remove so that redo/undo keep working
        let Some(shape) = self.shapes.get(&id) else {
            return;
        };

        // Only remove the children when is being deleted from the owner
        if shape.parent_id.is_none() || shape.parent_id == Some(parent_id) {
            // IMPORTANT:
            // Do NOT use `get_tiles_for_shape` here. That method intersects the shape
            // tiles with the current interest area, which means we'd only invalidate
            // the subset currently near the viewport. When the user later pans/zooms
            // to reveal previously cached tiles, stale pixels could reappear.
            //
            // Instead, remove the shape from *all* tiles where it was indexed, and
            // drop cached tiles for those entries.
            let indexed_tiles: Vec<tiles::Tile> = render_state
                .tiles
                .get_tiles_of(shape.id)
                .map(|t| t.iter().copied().collect())
                .unwrap_or_default();

            for tile in indexed_tiles {
                render_state.remove_cached_tile(tile);
                render_state.tiles.remove_shape_at(tile, shape.id);
            }

            if let Some(shape_to_delete) = self.shapes.get(&id) {
                let to_delete = shape_to_delete.all_children(&self.shapes, true, true);
                for shape_id in to_delete {
                    if let Some(shape_to_delete) = self.shapes.get_mut(&shape_id) {
                        shape_to_delete.set_deleted(true);
                    }
                    if render_state.show_grid == Some(shape_id) {
                        render_state.show_grid = None;
                    }
                }
            }
        }
    }

    pub fn current_shape_mut(&mut self) -> Option<&mut Shape> {
        self.shapes.get_mut(&self.current_id?)
    }

    pub fn current_shape(&self) -> Option<&Shape> {
        self.shapes.get(&self.current_id?)
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        get_render_state().set_background_color(color);
    }

    pub fn set_browser(&mut self, browser: u8) {
        self.current_browser = browser;
    }

    /// Sets the parent for the current shape and updates the parent's extended rectangle
    ///
    /// When a shape is assigned a new parent, the parent's extended rectangle needs to be
    /// invalidated and recalculated to include the new child. This ensures that frames
    /// and groups properly encompass their children.
    pub fn set_parent_for_current_shape(&mut self, id: Uuid) {
        let Some(shape) = self.current_shape_mut() else {
            panic!("Invalid current shape")
        };

        // If the shape already has the same parent, do nothing
        if shape.parent_id == Some(id) {
            return;
        }

        shape.set_parent(id);

        // Note: We don't call parent.add_child() here because we are
        // asuming the parent is updating its children list via set_children() calls.
        // Calling add_child here would create duplicates.

        // Invalidate parent's extrect so it gets recalculated to include the new child
        if let Some(parent) = self.shapes.get_mut(&id) {
            parent.invalidate_extrect();
        }
    }

    pub fn rebuild_tiles_shallow(&mut self) {
        get_render_state().rebuild_tiles_shallow(&self.shapes);
    }

    pub fn rebuild_tiles(&mut self) {
        get_render_state().rebuild_tiles_from(&self.shapes, None);
    }

    pub fn rebuild_tiles_from(&mut self, base_id: Option<&Uuid>) {
        get_render_state().rebuild_tiles_from(&self.shapes, base_id);
    }

    pub fn rebuild_touched_tiles(&mut self) {
        get_render_state().rebuild_touched_tiles(&self.shapes);
    }

    pub fn render_preview(&mut self, timestamp: i32) {
        let _ = get_render_state().render_preview(&self.shapes, timestamp);
    }

    pub fn rebuild_modifier_tiles(&mut self, ids: &[Uuid]) -> Result<()> {
        get_render_state().rebuild_modifier_tiles(&mut self.shapes, ids)
    }

    pub fn font_collection(&self) -> &FontCollection {
        get_render_state().fonts().font_collection()
    }

    pub fn get_grid_coords(&self, pos_x: f32, pos_y: f32) -> Option<(i32, i32)> {
        let shape = self.current_shape()?;
        let bounds = shape.bounds();
        let position = Point::new(pos_x, pos_y);

        let cells = grid_cell_data(shape, &self.shapes, true);

        for cell in cells {
            let points = &[
                cell.anchor,
                cell.anchor + bounds.hv(cell.width),
                cell.anchor + bounds.hv(cell.width) + bounds.vv(cell.height),
                cell.anchor + bounds.vv(cell.height),
            ];

            let polygon = Path::polygon(points, true, None, None);

            if polygon.contains(position) {
                return Some((cell.row as i32 + 1, cell.column as i32 + 1));
            }
        }

        None
    }

    pub fn set_modifiers(&mut self, modifiers: HashMap<Uuid, skia::Matrix>) {
        self.shapes.set_modifiers(modifiers);
    }

    pub fn touch_current(&mut self) {
        let render_state = get_render_state();
        if !self.loading {
            if let Some(current_id) = self.current_id {
                render_state.mark_touched(current_id);
            }
        }
    }

    pub fn touch_shape(&mut self, id: Uuid) {
        let render_state = get_render_state();
        if !self.loading {
            render_state.mark_touched(id);
        }
    }
}
