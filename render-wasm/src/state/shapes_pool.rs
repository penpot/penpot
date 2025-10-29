use std::collections::HashMap;
use std::iter;

use crate::performance;
use crate::shapes;
use crate::shapes::Shape;
use crate::uuid::Uuid;

use crate::shapes::StructureEntry;
use crate::skia;

use std::cell::OnceCell;

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
            let cache_ptr = &self.modified_shape_cache as *const HashMap<&'a Uuid, OnceCell<Shape>>;

            // Extend the lifetime of id to 'a - safe because it's the same Uuid stored in shapes[idx].id
            let id_ref: &'a Uuid = &*(id as *const Uuid);

            if self.to_update_bool(&*shape_ptr)
                || (*modifiers_ptr).contains_key(&id_ref)
                || (*structure_ptr).contains_key(&id_ref)
            {
                if let Some(cell) = (*cache_ptr).get(&id_ref) {
                    Some(cell.get_or_init(|| {
                        let shape = &*shape_ptr;
                        shape.transformed(
                            &self,
                            (*modifiers_ptr).get(&id_ref),
                            (*structure_ptr).get(&id_ref),
                        )
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

    pub fn iter_mut(&mut self) -> std::slice::IterMut<'_, Shape> {
        self.shapes.iter_mut()
    }

    #[allow(dead_code)]
    fn clean_shape_cache(&mut self) {
        self.modified_shape_cache.clear()
    }

    #[allow(dead_code)]
    pub fn set_modifiers(&mut self, modifiers: HashMap<Uuid, skia::Matrix>) {
        // self.clean_shape_cache();

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

        let all_ids = shapes::all_with_ancestors(&ids, &self, true);
        for uuid in all_ids {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
            }
        }
    }

    #[allow(dead_code)]
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

        let all_ids = shapes::all_with_ancestors(&ids, &self, true);
        for uuid in all_ids {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
            }
        }
    }

    #[allow(dead_code)]
    pub fn clean_modifiers(&mut self) {
        self.clean_shape_cache();
        self.modifiers = HashMap::default();
    }

    #[allow(dead_code)]
    pub fn clean_structure(&mut self) {
        self.clean_shape_cache();
        self.structure = HashMap::default();
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
        let Some(shape) = self.get(id) else { panic!("Subtree not found"); };

        // TODO: Maybe create all_children_iter
        let all_children = shape.all_children(self, true, true);

        let mut shapes = vec![];
        let mut idx = 0;
        let mut shapes_uuid_to_idx = HashMap::default();

        for id in all_children.iter() {
            let Some(shape) = self.get(id) else { panic!("Not found"); };
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
        };
        result.rebuild_references();

        result
    }

    fn to_update_bool(&self, shape: &Shape) -> bool {
        // TODO: Check if any of the children is in the modifiers with a
        // different matrix than the current one.
        shape.is_bool()
    }
}

// fn is_modified_child(
//     shape: &Shape,
//     shapes: ShapesPoolRef,
//     modifiers: &HashMap<Uuid, Matrix>,
// ) -> bool {
//     if modifiers.is_empty() {
//         return false;
//     }
//
//     let ids = shape.all_children(shapes, true, false);
//     let default = &Matrix::default();
//     let parent_modifier = modifiers.get(&shape.id).unwrap_or(default);
//
//     // Returns true if the transform of any child is different to the parent's
//     ids.iter().any(|id| {
//         !math::is_close_matrix(
//             parent_modifier,
//             modifiers.get(id).unwrap_or(&Matrix::default()),
//         )
//     })
// }
