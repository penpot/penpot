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
pub struct ShapesPoolImpl<'a> {
    shapes: Vec<Shape>,
    counter: usize,

    shapes_uuid_to_idx: HashMap<&'a Uuid, usize>,

    modified_shape_cache: HashMap<&'a Uuid, OnceCell<Shape>>,
    modifiers: HashMap<&'a Uuid, skia::Matrix>,
    structure: HashMap<&'a Uuid, Vec<StructureEntry>>,
    scale_content: HashMap<&'a Uuid, f32>,
}

// Type aliases to avoid writing lifetimes everywhere
pub type ShapesPool<'a> = ShapesPoolImpl<'a>;
pub type ShapesPoolRef<'a> = &'a ShapesPoolImpl<'a>;
pub type ShapesPoolMutRef<'a> = &'a mut ShapesPoolImpl<'a>;

impl<'a> ShapesPoolImpl<'a> {
    pub fn new() -> Self {
        ShapesPoolImpl {
            shapes: vec![],
            counter: 0,
            shapes_uuid_to_idx: HashMap::default(),

            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
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

        // Reserve exact capacity to avoid any future reallocations
        // This is critical because we store &'a Uuid references that would be invalidated
        let target_capacity = (capacity as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
        self.shapes
            .reserve_exact(target_capacity.saturating_sub(self.shapes.len()));

        self.shapes
            .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional as usize));
        performance::end_measure!("shapes_pool_initialize");
    }

    pub fn add_shape(&mut self, id: Uuid) -> &mut Shape {
        let did_reallocate = if self.counter >= self.shapes.len() {
            // We need more space. Check if we'll need to reallocate the Vec.
            let current_capacity = self.shapes.capacity();
            let additional = (self.shapes.len() as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
            let needed_capacity = self.shapes.len() + additional;

            let will_reallocate = needed_capacity > current_capacity;

            if will_reallocate {
                // Reserve extra space to minimize future reallocations
                let extra_reserve = (needed_capacity as f32 * 0.5) as usize;
                self.shapes
                    .reserve(needed_capacity + extra_reserve - current_capacity);
            }

            self.shapes
                .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional));

            will_reallocate
        } else {
            false
        };

        let idx = self.counter;
        let new_shape = &mut self.shapes[idx];
        new_shape.id = id;

        // Get a reference to the id field in the shape with lifetime 'a
        // SAFETY: This is safe because:
        // 1. We pre-allocate enough capacity to avoid Vec reallocation
        // 2. The shape and its id field won't move within the Vec
        // 3. The reference won't outlive the ShapesPoolImpl
        let id_ref: &'a Uuid = unsafe { &*(&self.shapes[idx].id as *const Uuid) };

        self.shapes_uuid_to_idx.insert(id_ref, idx);
        self.counter += 1;

        // If the Vec reallocated, we need to rebuild all references in the HashMaps
        // because the old references point to deallocated memory
        if did_reallocate {
            self.rebuild_references();
        }

        &mut self.shapes[idx]
    }

    /// Rebuilds all &'a Uuid references in the HashMaps after a Vec reallocation.
    /// This is necessary because Vec reallocation invalidates all existing references.
    fn rebuild_references(&mut self) {
        // Rebuild shapes_uuid_to_idx with fresh references
        let mut new_map = HashMap::with_capacity(self.shapes_uuid_to_idx.len());
        for (_, idx) in self.shapes_uuid_to_idx.drain() {
            let id_ref: &'a Uuid = unsafe { &*(&self.shapes[idx].id as *const Uuid) };
            new_map.insert(id_ref, idx);
        }
        self.shapes_uuid_to_idx = new_map;

        // Rebuild modifiers with fresh references
        if !self.modifiers.is_empty() {
            let old_modifiers: Vec<(Uuid, skia::Matrix)> = self
                .modifiers
                .drain()
                .map(|(uuid_ref, matrix)| (*uuid_ref, matrix))
                .collect();

            for (uuid, matrix) in old_modifiers {
                if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                    self.modifiers.insert(uuid_ref, matrix);
                }
            }
        }

        // Rebuild structure with fresh references
        if !self.structure.is_empty() {
            let old_structure: Vec<(Uuid, Vec<StructureEntry>)> = self
                .structure
                .drain()
                .map(|(uuid_ref, entries)| (*uuid_ref, entries))
                .collect();

            for (uuid, entries) in old_structure {
                if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                    self.structure.insert(uuid_ref, entries);
                }
            }
        }

        // Rebuild scale_content with fresh references
        if !self.scale_content.is_empty() {
            let old_scale_content: Vec<(Uuid, f32)> = self
                .scale_content
                .drain()
                .map(|(uuid_ref, scale)| (*uuid_ref, scale))
                .collect();

            for (uuid, scale) in old_scale_content {
                if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                    self.scale_content.insert(uuid_ref, scale);
                }
            }
        }
        // Rebuild modified_shape_cache with fresh references
        if !self.modified_shape_cache.is_empty() {
            let old_cache: Vec<(Uuid, OnceCell<Shape>)> = self
                .modified_shape_cache
                .drain()
                .map(|(uuid_ref, cell)| (*uuid_ref, cell))
                .collect();

            for (uuid, cell) in old_cache {
                if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                    self.modified_shape_cache.insert(uuid_ref, cell);
                }
            }
        }
    }

    pub fn len(&self) -> usize {
        self.shapes_uuid_to_idx.len()
    }

    pub fn has(&self, id: &Uuid) -> bool {
        self.shapes_uuid_to_idx.contains_key(&id)
    }

    pub fn get_mut(&mut self, id: &Uuid) -> Option<&mut Shape> {
        let idx = *self.shapes_uuid_to_idx.get(&id)?;
        Some(&mut self.shapes[idx])
    }

    pub fn get(&self, id: &Uuid) -> Option<&'a Shape> {
        let idx = *self.shapes_uuid_to_idx.get(&id)?;

        // SAFETY: We're extending the lifetimes to 'a.
        // This is safe because:
        // 1. All internal HashMaps and the shapes Vec have fields with lifetime 'a
        // 2. The shape at idx won't be moved or reallocated (pre-allocated Vec)
        // 3. The id is stored in shapes[idx].id which has lifetime 'a
        // 4. The references won't outlive the ShapesPoolImpl
        unsafe {
            let shape_ptr = &self.shapes[idx] as *const Shape;
            let modifiers_ptr = &self.modifiers as *const HashMap<&'a Uuid, skia::Matrix>;
            let structure_ptr = &self.structure as *const HashMap<&'a Uuid, Vec<StructureEntry>>;
            let scale_content_ptr = &self.scale_content as *const HashMap<&'a Uuid, f32>;
            let cache_ptr = &self.modified_shape_cache as *const HashMap<&'a Uuid, OnceCell<Shape>>;

            // Extend the lifetime of id to 'a - safe because it's the same Uuid stored in shapes[idx].id
            let id_ref: &'a Uuid = &*(id as *const Uuid);

            if (*shape_ptr).is_bool()
                || (*modifiers_ptr).contains_key(&id_ref)
                || (*structure_ptr).contains_key(&id_ref)
                || (*scale_content_ptr).contains_key(&id_ref)
            {
                if let Some(cell) = (*cache_ptr).get(&id_ref) {
                    Some(cell.get_or_init(|| {
                        let mut shape = (*shape_ptr).transformed(
                            (*modifiers_ptr).get(&id_ref),
                            (*structure_ptr).get(&id_ref),
                        );

                        if self.to_update_bool(&shape) {
                            math_bools::update_bool_to_path(&mut shape, self);
                        }

                        if let Some(scale) = (*scale_content_ptr).get(&id_ref) {
                            shape.scale_content(*scale);
                        }
                        shape
                    }))
                } else {
                    Some(&*shape_ptr)
                }
            } else {
                Some(&*shape_ptr)
            }
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
        // Convert HashMap<Uuid, V> to HashMap<&'a Uuid, V> using references from shapes and
        // Initialize the cache cells because later we don't want to have the mutable pointer

        let mut ids = Vec::<Uuid>::new();

        let mut modifiers_with_refs = HashMap::with_capacity(modifiers.len());
        for (uuid, matrix) in modifiers {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                // self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
                modifiers_with_refs.insert(uuid_ref, matrix);
                ids.push(*uuid_ref);
            }
        }
        self.modifiers = modifiers_with_refs;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
            }
        }
    }

    pub fn set_structure(&mut self, structure: HashMap<Uuid, Vec<StructureEntry>>) {
        // Convert HashMap<Uuid, V> to HashMap<&'a Uuid, V> using references from shapes and
        // Initialize the cache cells because later we don't want to have the mutable pointer
        let mut structure_with_refs = HashMap::with_capacity(structure.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, entries) in structure {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                structure_with_refs.insert(uuid_ref, entries);
                ids.push(*uuid_ref);
            }
        }
        self.structure = structure_with_refs;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
            }
        }
    }

    pub fn set_scale_content(&mut self, scale_content: HashMap<Uuid, f32>) {
        // Convert HashMap<Uuid, V> to HashMap<&'a Uuid, V> using references from shapes and
        // Initialize the cache cells because later we don't want to have the mutable pointer
        let mut scale_content_with_refs = HashMap::with_capacity(scale_content.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, value) in scale_content {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                scale_content_with_refs.insert(uuid_ref, value);
                ids.push(*uuid_ref);
            }
        }
        self.scale_content = scale_content_with_refs;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
            }
        }
    }

    pub fn clean_all(&mut self) {
        self.clean_shape_cache();
        self.modifiers = HashMap::default();
        self.structure = HashMap::default();
        self.scale_content = HashMap::default();
    }

    /// Get a reference to the Uuid stored in a shape, if it exists
    pub fn get_uuid_ref(&self, id: &Uuid) -> Option<&'a Uuid> {
        let idx = *self.shapes_uuid_to_idx.get(&id)?;
        // SAFETY: We're returning a reference with lifetime 'a to a Uuid stored
        // in the shapes Vec. This is safe because the Vec is stable (pre-allocated)
        // and won't be reallocated.
        unsafe { Some(&*(&self.shapes[idx].id as *const Uuid)) }
    }

    pub fn subtree(&self, id: &Uuid) -> ShapesPoolImpl<'a> {
        let Some(shape) = self.get(id) else {
            panic!("Subtree not found");
        };

        let mut shapes = vec![];
        let mut idx = 0;
        let mut shapes_uuid_to_idx = HashMap::default();

        for id in shape.all_children_iter(self, true, true) {
            let Some(shape) = self.get(&id) else {
                panic!("Not found");
            };
            shapes.push(shape.clone());

            let id_ref: &'a Uuid = unsafe { &*(&self.shapes[idx].id as *const Uuid) };
            shapes_uuid_to_idx.insert(id_ref, idx);
            idx += 1;
        }

        let mut result = ShapesPoolImpl {
            shapes,
            counter: idx,
            shapes_uuid_to_idx,
            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
        };
        result.rebuild_references();

        result
    }

    fn to_update_bool(&self, shape: &Shape) -> bool {
        if !shape.is_bool() {
            return false;
        }

        let default = &Matrix::default();
        let parent_modifier = self.modifiers.get(&shape.id).unwrap_or(default);

        // Returns true if the transform of any child is different to the parent's
        shape.all_children_iter(self, true, false).any(|id| {
            !math::is_close_matrix(parent_modifier, self.modifiers.get(&id).unwrap_or(default))
        })
    }
}
