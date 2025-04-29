use crate::mem;
use crate::shapes;
use crate::with_current_shape;
use crate::STATE;

#[no_mangle]
pub extern "C" fn add_shape_center_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_center_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_inner_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_inner_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_outer_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_outer_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_solid_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let solid_color =
            shapes::SolidColor::try_from(&bytes[..]).expect("Invalid solid color data");
        shape
            .set_stroke_fill(shapes::Fill::Solid(solid_color))
            .expect("could not add stroke solid fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_linear_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");

        shape
            .set_stroke_fill(shapes::Fill::LinearGradient(gradient))
            .expect("could not add stroke linear gradient fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_radial_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");

        shape
            .set_stroke_fill(shapes::Fill::RadialGradient(gradient))
            .expect("could not add stroke radial gradient fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_image_stroke() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let image_fill = shapes::ImageFill::try_from(&bytes[..]).expect("Invalid image fill data");

        shape
            .set_stroke_fill(shapes::Fill::Image(image_fill))
            .expect("could not add stroke image fill");
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_strokes() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_strokes();
    });
}
