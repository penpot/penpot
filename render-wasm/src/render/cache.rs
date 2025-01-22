use super::{Image, Viewbox};
use skia::Contains;
use skia_safe as skia;

pub(crate) struct CachedSurfaceImage {
    pub image: Image,
    pub viewbox: Viewbox,
    pub has_all_shapes: bool,
}

impl CachedSurfaceImage {
    pub fn is_dirty_for_zooming(&mut self, viewbox: &Viewbox) -> bool {
        !self.has_all_shapes && !self.viewbox.area.contains(viewbox.area)
    }

    pub fn is_dirty_for_panning(&mut self, _viewbox: &Viewbox) -> bool {
        !self.has_all_shapes
    }
}
