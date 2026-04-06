use crate::shapes::GlassEffect;
use crate::{with_current_shape_mut, STATE};

#[no_mangle]
pub extern "C" fn set_shape_glass(
    radius: f32,
    refraction: f32,
    depth: f32,
    dispersion: f32,
    light_intensity: f32,
    light_angle: f32,
    hidden: u8,
) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_glass(Some(GlassEffect {
            radius,
            refraction,
            depth,
            dispersion,
            light_intensity,
            light_angle,
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
