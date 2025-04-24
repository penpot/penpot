use std::collections::HashMap;

use skia_safe as skia;

use crate::render::RenderState;
use crate::shapes::Shape;
use crate::shapes::StructureEntry;
use crate::uuid::Uuid;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub current_shape: Option<&'a mut Shape>,
    pub shapes: HashMap<Uuid, Shape>,
    pub modifiers: HashMap<Uuid, skia::Matrix>,
    pub structure: HashMap<Uuid, Vec<StructureEntry>>,
}

impl<'a> State<'a> {
    pub fn new(width: i32, height: i32, capacity: usize) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
            modifiers: HashMap::new(),
            structure: HashMap::new(),
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        self.render_state.resize(width, height);
    }

    pub fn render_state(&'a mut self) -> &'a mut RenderState {
        &mut self.render_state
    }

    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state.start_render_loop(
            &mut self.shapes,
            &self.modifiers,
            &self.structure,
            timestamp,
        )?;
        Ok(())
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state.process_animation_frame(
            &mut self.shapes,
            &self.modifiers,
            &self.structure,
            timestamp,
        )?;
        Ok(())
    }

    pub fn use_shape(&'a mut self, id: Uuid) {
        if !self.shapes.contains_key(&id) {
            let new_shape = Shape::new(id);
            self.shapes.insert(id, new_shape);
        }
        self.current_id = Some(id);
        self.current_shape = self.shapes.get_mut(&id);
    }

    pub fn delete_shape(&mut self, id: Uuid) {
        // We don't really do a self.shapes.remove so that redo/undo keep working
        if let Some(shape) = self.shapes.get(&id) {
            let (rsx, rsy, rex, rey) = self.render_state.get_tiles_for_shape(&shape);
            for x in rsx..=rex {
                for y in rsy..=rey {
                    let tile = (x, y);
                    self.render_state.surfaces.remove_cached_tile_surface(tile);
                    self.render_state.tiles.remove_shape_at(tile, id);
                }
            }
        }
    }

    pub fn current_shape(&mut self) -> Option<&mut Shape> {
        self.current_shape.as_deref_mut()
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.render_state.set_background_color(color);
    }

    pub fn set_selrect_for_current_shape(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        match self.current_shape.as_mut() {
            Some(shape) => {
                shape.set_selrect(left, top, right, bottom);
                // We don't need to update the tile for the root shape.
                if !shape.id.is_nil() {
                    self.render_state.update_tile_for(&shape);
                }
            }
            None => panic!("Invalid current shape"),
        }
    }

    pub fn update_tile_for_current_shape(&mut self) {
        match self.current_shape.as_mut() {
            Some(shape) => {
                // We don't need to update the tile for the root shape.
                // We can also have deleted the selected shape
                if !shape.id.is_nil() && self.shapes.contains_key(&shape.id) {
                    self.render_state.update_tile_for(&shape);
                }
            }
            None => panic!("Invalid current shape"),
        }
    }

    pub fn rebuild_tiles_shallow(&mut self) {
        self.render_state
            .rebuild_tiles_shallow(&mut self.shapes, &self.modifiers, &self.structure);
    }

    pub fn rebuild_tiles(&mut self) {
        self.render_state
            .rebuild_tiles(&mut self.shapes, &self.modifiers, &self.structure);
    }

    pub fn rebuild_modifier_tiles(&mut self) {
        self.render_state
            .rebuild_modifier_tiles(&mut self.shapes, &self.modifiers);
    }
}
