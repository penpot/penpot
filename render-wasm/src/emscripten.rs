#[macro_export]
macro_rules! run_script {
    ($s:expr) => {{
        extern "C" {
            fn emscripten_run_script(script: *const i8);
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
            fn emscripten_run_script_int(script: *const i8) -> i32;
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
            fn emscripten_get_now() -> f64;
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

#[macro_export]
macro_rules! debugger {
    () => {{
        extern "C" {
            fn emscripten_debugger();
        }
        unsafe { emscripten_debugger() }
    }}
}

#[repr(u32)]
#[allow(dead_code)]
pub enum Log {
    Default = 1,
    Warn = 2,
    Error = 4,
    CStack = 8,
    JSStack = 16,
    Demangle = 32,
    NoPaths = 64,
    FuncParams = 128,
}

#[macro_export]
macro_rules! log {
    ($flags:expr, $($arg:tt)*) => {{
        #[cfg(debug_assertions)]
        {
            extern "C" {
                fn emscripten_log(flags: u32, message: *const std::os::raw::c_char);
            }
            use std::ffi::CString;
            let msg = format!($($arg)*);
            let c_msg = CString::new(msg).unwrap();
            let flags = $flags as u32;
            unsafe {
                emscripten_log(flags, c_msg.as_ptr());
            }
        }
    }}
}

#[allow(unused_imports)]
pub use log;

#[allow(unused_imports)]
pub use debugger;

#[allow(unused_imports)]
pub use run_script;

#[allow(unused_imports)]
pub use run_script_int;

#[allow(unused_imports)]
pub use get_now;

#[allow(unused_imports)]
pub use init_gl;

