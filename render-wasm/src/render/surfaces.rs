use crate::shapes::Shape;
use crate::view::Viewbox;
use skia_safe::{self as skia, Paint, RRect};

use super::{gpu_state::GpuState, tiles::Tile};

use base64::{engine::general_purpose, Engine as _};
use std::collections::HashMap;

const POOL_CAPACITY_MINIMUM: i32 = 32;
const POOL_CAPACITY_THRESHOLD: i32 = 4;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target,
    Current,
    Fills,
    Strokes,
    DropShadows,
    InnerShadows,
    Debug,
}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    // keeps the current render
    current: skia::Surface,
    // keeps the current shape's fills
    shape_fills: skia::Surface,
    // keeps the current shape's strokes
    shape_strokes: skia::Surface,
    // used for rendering shadows
    drop_shadows: skia::Surface,
    // used fo rendering over shadows.
    inner_shadows: skia::Surface,
    // for drawing debug info.
    debug: skia::Surface,
    // for drawing tiles.
    tiles: TileSurfaceCache,
    sampling_options: skia::SamplingOptions,
    margins: skia::ISize,
}

#[allow(dead_code)]
impl Surfaces {
    pub fn new(
        gpu_state: &mut GpuState,
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
        tile_dims: skia::ISize,
    ) -> Self {
        // This is the amount of extra space we're going
        // to give to all the surfaces to render shapes.
        // If it's too big it could affect performance.
        let extra_tile_size = 2;
        let extra_tile_dims = skia::ISize::new(
            tile_dims.width * extra_tile_size,
            tile_dims.height * extra_tile_size,
        );
        let margins = skia::ISize::new(extra_tile_dims.width / 4, extra_tile_dims.height / 4);

        let mut target = gpu_state.create_target_surface(width, height);
        let current = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let drop_shadows = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let inner_shadows = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let shape_fills = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let shape_strokes = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let debug = target.new_surface_with_dimensions((width, height)).unwrap();

        let pool_capacity =
            ((width / tile_dims.width) * (height / tile_dims.height) * POOL_CAPACITY_THRESHOLD)
                .max(POOL_CAPACITY_MINIMUM);

        let pool = SurfacePool::with_capacity(&mut target, tile_dims, pool_capacity as usize);
        let tiles = TileSurfaceCache::new(pool);
        Surfaces {
            target,
            current,
            drop_shadows,
            inner_shadows,
            shape_fills,
            shape_strokes,
            debug,
            sampling_options,
            tiles,
            margins,
        }
    }

    pub fn resize(&mut self, gpu_state: &mut GpuState, new_width: i32, new_height: i32) {
        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height));
    }

    pub fn base64_snapshot_tile(&mut self, tile: Tile) -> String {
        let surface = self.tiles.get(tile).unwrap();
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .unwrap();
        general_purpose::STANDARD.encode(&encoded_image.as_bytes())
    }

    pub fn base64_snapshot(&mut self, id: SurfaceId) -> String {
        let surface = self.get_mut(id);
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .unwrap();
        general_purpose::STANDARD.encode(&encoded_image.as_bytes())
    }

    pub fn base64_snapshot_rect(&mut self, id: SurfaceId, irect: skia::IRect) -> Option<String> {
        let surface = self.get_mut(id);
        if let Some(image) = surface.image_snapshot_with_bounds(irect) {
            let mut context = surface.direct_context();
            let encoded_image = image
                .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
                .unwrap();
            return Some(general_purpose::STANDARD.encode(&encoded_image.as_bytes()));
        }
        None
    }

    pub fn canvas(&mut self, id: SurfaceId) -> &skia::Canvas {
        self.get_mut(id).canvas()
    }

    pub fn flush_and_submit(&mut self, gpu_state: &mut GpuState, id: SurfaceId) {
        let surface = self.get_mut(id);
        gpu_state.context.flush_and_submit_surface(surface, None);
    }

    pub fn draw_into(&mut self, from: SurfaceId, to: SurfaceId, paint: Option<&skia::Paint>) {
        let sampling_options = self.sampling_options;

        self.get_mut(from)
            .clone()
            .draw(self.canvas(to), (0.0, 0.0), sampling_options, paint);
    }

    pub fn apply_mut(&mut self, ids: &[SurfaceId], mut f: impl FnMut(&mut skia::Surface) -> ()) {
        for id in ids {
            let surface = self.get_mut(*id);
            f(surface);
        }
    }

    pub fn update_render_context(&mut self, render_area: skia::Rect, viewbox: Viewbox) {
        let translation = (
            -render_area.left() + self.margins.width as f32 / viewbox.zoom,
            -render_area.top() + self.margins.height as f32 / viewbox.zoom,
        );
        self.apply_mut(
            &[
                SurfaceId::Fills,
                SurfaceId::Strokes,
                SurfaceId::DropShadows,
                SurfaceId::InnerShadows,
            ],
            |s| {
                s.canvas().restore();
                s.canvas().save();
                s.canvas().translate(translation);
            },
        );
    }

    fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Current => &mut self.current,
            SurfaceId::DropShadows => &mut self.drop_shadows,
            SurfaceId::InnerShadows => &mut self.inner_shadows,
            SurfaceId::Fills => &mut self.shape_fills,
            SurfaceId::Strokes => &mut self.shape_strokes,
            SurfaceId::Debug => &mut self.debug,
        }
    }

    fn reset_from_target(&mut self, target: skia::Surface) {
        let dim = (target.width(), target.height());
        self.target = target;
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
        // The rest are tile size surfaces
    }

    pub fn draw_rect_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        if let Some(corners) = shape.shape_type.corners() {
            let rrect = RRect::new_rect_radii(shape.selrect, &corners);
            self.canvas(id).draw_rrect(rrect, paint);
        } else {
            self.canvas(id).draw_rect(shape.selrect, paint);
        }
    }

    pub fn draw_circle_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        self.canvas(id).draw_oval(shape.selrect, paint);
    }

    pub fn draw_path_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        if let Some(path) = shape.get_skia_path() {
            self.canvas(id).draw_path(&path, paint);
        }
    }

    pub fn reset(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Fills).restore_to_count(1);
        self.canvas(SurfaceId::DropShadows).restore_to_count(1);
        self.canvas(SurfaceId::InnerShadows).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.apply_mut(
            &[
                SurfaceId::Fills,
                SurfaceId::Strokes,
                SurfaceId::Current,
                SurfaceId::DropShadows,
                SurfaceId::InnerShadows,
            ],
            |s| {
                s.canvas().clear(color).reset_matrix();
            },
        );

        self.canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn cache_clear_visited(&mut self) {
        self.tiles.clear_visited();
    }

    pub fn cache_visit(&mut self, tile: Tile) {
        self.tiles.visit(tile);
    }

    pub fn cache_visited_amount(&self) -> usize {
        self.tiles.visited_amount()
    }

    pub fn cache_visited_capacity(&self) -> usize {
        self.tiles.visited_capacity()
    }

    pub fn cache_tile_surface(&mut self, tile: Tile, id: SurfaceId, color: skia::Color) {
        let sampling_options = self.sampling_options;
        let mut tile_surface = self.tiles.get_or_create(tile).unwrap();
        let margins = self.margins;
        let surface = self.get_mut(id);
        tile_surface.canvas().clear(color);
        surface.draw(
            tile_surface.canvas(),
            (-margins.width, -margins.height),
            sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    pub fn has_cached_tile_surface(&mut self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile) -> bool {
        self.tiles.remove(tile)
    }

    pub fn draw_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect) {
        let sampling_options = self.sampling_options;
        let tile_surface = self.tiles.get(tile).unwrap();
        tile_surface.draw(
            self.target.canvas(),
            (rect.x(), rect.y()),
            sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    pub fn remove_cached_tiles(&mut self) {
        self.tiles.clear_grid();
    }
}

pub struct SurfaceRef {
    pub index: usize,
    pub in_use: bool,
    pub surface: skia::Surface,
}

impl Clone for SurfaceRef {
    fn clone(&self) -> Self {
        Self {
            index: self.index,
            in_use: self.in_use,
            surface: self.surface.clone(),
        }
    }
}

pub struct SurfacePool {
    pub surfaces: Vec<SurfaceRef>,
    pub index: usize,
}

#[allow(dead_code)]
impl SurfacePool {
    pub fn with_capacity(surface: &mut skia::Surface, dims: skia::ISize, capacity: usize) -> Self {
        let mut surfaces = Vec::with_capacity(capacity);
        for _ in 0..capacity {
            surfaces.push(surface.new_surface_with_dimensions(dims).unwrap())
        }

        Self {
            index: 0,
            surfaces: surfaces
                .into_iter()
                .enumerate()
                .map(|(index, surface)| SurfaceRef {
                    index,
                    in_use: false,
                    surface: surface,
                })
                .collect(),
        }
    }

    pub fn clear(&mut self) {
        for surface in self.surfaces.iter_mut() {
            surface.in_use = false;
        }
    }

    pub fn capacity(&self) -> usize {
        self.surfaces.len()
    }

    pub fn available(&self) -> usize {
        let mut available: usize = 0;
        for surface_ref in self.surfaces.iter() {
            if surface_ref.in_use == false {
                available += 1;
            }
        }
        available
    }

    pub fn deallocate(&mut self, surface_ref_to_deallocate: &SurfaceRef) {
        let surface_ref = self
            .surfaces
            .get_mut(surface_ref_to_deallocate.index)
            .unwrap();

        // This could happen when the "clear" method of the pool is called.
        if surface_ref.in_use == false {
            return;
        }
        surface_ref.in_use = false;
        self.index = surface_ref_to_deallocate.index;
    }

    pub fn allocate(&mut self) -> Option<SurfaceRef> {
        let start = self.index;
        let len = self.surfaces.len();
        loop {
            if let Some(surface_ref) = self.surfaces.get_mut(self.index) {
                if !surface_ref.in_use {
                    surface_ref.in_use = true;
                    return Some(surface_ref.clone());
                }
            }
            self.index = (self.index + 1) % len;
            if self.index == start {
                return None;
            }
        }
    }
}

pub struct TileSurfaceCache {
    pool: SurfacePool,
    grid: HashMap<Tile, SurfaceRef>,
    visited: HashMap<Tile, bool>,
}

#[allow(dead_code)]
impl TileSurfaceCache {
    pub fn new(pool: SurfacePool) -> Self {
        Self {
            pool,
            grid: HashMap::new(),
            visited: HashMap::new(),
        }
    }

    pub fn has(&mut self, tile: Tile) -> bool {
        return self.grid.contains_key(&tile);
    }

    fn remove_list(&mut self, marked: Vec<Tile>) {
        for tile in marked.iter() {
            self.grid.remove(tile);
        }
    }

    fn try_get_or_create(&mut self, tile: Tile) -> Result<skia::Surface, String> {
        // TODO: I don't know yet how to improve this but I don't like it. I think
        // there should be a better solution.
        let mut marked = vec![];
        for (tile, surface_ref) in self.grid.iter_mut() {
            let exists_as_visited = self.visited.contains_key(tile);
            if !exists_as_visited {
                marked.push(tile.clone());
                self.pool.deallocate(surface_ref);
                continue;
            }

            let is_visited = self.visited.get(tile).unwrap();
            if !*is_visited {
                marked.push(tile.clone());
                self.pool.deallocate(surface_ref);
            }
        }

        self.remove_list(marked);

        if let Some(surface_ref) = self.pool.allocate() {
            self.grid.insert(tile, surface_ref.clone());
            return Ok(surface_ref.surface.clone());
        }
        return Err("Not enough surfaces".into());
    }

    pub fn get_or_create(&mut self, tile: Tile) -> Result<skia::Surface, String> {
        if let Some(surface_ref) = self.pool.allocate() {
            self.grid.insert(tile, surface_ref.clone());
            return Ok(surface_ref.surface.clone());
        }
        self.try_get_or_create(tile)
    }

    pub fn get(&mut self, tile: Tile) -> Result<&mut skia::Surface, String> {
        Ok(&mut self.grid.get_mut(&tile).unwrap().surface)
    }

    pub fn remove(&mut self, tile: Tile) -> bool {
        if !self.grid.contains_key(&tile) {
            return false;
        }
        let surface_ref_to_deallocate = self.grid.remove(&tile);
        self.pool.deallocate(&surface_ref_to_deallocate.unwrap());
        true
    }

    pub fn clear_grid(&mut self) {
        self.grid.clear();
        self.pool.clear();
    }

    pub fn visited_amount(&self) -> usize {
        self.visited.len()
    }

    pub fn visited_capacity(&self) -> usize {
        self.visited.capacity()
    }

    pub fn clear_visited(&mut self) {
        self.visited.clear();
    }

    pub fn visit(&mut self, tile: Tile) {
        self.visited.insert(tile, true);
    }
}
