use crate::view::Viewbox;

use super::{gpu_state::GpuState, tiles::Tile};
use skia_safe as skia;

use base64::{engine::general_purpose, Engine as _};
use std::collections::HashMap;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target,
    Current,
    Fills,
    Strokes,
    Shadow,
    Overlay,
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
    shadow: skia::Surface,
    // for drawing the things that are over shadows.
    overlay: skia::Surface,
    // for drawing debug info.
    debug: skia::Surface,
    // for drawing tiles.
    tiles: TileSurfaceCache,
    sampling_options: skia::SamplingOptions,
    margins: skia::ISize,
}

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
        let shadow = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let overlay = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let shape_fills = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let shape_strokes = target.new_surface_with_dimensions(extra_tile_dims).unwrap();
        let debug = target.new_surface_with_dimensions((width, height)).unwrap();

        const POOL_CAPACITY_THRESHOLD: i32 = 4;
        let pool_capacity =
            (width / tile_dims.width) * (height / tile_dims.height) * POOL_CAPACITY_THRESHOLD;
        let pool = SurfacePool::with_capacity(&mut target, tile_dims, pool_capacity as usize);
        // let pool = SurfacePool::new(&mut target, tile_dims);
        let tiles = TileSurfaceCache::new(pool);
        Surfaces {
            target,
            current,
            shadow,
            overlay,
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

    pub fn draw_into_point(
        &mut self,
        from: SurfaceId,
        to: SurfaceId,
        paint: Option<&skia::Paint>,
        point: skia::Point,
    ) {
        let sampling_options = self.sampling_options;
        self.get_mut(from)
            .clone()
            .draw(self.canvas(to), point, sampling_options, paint);
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
        self.apply_mut(&[SurfaceId::Fills, SurfaceId::Strokes], |s| {
            s.canvas().restore();
            s.canvas().save();
            // TODO AITOR SHADOWS
            s.canvas().translate(translation);
        });
    }

    fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Current => &mut self.current,
            SurfaceId::Shadow => &mut self.shadow,
            SurfaceId::Overlay => &mut self.overlay,
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

    pub fn reset(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Fills).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);

        self.apply_mut(
            &[
                SurfaceId::Fills,
                SurfaceId::Strokes,
                SurfaceId::Current,
                SurfaceId::Shadow,
                SurfaceId::Overlay,
            ],
            |s| {
                s.canvas().clear(color).reset_matrix();
            },
        );

        self.canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
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

    pub fn clear_cached_tile_surface(&mut self, tile: Tile) -> bool {
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

    pub fn clear_tiles(&mut self) {
        self.tiles.clear();
    }
}

pub struct SurfaceRef {
    pub surface: skia::Surface,
}

pub struct SurfacePool {
    pub surfaces: Vec<SurfaceRef>,
    pub index: usize,
}

// 64 * 512 * 512 * 4 = ~68MB (GPU RAM)
const SURFACE_POOL_DEFAULT_CAPACITY: usize = 64;

impl SurfacePool {
    pub fn with_capacity(surface: &mut skia::Surface, dims: skia::ISize, capacity: usize) -> Self {
        let mut surfaces = Vec::with_capacity(capacity);
        for _ in 0..capacity {
            surfaces.push(surface.new_surface_with_dimensions(dims).unwrap())
        }

        SurfacePool {
            index: 0,
            surfaces: surfaces
                .into_iter()
                .map(|surface| SurfaceRef { surface: surface })
                .collect(),
        }
    }

    pub fn new(surface: &mut skia::Surface, dims: skia::ISize) -> Self {
        SurfacePool::with_capacity(surface, dims, SURFACE_POOL_DEFAULT_CAPACITY)
    }

    pub fn allocate(&mut self) -> Result<skia::Surface, String> {
        let start = self.index;
        let len = self.surfaces.len();
        loop {
            self.index = (self.index + 1) % len;
            if self.index == start {
                return Err("Not enough surfaces in the pool".into());
            }
            if let Some(surface_ref) = self.surfaces.get(self.index) {
                return Ok(surface_ref.surface.clone());
            }
        }
    }
}

pub struct TileSurfaceCache {
    pool: SurfacePool,
    grid: HashMap<Tile, skia::Surface>,
}

impl TileSurfaceCache {
    pub fn new(pool: SurfacePool) -> Self {
        TileSurfaceCache {
            pool,
            grid: HashMap::new(),
        }
    }

    pub fn has(&mut self, tile: Tile) -> bool {
        return self.grid.contains_key(&tile);
    }

    pub fn get_or_create(&mut self, tile: Tile) -> Result<skia::Surface, String> {
        let surface = self.pool.allocate()?;
        self.grid.insert(tile, surface.clone());
        Ok(surface)
    }

    pub fn get(&mut self, tile: Tile) -> Result<&mut skia::Surface, String> {
        Ok(self.grid.get_mut(&tile).unwrap())
    }

    pub fn remove(&mut self, tile: Tile) -> bool {
        if !self.grid.contains_key(&tile) {
            return false;
        }
        self.grid.remove(&tile);
        true
    }

    pub fn clear(&mut self) {
        self.grid.clear();
    }
}
