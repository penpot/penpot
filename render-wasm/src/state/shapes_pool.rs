use std::collections::HashMap;
use std::iter;

use crate::performance;
use crate::shapes;
use crate::shapes::Shape;
use crate::uuid::Uuid;

use crate::shapes::StructureEntry;
use crate::skia;

use std::cell::OnceCell;

use crate::math;
use crate::math::bools as math_bools;
use crate::math::Matrix;

const SHAPES_POOL_ALLOC_MULTIPLIER: f32 = 1.3;

/// A pool allocator for `Shape` objects that attempts to minimize memory reallocations.
///
/// `ShapesPoolImpl` pre-allocates a contiguous vector of `Shape` instances,
/// which can be reused and indexed efficiently. This design helps avoid
/// memory reallocation overhead by reserving enough space in advance.
///
/// # Memory Layout
///
/// Shapes are stored in a `Vec<Shape>`, which keeps the `Shape` instances
/// in a contiguous memory block.
///
/// # Index-based Design
///
/// All auxiliary HashMaps (modifiers, structure, scale_content, modified_shape_cache)
/// use `usize` indices instead of `&'a Uuid` references. This eliminates:
/// - Unsafe lifetime extensions
/// - The need for `rebuild_references()` after Vec reallocation
/// - Complex lifetime annotations
///
/// The `uuid_to_idx` HashMap maps `Uuid` (owned) to indices, avoiding lifetime issues.
///
pub struct ShapesPoolImpl {
    shapes: Vec<Shape>,
    counter: usize,

    /// Maps UUID to index in the shapes Vec. Uses owned Uuid, no lifetime needed.
    uuid_to_idx: HashMap<Uuid, usize>,

    /// Cache for modified shapes, keyed by index
    modified_shape_cache: HashMap<usize, OnceCell<Shape>>,
    /// Transform modifiers, keyed by index
    modifiers: HashMap<usize, skia::Matrix>,
    /// Structure entries, keyed by index
    structure: HashMap<usize, Vec<StructureEntry>>,
    /// Scale content values, keyed by index
    scale_content: HashMap<usize, f32>,
}

// Type aliases - no longer need lifetimes!
pub type ShapesPool = ShapesPoolImpl;
pub type ShapesPoolRef<'a> = &'a ShapesPoolImpl;
pub type ShapesPoolMutRef<'a> = &'a mut ShapesPoolImpl;

impl ShapesPoolImpl {
    pub fn new() -> Self {
        ShapesPoolImpl {
            shapes: vec![],
            counter: 0,
            uuid_to_idx: HashMap::default(),

            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
        }
    }

    pub fn initialize(&mut self, capacity: usize) {
        performance::begin_measure!("shapes_pool_initialize");
        self.counter = 0;
        self.uuid_to_idx = HashMap::with_capacity(capacity);

        let additional = capacity as i32 - self.shapes.len() as i32;
        if additional <= 0 {
            return;
        }

        // Reserve extra capacity to avoid future reallocations
        let target_capacity = (capacity as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
        self.shapes
            .reserve_exact(target_capacity.saturating_sub(self.shapes.len()));

        self.shapes
            .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional as usize));
        performance::end_measure!("shapes_pool_initialize");
    }

    pub fn add_shape(&mut self, id: Uuid) -> &mut Shape {
        if self.counter >= self.shapes.len() {
            // We need more space
            let current_capacity = self.shapes.capacity();
            // Ensure we add at least 1 shape when the pool is empty
            let additional =
                ((self.shapes.len() as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize).max(1);
            let needed_capacity = self.shapes.len() + additional;

            if needed_capacity > current_capacity {
                // Reserve extra space to minimize future reallocations
                let extra_reserve = (needed_capacity as f32 * 0.5) as usize;
                self.shapes
                    .reserve(needed_capacity + extra_reserve - current_capacity);
            }

            self.shapes
                .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional));
        }

        let idx = self.counter;
        let new_shape = &mut self.shapes[idx];
        new_shape.id = id;

        // Simply store the UUID -> index mapping. No unsafe lifetime tricks needed!
        self.uuid_to_idx.insert(id, idx);
        self.counter += 1;

        &mut self.shapes[idx]
    }
    // No longer needed! Index-based storage means no references to rebuild.
    // The old rebuild_references() function has been removed entirely.

    pub fn len(&self) -> usize {
        self.uuid_to_idx.len()
    }

    pub fn has(&self, id: &Uuid) -> bool {
        self.uuid_to_idx.contains_key(id)
    }

    pub fn get_mut(&mut self, id: &Uuid) -> Option<&mut Shape> {
        let idx = *self.uuid_to_idx.get(id)?;
        Some(&mut self.shapes[idx])
    }

    /// Get a shape by UUID. Returns the modified shape if modifiers/structure
    /// are applied, otherwise returns the base shape.
    pub fn get(&self, id: &Uuid) -> Option<&Shape> {
        let idx = *self.uuid_to_idx.get(id)?;

        let shape = &self.shapes[idx];

        // Check if this shape needs modification (has modifiers, structure changes, or is a bool)
        let needs_modification = shape.is_bool()
            || self.modifiers.contains_key(&idx)
            || self.structure.contains_key(&idx)
            || self.scale_content.contains_key(&idx);

        if needs_modification {
            // Check if we have a cached modified version
            if let Some(cell) = self.modified_shape_cache.get(&idx) {
                Some(cell.get_or_init(|| {
                    let mut modified_shape =
                        shape.transformed(self.modifiers.get(&idx), self.structure.get(&idx));

                    if self.to_update_bool(&modified_shape) {
                        math_bools::update_bool_to_path(&mut modified_shape, self);
                    }

                    if let Some(scale) = self.scale_content.get(&idx) {
                        modified_shape.scale_content(*scale);
                    }
                    modified_shape
                }))
            } else {
                Some(shape)
            }
        } else {
            Some(shape)
        }
    }

    #[allow(dead_code)]
    pub fn iter(&self) -> std::slice::Iter<'_, Shape> {
        self.shapes.iter()
    }

    #[allow(dead_code)]
    pub fn iter_mut(&mut self) -> std::slice::IterMut<'_, Shape> {
        self.shapes.iter_mut()
    }

    fn clean_shape_cache(&mut self) {
        self.modified_shape_cache.clear()
    }

    pub fn set_modifiers(&mut self, modifiers: HashMap<Uuid, skia::Matrix>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes

        let mut ids = Vec::<Uuid>::new();
        let mut modifiers_with_idx = HashMap::with_capacity(modifiers.len());

        for (uuid, matrix) in modifiers {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                modifiers_with_idx.insert(idx, matrix);
                ids.push(uuid);
            }
        }
        self.modifiers = modifiers_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    pub fn set_structure(&mut self, structure: HashMap<Uuid, Vec<StructureEntry>>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes
        let mut structure_with_idx = HashMap::with_capacity(structure.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, entries) in structure {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                structure_with_idx.insert(idx, entries);
                ids.push(uuid);
            }
        }
        self.structure = structure_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    pub fn set_scale_content(&mut self, scale_content: HashMap<Uuid, f32>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes
        let mut scale_content_with_idx = HashMap::with_capacity(scale_content.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, value) in scale_content {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                scale_content_with_idx.insert(idx, value);
                ids.push(uuid);
            }
        }
        self.scale_content = scale_content_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    pub fn clean_all(&mut self) {
        self.clean_shape_cache();
        self.modifiers = HashMap::default();
        self.structure = HashMap::default();
        self.scale_content = HashMap::default();
    }

    pub fn subtree(&self, id: &Uuid) -> ShapesPoolImpl {
        let Some(shape) = self.get(id) else {
            panic!("Subtree not found");
        };

        let mut shapes = vec![];
        let mut new_idx = 0;
        let mut uuid_to_idx = HashMap::default();

        for child_id in shape.all_children_iter(self, true, true) {
            let Some(child_shape) = self.get(&child_id) else {
                panic!("Not found");
            };
            shapes.push(child_shape.clone());
            uuid_to_idx.insert(child_id, new_idx);
            new_idx += 1;
        }

        ShapesPoolImpl {
            shapes,
            counter: new_idx,
            uuid_to_idx,
            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
        }
    }

    fn to_update_bool(&self, shape: &Shape) -> bool {
        if !shape.is_bool() {
            return false;
        }

        let default = &Matrix::default();

        // Get parent modifier by index
        let parent_idx = self.uuid_to_idx.get(&shape.id);
        let parent_modifier = parent_idx
            .and_then(|idx| self.modifiers.get(idx))
            .unwrap_or(default);

        // Returns true if the transform of any child is different to the parent's
        shape.all_children_iter(self, true, false).any(|child_id| {
            let child_modifier = self
                .uuid_to_idx
                .get(&child_id)
                .and_then(|idx| self.modifiers.get(idx))
                .unwrap_or(default);
            !math::is_close_matrix(parent_modifier, child_modifier)
        })
    }
}
