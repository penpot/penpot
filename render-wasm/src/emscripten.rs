#[macro_export]
macro_rules! run_script {
    ($s:expr) => {{
        extern "C" {
            pub fn emscripten_run_script(script: *const i8);
        }

        match std::ffi::CString::new($s) {
            Ok(cstr) => unsafe { emscripten_run_script(cstr.as_ptr()) },
            Err(e) => panic!("Failed to create CString: {}", e),
        }
    }};
}

#[macro_export]
macro_rules! run_script_int {
    ($s:expr) => {{
        extern "C" {
            pub fn emscripten_run_script_int(script: *const i8) -> i32;
        }

        match std::ffi::CString::new($s) {
            Ok(cstr) => unsafe { emscripten_run_script_int(cstr.as_ptr()) },
            Err(e) => panic!("Failed to create CString: {}", e),
        }
    }};
}

#[macro_export]
macro_rules! init_gl {
    () => {{
        extern "C" {
            fn emscripten_GetProcAddress(
                name: *const ::std::os::raw::c_char,
            ) -> *const ::std::os::raw::c_void;
        }

        unsafe {
            gl::load_with(|addr| {
                let addr = std::ffi::CString::new(addr).unwrap();
                emscripten_GetProcAddress(addr.into_raw() as *const _) as *const _
            });
        }
    }};
}
