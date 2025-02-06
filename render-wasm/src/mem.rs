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

pub fn write_bytes(bytes: Vec<u8>) -> *mut u8 {
    if unsafe { BUFFERU8.is_some() } {
        panic!("Bytes already allocated");
    }

    let mut buffer = Box::new(bytes);
    let ptr = buffer.as_mut_ptr();

    unsafe { BUFFERU8 = Some(buffer) };
    return ptr;
}

#[no_mangle]
pub extern "C" fn free_bytes() {
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

pub trait SerializableResult {
    type BytesType;
    fn from_bytes(bytes: Self::BytesType) -> Self;
    fn as_bytes(&self) -> Self::BytesType;
    fn clone_to_slice(&self, slice: &mut [u8]);
}

/*
  Returns an array in the heap. The first 4 bytes is always the size
  of the array. Then the items are serialized one after the other
  by the implementation of SerializableResult trait
*/
pub fn write_vec<T: SerializableResult>(result: Vec<T>) -> *mut u8 {
    let elem_size = size_of::<T>();
    let bytes_len = 4 + result.len() * elem_size;
    let mut result_bytes = Vec::<u8>::with_capacity(bytes_len);

    result_bytes.resize(bytes_len, 0);
    result_bytes[0..4].clone_from_slice(&result.len().to_le_bytes());

    for i in 0..result.len() {
        let base = 4 + i * elem_size;
        result[i].clone_to_slice(&mut result_bytes[base..base + elem_size]);
    }

    write_bytes(result_bytes)
}
