use skia_safe as skia;
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

use super::Shape;
use crate::view::Viewbox;
use indexmap::IndexSet;

pub type Tile = (i32, i32);

pub const TILE_SIZE: f32 = 512.;

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

    pub fn has_shapes_at(&mut self, tile: Tile) -> bool {
        return self.grid.contains_key(&tile);
    }

    pub fn get_shapes_at(&mut self, tile: Tile) -> Option<&IndexSet<Uuid>> {
        return self.grid.get(&tile);
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

    pub fn remove_shape(&mut self, shape_id: Uuid) {
        if let Some(index_set) = self.index.get(&shape_id) {
            for tile in index_set {
                if let Some(tile_set) = self.grid.get_mut(tile) {
                    tile_set.remove(&shape_id);
                }
            }
        }
    }

    pub fn remove_shape_at(&mut self, tile: Tile, shape_id: Uuid) -> bool {
        if !self.grid.contains_key(&tile) {
            return false;
        }

        let tile_set = self.grid.get_mut(&tile).unwrap();
        tile_set.remove(&shape_id);

        let index_set = self.index.get_mut(&shape_id).unwrap();
        index_set.remove(&tile);

        true
    }

    pub fn remove_all_shapes_at(&mut self, tile: Tile) -> bool {
        if !self.grid.contains_key(&tile) {
            return false;
        }

        let tile_set = self.grid.get_mut(&tile).unwrap();
        for shape_id in tile_set.iter() {
            let index_set = self.index.get_mut(&shape_id).unwrap();
            index_set.clear();
        }
        tile_set.clear();

        true
    }

    pub fn clear(&mut self) {
        self.grid.clear();
        self.index.clear();
    }
}

/*
pub struct Tiles {
    surfaces: TileSurfaceCache,
    shapes: TileHashMap,
}

impl Tiles {
    pub fn new(pool: SurfacePool) -> Self {
        Tiles {
            surfaces: TileSurfaceCache::new(pool),
            shapes: TileHashMap::new(),
        }
    }

    pub fn invalidate_surfaces(&mut self) {
        self.surfaces.clear();
    }

    pub fn invalidate_shapes(&mut self) {
        self.shapes.clear();
    }

    pub fn invalidate_tiles(&mut self) {
        self.surfaces.clear();
        self.shapes.clear();
    }

    pub fn has_cached_surface(&mut self, tile: Tile) -> bool {
        self.surfaces.has(tile)
    }

    pub fn cached_surface(&mut self, tile: Tile) -> Result<&mut skia::Surface, String> {
        self.surfaces.get(tile)
    }

    pub fn cache_surface(&mut self, tile: Tile, surface: &mut skia::Surface, sampling: skia::SamplingOptions) {
        let mut tile_surface = self.surfaces.get_or_create(tile).unwrap();
        surface.draw(tile_surface.canvas(), (0, 0), sampling, Some(&skia::Paint::default()));
    }

    pub fn get_tile_shape_count(&mut self, tile: Tile) -> usize {
        if let Some(shapes) = self.shapes.get_shapes_at(tile) {
            return shapes.len();
        }

        // the tile is empty
        0
    }

    pub fn has_tile_at(&mut self, tile: Tile) -> bool {
        self.shapes.has_shapes_at(tile)
    }

    pub fn get_tile_at(&mut self, tile: Tile) -> Option<&IndexSet<Uuid>> {
        self.shapes.get_shapes_at(tile)
    }

    pub fn update_tile_for(&mut self, viewbox: Viewbox, shape: &Shape) {
        let tile_size = get_tile_size(viewbox);
        let (rsx, rsy, rex, rey) = get_tiles_for_rect(shape.selrect, tile_size);
        for x in rsx..=rex {
            for y in rsy..=rey {
                let tile = (x, y);
                self.shapes.add_shape_at(tile, shape.id);
            }
        }
    }
}
*/
