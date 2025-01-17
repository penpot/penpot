static mut BUFFERU8: Option<Box<Vec<u8>>> = None;

#[no_mangle]
pub extern "C" fn alloc_bytes(len: usize) -> *mut u8 {
    // TODO: Figure out how to deal with Result<T> from Emscripten
    if unsafe { BUFFERU8.is_some() } {
        panic!("Bytes already allocated");
    }

    let mut buffer = Box::new(vec![0u8; len]);
    let ptr = buffer.as_mut_ptr();

    unsafe { BUFFERU8 = Some(buffer) };
    return ptr;
}

pub fn free_bytes() {
    if unsafe { BUFFERU8.is_some() } {
        let buffer = unsafe { BUFFERU8.take() }.expect("uninitialized buffer");
        std::mem::drop(buffer);
    }
}

pub fn buffer_ptr() -> *mut u8 {
    let buffer = unsafe { BUFFERU8.as_mut() }.expect("uninitializied buffer");
    buffer.as_mut_ptr()
}

pub fn bytes() -> Vec<u8> {
    let buffer = unsafe { BUFFERU8.take() }.expect("uninitialized buffer");
    *buffer
}
