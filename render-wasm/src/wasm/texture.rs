use crate::shapes::TextureEffect;
use crate::{with_current_shape_mut, STATE};

#[no_mangle]
pub extern "C" fn set_shape_texture(noise_size: f32, radius: f32, clip_to_shape: bool, hidden: bool) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_texture(Some(TextureEffect::new(noise_size, radius, clip_to_shape, hidden)));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_texture() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_texture(None);
    });
}
