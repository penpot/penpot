use std::alloc::{alloc, Layout};
use std::ptr;

#[allow(unused_imports)]
use crate::error::{Error, Result};
use crate::mem::{BUFFERU8, LAYOUT_ALIGN};
use macros::wasm_error;

#[no_mangle]
#[wasm_error]
pub extern "C" fn alloc_bytes(len: usize) -> Result<*mut u8> {
    unsafe {
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
        #[allow(static_mut_refs)]
        if BUFFERU8.is_some() {
            return Err(Error::CriticalError("Bytes already allocated".to_string()));
        }

        let layout = Layout::from_size_align_unchecked(len, LAYOUT_ALIGN);
        let ptr = alloc(layout);
        if ptr.is_null() {
            return Err(Error::CriticalError("Allocation failed".to_string()));
        }
        // TODO: Maybe this could be removed.
        ptr::write_bytes(ptr, 0, len);
        BUFFERU8 = Some(Vec::from_raw_parts(ptr, len, len));
        Ok(ptr)
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn free_bytes() -> Result<()> {
    crate::mem::free_bytes()?;
    Ok(())
}
