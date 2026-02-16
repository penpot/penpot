use macros::ToJs;

use crate::mem;
use crate::shapes::{FontFamily, FontStyle};
use crate::utils::uuid_from_u32_quartet;
use crate::with_state_mut;
use crate::STATE;

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawFontStyle {
    Normal = 0,
    Italic = 1,
}

impl From<u8> for RawFontStyle {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawFontStyle> for FontStyle {
    fn from(value: RawFontStyle) -> Self {
        match value {
            RawFontStyle::Normal => FontStyle::Normal,
            RawFontStyle::Italic => FontStyle::Italic,
        }
    }
}

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
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let font_bytes = mem::bytes();
        let font_style = RawFontStyle::from(style);

        let family = FontFamily::new(id, weight, font_style.into());
        let _ =
            state
                .render_state_mut()
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
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let font_style = RawFontStyle::from(style);
        let family = FontFamily::new(id, weight, font_style.into());
        let res = state.render_state().fonts().has_family(&family, is_emoji);

        res
    })
}
