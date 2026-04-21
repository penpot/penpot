use crate::error::{Error, Result};
use crate::performance;
use crate::shapes::Shape;
use crate::uuid::Uuid;

use skia_safe::{self as skia, IRect, Paint, RRect};

use super::{gpu_state::GpuState, tiles::Tile, tiles::TileViewbox, tiles::TILE_SIZE};

use base64::{engine::general_purpose, Engine as _};
use std::collections::{HashMap, HashSet};

const TEXTURES_CACHE_CAPACITY: usize = 1024;
const TEXTURES_BATCH_DELETE: usize = 256;
// This is the amount of extra space we're going to give to all the surfaces to render shapes.
// If it's too big it could affect performance.
const TILE_SIZE_MULTIPLIER: i32 = 2;

#[repr(u32)]
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target = 0b000_0000_0001,
    Filter = 0b000_0000_0010,
    Cache = 0b000_0000_0100,
    Current = 0b000_0000_1000,
    Fills = 0b000_0001_0000,
    Strokes = 0b000_0010_0000,
    DropShadows = 0b000_0100_0000,
    InnerShadows = 0b000_1000_0000,
    TextDropShadows = 0b001_0000_0000,
    Export = 0b010_0000_0000,
    UI = 0b100_0000_0000,
    Debug = 0b100_0000_0001,
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
    export: skia::Surface,

    tiles: TileTextureCache,
    /// Transient per-frame cache used by the band-aware tile scheduler to
    /// carry `Current` state across visits of the same tile. Only populated
    /// for tiles whose paint order is split by a gather barrier; tiles with
    /// a single band never touch this map. Dropped per-tile on the tile's
    /// last-band visit and cleared at the end of each `run_schedule`.
    interband_cache: HashMap<Tile, skia::Image>,

    /// Transient per-frame cache mapping a gather shape's id to ONE Target
    /// snapshot taken before the gather renders any of its tiles. All of
    /// that gather's tiles read this same snapshot as their backdrop instead
    /// of taking a fresh `image_snapshot(Target)` each — which is both
    /// cheaper (1 snapshot per gather vs. 1 per tile) AND eliminates the
    /// peer-sampling artifact where tile (A)'s glass output leaks into
    /// tile (B)'s backdrop because both write to and read from live Target.
    /// Cleared at the end of each `run_schedule`.
    glass_backdrop_cache: HashMap<Uuid, skia::Image>,
    /// Per-shape cache of fully-rendered, post-displacement images for
    /// texture (scatter) shapes. Built once per frame on the first tile
    /// that touches the shape, then blitted per-tile. The paired `Rect` is
    /// the **world-space rect** the image represents — this is the
    /// viewport-clipped extrect returned by
    /// [`crate::render::texture::render_and_filter_to_image`], and callers
    /// must use it (not the full `shape.extrect`) as the blit dst so the
    /// image lines up with the pixels that were actually rendered.
    ///
    /// Cleared at the end of each `run_schedule` so a stale displaced image
    /// (or a stale viewport-clip rect) can't leak across frames when the
    /// shape or viewport changes.
    scatter_output_cache: HashMap<Uuid, (skia::Image, skia::Rect)>,
    sampling_options: skia::SamplingOptions,
    pub margins: skia::ISize,
    // Tracks which surfaces have content (dirty flag bitmask)
    dirty_surfaces: u32,

    extra_tile_dims: skia::ISize,
}

#[allow(dead_code)]
impl Surfaces {
    pub fn try_new(
        gpu_state: &mut GpuState,
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
        tile_dims: skia::ISize,
    ) -> Result<Self> {
        let extra_tile_dims = skia::ISize::new(
            tile_dims.width * TILE_SIZE_MULTIPLIER,
            tile_dims.height * TILE_SIZE_MULTIPLIER,
        );
        let margins = skia::ISize::new(extra_tile_dims.width / 4, extra_tile_dims.height / 4);

        let target = gpu_state.create_target_surface(width, height)?;
        let filter = gpu_state.create_surface_with_isize("filter".to_string(), extra_tile_dims)?;
        let cache = gpu_state.create_surface_with_dimensions("cache".to_string(), width, height)?;
        let current =
            gpu_state.create_surface_with_isize("current".to_string(), extra_tile_dims)?;

        let drop_shadows =
            gpu_state.create_surface_with_isize("drop_shadows".to_string(), extra_tile_dims)?;
        let inner_shadows =
            gpu_state.create_surface_with_isize("inner_shadows".to_string(), extra_tile_dims)?;
        let text_drop_shadows = gpu_state
            .create_surface_with_isize("text_drop_shadows".to_string(), extra_tile_dims)?;
        let shape_fills =
            gpu_state.create_surface_with_isize("shape_fills".to_string(), extra_tile_dims)?;
        let shape_strokes =
            gpu_state.create_surface_with_isize("shape_strokes".to_string(), extra_tile_dims)?;
        let export = gpu_state.create_surface_with_isize("export".to_string(), extra_tile_dims)?;

        let ui = gpu_state.create_surface_with_dimensions("ui".to_string(), width, height)?;
        let debug = gpu_state.create_surface_with_dimensions("debug".to_string(), width, height)?;

        let tiles = TileTextureCache::new();
        Ok(Surfaces {
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
            export,
            tiles,
            interband_cache: HashMap::new(),
            glass_backdrop_cache: HashMap::new(),
            scatter_output_cache: HashMap::new(),
            sampling_options,
            margins,
            dirty_surfaces: 0,
            extra_tile_dims,
        })
    }

    pub fn clear_tiles(&mut self) {
        self.tiles.clear();
    }

    pub fn margins(&self) -> skia::ISize {
        self.margins
    }

    pub fn resize(
        &mut self,
        gpu_state: &mut GpuState,
        new_width: i32,
        new_height: i32,
    ) -> Result<()> {
        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height)?)?;
        Ok(())
    }

    pub fn snapshot(&mut self, id: SurfaceId) -> skia::Image {
        let surface = self.get_mut(id);
        surface.image_snapshot()
    }

    pub fn filter_size(&self) -> (i32, i32) {
        (self.filter.width(), self.filter.height())
    }

    pub fn base64_snapshot(&mut self, id: SurfaceId) -> Result<String> {
        let surface = self.get_mut(id);
        let image = surface.image_snapshot();
        let mut context = surface.direct_context();
        let encoded_image = image
            .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
            .ok_or(Error::CriticalError("Failed to encode image".to_string()))?;
        Ok(general_purpose::STANDARD.encode(encoded_image.as_bytes()))
    }

    pub fn base64_snapshot_rect(
        &mut self,
        id: SurfaceId,
        irect: skia::IRect,
    ) -> Result<Option<String>> {
        let surface = self.get_mut(id);
        if let Some(image) = surface.image_snapshot_with_bounds(irect) {
            let mut context = surface.direct_context();
            let encoded_image = image
                .encode(context.as_mut(), skia::EncodedImageFormat::PNG, None)
                .ok_or(Error::CriticalError("Failed to encode image".to_string()))?;
            Ok(Some(
                general_purpose::STANDARD.encode(encoded_image.as_bytes()),
            ))
        } else {
            Ok(None)
        }
    }

    /// Returns a mutable reference to the canvas and automatically marks
    /// render surfaces as dirty when accessed. This tracks which surfaces
    /// have content for optimization purposes.
    pub fn canvas_and_mark_dirty(&mut self, id: SurfaceId) -> &skia::Canvas {
        // Automatically mark render surfaces as dirty when accessed
        // This tracks which surfaces have content for optimization
        match id {
            SurfaceId::Fills
            | SurfaceId::Strokes
            | SurfaceId::InnerShadows
            | SurfaceId::TextDropShadows => {
                self.mark_dirty(id);
            }
            _ => {}
        }
        self.canvas(id)
    }

    /// Returns a mutable reference to the canvas without any side effects.
    /// Use this when you only need to read or manipulate the canvas state
    /// without marking the surface as dirty.
    pub fn canvas(&mut self, id: SurfaceId) -> &skia::Canvas {
        self.get_mut(id).canvas()
    }

    pub fn surface_clone(&self, id: SurfaceId) -> skia::Surface {
        self.get(id).clone()
    }

    /// Marks a surface as having content (dirty)
    pub fn mark_dirty(&mut self, id: SurfaceId) {
        self.dirty_surfaces |= id as u32;
    }

    /// Checks if a surface has content
    pub fn is_dirty(&self, id: SurfaceId) -> bool {
        (self.dirty_surfaces & id as u32) != 0
    }

    /// Clears the dirty flag for a surface or set of surfaces
    pub fn clear_dirty(&mut self, ids: u32) {
        self.dirty_surfaces &= !ids;
    }

    /// Clears all dirty flags
    pub fn clear_all_dirty(&mut self) {
        self.dirty_surfaces = 0;
    }

    pub fn flush_and_submit(&mut self, gpu_state: &mut GpuState, id: SurfaceId) {
        let surface = self.get_mut(id);
        gpu_state.context.flush_and_submit_surface(surface, None);
    }

    pub fn draw_into(&mut self, from: SurfaceId, to: SurfaceId, paint: Option<&skia::Paint>) {
        let sampling_options = self.sampling_options;

        self.get_mut(from).clone().draw(
            self.canvas_and_mark_dirty(to),
            (0.0, 0.0),
            sampling_options,
            paint,
        );
    }

    /// Draws the cache surface directly to the target canvas.
    /// This avoids creating an intermediate snapshot, reducing GPU stalls.
    pub fn draw_cache_to_target(&mut self) {
        let sampling_options = self.sampling_options;
        self.cache.clone().draw(
            self.target.canvas(),
            (0.0, 0.0),
            sampling_options,
            Some(&skia::Paint::default()),
        );
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
        if ids & SurfaceId::Export as u32 != 0 {
            f(self.get_mut(SurfaceId::Export));
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

        // When context changes (zoom/pan/tile), clear all render surfaces first
        // to remove any residual content from previous tiles, then mark as dirty
        // so they get redrawn with new transformations
        let surface_ids = SurfaceId::Fills as u32
            | SurfaceId::Strokes as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32
            | SurfaceId::DropShadows as u32;

        // Clear surfaces before updating transformations to remove residual content
        self.apply_mut(surface_ids, |s| {
            s.canvas().clear(skia::Color::TRANSPARENT);
        });

        // Mark all render surfaces as dirty so they get redrawn
        self.mark_dirty(SurfaceId::Fills);
        self.mark_dirty(SurfaceId::Strokes);
        self.mark_dirty(SurfaceId::InnerShadows);
        self.mark_dirty(SurfaceId::TextDropShadows);
        self.mark_dirty(SurfaceId::DropShadows);

        // Update transformations
        self.apply_mut(surface_ids, |s| {
            let canvas = s.canvas();
            canvas.reset_matrix();
            canvas.scale((scale, scale));
            canvas.translate(translation);
        });
    }

    #[inline]
    pub fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
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
            SurfaceId::Export => &mut self.export,
        }
    }

    fn get(&self, id: SurfaceId) -> &skia::Surface {
        match id {
            SurfaceId::Target => &self.target,
            SurfaceId::Filter => &self.filter,
            SurfaceId::Cache => &self.cache,
            SurfaceId::Current => &self.current,
            SurfaceId::DropShadows => &self.drop_shadows,
            SurfaceId::InnerShadows => &self.inner_shadows,
            SurfaceId::TextDropShadows => &self.text_drop_shadows,
            SurfaceId::Fills => &self.shape_fills,
            SurfaceId::Strokes => &self.shape_strokes,
            SurfaceId::Debug => &self.debug,
            SurfaceId::UI => &self.ui,
            SurfaceId::Export => &self.export,
        }
    }


    /// Swap the `Filter` surface with a caller-provided one, returning the
    /// old surface. Used by `render_and_filter_to_image` to temporarily
    /// grow the scratch when a textured shape's viewport-clipped extrect
    /// would overflow the viewport-sized default Filter. Caller is
    /// responsible for swapping the original back after snapshotting.
    pub fn swap_filter(&mut self, surface: skia::Surface) -> skia::Surface {
        std::mem::replace(&mut self.filter, surface)
    }

    /// Allocate a new RGBA8 surface with the given dimensions, using the
    /// current Filter surface's context. Returns `None` if the allocation
    /// fails (e.g. requested dims exceed platform texture limits).
    pub fn create_filter_sized(&mut self, width: i32, height: i32) -> Option<skia::Surface> {
        self.filter.new_surface_with_dimensions((width, height))
    }

    fn reset_from_target(&mut self, target: skia::Surface) -> Result<()> {
        let dim = (target.width(), target.height());
        self.target = target;
        self.filter = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.debug = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.ui = self
            .target
            .new_surface_with_dimensions(dim)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        // The rest are tile size surfaces

        Ok(())
    }

    pub fn resize_cache(
        &mut self,
        cache_dims: skia::ISize,
        interest_area_threshold: i32,
    ) -> Result<()> {
        self.cache = self
            .target
            .new_surface_with_dimensions(cache_dims)
            .ok_or(Error::CriticalError("Failed to create surface".to_string()))?;
        self.cache.canvas().reset_matrix();
        self.cache.canvas().translate((
            (interest_area_threshold as f32 * TILE_SIZE),
            (interest_area_threshold as f32 * TILE_SIZE),
        ));
        Ok(())
    }

    pub fn draw_rect_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        let mut rect = if let Some(s) = outset.filter(|&s| s > 0.0) {
            let mut r = shape.selrect;
            r.outset((s, s));
            r
        } else {
            shape.selrect
        };
        if let Some(eps) = inset.filter(|&e| e > 0.0) {
            rect.inset((eps, eps));
        }
        if let Some(corners) = shape.shape_type.corners() {
            let corners = if let Some(eps) = inset.filter(|&e| e > 0.0) {
                let mut c = corners;
                for r in c.iter_mut() {
                    r.x = (r.x - eps).max(0.0);
                    r.y = (r.y - eps).max(0.0);
                }
                c
            } else {
                corners
            };
            let rrect = RRect::new_rect_radii(rect, &corners);
            self.canvas_and_mark_dirty(id).draw_rrect(rrect, paint);
        } else {
            self.canvas_and_mark_dirty(id).draw_rect(rect, paint);
        }
    }

    pub fn draw_circle_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        let mut rect = if let Some(s) = outset.filter(|&s| s > 0.0) {
            let mut r = shape.selrect;
            r.outset((s, s));
            r
        } else {
            shape.selrect
        };
        if let Some(eps) = inset.filter(|&e| e > 0.0) {
            rect.inset((eps, eps));
        }
        self.canvas_and_mark_dirty(id).draw_oval(rect, paint);
    }

    pub fn draw_path_to(
        &mut self,
        id: SurfaceId,
        shape: &Shape,
        paint: &Paint,
        outset: Option<f32>,
        inset: Option<f32>,
    ) {
        if let Some(path) = shape.get_skia_path() {
            let canvas = self.canvas_and_mark_dirty(id);
            if let Some(s) = outset.filter(|&s| s > 0.0) {
                // Draw path as a thick stroke to get outset (expanded) silhouette
                let mut stroke_paint = paint.clone();
                stroke_paint.set_stroke_width(s * 2.0);
                canvas.draw_path(&path, &stroke_paint);
            } else if let Some(eps) = inset.filter(|&e| e > 0.0) {
                // Wrap fill + clear in a save_layer so the BlendMode::Clear
                // only erases the current shape's fill, not other shapes
                // already drawn on this surface (avoids white seams at
                // intersections of shapes with inner strokes).
                let layer_rec = skia::canvas::SaveLayerRec::default();
                canvas.save_layer(&layer_rec);
                canvas.draw_path(&path, paint);
                let mut clear_paint = skia::Paint::default();
                clear_paint.set_style(skia::PaintStyle::Stroke);
                clear_paint.set_stroke_width(eps * 2.0);
                clear_paint.set_blend_mode(skia::BlendMode::Clear);
                clear_paint.set_anti_alias(paint.is_anti_alias());
                canvas.draw_path(&path, &clear_paint);
                canvas.restore();
            } else {
                canvas.draw_path(&path, paint);
            }
        }
    }

    pub fn reset(&mut self, color: skia::Color) {
        self.canvas(SurfaceId::Fills).restore_to_count(1);
        self.canvas(SurfaceId::InnerShadows).restore_to_count(1);
        self.canvas(SurfaceId::TextDropShadows).restore_to_count(1);
        self.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.canvas(SurfaceId::Current).restore_to_count(1);
        self.canvas(SurfaceId::Export).restore_to_count(1);
        self.apply_mut(
            SurfaceId::Fills as u32
                | SurfaceId::Strokes as u32
                | SurfaceId::Current as u32
                | SurfaceId::InnerShadows as u32
                | SurfaceId::TextDropShadows as u32
                | SurfaceId::Export as u32,
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

        // Clear all dirty flags after reset
        self.clear_all_dirty();
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

        let tile_image_opt = self.current.image_snapshot_with_bounds(rect);

        if let Some(tile_image) = tile_image_opt {
            // Draw to cache first (takes reference), then move to tile cache
            self.cache.canvas().draw_image_rect(
                &tile_image,
                None,
                tile_rect,
                &skia::Paint::default(),
            );

            self.tiles.add(tile_viewbox, tile, tile_image);
        }
    }

    pub fn has_cached_tile_surface(&self, tile: Tile) -> bool {
        self.tiles.has(tile)
    }

    pub fn remove_cached_tile_surface(&mut self, tile: Tile) {
        // Mark tile as invalid
        // Old content stays visible until new tile overwrites it atomically,
        // preventing flickering during tile re-renders.
        self.tiles.remove(tile);
    }

    pub fn draw_cached_tile_surface(&mut self, tile: Tile, rect: skia::Rect, color: skia::Color) {
        if let Some(image) = self.tiles.get(tile) {
            let mut paint = skia::Paint::default();
            paint.set_color(color);

            self.target.canvas().draw_rect(rect, &paint);

            self.target
                .canvas()
                .draw_image_rect(&image, None, rect, &skia::Paint::default());
        }
    }

    /// Snapshot the content region of `Current` (margins excluded) into a
    /// transient per-tile cache used by the band-aware scheduler to carry
    /// Current state across visits of the same tile. Called at the end of a
    /// non-last band visit; matched by a later `restore_current_from_interband`
    /// when the same tile's next band runs.
    pub fn snapshot_current_for_interband(&mut self, tile: Tile) {
        let rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        if let Some(image) = self.current.image_snapshot_with_bounds(rect) {
            self.interband_cache.insert(tile, image);
        }
    }

    /// Restore a tile's Current state from the inter-band cache. Draws the
    /// snapshot image into `Current` at the content-region origin (margins
    /// offset). Returns true if a snapshot was present and restored.
    pub fn restore_current_from_interband(&mut self, tile: Tile) -> bool {
        let Some(image) = self.interband_cache.get(&tile) else {
            return false;
        };
        let origin = skia::Point::new(
            self.margins.width as f32,
            self.margins.height as f32,
        );
        self.current
            .canvas()
            .draw_image(image, origin, Some(&skia::Paint::default()));
        true
    }

    /// Drop the inter-band snapshot for a tile — called after that tile's
    /// last-band visit so the image doesn't linger until end-of-frame.
    pub fn drop_interband(&mut self, tile: Tile) {
        self.interband_cache.remove(&tile);
    }

    /// Clear the inter-band cache entirely — called at the end of each
    /// `run_schedule` so snapshots don't leak across frames.
    pub fn clear_interband_cache(&mut self) {
        self.interband_cache.clear();
    }

    /// Get the backdrop snapshot for a gather shape, taking it from the
    /// given source surface on first access and caching it by shape id.
    /// All subsequent tiles of the same gather shape reuse this snapshot
    /// — all peers see the same frozen backdrop state, avoiding the
    /// tile-order dependence where whichever peer finalizes first has its
    /// glass output leak into the other peers' sample.
    pub fn get_or_snapshot_glass_backdrop(
        &mut self,
        shape_id: Uuid,
        source: SurfaceId,
    ) -> skia::Image {
        if let Some(image) = self.glass_backdrop_cache.get(&shape_id) {
            return image.clone();
        }
        let image = self.get_mut(source).image_snapshot();
        self.glass_backdrop_cache.insert(shape_id, image.clone());
        image
    }

    /// Clear the per-gather backdrop cache — called at the end of each
    /// `run_schedule` so snapshots don't leak across frames.
    pub fn clear_glass_backdrop_cache(&mut self) {
        self.glass_backdrop_cache.clear();
    }

    /// Whether the scatter output cache already holds a displaced image for
    /// this shape. Callers use this to skip re-running the expensive
    /// render+filter step on every tile after the first.
    pub fn has_scatter_output(&self, shape_id: Uuid) -> bool {
        self.scatter_output_cache.contains_key(&shape_id)
    }

    /// Store the fully-rendered, displaced image for a scatter shape along
    /// with the world-space `clipped_extrect` the image covers. The rect
    /// is the viewport-clipped extrect (not the full scheduler extrect)
    /// and must be used by the caller as the `dst` when blitting.
    pub fn insert_scatter_output(
        &mut self,
        shape_id: Uuid,
        image: skia::Image,
        clipped_extrect: skia::Rect,
    ) {
        self.scatter_output_cache
            .insert(shape_id, (image, clipped_extrect));
    }

    /// Retrieve `(image, clipped_extrect)` for a scatter shape, if cached.
    /// The rect is the world-space `dst` the image should be blitted into.
    pub fn get_scatter_output(&self, shape_id: Uuid) -> Option<&(skia::Image, skia::Rect)> {
        self.scatter_output_cache.get(&shape_id)
    }

    /// Clear the per-shape scatter output cache — called at the end of each
    /// `run_schedule` so displaced images don't leak across frames.
    pub fn clear_scatter_output_cache(&mut self) {
        self.scatter_output_cache.clear();
    }

    /// Composite the content region of `Current` (excluding margins) onto
    /// Target at `tile_rect`, without caching. Intended for mid-render partial
    /// flushes (e.g. the pre-glass flush in run_schedule) that will be
    /// superseded by a later `apply_render_to_final_canvas`.
    pub fn composite_current_to_target(&mut self, tile_rect: skia::Rect, bg_color: skia::Color) {
        let content_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let Some(tile_image) = self.current.image_snapshot_with_bounds(content_rect) else {
            return;
        };
        let mut paint = skia::Paint::default();
        paint.set_color(bg_color);
        self.target.canvas().draw_rect(tile_rect, &paint);
        self.target
            .canvas()
            .draw_image_rect(&tile_image, None, tile_rect, &skia::Paint::default());
    }

    /// Draws the current tile directly to the target and cache surfaces without
    /// creating a snapshot. This avoids GPU stalls from ReadPixels but doesn't
    /// populate the tile texture cache (suitable for one-shot renders like tests).
    pub fn draw_current_tile_direct(&mut self, tile_rect: &skia::Rect, color: skia::Color) {
        let sampling_options = self.sampling_options;
        let src_rect = IRect::from_xywh(
            self.margins.width,
            self.margins.height,
            self.current.width() - TILE_SIZE_MULTIPLIER * self.margins.width,
            self.current.height() - TILE_SIZE_MULTIPLIER * self.margins.height,
        );
        let src_rect_f = skia::Rect::from(src_rect);

        // Draw background
        let mut paint = skia::Paint::default();
        paint.set_color(color);
        self.target.canvas().draw_rect(tile_rect, &paint);

        // Draw current surface directly to target (no snapshot)
        self.current.clone().draw(
            self.target.canvas(),
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );

        // Also draw to cache for render_from_cache
        self.current.clone().draw(
            self.cache.canvas(),
            (
                tile_rect.left - src_rect_f.left,
                tile_rect.top - src_rect_f.top,
            ),
            sampling_options,
            None,
        );
    }

    /// Full cache reset: clears both the tile texture cache and the cache canvas.
    /// Used by `rebuild_tiles` (full rebuild). For shallow rebuilds that preserve
    /// the cache canvas for scaled previews, use `invalidate_tile_cache` instead.
    pub fn remove_cached_tiles(&mut self, color: skia::Color) {
        self.tiles.clear();
        self.cache.canvas().clear(color);
    }

    /// Invalidate the tile texture cache without clearing the cache canvas.
    /// This forces all tiles to be re-rendered, but preserves the cache canvas
    /// so that `render_from_cache` can still show a scaled preview of the old
    /// content while new tiles are being rendered.
    pub fn invalidate_tile_cache(&mut self) {
        self.tiles.clear();
    }

    pub fn gc(&mut self) {
        self.tiles.gc();
    }

    pub fn resize_export_surface(&mut self, scale: f32, rect: skia::Rect) {
        let target_w = (scale * rect.width()).ceil() as i32;
        let target_h = (scale * rect.height()).ceil() as i32;

        let max_w = i32::max(self.extra_tile_dims.width, target_w);
        let max_h = i32::max(self.extra_tile_dims.height, target_h);

        if max_w > self.extra_tile_dims.width || max_h > self.extra_tile_dims.height {
            self.extra_tile_dims = skia::ISize::new(max_w, max_h);
            self.drop_shadows = self
                .drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.inner_shadows = self
                .inner_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.text_drop_shadows = self
                .text_drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.text_drop_shadows = self
                .text_drop_shadows
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.shape_strokes = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
            self.shape_fills = self
                .shape_strokes
                .new_surface_with_dimensions((max_w, max_h))
                .unwrap();
        }

        self.export = self
            .export
            .new_surface_with_dimensions((target_w, target_h))
            .unwrap();
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

    pub fn get(&mut self, tile: Tile) -> Option<&mut skia::Image> {
        if self.removed.contains(&tile) {
            return None;
        }
        self.grid.get_mut(&tile)
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
