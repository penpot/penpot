#[macro_export]
macro_rules! request_animation_frame {
    () => {{
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_requestAnimationFrame() -> i32;
        }

        #[cfg(target_arch = "wasm32")]
        let result = unsafe { wapi_requestAnimationFrame() };
        #[cfg(not(target_arch = "wasm32"))]
        let result = 0;

        result
    }};
}

#[macro_export]
macro_rules! cancel_animation_frame {
    ($frame_id:expr) => {
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_cancelAnimationFrame(frame_id: i32);
        }

        {
            let frame_id = $frame_id;
            #[cfg(target_arch = "wasm32")]
            unsafe {
                wapi_cancelAnimationFrame(frame_id)
            };
            #[cfg(not(target_arch = "wasm32"))]
            let _ = frame_id;
        }
    };
}

pub use cancel_animation_frame;
pub use request_animation_frame;
