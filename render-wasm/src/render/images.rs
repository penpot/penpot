use crate::math::Rect as MathRect;
use crate::uuid::Uuid;

use skia_safe as skia;
use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use std::collections::HashMap;

pub type Image = skia::Image;

pub struct ImageStore {
    images: HashMap<Uuid, Image>,
}

impl ImageStore {
    pub fn new() -> Self {
        Self {
            images: HashMap::with_capacity(2048),
        }
    }

    pub fn add(
        &mut self,
        id: Uuid,
        image_data: &[u8],
        context: &mut DirectContext,
    ) -> Result<(), String> {
        let image_data = unsafe { skia::Data::new_bytes(image_data) };
        let image = Image::from_encoded(image_data).ok_or("Error decoding image data")?;

        let width = image.width();
        let height = image.height();

        let image_info = skia::ImageInfo::new_n32_premul((width, height), None);
        let mut surface = surfaces::render_target(
            context,
            Budgeted::Yes,
            &image_info,
            None,
            None,
            None,
            None,
            false,
        )
        .ok_or("Can't create GPU surface")?;

        let dest_rect = MathRect::from_xywh(0.0, 0.0, width as f32, height as f32);

        surface
            .canvas()
            .draw_image_rect(&image, None, dest_rect, &skia::Paint::default());

        let gpu_image = surface.image_snapshot();

        // This way we store the image as a texture
        self.images.insert(id, gpu_image);
        Ok(())
    }

    pub fn contains(&mut self, id: &Uuid) -> bool {
        self.images.contains_key(id)
    }

    pub fn get(&self, id: &Uuid) -> Option<&Image> {
        self.images.get(id)
    }
}
