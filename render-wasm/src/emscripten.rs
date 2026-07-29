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
macro_rules! get_now {
    () => {{
        extern "C" {
            pub fn emscripten_get_now() -> f64;
        }
        unsafe { emscripten_get_now() }
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

#[allow(unused_imports)]
pub use run_script;

#[allow(unused_imports)]
pub use run_script_int;

#[allow(unused_imports)]
pub use get_now;

#[allow(unused_imports)]
pub use init_gl;
