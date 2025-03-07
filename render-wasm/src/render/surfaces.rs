use super::{gpu_state::GpuState, tiles::Tile};
use skia_safe::{self as skia, sampling_options};

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
    // TODO FIX remove pub
    pub target: skia::Surface,
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
}

impl Surfaces {
    pub fn new(
        gpu_state: &mut GpuState,
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
        tile_dims: skia::ISize
    ) -> Self {
        let mut target = gpu_state.create_target_surface(width, height);
        let current = target.new_surface_with_dimensions((width, height)).unwrap();
        let shadow = target.new_surface_with_dimensions((width, height)).unwrap();
        let overlay = target.new_surface_with_dimensions((width, height)).unwrap();
        let shape_fills = target.new_surface_with_dimensions((width, height)).unwrap();
        let shape_strokes = target.new_surface_with_dimensions((width, height)).unwrap();
        let debug = target.new_surface_with_dimensions((width, height)).unwrap();
        let pool = SurfacePool::new(&mut target, tile_dims);
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
            tiles
        }
    }

    pub fn resize(&mut self, gpu_state: &mut GpuState, new_width: i32, new_height: i32) {
        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height));
    }

    pub fn snapshot(&mut self, id: SurfaceId) -> skia::Image {
        self.get_mut(id).image_snapshot()
    }

    pub fn base64_snapshot_tile(&mut self, tile: Tile) -> String {
        let surface = self.tiles.get(tile).unwrap();
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .unwrap();
        base64::encode(&encoded_image.as_bytes())
    }

    pub fn base64_snapshot(&mut self, id: SurfaceId) -> String {
        let surface = self.get_mut(id);
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .unwrap();
        base64::encode(&encoded_image.as_bytes())
    }

    pub fn base64_snapshot_rect(&mut self, id: SurfaceId, irect: skia::IRect) -> Option<String> {
        let surface = self.get_mut(id);
        if let Some(image) = surface.image_snapshot_with_bounds(irect) {
            let mut context = surface.direct_context();
            let encoded_image = image
                .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
                .unwrap();
            return Some(base64::encode(&encoded_image.as_bytes()));
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

    pub fn clip_into(
        &mut self,
        from: SurfaceId,
        to: SurfaceId,
        paint: Option<&skia::Paint>,
        rect: skia::Rect,
    ) {
        self.canvas(to).save();
        self.canvas(to)
            .clip_rect(rect, skia_safe::ClipOp::Intersect, true);
        self.draw_into(from, to, paint);
        self.canvas(to).restore();
    }

    pub fn apply_mut(&mut self, ids: &[SurfaceId], mut f: impl FnMut(&mut skia::Surface) -> ()) {
        for id in ids {
            let surface = self.get_mut(*id);
            f(surface);
        }
    }

    // TODO: Review this because it should be private.
    pub fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
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
        self.current = self.target.new_surface_with_dimensions(dim).unwrap();
        self.overlay = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shadow = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shape_fills = self.target.new_surface_with_dimensions(dim).unwrap();
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
    }

    pub fn reset(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Target).clear(color);
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

    pub fn cache_tile_surface(&mut self, tile: Tile, id: SurfaceId, rect: skia::Rect) {
        let sampling_options = self.sampling_options;
        let mut tile_surface = self.tiles.get_or_create(tile).unwrap();
        let surface = self.get_mut(id);
        surface.draw(tile_surface.canvas(), (-rect.x(), -rect.y()), sampling_options, Some(&skia::Paint::default()));
    }

    pub fn has_cached_tile_surface(&mut self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn draw_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect) {
        let sampling_options = self.sampling_options;
        let tile_surface = self.tiles.get(tile).unwrap();
        tile_surface.draw(self.target.canvas(), (rect.x(), rect.y()), sampling_options, Some(&skia::Paint::default()));
    }
}

pub struct SurfaceRef {
    pub in_use: bool,
    pub surface: skia::Surface,
}

pub struct SurfacePool {
    pub surfaces: Vec<SurfaceRef>,
    pub index: usize,
}

impl SurfaceRef {
    pub fn allocated(&mut self) {
        self.in_use = true;
    }

    pub fn deallocated(&mut self) {
        self.in_use = false;
    }
}

impl SurfacePool {
    pub fn new(surface: &mut skia::Surface, dims: skia::ISize) -> Self {
        let mut surfaces = Vec::new();
        for _ in 0..32 {
            surfaces.push(surface.new_surface_with_dimensions(dims).unwrap())
        }

        SurfacePool {
            index: 0,
            surfaces: surfaces
                .into_iter()
                .map(|surface| SurfaceRef {
                    surface: surface,
                    in_use: false,
                })
                .collect(),
        }
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

    /*
    pub fn remove(&mut self, tile: Tile) -> bool {
        if !self.grid.contains_key(&tile) {
            return false;
        }
        self.grid.remove(&tile);
        true
    }
    */

    pub fn clear(&mut self) {
        self.grid.clear();
    }
}
