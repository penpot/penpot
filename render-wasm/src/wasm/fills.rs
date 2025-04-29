mod gradient;
mod image;
mod solid;

use crate::mem;
use crate::shapes;
use crate::with_current_shape;
use crate::STATE;

#[no_mangle]
pub extern "C" fn add_shape_solid_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let solid_color =
            shapes::SolidColor::try_from(&bytes[..]).expect("Invalid solid color data");

        shape.add_fill(shapes::Fill::Solid(solid_color));
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
pub extern "C" fn add_shape_image_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let image_fill = shapes::ImageFill::try_from(&bytes[..]).expect("Invalid image fill data");

        shape.add_fill(shapes::Fill::Image(image_fill));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_fills();
    });
}
