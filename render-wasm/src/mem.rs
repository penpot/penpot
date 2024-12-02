#[no_mangle]
pub extern "C" fn alloc_bytes(len: usize) -> *mut u8 {
    // create a new mutable buffer with capacity `len`
    let mut buf: Vec<u8> = Vec::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    // take ownership of the memory block and ensure the its destructor is not
    // called when the object goes out of scope at the end of the function
    std::mem::forget(buf);
    return ptr;
}

pub fn free(ptr: *mut u8, len: usize) {
    unsafe {
        let buf = Vec::<u8>::from_raw_parts(ptr, len, len);
        std::mem::forget(buf);
    }
}
