//! GPU-free scene builders and render helpers for SVG export tests.

use skia_safe as skia;

use crate::render::RenderResources;
use crate::shapes::{Fill, Frame, Group, Rect, SolidColor, Type};
use crate::state::ShapesPool;
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;

use super::render_tree_to_svg;

/// Deterministic UUID from a small integer, keeping snapshots stable.
pub(super) fn uid(n: u32) -> Uuid {
    uuid_from_u32_quartet(0, 0, 0, n)
}

/// Adds a solid-filled rectangle to the pool.
pub(super) fn add_solid_rect(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    color: skia::Color,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Rect(Rect::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(color))]);
}

/// Adds a solid-filled frame (board) to the pool.
pub(super) fn add_frame(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    color: skia::Color,
    clip: bool,
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Frame(Frame::default()));
    shape.set_selrect(l, t, r, b);
    shape.set_fills(vec![Fill::Solid(SolidColor(color))]);
    shape.set_clip(clip);
}

/// Adds an empty (unmasked) group.
pub(super) fn add_group(
    pool: &mut ShapesPool,
    id: Uuid,
    parent: Uuid,
    (l, t, r, b): (f32, f32, f32, f32),
    children: &[Uuid],
) {
    let shape = pool.add_shape(id);
    shape.set_parent(parent);
    shape.set_shape_type(Type::Group(Group { masked: false }));
    shape.set_selrect(l, t, r, b);
    for child in children {
        shape.add_child(*child);
    }
}

pub(super) fn render(pool: &ShapesPool, root: Uuid) -> String {
    let mut resources = RenderResources::try_new_headless().expect("headless resources");
    let bytes = render_tree_to_svg(&mut resources, &root, pool, 1.0).expect("svg export");
    String::from_utf8(bytes).expect("utf8 svg")
}
