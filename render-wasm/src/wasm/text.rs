use crate::mem;
use crate::shapes::{auto_height, auto_width, max_width, GrowType, RawTextData, Type};

use crate::STATE;
use crate::{with_current_shape, with_state};

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_text();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_text_content() {
    let bytes = mem::bytes();
    with_current_shape!(state, |shape: &mut Shape| {
        let raw_text_data = RawTextData::from(&bytes);
        shape
            .add_paragraph(raw_text_data.paragraph)
            .expect("Failed to add paragraph");
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_shape_grow_type(grow_type: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.set_grow_type(GrowType::from(grow_type));
        }
    });
}

#[no_mangle]
pub extern "C" fn get_text_dimensions() -> *mut u8 {
    let font_col;
    with_state!(state, {
        font_col = state.render_state.fonts.font_collection();
    });

    let mut width = 0.01;
    let mut height = 0.01;
    let mut m_width = 0.01;
    with_current_shape!(state, |shape: &mut Shape| {
        width = shape.selrect.width();
        height = shape.selrect.height();

        if let Type::Text(content) = &shape.shape_type {
            let paragraphs = content.get_skia_paragraphs(font_col);
            height = auto_height(&paragraphs).ceil();
            m_width = max_width(&paragraphs);
            if content.grow_type() == GrowType::AutoWidth {
                width = auto_width(&paragraphs).ceil();
            }
        }
    });

    let mut bytes = vec![0; 12];
    bytes[0..4].clone_from_slice(&width.to_le_bytes());
    bytes[4..8].clone_from_slice(&height.to_le_bytes());
    bytes[8..12].clone_from_slice(&m_width.to_le_bytes());
    mem::write_bytes(bytes)
}
