use macros::wasm_error;
use skia_safe::{self as skia};

#[allow(unused_imports)]
use crate::error::{Error, Result};
use crate::get_render_state;

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_visible(visible: u32) -> Result<()> {
    get_render_state().rulers.visible = visible != 0;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_offsets(offset_x: f32, offset_y: f32) -> Result<()> {
    let r = &mut get_render_state().rulers;
    r.offset_x = offset_x;
    r.offset_y = offset_y;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_selection(has: u32, x: f32, y: f32, w: f32, h: f32) -> Result<()> {
    get_render_state()
        .rulers
        .set_selection(has != 0, x, y, w, h);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_colors(bg: u32, border: u32, label: u32, accent: u32) -> Result<()> {
    let r = &mut get_render_state().rulers;
    r.bg_color = skia::Color::new(bg);
    r.border_color = skia::Color::new(border);
    r.label_color = skia::Color::new(label);
    r.accent_color = skia::Color::new(accent);
    Ok(())
}
