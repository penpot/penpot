use std::collections::HashMap;
use std::iter;

use crate::performance;
use crate::shapes::Shape;
use crate::uuid::Uuid;

const SHAPES_POOL_ALLOC_MULTIPLIER: f32 = 1.3;

/// A pool allocator for `Shape` objects that attempts to minimize memory reallocations.
///
/// `ShapesPool` pre-allocates a contiguous vector of `Shape` instances,
/// which can be reused and indexed efficiently. This design helps avoid
/// memory reallocation overhead by reserving enough space in advance.
///
/// # Memory Layout
///
/// Shapes are stored in a `Vec<Shape>`, which keeps the `Shape` instances
/// in a contiguous memory block.
///
pub struct ShapesPool {
    shapes: Vec<Shape>,
    shapes_uuid_to_idx: HashMap<Uuid, usize>,
    counter: usize,
}

impl ShapesPool {
    pub fn new() -> Self {
        ShapesPool {
            shapes: vec![],
            counter: 0,
            shapes_uuid_to_idx: HashMap::default(),
        }
    }

    pub fn initialize(&mut self, capacity: usize) {
        performance::begin_measure!("shapes_pool_initialize");
        self.counter = 0;
        self.shapes_uuid_to_idx = HashMap::with_capacity(capacity);

        let additional = capacity as i32 - self.shapes.len() as i32;
        if additional <= 0 {
            return;
        }

        self.shapes
            .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional as usize));
        performance::end_measure!("shapes_pool_initialize");
    }

    pub fn add_shape(&mut self, id: Uuid) -> &mut Shape {
        if self.counter >= self.shapes.len() {
            let additional = (self.shapes.len() as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
            self.shapes
                .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional));
        }
        let new_shape = &mut self.shapes[self.counter];
        new_shape.id = id;
        self.shapes_uuid_to_idx.insert(id, self.counter);
        self.counter += 1;
        new_shape
    }

    pub fn len(&self) -> usize {
        self.shapes_uuid_to_idx.len()
    }

    pub fn has(&self, id: &Uuid) -> bool {
        self.shapes_uuid_to_idx.contains_key(id)
    }

    pub fn get_mut(&mut self, id: &Uuid) -> Option<&mut Shape> {
        let idx = *self.shapes_uuid_to_idx.get(id)?;
        Some(&mut self.shapes[idx])
    }

    pub fn get(&self, id: &Uuid) -> Option<&Shape> {
        let idx = *self.shapes_uuid_to_idx.get(id)?;
        Some(&self.shapes[idx])
    }
}
