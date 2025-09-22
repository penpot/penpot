use crate::math::{Matrix, Point};
use crate::mem;
use crate::shapes::{GrowType, RawTextData, Type};
use crate::{with_current_shape, with_current_shape_mut, with_state_mut_current_shape, STATE};

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_text();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_text_content() {
    let bytes = mem::bytes();
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let raw_text_data = RawTextData::from(&bytes);
        shape
            .add_paragraph(raw_text_data.paragraph)
            .expect("Failed to add paragraph");
    });
    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_shape_grow_type(grow_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.set_grow_type(GrowType::from(grow_type));
        } else {
            panic!("Trying to update grow type in a shape that it's not a text shape");
        }
    });
}

#[no_mangle]
pub extern "C" fn get_text_dimensions() -> *mut u8 {
    let mut ptr = std::ptr::null_mut();
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(content) = &mut shape.shape_type {
            let text_content_size = content.update_layout(shape.selrect);

            let mut bytes = vec![0; 12];
            bytes[0..4].clone_from_slice(&text_content_size.width.to_le_bytes());
            bytes[4..8].clone_from_slice(&text_content_size.height.to_le_bytes());
            bytes[8..12].clone_from_slice(&text_content_size.max_width.to_le_bytes());
            ptr = mem::write_bytes(bytes)
        }
    });

    // FIXME: Esto no me mola mucho pero no he encontrado
    // una solución mejor.
    ptr
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.update_layout(shape.selrect);
        }
    });
}

#[no_mangle]
pub extern "C" fn get_caret_position_at(x: f32, y: f32) -> i32 {
    with_state_mut_current_shape!(state, |shape: &Shape| {
        if let Type::Text(text_content) = &shape.shape_type {
            let mut matrix = Matrix::new_identity();
            let shape_matrix = shape.get_concatenated_matrix(&state.shapes);
            let view_matrix = state.render_state.viewbox.get_matrix();
            if let Some(inv_view_matrix) = view_matrix.invert() {
                matrix.post_concat(&inv_view_matrix);
                matrix.post_concat(&shape_matrix);
                let mapped_point = matrix.map_point(Point::new(x, y));
                if let Some(text_position_with_affinity) =
                    text_content.get_caret_position_at(&mapped_point)
                {
                    state
                        .text_editor_state_mut()
                        .set_caret_position_from(text_position_with_affinity);
                    return text_position_with_affinity.position_with_affinity.position;
                }
            }
        } else {
            panic!("Trying to update grow type in a shape that it's not a text shape");
        }
    });
    return -1;
}
