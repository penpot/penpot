#[macro_export]
macro_rules! request_animation_frame {
    () => {
        #[cfg(target_arch = "wasm32")]
        {
            extern "C" {
                pub fn wapi_requestAnimationFrame() -> i32;
            }
            unsafe { wapi_requestAnimationFrame() }
        }
    };
}

#[macro_export]
macro_rules! cancel_animation_frame {
    ($frame_id:expr) => {
        #[cfg(target_arch = "wasm32")]
        {
            extern "C" {
                pub fn wapi_cancelAnimationFrame(frame_id: i32);
            }
            unsafe { wapi_cancelAnimationFrame($frame_id) }
        }
    };
}

pub use cancel_animation_frame;
pub use request_animation_frame;
