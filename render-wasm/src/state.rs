use skia_safe::{self as skia, textlayout::FontCollection, Path, Point};
use std::collections::HashMap;

mod shapes_pool;
pub use shapes_pool::*;

use crate::render::RenderState;
use crate::shapes::Shape;
use crate::shapes::StructureEntry;
use crate::tiles;
use crate::uuid::Uuid;

use crate::shapes::modifiers::grid_layout::grid_cell_data;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State {
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub shapes: ShapesPool,
    pub modifiers: HashMap<Uuid, skia::Matrix>,
    pub scale_content: HashMap<Uuid, f32>,
    pub structure: HashMap<Uuid, Vec<StructureEntry>>,
}

impl State {
    pub fn new(width: i32, height: i32) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            shapes: ShapesPool::new(),
            modifiers: HashMap::new(),
            scale_content: HashMap::new(),
            structure: HashMap::new(),
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

    pub fn render_from_cache(&mut self) {
        self.render_state
            .render_from_cache(&self.shapes, &self.modifiers, &self.structure);
    }

    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state.start_render_loop(
            &self.shapes,
            &self.modifiers,
            &self.structure,
            &self.scale_content,
            timestamp,
        )?;
        Ok(())
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state.process_animation_frame(
            &self.shapes,
            &self.modifiers,
            &self.structure,
            &self.scale_content,
            timestamp,
        )?;
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

    pub fn delete_shape(&mut self, id: Uuid) {
        // We don't really do a self.shapes.remove so that redo/undo keep working
        if let Some(shape) = self.shapes.get(&id) {
            let tiles::TileRect(rsx, rsy, rex, rey) =
                self.render_state
                    .get_tiles_for_shape(shape, &self.shapes, &self.modifiers);
            for x in rsx..=rex {
                for y in rsy..=rey {
                    let tile = tiles::Tile(x, y);
                    self.render_state.remove_cached_tile_shape(tile, id);
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

    /// Sets the parent for the current shape and updates the parent's extended rectangle
    ///
    /// When a shape is assigned a new parent, the parent's extended rectangle needs to be
    /// invalidated and recalculated to include the new child. This ensures that frames
    /// and groups properly encompass their children.
    pub fn set_parent_for_current_shape(&mut self, id: Uuid) {
        let shape = {
            let Some(shape) = self.current_shape_mut() else {
                panic!("Invalid current shape")
            };
            shape.set_parent(id);
            shape.clone()
        };

        if let Some(parent) = shape.parent_id.and_then(|id| self.shapes.get_mut(&id)) {
            parent.invalidate_extrect();
            parent.add_child(shape.id);
        }
    }

    /// Sets the selection rectangle for the current shape and processes its ancestors
    ///
    /// When a shape's selection rectangle changes, all its ancestors need to have their
    /// extended rectangles recalculated because the shape's bounds may have changed.
    /// This ensures proper rendering of frames and groups containing the modified shape.
    pub fn set_selrect_for_current_shape(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        let shape = {
            let Some(shape) = self.current_shape_mut() else {
                panic!("Invalid current shape")
            };
            shape.set_selrect(left, top, right, bottom);
            shape.clone()
        };
        self.render_state
            .process_shape_ancestors(&shape, &mut self.shapes, &self.modifiers);
    }

    pub fn update_tile_for_shape(&mut self, shape_id: Uuid) {
        if let Some(shape) = self.shapes.get(&shape_id) {
            self.render_state
                .update_tile_for(shape, &self.shapes, &self.modifiers);
        }
    }

    pub fn update_tile_for_current_shape(&mut self) {
        let Some(shape) = self.current_shape() else {
            panic!("Invalid current shape")
        };
        if !shape.id.is_nil() {
            self.render_state
                .update_tile_for(&shape.clone(), &self.shapes, &self.modifiers);
        }
    }

    pub fn rebuild_tiles_shallow(&mut self) {
        self.render_state
            .rebuild_tiles_shallow(&self.shapes, &self.modifiers, &self.structure);
    }

    pub fn rebuild_tiles(&mut self) {
        self.render_state
            .rebuild_tiles(&self.shapes, &self.modifiers, &self.structure);
    }

    pub fn rebuild_modifier_tiles(&mut self) {
        self.render_state
            .rebuild_modifier_tiles(&mut self.shapes, &self.modifiers);
    }

    pub fn font_collection(&self) -> &FontCollection {
        self.render_state.fonts().font_collection()
    }

    pub fn get_grid_coords(&self, pos_x: f32, pos_y: f32) -> Option<(i32, i32)> {
        let shape = self.current_shape()?;
        let bounds = shape.bounds();
        let position = Point::new(pos_x, pos_y);

        let cells = grid_cell_data(shape, &self.shapes, &self.modifiers, &self.structure, true);

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
}
