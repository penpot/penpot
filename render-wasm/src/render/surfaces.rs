use crate::performance;
use crate::shapes::Shape;

use skia_safe::{self as skia, IRect, Paint, RRect};

use super::{gpu_state::GpuState, tiles::Tile, tiles::TileViewbox, tiles::TILE_SIZE};

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_CACHE_CAPACITY: usize = 512;
const TEXTURES_BATCH_DELETE: usize = 32;
// This is the amount of extra space we're going to give to all the surfaces to render shapes.
// If it's too big it could affect performance.
const TILE_SIZE_MULTIPLIER: i32 = 2;

#[repr(u32)]
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target = 0b00_0000_0001,
    Filter = 0b00_0000_0010,
    Cache = 0b00_0000_0100,
    Current = 0b00_0000_1000,
    Fills = 0b00_0001_0000,
    Strokes = 0b00_0010_0000,
    DropShadows = 0b00_0100_0000,
    InnerShadows = 0b00_1000_0000,
    TextDropShadows = 0b01_0000_0000,
    UI = 0b10_0000_0000,
    Debug = 0b10_0000_0001,
}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    filter: skia::Surface,
    cache: skia::Surface,
    // keeps the current render
    current: skia::Surface,
    // keeps the current shape's fills
    shape_fills: skia::Surface,
    // keeps the current shape's strokes
    shape_strokes: skia::Surface,
    // used for rendering shadows
    drop_shadows: skia::Surface,
    // used for rendering over shadows.
    inner_shadows: skia::Surface,
    // used for rendering text drop shadows
    text_drop_shadows: skia::Surface,
    // used for displaying auxiliary workspace elements
    ui: skia::Surface,
    // for drawing debug info.
    debug: skia::Surface,
    // for drawing tiles.
    tiles: TileTextureCache,
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
        let extra_tile_dims = skia::ISize::new(
            tile_dims.width * TILE_SIZE_MULTIPLIER,
            tile_dims.height * TILE_SIZE_MULTIPLIER,
        );
        let margins = skia::ISize::new(extra_tile_dims.width / 4, extra_tile_dims.height / 4);

        let target = gpu_state.create_target_surface(width, height);
        let filter = gpu_state.create_surface_with_dimensions("filter".to_string(), width, height);
        let cache = gpu_state.create_surface_with_dimensions("cache".to_string(), width, height);
        let current = gpu_state.create_surface_with_isize("current".to_string(), extra_tile_dims);
        let drop_shadows =
            gpu_state.create_surface_with_isize("drop_shadows".to_string(), extra_tile_dims);
        let inner_shadows =
            gpu_state.create_surface_with_isize("inner_shadows".to_string(), extra_tile_dims);
        let text_drop_shadows =
            gpu_state.create_surface_with_isize("text_drop_shadows".to_string(), extra_tile_dims);
        let shape_fills =
            gpu_state.create_surface_with_isize("shape_fills".to_string(), extra_tile_dims);
        let shape_strokes =
            gpu_state.create_surface_with_isize("shape_strokes".to_string(), extra_tile_dims);

        let ui = gpu_state.create_surface_with_dimensions("ui".to_string(), width, height);
        let debug = gpu_state.create_surface_with_dimensions("debug".to_string(), width, height);

        let tiles = TileTextureCache::new();
        Surfaces {
            target,
            filter,
            cache,
            current,
            drop_shadows,
            inner_shadows,
            text_drop_shadows,
            shape_fills,
            shape_strokes,
            ui,
            debug,
            tiles,
            sampling_options,
            margins,
        }
    }

    pub fn resize(&mut self, gpu_state: &mut GpuState, new_width: i32, new_height: i32) {
        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height));
    }

    pub fn snapshot(&mut self, id: SurfaceId) -> skia::Image {
        let surface = self.get_mut(id);
        surface.image_snapshot()
    }

    pub fn filter_size(&self) -> (i32, i32) {
        (self.filter.width(), self.filter.height())
    }

    pub fn base64_snapshot(&mut self, id: SurfaceId) -> String {
        let surface = self.get_mut(id);
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .unwrap();
        general_purpose::STANDARD.encode(encoded_image.as_bytes())
    }

    pub fn base64_snapshot_rect(&mut self, id: SurfaceId, irect: skia::IRect) -> Option<String> {
        let surface = self.get_mut(id);
        if let Some(image) = surface.image_snapshot_with_bounds(irect) {
            let mut context = surface.direct_context();
            let encoded_image = image
                .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
                .unwrap();
            return Some(general_purpose::STANDARD.encode(encoded_image.as_bytes()));
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

    pub fn apply_mut(&mut self, ids: u32, mut f: impl FnMut(&mut skia::Surface)) {
        performance::begin_measure!("apply_mut::flags");
        if ids & SurfaceId::Target as u32 != 0 {
            f(self.get_mut(SurfaceId::Target));
        }
        if ids & SurfaceId::Filter as u32 != 0 {
            f(self.get_mut(SurfaceId::Filter));
        }
        if ids & SurfaceId::Current as u32 != 0 {
            f(self.get_mut(SurfaceId::Current));
        }
        if ids & SurfaceId::Cache as u32 != 0 {
            f(self.get_mut(SurfaceId::Cache));
        }
        if ids & SurfaceId::Fills as u32 != 0 {
            f(self.get_mut(SurfaceId::Fills));
        }
        if ids & SurfaceId::Strokes as u32 != 0 {
            f(self.get_mut(SurfaceId::Strokes));
        }
        if ids & SurfaceId::InnerShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::InnerShadows));
        }
        if ids & SurfaceId::TextDropShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::TextDropShadows));
        }
        if ids & SurfaceId::DropShadows as u32 != 0 {
            f(self.get_mut(SurfaceId::DropShadows));
        }
        if ids & SurfaceId::Debug as u32 != 0 {
            f(self.get_mut(SurfaceId::Debug));
        }
        performance::begin_measure!("apply_mut::flags");
    }

    pub fn get_render_context_translation(
        &mut self,
        render_area: skia::Rect,
        scale: f32,
    ) -> (f32, f32) {
        (
            -render_area.left() + self.margins.width as f32 / scale,
            -render_area.top() + self.margins.height as f32 / scale,
        )
    }

    pub fn update_render_context(&mut self, render_area: skia::Rect, scale: f32) {
        let translation = self.get_render_context_translation(render_area, scale);
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32,
            |s| {
                let canvas = s.canvas();
                canvas.reset_matrix();
                canvas.scale((scale, scale));
                canvas.translate(translation);
            },
        );
    }

    #[inline]
    fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Filter => &mut self.filter,
            SurfaceId::Cache => &mut self.cache,
            SurfaceId::Current => &mut self.current,
            SurfaceId::DropShadows => &mut self.drop_shadows,
            SurfaceId::InnerShadows => &mut self.inner_shadows,
            SurfaceId::TextDropShadows => &mut self.text_drop_shadows,
            SurfaceId::Fills => &mut self.shape_fills,
            SurfaceId::Strokes => &mut self.shape_strokes,
            SurfaceId::Debug => &mut self.debug,
            SurfaceId::UI => &mut self.ui,
        }
    }

    fn reset_from_target(&mut self, target: skia::Surface) {
        let dim = (target.width(), target.height());
        self.target = target;
        self.filter = self.target.new_surface_with_dimensions(dim).unwrap();
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
        self.ui = self.target.new_surface_with_dimensions(dim).unwrap();
        // The rest are tile size surfaces
    }

    pub fn resize_cache(
        &mut self,
        gpu_state: &mut GpuState,
        cache_dims: skia::ISize,
        interest_area_threshold: i32,
    ) {
        self.cache = gpu_state.create_surface_with_isize("cache".to_string(), cache_dims);
        self.cache.canvas().reset_matrix();
        self.cache.canvas().translate((
            (interest_area_threshold as f32 * TILE_SIZE),
            (interest_area_threshold as f32 * TILE_SIZE),
        ));
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
        self.canvas(SurfaceId::InnerShadows).restore_to_count(1);
        self.canvas(SurfaceId::TextDropShadows).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::Current as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32,
            |s| {
                s.canvas().clear(color).reset_matrix();
            },
        );

        self.canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();

        self.canvas(SurfaceId::UI)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn cache_current_tile_texture(
        &mut self,
        tile_viewbox: &TileViewbox,
        tile: &Tile,
        tile_rect: &skia::Rect,
    ) {
        let rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );

        if let Some(snapshot) = self.current.image_snapshot_with_bounds(rect) {
            self.tiles.add(tile_viewbox, tile, snapshot.clone());
            self.cache.canvas().draw_image_rect(
                snapshot.clone(),
                None,
                tile_rect,
                &skia::Paint::default(),
            );
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect, color: skia::Color) {
        // Clear the specific tile area in the cache surface with color
        let mut paint = skia::Paint::default();
        paint.set_color(color);
        self.cache.canvas().draw_rect(rect, &paint);
        self.tiles.remove(tile);
    }

    pub fn draw_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect, color: skia::Color) {
        let image = self.tiles.get(tile).unwrap();

        let mut paint = skia::Paint::default();
        paint.set_color(color);

        self.target.canvas().draw_rect(rect, &paint);

        self.target
            .canvas()
            .draw_image_rect(&image, None, rect, &skia::Paint::default());
    }

    pub fn remove_cached_tiles(&mut self, color: skia::Color) {
        self.tiles.clear();
        self.cache.canvas().clear(color);
    }

    pub fn gc(&mut self) {
        self.tiles.gc();
    }
}

pub struct TileTextureCache {
    grid: HashMap<Tile, skia::Image>,
    removed: HashSet<Tile>,
}

impl TileTextureCache {
    pub fn new() -> Self {
        Self {
            grid: HashMap::default(),
            removed: HashSet::default(),
        }
    }

    pub fn has(&self, tile: Tile) -> bool {
        self.grid.contains_key(&tile) && !self.removed.contains(&tile)
    }

    fn gc(&mut self) {
        // Make a real remove
        for tile in self.removed.iter() {
            self.grid.remove(tile);
        }
    }

    fn free_tiles(&mut self, tile_viewbox: &TileViewbox) {
        let marked: Vec<_> = self
            .grid
            .iter_mut()
            .filter_map(|(tile, _)| {
                if !tile_viewbox.is_visible(tile) {
                    Some(*tile)
                } else {
                    None
                }
            })
            .take(TEXTURES_BATCH_DELETE)
            .collect();

        for tile in marked.iter() {
            self.grid.remove(tile);
        }
    }

    pub fn add(&mut self, tile_viewbox: &TileViewbox, tile: &Tile, image: skia::Image) {
        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            // First we try to remove the obsolete tiles
            self.gc();
        }

        if self.grid.len() > TEXTURES_CACHE_CAPACITY {
            self.free_tiles(tile_viewbox);
        }

        self.grid.insert(*tile, image);

        if self.removed.contains(tile) {
            self.removed.remove(tile);
        }
    }

    pub fn get(&mut self, tile: Tile) -> Result<&mut skia::Image, String> {
        let image = self.grid.get_mut(&tile).unwrap();
        Ok(image)
    }

    pub fn remove(&mut self, tile: Tile) {
        self.removed.insert(tile);
    }

    pub fn clear(&mut self) {
        for k in self.grid.keys() {
            self.removed.insert(*k);
        }
    }
}
