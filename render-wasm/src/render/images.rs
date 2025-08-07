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
    images: HashMap<Uuid, StoredImage>,
    context: Box<DirectContext>,
}

impl ImageStore {
    pub fn new(context: DirectContext) -> Self {
        Self {
            images: HashMap::with_capacity(2048),
            context: Box::new(context),
        }
    }

    pub fn add(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        if self.images.contains_key(&id) {
            return Err("Image already exists".to_string());
        }

        self.images
            .insert(id, StoredImage::Raw(image_data.to_vec()));
        Ok(())
    }

    pub fn contains(&self, id: &Uuid) -> bool {
        self.images.contains_key(id)
    }

    pub fn get(&mut self, id: &Uuid) -> Option<&Image> {
        // Use entry API to mutate the HashMap in-place if needed
        if let Some(entry) = self.images.get_mut(id) {
            match entry {
                StoredImage::Gpu(ref img) => Some(img),
                StoredImage::Raw(raw_data) => {
                    // Decode and upload to GPU
                    let data = unsafe { skia::Data::new_bytes(raw_data) };
                    let codec = Codec::from_data(data.clone())?;
                    let image = Image::from_encoded(data.clone())?;

                    let mut dimensions = codec.dimensions();
                    if codec.origin().swaps_width_height() {
                        dimensions.width = codec.dimensions().height;
                        dimensions.height = codec.dimensions().width;
                    }

                    let image_info = skia::ImageInfo::new_n32_premul(dimensions, None);

                    let mut surface = surfaces::render_target(
                        &mut self.context,
                        Budgeted::Yes,
                        &image_info,
                        None,
                        None,
                        None,
                        true,
                        false,
                    )?;

                    let dest_rect: MathRect = MathRect::from_xywh(
                        0.0,
                        0.0,
                        dimensions.width as f32,
                        dimensions.height as f32,
                    );

                    surface.canvas().draw_image_rect(
                        &image,
                        None,
                        dest_rect,
                        &skia::Paint::default(),
                    );

                    let gpu_image = surface.image_snapshot();

                    // Replace raw data with GPU image
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
