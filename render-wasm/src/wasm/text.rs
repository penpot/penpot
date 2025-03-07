use crate::mem;
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
pub extern "C" fn add_text_leaf() {
    let bytes = mem::bytes();
    let text = unsafe {
        String::from_utf8_unchecked(bytes) // TODO: handle this error
    };

    with_current_shape!(state, |shape: &mut Shape| {
        let res = shape.add_text_leaf(&text);
        if let Err(err) = res {
            eprintln!("{}", err);
        }
    });
}
