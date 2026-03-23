#[allow(unused_imports)]
use crate::error::{Error, Result};
use macros::{wasm_error, ToJs};

use skia_safe as skia;

use crate::mem;
use crate::shapes::{self, TransformEntry, TransformEntrySource};
use crate::utils::uuid_from_u32_quartet;
use crate::{with_state, STATE};

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
enum RawTransformEntryKind {
    #[allow(dead_code)]
    Parent = 0,
    Child = 1,
}

impl From<u8> for RawTransformEntryKind {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

#[derive(Debug, PartialEq, Clone, Copy)]
#[repr(C)]
#[repr(align(4))]
pub struct RawTransformEntry {
    id: [u32; 4],
    transform: [f32; 6],
    kind: RawTransformEntryKind,
}

const RAW_TRANSFORM_ENTRY_SIZE: usize = size_of::<RawTransformEntry>();

impl From<[u8; RAW_TRANSFORM_ENTRY_SIZE]> for RawTransformEntry {
    fn from(bytes: [u8; RAW_TRANSFORM_ENTRY_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTransformEntry {
    type Error = Error;
    fn try_from(bytes: &[u8]) -> Result<Self> {
        let bytes: [u8; RAW_TRANSFORM_ENTRY_SIZE] = bytes
            .try_into()
            .map_err(|_| Error::CriticalError("Invalid transform entry bytes".to_string()))?;
        Ok(RawTransformEntry::from(bytes))
    }
}

impl From<RawTransformEntry> for TransformEntry {
    fn from(value: RawTransformEntry) -> Self {
        let [a, b, c, d] = value.id;
        let transform = skia::Matrix::new_all(
            value.transform[0],
            value.transform[2],
            value.transform[4],
            value.transform[1],
            value.transform[3],
            value.transform[5],
            0.0,
            0.0,
            1.0,
        );

        TransformEntry {
            id: uuid_from_u32_quartet(a, b, c, d),
            transform,
            source: TransformEntrySource::Input,
            propagate: value.kind == RawTransformEntryKind::Child,
        }
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn propagate_modifiers(pixel_precision: bool) -> Result<*mut u8> {
    let bytes = mem::bytes();

    let entries: Vec<TransformEntry> = bytes
        .chunks(RAW_TRANSFORM_ENTRY_SIZE)
        .map(|data| RawTransformEntry::try_from(data).map(|entry| entry.into()))
        .collect::<Result<Vec<_>>>()?;

    with_state!(state, {
        let result = shapes::propagate_modifiers(state, &entries, pixel_precision)?;
        Ok(mem::write_vec(result))
    })
}
