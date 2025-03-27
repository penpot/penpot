use crate::mem;
use crate::shapes::FontFamily;
use crate::utils::uuid_from_u32_quartet;
use crate::with_current_shape;
use crate::STATE;

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_text();
    });
}

#[no_mangle]
pub extern "C" fn add_text_paragraph() {
    with_current_shape!(state, |shape: &mut Shape| {
        let res = shape.add_text_paragraph();
        if let Err(err) = res {
            eprintln!("{}", err);
        }
    });
}

#[no_mangle]
pub extern "C" fn add_text_leaf(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    weight: u32,
    style: u8,
    font_size: f32,
) {
    let font_id = uuid_from_u32_quartet(a, b, c, d);
    let font_family = FontFamily::new(font_id, weight, style.into());
    let bytes = mem::bytes();

    let text = unsafe {
        String::from_utf8_unchecked(bytes) // TODO: handle this error
    };

    with_current_shape!(state, |shape: &mut Shape| {
        let res = shape.add_text_leaf(text, font_family, font_size);
        if let Err(err) = res {
            eprintln!("{}", err);
        }
    });
}
