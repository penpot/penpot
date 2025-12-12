use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Mutex;

use lbug::{get_storage_version, Connection, Database, SystemConfig, Value};
use uuid::Uuid;

pub(crate) static mut STATE: Option<Box<State>> = None;

// Buffer for returning UUID vectors from WASM
static SIMILAR_SHAPES_BUFFER: Mutex<Option<Vec<u8>>> = Mutex::new(None);

fn write_uuid_vec(uuids: Vec<Uuid>) -> *mut u8 {
    // Serialize UUIDs: first 4 bytes = count (u32), then 16 bytes per UUID
    let count = uuids.len() as u32;
    let mut buffer = Vec::with_capacity(4 + (uuids.len() * 16));

    // Write count (4 bytes, little-endian)
    buffer.extend_from_slice(&count.to_le_bytes());

    // Write each UUID (16 bytes each)
    for uuid in &uuids {
        let bytes = uuid.as_bytes();
        buffer.extend_from_slice(bytes);
    }

    // Store buffer in global Mutex and return pointer
    // The buffer will remain valid until free_similar_shapes_buffer is called
    let mut guard = SIMILAR_SHAPES_BUFFER.lock().unwrap();

    // Clear any existing buffer first
    *guard = Some(buffer);

    // Get pointer after storing (matches render-wasm pattern)
    let stored_buffer = guard.as_ref().unwrap();
    let ptr = stored_buffer.as_ptr() as *mut u8;

    ptr
}

pub(crate) struct State {
    pub current_id: Option<Uuid>,
    pub shapes: HashMap<Uuid, Shape>,
    pub conn: Connection<'static>,
    _db: Rc<Database>,
}

impl State {
    pub fn new() -> Self {
        // NOTE: Using the default SystemConfig tries to mmap a large
        // buffer pool (e.g. 1GB) which easily fails in WASM/browser
        // environments. We explicitly cap the buffer pool size to a
        // small value so we can re‑initialize the database multiple
        // times (e.g. when changing pages) without exhausting memory.
        let config = SystemConfig::default().buffer_pool_size(64 * 1024 * 1024); // 64 MB buffer pool

        let db = Database::in_memory(config).expect("Failed to create database");
        let db_rc = Rc::new(db);
        // SAFETY: We're extending the lifetime of the database reference to 'static.
        // This is safe because:
        // 1. The Database is owned by Rc in State and will live as long as State exists
        // 2. State is stored in a Box in a static, so it lives for the program's lifetime
        // 3. The Connection will not outlive the Database since both are in the same State struct
        let db_ref: &'static Database = unsafe { &*(Rc::as_ptr(&db_rc) as *const Database) };
        let conn = Connection::new(db_ref).expect("Failed to create connection");

        let query = format!("CREATE NODE TABLE Node (id UUID, type INT32, left DOUBLE, top DOUBLE, right DOUBLE, bottom DOUBLE, canonical_form STRING, PRIMARY KEY (id));");
        conn.query(&query).expect("Failed to create table");

        let query = format!("CREATE REL TABLE PARENT_OF (FROM Node TO Node);");
        conn.query(&query)
            .expect("Failed to create parent of relation table");

        State {
            current_id: None,
            shapes: HashMap::new(),
            conn,
            _db: db_rc,
        }
    }

    pub fn current_shape_mut(&mut self) -> Option<&mut Shape> {
        self.shapes.get_mut(&self.current_id?)
    }

    pub fn use_shape(&mut self, id: Uuid) {
        if !self.shapes.contains_key(&id) {
            self.shapes.insert(
                id,
                Shape {
                    id,
                    parent_id: None,
                    shape_type: Type::Rect,
                    children: Vec::new(),
                    selrect: Selrect::new(0.0, 0.0, 0.0, 0.0),
                    canonical_form: "".to_string(),
                },
            );
            let query = format!(
                "CREATE (:Node {{id: '{}', type: {}, left: {}, top: {}, right: {}, bottom: {}}});",
                id,
                Type::Rect as i32,
                0.0,
                0.0,
                0.0,
                0.0
            );
            self.conn.query(&query).expect("Failed to create node");
        }
        self.current_id = Some(id);
    }

    pub fn set_parent_for_current_shape(&mut self, id: Uuid) {
        let Some(shape) = self.current_shape_mut() else {
            panic!("Invalid current shape")
        };

        // If the shape already has the same parent, do nothing
        if shape.parent_id == Some(id) {
            return;
        }

        if !self.shapes.contains_key(&id) {
            self.shapes.insert(
                id,
                Shape {
                    id,
                    parent_id: None,
                    shape_type: Type::Rect,
                    children: Vec::new(),
                    selrect: Selrect::new(0.0, 0.0, 0.0, 0.0),
                    canonical_form: "".to_string(),
                },
            );
            let query = format!(
                "CREATE (:Node {{id: '{}', type: {}, left: {}, top: {}, right: {}, bottom: {}}});",
                id,
                Type::Rect as i32,
                0.0,
                0.0,
                0.0,
                0.0
            );
            self.conn.query(&query).expect("Failed to create node");
        }

        // Safely get mutable reference to the parent shape and add the current shape as its child
        if let Some(parent_shape) = self.shapes.get_mut(&id) {
            if let Some(current_id) = self.current_id {
                parent_shape.add_child(current_id);
            }
        }
    }

    pub fn canonicalize_shapes(&mut self, id: Uuid) -> String {
        // --- Reading ---
        let (shape_type_name, children) = {
            let shape = self.shapes.get(&id).expect("Shape not found");
            (shape.shape_type.name().to_string(), shape.children.clone())
        };

        // --- Recursion ---
        let canonical = if children.is_empty() {
            shape_type_name
        } else {
            let mut child_forms = Vec::new();

            for child_id in children {
                if self.shapes.contains_key(&child_id) {
                    let canonical = self.canonicalize_shapes(child_id);
                    child_forms.push(canonical);
                }
            }

            child_forms.sort();
            format!("{}[{}]", shape_type_name, child_forms.join(","))
        };

        // --- Writting ---
        if let Some(shape) = self.shapes.get_mut(&id) {
            shape.canonical_form = canonical.clone();
        }

        canonical
    }

    pub fn generate_db(&mut self) {
        println!("Generating DB");
        println!("Canonicalizing shapes");
        self.canonicalize_shapes(Uuid::nil());
        println!("Canonicalized shapes");
        for (id, shape) in self.shapes.iter() {
            let query = format!(
                "MATCH (n:Node {{id: '{}'}}) SET n.type = {}, n.left = {}, n.top = {}, n.right = {}, n.bottom = {}, n.canonical_form = '{}';",
                id,
                shape.shape_type.clone() as i32,
                shape.selrect.left,
                shape.selrect.top,
                shape.selrect.right,
                shape.selrect.bottom,
                shape.canonical_form.replace('\'', "\\'")
            );
            self.conn
                .query(&query)
                .expect("Failed to update shape attributes");
        }
        println!("Generated DB");
    }

    pub fn search_similar_shapes(&mut self, id: Uuid) -> Vec<Uuid> {
        // Calculate canonical form for the given shape
        let canonical = self
            .shapes
            .get(&id)
            .expect("Shape not found")
            .canonical_form
            .clone();

        // Query the database for all nodes with the same canonical_form
        // Note: We'll filter out the original id in the code below
        let query = format!(
            "MATCH (n:Node {{canonical_form: '{}'}}) RETURN n.id AS id;",
            canonical
        );

        let result = self
            .conn
            .query(&query)
            .expect("Failed to query similar shapes");

        // Parse results - QueryResult is iterable and returns Vec<Value> for each row
        let mut similar_shapes = Vec::new();

        for row in result {
            if !row.is_empty() {
                let id_value = &row[0];
                match id_value {
                    Value::UUID(uuid) => {
                        // Exclude the original shape id
                        if *uuid != id {
                            similar_shapes.push(*uuid);
                        }
                    }
                    Value::String(uuid_str) => {
                        // If stored as string, parse it
                        if let Ok(uuid) = Uuid::parse_str(uuid_str) {
                            // Exclude the original shape id
                            if uuid != id {
                                similar_shapes.push(uuid);
                            }
                        }
                    }
                    _ => {
                        // Skip if not a UUID or String
                        eprintln!(
                            "Warning: Expected UUID or String for id, got: {:?}",
                            id_value
                        );
                    }
                }
            }
        }

        similar_shapes
    }
}

#[derive(Debug, Clone)]
pub struct Selrect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Selrect {
    pub fn new(left: f32, top: f32, right: f32, bottom: f32) -> Self {
        Self {
            left,
            top,
            right,
            bottom,
        }
    }
}

#[derive(Debug, Clone)]
pub enum Type {
    Frame,
    Group,
    Bool,
    Rect,
    Path,
    Text,
    Circle,
    SVGRaw,
}

impl Type {
    pub fn name(&self) -> &'static str {
        match self {
            Type::Frame => "Frame",
            Type::Group => "Group",
            Type::Bool => "Bool",
            Type::Rect => "Rect",
            Type::Path => "Path",
            Type::Text => "Text",
            Type::Circle => "Circle",
            Type::SVGRaw => "SVGRaw",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Shape {
    pub id: Uuid,
    pub parent_id: Option<Uuid>,
    pub shape_type: Type,
    pub children: Vec<Uuid>,
    pub selrect: Selrect,
    pub canonical_form: String,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            parent_id: None,
            shape_type: Type::Rect,
            children: Vec::new(),
            selrect: Selrect::new(0.0, 0.0, 0.0, 0.0),
            canonical_form: "".to_string(),
        }
    }

    pub fn set_parent(&mut self, id: Uuid) {
        self.parent_id = Some(id);
    }

    pub fn set_shape_type(&mut self, shape_type: Type) {
        self.shape_type = shape_type;
    }

    pub fn set_selrect(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        self.selrect = Selrect::new(left, top, right, bottom);
    }

    pub fn add_child(&mut self, id: Uuid) {
        if !self.children.contains(&id) && id != self.id {
            self.children.push(id);
        }
    }
}

#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_ref()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

#[macro_export]
macro_rules! with_state_mut {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

#[macro_export]
macro_rules! with_current_shape_mut {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");

        if let Some($shape) = $state.current_shape_mut() {
            $block
        }
    };
}

pub fn uuid_from_u32_quartet(a: u32, b: u32, c: u32, d: u32) -> Uuid {
    let hi: u64 = ((a as u64) << 32) | b as u64;
    let lo: u64 = ((c as u64) << 32) | d as u64;
    Uuid::from_u64_pair(hi, lo)
}

#[no_mangle]
pub extern "C" fn init() {
    let state_box = Box::new(State::new());
    unsafe {
        STATE = Some(state_box);
    }
}

#[no_mangle]
pub extern "C" fn hello() {
    println!(
        "Hello from graph-wasm!!!, storage version: {}",
        get_storage_version()
    );

    with_state!(state, {
        state
            .conn
            .query("CREATE (:Person {name: 'Alice', age: 25});")
            .expect("Failed to create node");
        state
            .conn
            .query("CREATE (:Person {name: 'Bob', age: 30});")
            .expect("Failed to create node");
        let result = state
            .conn
            .query("MATCH (a:Person) RETURN a.name AS NAME, a.age AS AGE;")
            .expect("Failed to query");
        println!("result: {:?}", result);
    });
}

#[no_mangle]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_parent(a: u32, b: u32, c: u32, d: u32) {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.set_parent_for_current_shape(id);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_type(shape_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let shape_type: Type = match shape_type {
            0 => Type::Frame,
            1 => Type::Group,
            2 => Type::Bool,
            3 => Type::Rect,
            4 => Type::Path,
            5 => Type::Text,
            6 => Type::Circle,
            7 => Type::SVGRaw,
            _ => panic!("Invalid shape type: {}", shape_type),
        };
        shape.set_shape_type(shape_type.clone());
    });
}

#[no_mangle]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_selrect(left, top, right, bottom);
    });
}

#[no_mangle]
pub extern "C" fn generate_db() {
    with_state_mut!(state, {
        state.generate_db();
    });
}

#[no_mangle]
pub extern "C" fn search_similar_shapes(a: u32, b: u32, c: u32, d: u32) -> *mut u8 {
    let similar_shapes = with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.search_similar_shapes(id)
    });
    write_uuid_vec(similar_shapes)
}

#[no_mangle]
pub extern "C" fn free_similar_shapes_buffer() {
    let mut guard = SIMILAR_SHAPES_BUFFER.lock().unwrap();
    *guard = None;
}

fn main() {
    // Entry point for WASM module
}
