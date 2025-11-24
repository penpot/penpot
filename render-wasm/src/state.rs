use skia_safe::{self as skia, textlayout::FontCollection, Path, Point};
use std::collections::HashMap;

mod shapes_pool;
mod text_editor;
pub use shapes_pool::{ShapesPool, ShapesPoolMutRef, ShapesPoolRef};
pub use text_editor::*;

use crate::render::RenderState;
use crate::shapes::Shape;
use crate::tiles;
use crate::uuid::Uuid;

use crate::shapes::modifiers::grid_layout::grid_cell_data;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub render_state: RenderState,
    pub text_editor_state: TextEditorState,
    pub current_id: Option<Uuid>,
    pub current_browser: u8,
    pub shapes: ShapesPool<'a>,
}

impl<'a> State<'a> {
    pub fn new(width: i32, height: i32) -> Self {
        State {
            render_state: RenderState::new(width, height),
            text_editor_state: TextEditorState::new(),
            current_id: None,
            current_browser: 0,
            shapes: ShapesPool::new(),
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        self.render_state.resize(width, height);
    }

    pub fn render_state_mut(&mut self) -> &mut RenderState {
        &mut self.render_state
    }

    pub fn render_state(&self) -> &RenderState {
        &self.render_state
    }

    #[allow(dead_code)]
    pub fn text_editor_state_mut(&mut self) -> &mut TextEditorState {
        &mut self.text_editor_state
    }

    #[allow(dead_code)]
    pub fn text_editor_state(&self) -> &TextEditorState {
        &self.text_editor_state
    }

    pub fn render_from_cache(&mut self) {
        self.render_state.render_from_cache(&self.shapes);
    }

    pub fn render_sync(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(None, &self.shapes, timestamp, true)?;
        Ok(())
    }

    pub fn render_sync_shape(&mut self, id: &Uuid, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(Some(id), &self.shapes, timestamp, true)?;
        Ok(())
    }

    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(None, &self.shapes, timestamp, false)?;
        Ok(())
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .process_animation_frame(None, &self.shapes, timestamp)?;
        Ok(())
    }

    pub fn clear_focus_mode(&mut self) {
        self.render_state.clear_focus_mode();
    }

    pub fn set_focus_mode(&mut self, shapes: Vec<Uuid>) {
        self.render_state.set_focus_mode(shapes);
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

    pub fn delete_shape_children(&mut self, parent_id: Uuid, id: Uuid) {
        // We don't really do a self.shapes.remove so that redo/undo keep working
        let Some(shape) = self.shapes.get(&id) else {
            return;
        };

        // Only remove the children when is being deleted from the owner
        if shape.parent_id.is_none() || shape.parent_id == Some(parent_id) {
            let tiles::TileRect(rsx, rsy, rex, rey) =
                self.render_state.get_tiles_for_shape(shape, &self.shapes);
            for x in rsx..=rex {
                for y in rsy..=rey {
                    let tile = tiles::Tile(x, y);
                    self.render_state.remove_cached_tile(tile);
                    self.render_state.tiles.remove_shape_at(tile, shape.id);
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
        self.render_state.set_background_color(color);
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
        self.render_state.rebuild_tiles_shallow(&self.shapes);
    }

    pub fn rebuild_tiles(&mut self) {
        self.render_state.rebuild_tiles_from(&self.shapes, None);
    }

    pub fn rebuild_tiles_from(&mut self, base_id: Option<&Uuid>) {
        self.render_state.rebuild_tiles_from(&self.shapes, base_id);
    }

    pub fn rebuild_touched_tiles(&mut self) {
        self.render_state.rebuild_touched_tiles(&self.shapes);
    }

    pub fn rebuild_modifier_tiles(&mut self, ids: Vec<Uuid>) {
        // SAFETY: We're extending the lifetime of the mutable borrow to 'a.
        // This is safe because:
        // 1. shapes has lifetime 'a in the struct
        // 2. The reference won't outlive the struct
        // 3. No other references to shapes exist during this call
        unsafe {
            let shapes_ptr = &mut self.shapes as *mut ShapesPool<'a>;
            self.render_state
                .rebuild_modifier_tiles(&mut *shapes_ptr, ids);
        }
    }

    pub fn font_collection(&self) -> &FontCollection {
        self.render_state.fonts().font_collection()
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
        if let Some(current_id) = self.current_id {
            self.render_state.mark_touched(current_id);
        }
    }

    pub fn touch_shape(&mut self, id: Uuid) {
        self.render_state.mark_touched(id);
    }
}
