use crate::mem;
use crate::utils::uuid_from_u32_quartet;
use crate::with_state;
use crate::STATE;

use crate::shapes::FontFamily;

#[no_mangle]
pub extern "C" fn store_font(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    weight: u32,
    style: u8,
    is_emoji: bool,
    is_fallback: bool,
) {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let font_bytes = mem::bytes();

        let family = FontFamily::new(id, weight, style.into());
        let _ = state
            .render_state()
            .fonts_mut()
            .add(family, &font_bytes, is_emoji, is_fallback);

        mem::free_bytes();
    });
}

#[no_mangle]
pub extern "C" fn is_font_uploaded(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    weight: u32,
    style: u8,
    is_emoji: bool,
) -> bool {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let family = FontFamily::new(id, weight, style.into());
        let res = state.render_state().fonts().has_family(&family, is_emoji);

        res
    })
}
