use skia_safe as skia;

use crate::mem;
use crate::shapes;
use crate::utils::uuid_from_u32_quartet;
use crate::with_current_shape;
use crate::STATE;

#[no_mangle]
pub extern "C" fn add_shape_solid_fill(raw_color: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        shape.add_fill(shapes::Fill::Solid(color));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_linear_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");
        shape.add_fill(shapes::Fill::LinearGradient(gradient));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_radial_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");
        shape.add_fill(shapes::Fill::RadialGradient(gradient));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_image_fill(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    alpha: f32,
    width: i32,
    height: i32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_fill(shapes::Fill::new_image_fill(
            id,
            (alpha * 0xff as f32).floor() as u8,
            (width, height),
        ));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_fills();
    });
}
