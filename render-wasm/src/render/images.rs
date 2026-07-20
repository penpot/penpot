use crate::math::Rect as MathRect;
use crate::shapes::ImageFill;
use crate::uuid::Uuid;

use crate::error::Result;
use crate::get_gpu_state;
use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use skia_safe::{self as skia, Codec, ISize, Size};
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

pub struct ImageStore {
    images: HashMap<(Uuid, bool), StoredImage>,
    context: Box<DirectContext>,
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
            context: Box::new(context.clone()),
        }
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

        if let Some(gpu_image) = decode_image(&mut self.context, &raw_data) {
            self.images.insert(key, StoredImage::Gpu(gpu_image));
        } else if let Some((dom, size)) = parse_svg(&raw_data) {
            self.images.insert(
                key,
                StoredImage::Svg {
                    dom,
                    size,
                    raster: None,
                },
            );
        } else {
            // The lazy re-decode in `get_internal` only retries raster codecs,
            // so SVGs that fail to parse here stay raw.
            self.images.insert(key, StoredImage::Raw(raw_data));
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
        let image = create_image_from_gl_texture(&mut self.context, texture_id, width, height)?;
        self.images.insert(key, StoredImage::Gpu(image));

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
        let gpu_image = self.get(id)?.clone();
        gpu_image.make_non_texture_image(self.context.as_mut())
    }

    /// Vector access for SVG images: the fill render path draws the DOM
    /// directly so it stays crisp at any zoom level.
    pub fn get_svg(&self, id: &Uuid) -> Option<(&skia::svg::Dom, Size)> {
        let entry = self
            .images
            .get(&(*id, false))
            .or_else(|| self.images.get(&(*id, true)))?;
        match entry {
            StoredImage::Svg { dom, size, .. } => Some((dom, *size)),
            _ => None,
        }
    }

    fn get_internal(&mut self, id: &Uuid, is_thumbnail: bool) -> Option<&Image> {
        let key = (*id, is_thumbnail);
        // Use entry API to mutate the HashMap in-place if needed
        if let Some(entry) = self.images.get_mut(&key) {
            match entry {
                StoredImage::Gpu(ref img) => Some(img),
                StoredImage::Raw(raw_data) => {
                    let gpu_image = decode_image(&mut self.context, raw_data)?;
                    *entry = StoredImage::Gpu(gpu_image);

                    if let StoredImage::Gpu(ref img) = entry {
                        Some(img)
                    } else {
                        None
                    }
                }
                StoredImage::Svg { dom, size, raster } => {
                    if raster.is_none() {
                        *raster = rasterize_svg(&mut self.context, dom, *size);
                    }
                    raster.as_ref()
                }
            }
        } else {
            None
        }
    }
}
