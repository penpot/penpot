use crate::math::Rect as MathRect;
use crate::uuid::Uuid;

use skia_safe as skia;
use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use std::collections::HashMap;

pub type Image = skia::Image;

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
                    let image = Image::from_encoded(data)?;

                    let width = image.width();
                    let height = image.height();

                    let image_info = skia::ImageInfo::new_n32_premul((width, height), None);

                    let mut surface = surfaces::render_target(
                        &mut self.context,
                        Budgeted::Yes,
                        &image_info,
                        None,
                        None,
                        None,
                        None,
                        false,
                    )?;

                    let dest_rect = MathRect::from_xywh(0.0, 0.0, width as f32, height as f32);

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
