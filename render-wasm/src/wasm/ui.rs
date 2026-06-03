use crate::mem;
use crate::{
    error::{Error, Result},
    globals::get_ui_state,
    ui::{Guide, GuideKind},
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
#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, PartialEq, Copy)]
pub struct RawGuide {
    kind: u32,
    color: u32,
    position: f32,
}

impl From<RawGuide> for Guide {
    fn from(value: RawGuide) -> Self {
        let kind = match RawGuideKind::from(value.kind) {
            RawGuideKind::Vertical => GuideKind::Vertical(value.position),
            RawGuideKind::Horizontal => GuideKind::Horizontal(value.position),
        };
        Guide::new(kind, value.color.into())
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
        .map(|bytes| RawGuide::try_from(bytes).map(|guide| guide.into()))
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
    get_ui_state().guides = guides;

    mem::free_bytes()?;
    Ok(())
}
