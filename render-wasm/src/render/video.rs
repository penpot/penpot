//! Video painted as a composited overlay rather than as part of the tiles.
//!
//! A video frame changes the picture 30 to 60 times a second. Painting it
//! through the normal path means invalidating the shape's tiles that often,
//! and a tile re-raster redraws every shape intersecting it — so the cost of
//! playing a video grows with how crowded the page is around it.
//!
//! Instead the renderer keeps a transparent hole where an eligible video sits
//! and stamps the frame during composition. That only produces the right
//! picture when the shape composites trivially, so anything carrying opacity,
//! a blend mode, a blur, a shadow or a stroke is refused outright and keeps
//! showing its poster frame.

use std::collections::HashSet;

use crate::shapes::{BlendMode, Blur, Fill, Shape, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

/// The pool is not guaranteed acyclic while a file is loading, so the ancestor
/// walk is bounded rather than trusting it.
const MAX_ANCESTOR_DEPTH: u32 = 1024;

/// Why a shape cannot have its video stamped during composition. Reported back
/// to the bridge so the sidebar can name the property that is in the way.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoIneligible {
    NoVideoFill = 1,
    Opacity = 2,
    BlendMode = 3,
    Blur = 4,
    Shadow = 5,
    Stroke = 6,
    Masked = 7,
}

/// The image ids currently backed by a playing video.
#[derive(Default)]
pub struct VideoRegistry {
    images: HashSet<Uuid>,
}

impl VideoRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(&mut self, image_id: Uuid) {
        self.images.insert(image_id);
    }

    pub fn unregister(&mut self, image_id: Uuid) {
        self.images.remove(&image_id);
    }

    pub fn is_empty(&self) -> bool {
        self.images.is_empty()
    }

    pub fn contains(&self, image_id: &Uuid) -> bool {
        self.images.contains(image_id)
    }

    pub fn iter(&self) -> impl Iterator<Item = &Uuid> {
        self.images.iter()
    }
}

/// The video image a shape paints, if it paints one.
pub fn video_fill_id(shape: &Shape, videos: &VideoRegistry) -> Option<Uuid> {
    shape.fills().find_map(|fill| match fill {
        Fill::Image(image) if videos.contains(&image.id()) => Some(image.id()),
        _ => None,
    })
}

fn has_masked_ancestor(shapes: ShapesPoolRef, shape: &Shape) -> bool {
    let mut current = shape.parent_id;
    let mut depth = 0;
    while let Some(parent_id) = current.filter(|id| !id.is_nil()) {
        depth += 1;
        if depth > MAX_ANCESTOR_DEPTH {
            return true;
        }
        let Some(parent) = shapes.get_raw(&parent_id) else {
            return false;
        };
        if matches!(&parent.shape_type, Type::Group(group) if group.masked) {
            return true;
        }
        current = parent.parent_id;
    }
    false
}

/// Whether `shape` can have its video stamped during composition instead of
/// rastered into the tiles.
///
/// The overlay is a flat stamp: it cannot blend, blur, cast a shadow, or sit
/// under an inner stroke. Rotation and corner radii are fine — the stamp
/// carries the shape's transform and clips to its rounded rect.
pub fn video_overlay_eligibility(
    shapes: ShapesPoolRef,
    shape: &Shape,
    videos: &VideoRegistry,
) -> Result<Uuid, VideoIneligible> {
    let image_id = video_fill_id(shape, videos).ok_or(VideoIneligible::NoVideoFill)?;
    shape_overlay_blockers(shapes, shape)?;
    Ok(image_id)
}

/// The properties that stop a shape being stamped, independent of whether a
/// video is attached to it yet. The bridge asks this before starting a video,
/// so the sidebar can refuse with a reason rather than starting and stopping.
pub fn shape_overlay_blockers(
    shapes: ShapesPoolRef,
    shape: &Shape,
) -> Result<(), VideoIneligible> {
    if shape.opacity < 1.0 {
        return Err(VideoIneligible::Opacity);
    }
    if shape.blend_mode != BlendMode::default() {
        return Err(VideoIneligible::BlendMode);
    }
    // Hidden or zero effects paint nothing, so they are no reason to refuse.
    let visible_blur = |blur: &Option<Blur>| {
        blur.map(|blur| !blur.hidden && blur.value > 0.0)
            .unwrap_or(false)
    };
    if visible_blur(&shape.blur) || visible_blur(&shape.background_blur) {
        return Err(VideoIneligible::Blur);
    }
    if shape.shadows.iter().any(|shadow| !shadow.hidden()) {
        return Err(VideoIneligible::Shadow);
    }
    if shape.has_visible_strokes() {
        return Err(VideoIneligible::Stroke);
    }
    if has_masked_ancestor(shapes, shape) {
        return Err(VideoIneligible::Masked);
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::{BlurType, Group, ImageFill, Shadow, ShadowStyle, Stroke, StrokeStyle};
    use crate::state::ShapesPool;

    fn registry(image_id: Uuid) -> VideoRegistry {
        let mut videos = VideoRegistry::new();
        videos.register(image_id);
        videos
    }

    fn pool_with_video(image_id: Uuid) -> (ShapesPool, Uuid) {
        let mut pool = ShapesPool::new();
        let shape_id = Uuid::new_v4();
        let shape = pool.add_shape(shape_id);
        shape.set_shape_type(Type::Rect(Default::default()));
        shape.add_fill(Fill::Image(ImageFill::new(image_id, 255, 10, 10, false)));
        (pool, shape_id)
    }

    fn eligibility(
        pool: &ShapesPool,
        shape_id: Uuid,
        videos: &VideoRegistry,
    ) -> Result<Uuid, VideoIneligible> {
        let shape = pool.get(&shape_id).unwrap();
        video_overlay_eligibility(pool, shape, videos)
    }

    #[test]
    fn a_plain_video_shape_is_eligible() {
        let image_id = Uuid::new_v4();
        let (pool, shape_id) = pool_with_video(image_id);

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Ok(image_id)
        );
    }

    #[test]
    fn rotation_and_corner_radii_stay_eligible() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        {
            let shape = pool.get_mut(&shape_id).unwrap();
            shape.rotation = 33.0;
            shape.set_corners((8.0, 8.0, 8.0, 8.0));
        }

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Ok(image_id)
        );
    }

    #[test]
    fn a_shape_without_a_registered_video_is_not_eligible() {
        let image_id = Uuid::new_v4();
        let (pool, shape_id) = pool_with_video(image_id);

        assert_eq!(
            eligibility(&pool, shape_id, &VideoRegistry::new()),
            Err(VideoIneligible::NoVideoFill)
        );
    }

    #[test]
    fn opacity_below_one_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        pool.get_mut(&shape_id).unwrap().opacity = 0.4;

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::Opacity)
        );
    }

    #[test]
    fn a_blend_mode_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        pool.get_mut(&shape_id).unwrap().blend_mode = BlendMode(skia_safe::BlendMode::Multiply);

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::BlendMode)
        );
    }

    #[test]
    fn a_blur_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        pool.get_mut(&shape_id).unwrap().blur =
            Some(Blur::new(BlurType::LayerBlur, false, 4.0));

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::Blur)
        );
    }

    #[test]
    fn a_shadow_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        pool.get_mut(&shape_id).unwrap().shadows.push(Shadow::new(
            skia_safe::Color::BLACK,
            4.0,
            0.0,
            (2.0, 2.0),
            ShadowStyle::Drop,
            false,
        ));

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::Shadow)
        );
    }

    #[test]
    fn a_stroke_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        pool.get_mut(&shape_id).unwrap().add_stroke(
            Stroke::new_center_stroke(2.0, StrokeStyle::Solid, None, None, None, None),
        );

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::Stroke)
        );
    }

    #[test]
    fn a_masked_ancestor_is_refused() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        let group_id = Uuid::new_v4();
        {
            let group = pool.add_shape(group_id);
            group.set_shape_type(Type::Group(Group { masked: true }));
        }
        pool.get_mut(&shape_id).unwrap().parent_id = Some(group_id);

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Err(VideoIneligible::Masked)
        );
    }

    #[test]
    fn an_unmasked_ancestor_stays_eligible() {
        let image_id = Uuid::new_v4();
        let (mut pool, shape_id) = pool_with_video(image_id);
        let group_id = Uuid::new_v4();
        {
            let group = pool.add_shape(group_id);
            group.set_shape_type(Type::Group(Group { masked: false }));
        }
        pool.get_mut(&shape_id).unwrap().parent_id = Some(group_id);

        assert_eq!(
            eligibility(&pool, shape_id, &registry(image_id)),
            Ok(image_id)
        );
    }
}
