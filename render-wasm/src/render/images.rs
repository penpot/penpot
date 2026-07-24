use crate::math::Rect as MathRect;
use crate::shapes::ImageFill;
use crate::uuid::Uuid;

use crate::error::Result;
use crate::get_gpu_state;
use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use skia_safe::{self as skia, Codec, ISize, Size};
use std::cell::Cell;
use std::collections::HashMap;

pub type Image = skia::Image;

pub fn get_dest_rect(container: &MathRect, delta: f32) -> MathRect {
    MathRect::from_ltrb(
        container.left - delta,
        container.top - delta,
        container.right + delta,
        container.bottom + delta,
    )
}

pub fn get_source_rect(size: ISize, container: &MathRect, image_fill: &ImageFill) -> MathRect {
    let image_width = size.width as f32;
    let image_height = size.height as f32;

    // Container size
    let container_width = container.width();
    let container_height = container.height();

    let mut source_width = image_width;
    let mut source_height = image_height;
    let mut source_x = 0.;
    let mut source_y = 0.;

    let source_scale_y = image_height / container_height;
    let source_scale_x = image_width / container_width;

    if image_fill.keep_aspect_ratio() {
        // Calculate scale to ensure the image covers the container
        let image_aspect_ratio = image_width / image_height;
        let container_aspect_ratio = container_width / container_height;

        if image_aspect_ratio > container_aspect_ratio {
            // Image is taller, scale based on width to cover container
            source_width = container_width * source_scale_y;
            source_x = (image_width - source_width) / 2.0;
        } else {
            // Image is wider, scale based on height to cover container
            source_height = container_height * source_scale_x;
            source_y = (image_height - source_height) / 2.0;
        };
    }

    MathRect::from_xywh(source_x, source_y, source_width, source_height)
}

enum StoredImage {
    Raw(Vec<u8>),
    Gpu(Image),
    Svg {
        dom: skia::svg::Dom,
        size: Size,
        // Lazy raster for consumers that need a texture (stroke fills,
        // exports). The shape fill path draws the DOM directly instead.
        raster: Option<Image>,
    },
}

struct StoredEntry {
    image: StoredImage,
    /// Approximate retained cost: encoded byte length (raw/svg) or the
    /// decoded RGBA size for images registered from a GL texture.
    bytes: usize,
    /// LRU tick; `Cell` so read paths can touch it without `&mut self`.
    last_used: Cell<u64>,
}

pub struct ImageStore {
    images: HashMap<(Uuid, bool), StoredEntry>,
    total_bytes: usize,
    tick: Cell<u64>,
    /// gpu-only
    context: Option<Box<DirectContext>>,
}

/// Creates a Skia image from an existing WebGL texture.
/// This avoids re-decoding the image, as the browser has already decoded
/// and uploaded it to the GPU.
fn create_image_from_gl_texture(
    context: &mut Box<DirectContext>,
    texture_id: u32,
    width: i32,
    height: i32,
) -> Result<Image> {
    use skia_safe::gpu;
    use skia_safe::gpu::gl::TextureInfo;

    // Create a TextureInfo describing the existing GL texture
    let texture_info = TextureInfo {
        target: gl::TEXTURE_2D,
        id: texture_id,
        format: gl::RGBA8,
        protected: gpu::Protected::No,
    };

    // Create a backend texture from the GL texture using the new API
    let label = format!("shared_texture_{}", texture_id);
    let backend_texture = unsafe {
        gpu::backend_textures::make_gl((width, height), gpu::Mipmapped::No, texture_info, label)
    };

    // Create a Skia image from the backend texture
    // Use TopLeft origin because HTML images have their origin at top-left,
    // while WebGL textures traditionally use bottom-left
    let image = Image::from_texture(
        context.as_mut(),
        &backend_texture,
        gpu::SurfaceOrigin::TopLeft,
        skia::ColorType::RGBA8888,
        skia::AlphaType::Premul,
        None,
    )
    .ok_or(crate::error::Error::CriticalError(
        "Failed to create Skia image from GL texture".to_string(),
    ))?;

    Ok(image)
}

// Decode and upload to GPU
fn decode_image(context: &mut Box<DirectContext>, raw_data: &[u8]) -> Option<Image> {
    let data = unsafe { skia::Data::new_bytes(raw_data) };
    let codec = Codec::from_data(&data)?;
    let image = Image::from_encoded(&data)?;

    let mut dimensions = codec.dimensions();
    if codec.origin().swaps_width_height() {
        dimensions.width = codec.dimensions().height;
        dimensions.height = codec.dimensions().width;
    }

    let image_info = skia::ImageInfo::new_n32_premul(dimensions, None);

    let mut surface = surfaces::render_target(
        context,
        Budgeted::Yes,
        &image_info,
        None,
        None,
        None,
        true,
        false,
    )?;

    let dest_rect: MathRect =
        MathRect::from_xywh(0.0, 0.0, dimensions.width as f32, dimensions.height as f32);

    surface
        .canvas()
        .draw_image_rect(&image, None, dest_rect, &skia::Paint::default());

    Some(surface.image_snapshot())
}

// Size for SVGs without intrinsic dimensions nor a viewBox.
const DEFAULT_SVG_SIZE: f32 = 512.0;

// Parse an SVG and resolve its natural size. Skia codecs don't handle SVG,
// so this is the fallback when `decode_image` fails.
fn parse_svg(raw_data: &[u8]) -> Option<(skia::svg::Dom, Size)> {
    // An empty font manager: <text> elements inside SVG image fills won't
    // resolve typefaces. Wire the render state's font provider here if that
    // ever becomes a need.
    let font_mgr = skia::FontMgr::new();
    let mut dom = skia::svg::Dom::from_bytes(raw_data, font_mgr).ok()?;

    let mut size = dom.root().intrinsic_size();
    if size.is_empty() {
        // SVGs without width/height attributes have no intrinsic size;
        // fall back to the viewBox dimensions.
        size = dom
            .root()
            .view_box()
            .map(|vb| Size::new(vb.width(), vb.height()))
            .unwrap_or_else(|| Size::new(DEFAULT_SVG_SIZE, DEFAULT_SVG_SIZE));
    }

    // Ceil so the size matches the integer dimensions used when rasterizing.
    let size = Size::new(size.width.ceil(), size.height.ceil());
    if size.is_empty() {
        return None;
    }
    dom.set_container_size(size);

    Some((dom, size))
}

fn rasterize_svg(
    context: &mut Box<DirectContext>,
    dom: &skia::svg::Dom,
    size: Size,
) -> Option<Image> {
    let dimensions = ISize::new(size.width as i32, size.height as i32);
    let image_info = skia::ImageInfo::new_n32_premul(dimensions, None);
    let mut surface = surfaces::render_target(
        context,
        Budgeted::Yes,
        &image_info,
        None,
        None,
        None,
        true,
        false,
    )?;

    dom.render(surface.canvas());
    Some(surface.image_snapshot())
}

impl ImageStore {
    pub fn new() -> Self {
        let gpu_state = get_gpu_state();
        let context = &gpu_state.context;
        Self {
            images: HashMap::with_capacity(2048),
            total_bytes: 0,
            tick: Cell::new(0),
            context: Some(Box::new(context.clone())),
        }
    }

    /// GPU-free image store for the headless export path: no GPU context, so
    /// images are kept as encoded bytes and decoded on the CPU at draw time
    /// (see `get_cpu_image`).
    pub fn new_without_gpu() -> Self {
        Self {
            images: HashMap::with_capacity(16),
            total_bytes: 0,
            tick: Cell::new(0),
            context: None,
        }
    }

    /// Bumps the LRU clock and returns the new tick.
    fn next_tick(&self) -> u64 {
        let t = self.tick.get() + 1;
        self.tick.set(t);
        t
    }

    fn insert_entry(&mut self, key: (Uuid, bool), image: StoredImage, bytes: usize) {
        let last_used = Cell::new(self.next_tick());
        self.total_bytes += bytes;
        self.images.insert(
            key,
            StoredEntry {
                image,
                bytes,
                last_used,
            },
        );
    }

    /// Evicts least-recently-used images until the store retains at most
    /// `max_bytes`. Meant to be called by the headless exporter *between*
    /// requests, so an image can never disappear under a running render;
    /// evicted images are simply re-provisioned by a later request that
    /// needs them (`is_image_cached` reports them as missing). Returns the
    /// number of evicted images.
    pub fn evict_to_budget(&mut self, max_bytes: usize) -> usize {
        let mut evicted = 0;
        while self.total_bytes > max_bytes {
            let Some(key) = self
                .images
                .iter()
                .min_by_key(|(_, entry)| entry.last_used.get())
                .map(|(key, _)| *key)
            else {
                break;
            };
            if let Some(entry) = self.images.remove(&key) {
                self.total_bytes -= entry.bytes;
                evicted += 1;
            }
        }
        evicted
    }

    pub fn add(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        image_data: &[u8],
    ) -> crate::error::Result<()> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Ok(());
        }

        let raw_data = image_data.to_vec();
        let bytes = raw_data.len();

        match self.context.as_mut() {
            Some(context) => {
                if let Some(gpu_image) = decode_image(context, &raw_data) {
                    self.insert_entry(key, StoredImage::Gpu(gpu_image), bytes);
                } else if let Some((dom, size)) = parse_svg(&raw_data) {
                    self.insert_entry(
                        key,
                        StoredImage::Svg {
                            dom,
                            size,
                            raster: None,
                        },
                        bytes,
                    );
                } else {
                    // The lazy re-decode in `get_internal` only retries raster codecs,
                    // so SVGs that fail to parse here stay raw.
                    self.insert_entry(key, StoredImage::Raw(raw_data), bytes);
                }
            }
            // GPU-free: keep the encoded bytes; decoded on the CPU at draw time.
            // SVGs still get parsed up front since that needs no GPU context.
            None => {
                if let Some((dom, size)) = parse_svg(&raw_data) {
                    self.insert_entry(
                        key,
                        StoredImage::Svg {
                            dom,
                            size,
                            raster: None,
                        },
                        bytes,
                    );
                } else {
                    self.insert_entry(key, StoredImage::Raw(raw_data), bytes);
                }
            }
        }
        Ok(())
    }

    /// Creates a Skia image from an existing WebGL texture, avoiding re-decoding.
    /// This is much more efficient as it reuses the texture that was already
    /// decoded and uploaded to GPU by the browser.
    pub fn add_image_from_gl_texture(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        texture_id: u32,
        width: i32,
        height: i32,
    ) -> Result<()> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Ok(());
        }

        // Create a Skia image from the existing GL texture
        let Some(context) = self.context.as_mut() else {
            return Err(crate::error::Error::CriticalError(
                "Cannot register a GL texture without a GPU context".to_string(),
            ));
        };
        let image = create_image_from_gl_texture(context, texture_id, width, height)?;
        let bytes = (width as usize) * (height as usize) * 4;
        self.insert_entry(key, StoredImage::Gpu(image), bytes);

        Ok(())
    }

    pub fn contains(&self, id: &Uuid, is_thumbnail: bool) -> bool {
        self.images.contains_key(&(*id, is_thumbnail))
    }

    pub fn get(&mut self, id: &Uuid) -> Option<&Image> {
        // Try to get full image first, fallback to thumbnail
        let has_full = self.images.contains_key(&(*id, false));
        if has_full {
            self.get_internal(id, false)
        } else {
            self.get_internal(id, true)
        }
    }

    pub fn get_cpu_image(&mut self, id: &Uuid) -> Option<Image> {
        // GPU path: promote to a texture, then copy to a CPU image.
        if self.context.is_some() {
            let gpu_image = self.get(id)?.clone();
            let context = self.context.as_mut()?;
            return gpu_image.make_non_texture_image(context.as_mut());
        }
        // Headless (no GPU context): decode the stored encoded bytes directly to
        // a CPU image, which draws fine on a raster/PDF canvas. Try full first,
        // then thumbnail.
        self.decode_raw_cpu_image(id, false)
            .or_else(|| self.decode_raw_cpu_image(id, true))
    }

    fn decode_raw_cpu_image(&self, id: &Uuid, is_thumbnail: bool) -> Option<Image> {
        let entry = self.images.get(&(*id, is_thumbnail))?;
        entry.last_used.set(self.next_tick());
        match &entry.image {
            StoredImage::Raw(raw_data) => {
                let data = unsafe { skia::Data::new_bytes(raw_data) };
                Image::from_encoded(&data)
            }
            StoredImage::Gpu(img) => Some(img.clone()),
            StoredImage::Svg { dom, size, .. } => {
                // No GPU context in the headless path: rasterize on a CPU
                // surface instead of `rasterize_svg` (which needs one).
                let dimensions = ISize::new(size.width as i32, size.height as i32);
                let mut surface = skia::surfaces::raster_n32_premul(dimensions)?;
                dom.render(surface.canvas());
                Some(surface.image_snapshot())
            }
        }
    }

    /// Vector access for SVG images: the fill render path draws the DOM
    /// directly so it stays crisp at any zoom level.
    pub fn get_svg(&self, id: &Uuid) -> Option<(&skia::svg::Dom, Size)> {
        let entry = self
            .images
            .get(&(*id, false))
            .or_else(|| self.images.get(&(*id, true)))?;
        entry.last_used.set(self.next_tick());
        match &entry.image {
            StoredImage::Svg { dom, size, .. } => Some((dom, *size)),
            _ => None,
        }
    }

    fn get_internal(&mut self, id: &Uuid, is_thumbnail: bool) -> Option<&Image> {
        let key = (*id, is_thumbnail);
        let tick = self.tick.get() + 1;
        self.tick.set(tick);
        // Use entry API to mutate the HashMap in-place if needed
        if let Some(entry) = self.images.get_mut(&key) {
            entry.last_used.set(tick);
            match &mut entry.image {
                StoredImage::Gpu(ref img) => Some(img),
                StoredImage::Raw(raw_data) => {
                    let context = self.context.as_mut()?;
                    let gpu_image = decode_image(context, raw_data)?;
                    entry.image = StoredImage::Gpu(gpu_image);

                    if let StoredImage::Gpu(ref img) = entry.image {
                        Some(img)
                    } else {
                        None
                    }
                }
                StoredImage::Svg { dom, size, raster } => {
                    if raster.is_none() {
                        let context = self.context.as_mut()?;
                        *raster = rasterize_svg(context, dom, *size);
                    }
                    raster.as_ref()
                }
            }
        } else {
            None
        }
    }
}
