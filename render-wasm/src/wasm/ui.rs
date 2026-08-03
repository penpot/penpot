use crate::mem;
use crate::with_state;
use crate::{
    error::{Error, Result},
    globals::{get_render_state, get_ui_state},
    ui::{Color, Guide, GuideKind},
};
use macros::{wasm_error, ToJs};

const RAW_GUIDE_SIZE: usize = std::mem::size_of::<RawGuide>();

#[repr(u8)]
#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
pub enum RawGuideKind {
    Vertical = 0,
    Horizontal = 1,
}

impl From<u32> for RawGuideKind {
    fn from(value: u32) -> Self {
        match value {
            1 => RawGuideKind::Horizontal,
            _ => RawGuideKind::Vertical,
        }
    }
}

/// Flat, FFI-friendly representation of a guide.
///
/// The layout uses only 32-bit fields so it can be written from ClojureScript
/// straight into the `HEAPU32`/`HEAPF32` views without padding surprises.
///
/// `frame_start` / `frame_end` carry the board clip range (along the guide's
/// line direction). When the guide is not bound to a board they are `NaN`.
#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, PartialEq, Copy)]
pub struct RawGuide {
    kind: u32,
    color: u32,
    position: f32,
    frame_start: f32,
    frame_end: f32,
}

impl From<RawGuide> for Guide {
    fn from(value: RawGuide) -> Self {
        let kind = match RawGuideKind::from(value.kind) {
            RawGuideKind::Vertical => GuideKind::Vertical(value.position),
            RawGuideKind::Horizontal => GuideKind::Horizontal(value.position),
        };
        let frame_range = if value.frame_start.is_nan() || value.frame_end.is_nan() {
            None
        } else {
            Some((value.frame_start, value.frame_end))
        };
        Guide::new(kind, value.color.into(), None, frame_range)
    }
}

impl From<[u8; RAW_GUIDE_SIZE]> for RawGuide {
    fn from(bytes: [u8; RAW_GUIDE_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawGuide {
    type Error = Error;
    fn try_from(bytes: &[u8]) -> Result<Self> {
        let bytes: [u8; RAW_GUIDE_SIZE] = bytes
            .try_into()
            .map_err(|_| Error::CriticalError("Invalid guide data".to_string()))?;
        Ok(RawGuide::from(bytes))
    }
}

fn read_guides_from_bytes(buffer: &[u8], count: usize) -> Result<Vec<Guide>> {
    buffer
        .chunks_exact(RAW_GUIDE_SIZE)
        .take(count)
        .enumerate()
        .map(|(i, bytes)| {
            RawGuide::try_from(bytes).map(|raw_guide| {
                let mut guide: Guide = raw_guide.into();
                guide.index = i;
                guide
            })
        })
        .collect::<Result<Vec<Guide>>>()
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_guides() -> Result<()> {
    let bytes = mem::bytes();
    // The first 4 bytes are a header holding the number of guides.
    let count = u32::from_le_bytes(
        bytes
            .get(0..4)
            .and_then(|slice| slice.try_into().ok())
            .unwrap_or([0; 4]),
    ) as usize;
    let guides = read_guides_from_bytes(&bytes[4..], count)?;
    get_ui_state().set_guides(guides);

    mem::free_bytes()?;

    // Guides are drawn on the UI overlay composited onto `Target`. Refresh the
    // presented frame immediately so removed guides do not linger as stale pixels.
    with_state!(state, {
        get_render_state().present_frame(&state.shapes);
    });

    Ok(())
}

#[wasm_error]
#[no_mangle]
pub extern "C" fn find_guide_at(x: f32, y: f32, zoom: f32, tolerance: f32) -> Result<i32> {
    Ok(get_ui_state()
        .find_guide_at(x, y, zoom, tolerance)
        .map(|guide| guide.index as i32)
        .unwrap_or(-1))
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_visible(visible: u32) -> Result<()> {
    get_ui_state().ruler_state_mut().visible = visible != 0;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_frame_visible(visible: u32) -> Result<()> {
    get_ui_state().ruler_state_mut().frame = visible != 0;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_offsets(offset_x: f32, offset_y: f32) -> Result<()> {
    let r = &mut get_ui_state().ruler_state_mut();
    r.offset_x = offset_x;
    r.offset_y = offset_y;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_selection(has: u32, x: f32, y: f32, w: f32, h: f32) -> Result<()> {
    get_ui_state()
        .ruler_state_mut()
        .set_selection(has != 0, x, y, w, h);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_rulers_colors(bg: u32, border: u32, label: u32, accent: u32) -> Result<()> {
    let r = &mut get_ui_state().ruler_state_mut();
    r.bg_color = Color::new(bg);
    r.border_color = Color::new(border);
    r.label_color = Color::new(label);
    r.accent_color = Color::new(accent);
    Ok(())
}
