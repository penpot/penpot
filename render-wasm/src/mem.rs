use std::alloc::{alloc, Layout};
use std::ptr;
use std::sync::Mutex;

const LAYOUT_ALIGN: usize = 4;

static BUFFERU8: Mutex<Option<Vec<u8>>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn alloc_bytes(len: usize) -> *mut u8 {
    let mut guard = BUFFERU8.lock().unwrap();

    if guard.is_some() {
        panic!("Bytes already allocated");
    }

    unsafe {
        let layout = Layout::from_size_align_unchecked(len, LAYOUT_ALIGN);
        let ptr = alloc(layout);
        if ptr.is_null() {
            panic!("Allocation failed");
        }
        // TODO: Maybe this could be removed.
        ptr::write_bytes(ptr, 0, len);
        *guard = Some(Vec::from_raw_parts(ptr, len, len));
        ptr
    }
}

pub fn write_bytes(mut bytes: Vec<u8>) -> *mut u8 {
    let mut guard = BUFFERU8.lock().unwrap();

    if guard.is_some() {
        panic!("Bytes already allocated");
    }

    let ptr = bytes.as_mut_ptr();

    *guard = Some(bytes);
    ptr
}

#[no_mangle]
pub extern "C" fn free_bytes() {
    let mut guard = BUFFERU8.lock().unwrap();
    *guard = None;
    std::mem::drop(guard);
}

pub fn bytes() -> Vec<u8> {
    let mut guard = BUFFERU8.lock().unwrap();
    guard.take().expect("Buffer is not initialized")
}

pub fn bytes_or_empty() -> Vec<u8> {
    let mut guard = BUFFERU8.lock().unwrap();
    guard.take().unwrap_or_default()
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
    let elem_size = size_of::<T::BytesType>();
    let bytes_len = 4 + result.len() * elem_size;
    let mut result_bytes = vec![0; bytes_len];

    result_bytes[0..4].clone_from_slice(&result.len().to_le_bytes());

    for (i, item) in result.iter().enumerate() {
        let base = 4 + i * elem_size;
        item.clone_to_slice(&mut result_bytes[base..base + elem_size]);
    }

    write_bytes(result_bytes)
}
