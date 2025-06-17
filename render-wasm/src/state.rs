use std::collections::{hash_map::Entry, HashMap};
use std::iter;

use skia_safe as skia;

use crate::performance;
use crate::render::RenderState;
use crate::shapes::Shape;
use crate::shapes::StructureEntry;
use crate::tiles;
use crate::uuid::Uuid;

const SHAPES_POOL_ALLOC_MULTIPLIER: f32 = 1.3;

/// A pool allocator for `Shape` objects that attempts to minimize memory reallocations.
///
/// `ShapesPool` pre-allocates a contiguous vector of boxed `Shape` instances,
/// which can be reused and indexed efficiently. This design helps avoid
/// memory reallocation overhead by reserving enough space in advance.
///
/// # Memory Layout
///
/// Shapes are stored in a `Vec<Box<Shape>>`, which keeps the `Box` pointers
/// in a contiguous memory block. The actual `Shape` instances are heap-allocated,
/// and this approach ensures that pushing new shapes does not invalidate
/// previously returned mutable references.
///
/// This is especially important because references to `Shape` are also held in the
/// state shapes attribute
pub(crate) struct ShapesPool {
    // We need a box so that pushing here doesn't invalidate state.shapes references
    // FIXME: See if we can avoid this
    #[allow(clippy::vec_box)]
    shapes: Vec<Box<Shape>>,
    counter: usize,
}

impl ShapesPool {
    pub fn new() -> Self {
        ShapesPool {
            shapes: vec![],
            counter: 0,
        }
    }

    pub fn initialize(&mut self, capacity: usize) {
        performance::begin_measure!("shapes_pool_initialize");
        self.counter = 0;
        let additional = capacity as i32 - self.shapes.len() as i32;
        if additional <= 0 {
            return;
        }

        self.shapes.extend(
            iter::repeat_with(|| Box::new(Shape::new(Uuid::nil()))).take(additional as usize),
        );
        performance::end_measure!("shapes_pool_initialize");
    }

    pub fn add_shape(&mut self, id: Uuid) -> &mut Shape {
        if self.counter >= self.shapes.len() {
            let additional = (self.shapes.len() as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
            self.shapes
                .extend(iter::repeat_with(|| Box::new(Shape::new(Uuid::nil()))).take(additional));
        }
        let new_shape = &mut self.shapes[self.counter];
        new_shape.id = id;
        self.counter += 1;
        new_shape
    }
}

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub current_shape: Option<&'a mut Shape>,
    pub shapes: HashMap<Uuid, &'a mut Shape>,
    pub modifiers: HashMap<Uuid, skia::Matrix>,
    pub scale_content: HashMap<Uuid, f32>,
    pub structure: HashMap<Uuid, Vec<StructureEntry>>,
    pub shapes_pool: ShapesPool,
}

impl<'a> State<'a> {
    pub fn new(width: i32, height: i32, capacity: usize) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
            modifiers: HashMap::new(),
            scale_content: HashMap::new(),
            structure: HashMap::new(),
            shapes_pool: ShapesPool::new(),
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
            &self.scale_content,
            timestamp,
        )?;
        Ok(())
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state.process_animation_frame(
            &mut self.shapes,
            &self.modifiers,
            &self.structure,
            &self.scale_content,
            timestamp,
        )?;
        Ok(())
    }

    pub fn init_shapes_pool(&mut self, capacity: usize) {
        self.shapes_pool.initialize(capacity);
    }

    pub fn use_shape(&'a mut self, id: Uuid) {
        if let Entry::Vacant(e) = self.shapes.entry(id) {
            let new_shape = self.shapes_pool.add_shape(id);
            e.insert(new_shape);
        }
        self.current_id = Some(id);
        self.current_shape = self.shapes.get_mut(&id).map(|r| &mut **r);
    }

    pub fn delete_shape(&mut self, id: Uuid) {
        // We don't really do a self.shapes.remove so that redo/undo keep working
        if let Some(shape) = self.shapes.get(&id) {
            let tiles::TileRect(rsx, rsy, rex, rey) = self.render_state.get_tiles_for_shape(shape);
            for x in rsx..=rex {
                for y in rsy..=rey {
                    let tile = tiles::Tile(x, y);
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
                    self.render_state.update_tile_for(shape);
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
                    self.render_state.update_tile_for(shape);
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
