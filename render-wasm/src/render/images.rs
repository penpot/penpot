use skia_safe as skia;
use std::collections::HashMap;
use uuid::Uuid;

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

    pub fn add(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        let image_data = skia::Data::new_copy(image_data);
        let image = Image::from_encoded(image_data).ok_or("Error decoding image data")?;

        self.images.insert(id, image);
        Ok(())
    }

    pub fn contains(&mut self, id: &Uuid) -> bool {
        self.images.contains_key(id)
    }

    pub fn get(&self, id: &Uuid) -> Option<&Image> {
        self.images.get(id)
    }
}
