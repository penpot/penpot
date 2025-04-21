use crate::mem;
use crate::shapes::RawTextData;
use crate::with_current_shape;
use crate::STATE;

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
