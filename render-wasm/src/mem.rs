use crate::{error::Result, performance};

pub const LAYOUT_ALIGN: usize = 4;

// Please, read about the #[allow(static_mut_refs)]
//
// If we don't put this allow, the compiler shows a warning like this:
//
// shared references to mutable statics are dangerous; it's undefined behavior
// if the static is mutated or if a mutable reference is created for it while
// the shared reference lives
//
// https://doc.rust-lang.org/edition-guide/rust-2024/static-mut-references.html
//
// But this isn't a problem in a single-threaded environment like WebAssembly
// because access/modification is always sequential, not parallel.
pub static mut BUFFERU8: Option<Vec<u8>> = None;
pub static mut BUFFER_ERROR: u8 = 0x00;

pub fn clear_error_code() {
    unsafe {
        BUFFER_ERROR = 0x00;
    }
}

/// Sets the error buffer from a byte. Used by #[wasm_error] when E: Into<u8>.
pub fn set_error_code(code: u8) {
    unsafe {
        BUFFER_ERROR = code;
    }
}

#[no_mangle]
pub extern "C" fn read_error_code() -> u8 {
    unsafe { BUFFER_ERROR }
}

pub fn write_bytes(mut bytes: Vec<u8>) -> *mut u8 {
    unsafe {
        performance::begin_measure!("write_bytes");
        #[allow(static_mut_refs)]
        if BUFFERU8.is_some() {
            panic!("Bytes already allocated");
        }

        let ptr = bytes.as_mut_ptr();
        BUFFERU8 = Some(bytes);
        performance::end_measure!("write_bytes");
        ptr
    }
}

pub fn bytes() -> Vec<u8> {
    unsafe {
        #[allow(static_mut_refs)]
        BUFFERU8.take().expect("Buffer is not initialized")
    }
}

pub fn bytes_or_empty() -> Vec<u8> {
    unsafe {
        #[allow(static_mut_refs)]
        BUFFERU8.take().unwrap_or_default()
    }
}

pub fn free_bytes() -> Result<()> {
    unsafe {
        BUFFERU8 = None;
    }
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
