use std::sync::Mutex;

use crate::error::{Error, Result, CRITICAL_ERROR};

pub const LAYOUT_ALIGN: usize = 4;

pub static BUFFERU8: Mutex<Option<Vec<u8>>> = Mutex::new(None);
pub static BUFFER_ERROR: Mutex<u8> = Mutex::new(0x00);

pub fn clear_error_code() {
    let mut guard = BUFFER_ERROR.lock().unwrap();
    *guard = 0x00;
}

/// Sets the error buffer from a byte. Used by #[wasm_error] when E: Into<u8>.
pub fn set_error_code(code: u8) {
    let mut guard = BUFFER_ERROR.lock().unwrap();
    *guard = code;
}

#[no_mangle]
pub extern "C" fn read_error_code() -> u8 {
    if let Ok(guard) = BUFFER_ERROR.lock() {
        *guard
    } else {
        CRITICAL_ERROR
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

pub fn bytes() -> Vec<u8> {
    let mut guard = BUFFERU8.lock().unwrap();
    guard.take().expect("Buffer is not initialized")
}

pub fn bytes_or_empty() -> Vec<u8> {
    let mut guard = BUFFERU8.lock().unwrap();
    guard.take().unwrap_or_default()
}

pub fn free_bytes() -> Result<()> {
    let mut guard = BUFFERU8
        .lock()
        .map_err(|_| Error::CriticalError("Failed to lock buffer".to_string()))?;
    *guard = None;
    std::mem::drop(guard);
    Ok(())
}

pub trait SerializableResult: From<Self::BytesType> + Into<Self::BytesType> {
    type BytesType;
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
