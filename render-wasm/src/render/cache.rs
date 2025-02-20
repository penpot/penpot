use super::{Image, Viewbox};
use skia::Contains;
use skia_safe as skia;

pub(crate) struct CachedSurfaceImage {
    pub image: Image,
    pub viewbox: Viewbox,
    pub invalid: bool,
    pub has_all_shapes: bool,
}

impl CachedSurfaceImage {
    pub fn invalidate_if_dirty(&mut self, viewbox: &Viewbox) {
        if !self.has_all_shapes && !self.viewbox.area.contains(viewbox.area) {
            self.invalid = true;
        }
    }
}
