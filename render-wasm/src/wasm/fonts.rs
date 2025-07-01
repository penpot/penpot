use crate::mem;
use crate::utils::uuid_from_u32_quartet;
use crate::with_state_mut;
use crate::STATE;

use crate::shapes::FontFamily;

#[no_mangle]
pub extern "C" fn store_font(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    weight: u32,
    style: u8,
    is_emoji: bool,
    is_fallback: bool,
) {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a2, b2, c2, d2);
        let font_bytes = mem::bytes();

        let family = FontFamily::new(id, weight, style.into());
        let _ =
            state
                .render_state_mut()
                .fonts_mut()
                .add(family, &font_bytes, is_emoji, is_fallback);

        mem::free_bytes();
    });

    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a1, b1, c1, d1);
        state.update_tile_for_shape(shape_id);
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
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let family = FontFamily::new(id, weight, style.into());
        let res = state.render_state().fonts().has_family(&family, is_emoji);

        res
    })
}
