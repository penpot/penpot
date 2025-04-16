use crate::uuid::Uuid;
use crate::view::Viewbox;
use indexmap::IndexSet;
use skia_safe as skia;
use std::collections::{HashMap, HashSet};

pub type Tile = (i32, i32);
pub type TileWithDistance = (i32, i32, i32);

pub const TILE_SIZE: f32 = 512.;

// @see https://en.wikipedia.org/wiki/Taxicab_geometry
pub fn manhattan_distance(a: (i32, i32), b: (i32, i32)) -> i32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs()
}

pub fn get_tile_dimensions() -> skia::ISize {
    (TILE_SIZE as i32, TILE_SIZE as i32).into()
}

pub fn get_tiles_for_rect(rect: skia::Rect, tile_size: f32) -> (i32, i32, i32, i32) {
    // start
    let sx = (rect.left / tile_size).floor() as i32;
    let sy = (rect.top / tile_size).floor() as i32;
    // end
    let ex = (rect.right / tile_size).floor() as i32;
    let ey = (rect.bottom / tile_size).floor() as i32;
    (sx, sy, ex, ey)
}

pub fn get_tiles_for_viewbox(viewbox: Viewbox) -> (i32, i32, i32, i32) {
    let tile_size = get_tile_size(viewbox);
    get_tiles_for_rect(viewbox.area, tile_size)
}

pub fn get_tiles_for_viewbox_with_interest(
    viewbox: Viewbox,
    interest: i32,
) -> (i32, i32, i32, i32) {
    let (sx, sy, ex, ey) = get_tiles_for_viewbox(viewbox);
    (sx - interest, sy - interest, ex + interest, ey + interest)
}

pub fn get_tile_pos(viewbox: Viewbox, (x, y): Tile) -> (f32, f32) {
    (
        x as f32 * get_tile_size(viewbox),
        y as f32 * get_tile_size(viewbox),
    )
}

pub fn get_tile_size(viewbox: Viewbox) -> f32 {
    // TODO:  * self.options.dpr() too?
    1. / viewbox.zoom * TILE_SIZE
}

pub fn get_tile_rect(viewbox: Viewbox, tile: Tile) -> skia::Rect {
    let (tx, ty) = get_tile_pos(viewbox, tile);
    let ts = get_tile_size(viewbox);
    skia::Rect::from_xywh(tx, ty, ts, ts)
}

// This structure is usseful to keep all the shape uuids by shape id.
pub struct TileHashMap {
    grid: HashMap<Tile, IndexSet<Uuid>>,
    index: HashMap<Uuid, HashSet<Tile>>,
}

impl TileHashMap {
    pub fn new() -> Self {
        TileHashMap {
            grid: HashMap::new(),
            index: HashMap::new(),
        }
    }

    pub fn get_shapes_at(&mut self, tile: Tile) -> Option<&IndexSet<Uuid>> {
        return self.grid.get(&tile);
    }

    pub fn remove_shape_at(&mut self, tile: Tile, id: Uuid) {
        if let Some(shapes) = self.grid.get_mut(&tile) {
            shapes.shift_remove(&id);
        }

        if let Some(tiles) = self.index.get_mut(&id) {
            tiles.remove(&tile);
        }
    }

    pub fn get_tiles_of(&mut self, shape_id: Uuid) -> Option<&HashSet<Tile>> {
        self.index.get(&shape_id)
    }

    pub fn add_shape_at(&mut self, tile: Tile, shape_id: Uuid) {
        if !self.grid.contains_key(&tile) {
            self.grid.insert(tile, IndexSet::new());
        }

        if !self.index.contains_key(&shape_id) {
            self.index.insert(shape_id, HashSet::new());
        }

        let tile_set = self.grid.get_mut(&tile).unwrap();
        tile_set.insert(shape_id);

        let index_set = self.index.get_mut(&shape_id).unwrap();
        index_set.insert(tile);
    }

    pub fn invalidate(&mut self) {
        self.grid.clear();
        self.index.clear();
    }
}
