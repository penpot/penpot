use std::alloc::{alloc, Layout};
use std::ptr;

#[allow(unused_imports)]
use crate::error::{Error, Result};
use crate::mem::{BUFFERU8, LAYOUT_ALIGN};
use macros::wasm_error;

#[no_mangle]
#[wasm_error]
pub extern "C" fn alloc_bytes(len: usize) -> Result<*mut u8> {
    let mut guard = BUFFERU8
        .lock()
        .map_err(|_| Error::CriticalError("Failed to lock buffer".to_string()))?;

    if guard.is_some() {
        return Err(Error::CriticalError("Bytes already allocated".to_string()));
    }

    unsafe {
        let layout = Layout::from_size_align_unchecked(len, LAYOUT_ALIGN);
        let ptr = alloc(layout);
        if ptr.is_null() {
            return Err(Error::CriticalError("Allocation failed".to_string()));
        }
        // TODO: Maybe this could be removed.
        ptr::write_bytes(ptr, 0, len);
        *guard = Some(Vec::from_raw_parts(ptr, len, len));
        Ok(ptr)
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn free_bytes() -> Result<()> {
    crate::mem::free_bytes()?;
    Ok(())
}
