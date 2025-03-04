use crate::mem;
use crate::STATE;

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    let state = unsafe { STATE.as_mut() }.expect("Got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.clear_text();
    }
}

#[no_mangle]
pub extern "C" fn add_text_paragraph() {
    let state = unsafe { STATE.as_mut() }.expect("Got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let res = shape.add_text_paragraph();
        if let Err(err) = res {
            eprintln!("{}", err);
        }
    }
}

#[no_mangle]
pub extern "C" fn add_text_leaf() {
    let bytes = mem::bytes();
    let text = unsafe {
        String::from_utf8_unchecked(bytes) // TODO: handle this error
    };

    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let res = shape.add_text_leaf(&text);
        if let Err(err) = res {
            eprintln!("{}", err);
        }
    }
}
