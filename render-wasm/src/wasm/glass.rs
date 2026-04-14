use crate::shapes::GlassEffect;
use crate::{with_current_shape_mut, STATE};

#[no_mangle]
pub extern "C" fn set_shape_glass(
    surface_type: i32,
    bezel_width: f32,
    glass_thickness: f32,
    refractive_index: f32,
    specular_angle: f32,
    specular_opacity: f32,
    specular_saturation: f32,
    chromatic_aberration: f32,
    splay: f32,
    tilt_angle: f32,
    edge_boost: f32,
    zoom: f32,
    blur: f32,
    frost: f32,
    hidden: u8,
) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_glass(Some(GlassEffect {
            surface_type,
            bezel_width,
            glass_thickness,
            refractive_index,
            specular_angle,
            specular_opacity,
            specular_saturation,
            chromatic_aberration,
            splay,
            tilt_angle,
            edge_boost,
            zoom,
            blur,
            frost,
            hidden: hidden != 0,
        }));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_glass() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_glass(None);
    });
}
