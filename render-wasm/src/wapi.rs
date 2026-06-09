#[macro_export]
macro_rules! notify_tiles_render_complete {
    () => {{
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_notifyTilesRenderComplete();
        }

        #[cfg(target_arch = "wasm32")]
        unsafe {
            wapi_notifyTilesRenderComplete()
        };
    }};
}

pub use notify_tiles_render_complete;
