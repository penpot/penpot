use std::collections::HashMap;
use std::iter;

use crate::performance;
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
        let idx = self.counter;
        let new_shape = &mut self.shapes[idx];
        new_shape.id = id;

        // Get a reference to the id field in the shape
        // SAFETY: We need to get a reference with lifetime 'a from the shape's id.
        // This is safe because the shapes Vec is stable and won't be reallocated
        // (we pre-allocate), and the id field won't move within the Shape.
        let id_ref: &'a Uuid = unsafe { &*(&self.shapes[idx].id as *const Uuid) };

        self.shapes_uuid_to_idx.insert(id_ref, idx);
        self.counter += 1;
        &mut self.shapes[idx]
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

            if (*modifiers_ptr).contains_key(&id_ref) || (*structure_ptr).contains_key(&id_ref) {
                if let Some(cell) = (*cache_ptr).get(&id_ref) {
                    Some(cell.get_or_init(|| {
                        let shape = &*shape_ptr;
                        shape.transformed((*modifiers_ptr).get(&id_ref))
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
        self.clean_shape_cache();

        // Convert HashMap<Uuid, V> to HashMap<&'a Uuid, V> using references from shapes and
        // Initialize the cache cells because later we don't want to have the mutable pointer
        let mut modifiers_with_refs = HashMap::with_capacity(modifiers.len());
        for (uuid, matrix) in modifiers {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
                modifiers_with_refs.insert(uuid_ref, matrix);
            }
        }
        self.modifiers = modifiers_with_refs;
    }

    #[allow(dead_code)]
    pub fn set_structure(&mut self, structure: HashMap<Uuid, Vec<StructureEntry>>) {
        self.clean_shape_cache();

        // Convert HashMap<Uuid, V> to HashMap<&'a Uuid, V> using references from shapes and
        // Initialize the cache cells because later we don't want to have the mutable pointer
        let mut structure_with_refs = HashMap::with_capacity(structure.len());
        for (uuid, entries) in structure {
            if let Some(uuid_ref) = self.get_uuid_ref(&uuid) {
                self.modified_shape_cache.insert(uuid_ref, OnceCell::new());
                structure_with_refs.insert(uuid_ref, entries);
            }
        }
        self.structure = structure_with_refs;
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
}
