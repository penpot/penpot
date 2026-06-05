use macros::{wasm_error, ToJs};

use crate::get_render_state;
use crate::mem;
use crate::render::FontStore;
use crate::shapes::{FontFamily, FontStyle};
use crate::utils::uuid_from_u32_quartet;
use crate::with_state;

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
#[wasm_error]
pub extern "C" fn store_font(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    weight: u32,
    style: u8,
    is_emoji: bool,
    is_fallback: bool,
) -> Result<()> {
    let id = uuid_from_u32_quartet(a, b, c, d);
    let font_bytes = mem::bytes();
    let font_style = RawFontStyle::from(style);

    let family = FontFamily::new(id, weight, font_style.into());
    let _ = get_render_state()
        .fonts_mut()
        .add(family, &font_bytes, is_emoji, is_fallback);

    mem::free_bytes()?;
    Ok(())
}

/// Resets the font store to its default state, dropping every font uploaded via
/// `store_font`. A headless host that reuses a single WASM instance across
/// requests must call this per render so fonts don't accumulate unbounded.
#[no_mangle]
#[wasm_error]
pub extern "C" fn clear_fonts() -> Result<()> {
    *get_render_state().fonts_mut() = FontStore::try_new()?;
    Ok(())
}

/// Distinct font families used by the subtree, for on-demand provisioning by a
/// headless host. Buffer (LE): `[count u32]` then `count` ×
/// `[uuid 16B][weight u32][style u32]` (0=normal, 1=italic); uuid bytes match
/// the quartet `store_font` consumes.
#[no_mangle]
pub extern "C" fn get_fonts_for_shape(a: u32, b: u32, c: u32, d: u32) -> *mut u8 {
    let id = uuid_from_u32_quartet(a, b, c, d);
    let families = with_state!(state, { state.fonts_used_by_shape(&id) });

    let mut buf = Vec::with_capacity(4 + families.len() * 24);
    buf.extend_from_slice(&(families.len() as u32).to_le_bytes());
    for family in families {
        let id_bytes: [u8; 16] = family.id().into();
        buf.extend_from_slice(&id_bytes);
        buf.extend_from_slice(&family.weight().to_le_bytes());
        buf.extend_from_slice(&(family.style() as u32).to_le_bytes());
    }
    mem::write_bytes(buf)
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
    let id = uuid_from_u32_quartet(a, b, c, d);
    let font_style = RawFontStyle::from(style);
    let family = FontFamily::new(id, weight, font_style.into());
    let res = get_render_state().fonts().has_family(&family, is_emoji);

    res
}
