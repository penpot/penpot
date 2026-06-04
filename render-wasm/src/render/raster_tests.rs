//! GPU-free raster export tests. Run on the host (not wasm32), never call
//! `init()`/`gpu_init()` — a headless `RenderState` needs no GL context.

use super::*;
use crate::shapes::{Fill, SolidColor};
use crate::state::ShapesPool;

const PNG_MAGIC: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];

fn rect_pool(id: Uuid, color: skia::Color) -> ShapesPool {
    let mut pool = ShapesPool::new();
    let shape = pool.add_shape(id);
    shape.selrect = skia::Rect::from_xywh(0.0, 0.0, 100.0, 50.0);
    shape.add_fill(Fill::Solid(SolidColor(color)));
    pool
}

#[test]
fn renders_solid_rect_to_png_without_gpu() {
    let mut rs = RenderState::try_new_headless(64, 64).expect("headless render state");
    let id = Uuid::new_v4();
    let pool = rect_pool(id, skia::Color::RED);

    let (png, width, height) = render_to_raster(&mut rs, &id, &pool, 1.0).expect("raster render");

    assert_eq!((width, height), (100, 50), "output dimensions");
    assert!(png.len() > PNG_MAGIC.len(), "non-empty PNG");
    assert_eq!(&png[..8], &PNG_MAGIC, "valid PNG signature");
}

#[test]
fn honors_scale() {
    let mut rs = RenderState::try_new_headless(64, 64).expect("headless render state");
    let id = Uuid::new_v4();
    let pool = rect_pool(id, skia::Color::BLUE);

    let (_png, width, height) = render_to_raster(&mut rs, &id, &pool, 2.0).expect("raster render");

    assert_eq!((width, height), (200, 100), "dimensions scale with `scale`");
}

#[test]
fn missing_shape_returns_empty() {
    let mut rs = RenderState::try_new_headless(64, 64).expect("headless render state");
    let pool = ShapesPool::new();

    let (png, width, height) =
        render_to_raster(&mut rs, &Uuid::new_v4(), &pool, 1.0).expect("render");

    assert!(png.is_empty());
    assert_eq!((width, height), (0, 0));
}
