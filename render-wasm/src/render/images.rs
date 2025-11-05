use crate::math::Rect as MathRect;
use crate::shapes::ImageFill;
use crate::uuid::Uuid;

use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use skia_safe::{self as skia, Codec, ISize};
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
) -> Result<Image, String> {
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
    .ok_or("Failed to create Skia image from GL texture")?;

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

impl ImageStore {
    pub fn new(context: DirectContext) -> Self {
        Self {
            images: HashMap::with_capacity(2048),
            context: Box::new(context),
        }
    }

    pub fn add(&mut self, id: Uuid, is_thumbnail: bool, image_data: &[u8]) -> Result<(), String> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Err("Image already exists".to_string());
        }

        let raw_data = image_data.to_vec();

        if let Some(gpu_image) = decode_image(&mut self.context, &raw_data) {
            self.images.insert(key, StoredImage::Gpu(gpu_image));
        } else {
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
    ) -> Result<(), String> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Err("Image already exists".to_string());
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
            }
        } else {
            None
        }
    }
}
