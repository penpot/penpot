use macros::{wasm_error, ToJs};

use crate::error::Error;
use crate::mem;
use crate::shapes;
use crate::utils::uuid_from_u32_quartet;
use crate::with_current_shape_mut;
use crate::with_state_mut;
use crate::STATE;

mod gradient;
mod image;
mod solid;

const RAW_FILL_DATA_SIZE: usize = std::mem::size_of::<RawFillData>();

#[repr(C, u8, align(4))]
#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[allow(dead_code)]
pub enum RawFillData {
    Solid(solid::RawSolidData) = 0x00,
    Linear(gradient::RawGradientData) = 0x01,
    Radial(gradient::RawGradientData) = 0x02,
    Image(image::RawImageFillData) = 0x03,
    Angular(gradient::RawGradientData) = 0x04,
    Diamond(gradient::RawGradientData) = 0x05,
}

impl From<RawFillData> for shapes::Fill {
    fn from(fill_data: RawFillData) -> Self {
        match fill_data {
            RawFillData::Solid(solid_fill_data) => shapes::Fill::Solid(solid_fill_data.into()),
            RawFillData::Linear(linear_fill_data) => {
                shapes::Fill::LinearGradient(linear_fill_data.into())
            }
            RawFillData::Radial(radial_fill_data) => {
                shapes::Fill::RadialGradient(radial_fill_data.into())
            }
            RawFillData::Image(image_fill_data) => shapes::Fill::Image(image_fill_data.into()),
            RawFillData::Angular(angular_fill_data) => {
                shapes::Fill::AngularGradient(angular_fill_data.into())
            }
            RawFillData::Diamond(diamond_fill_data) => {
                shapes::Fill::DiamondGradient(diamond_fill_data.into())
            }
        }
    }
}

impl From<[u8; RAW_FILL_DATA_SIZE]> for RawFillData {
    fn from(bytes: [u8; RAW_FILL_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawFillData {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_FILL_DATA_SIZE] = bytes
            .get(0..RAW_FILL_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid fill data".to_string())?;
        Ok(RawFillData::from(data))
    }
}

// FIXME: return Result
pub fn parse_fills_from_bytes(buffer: &[u8], num_fills: usize) -> Vec<shapes::Fill> {
    buffer
        .chunks_exact(RAW_FILL_DATA_SIZE)
        .take(num_fills)
        .map(|bytes| {
            RawFillData::try_from(bytes)
                .expect("Invalid fill data")
                .into()
        })
        .collect()
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_fills() -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        // The first byte contains the actual number of fills
        let num_fills = bytes.first().copied().unwrap_or(0) as usize;
        // Skip the first 4 bytes (header with fill count) and parse only the actual fills
        let fills = parse_fills_from_bytes(&bytes[4..], num_fills);
        shape.set_fills(fills);
        mem::free_bytes()?;
    });
    Ok(())
}

#[no_mangle]
pub extern "C" fn add_shape_fill() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let raw_fill = RawFillData::try_from(&bytes[..]).expect("Invalid fill data");
        shape.add_fill(raw_fill.into());
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_fills();
    });
}

/// Set a temporary fill override for one shape (used for gradient drag preview).
/// Heap layout: [16 bytes UUID as 4×u32 LE] [4 bytes fill_count u32 LE] [fills…]
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_fill_modifier() -> Result<()> {
    let bytes = mem::bytes();

    let a = u32::from_le_bytes(
        bytes[0..4].try_into().map_err(|_| Error::RecoverableError("uuid[0]".into()))?,
    );
    let b = u32::from_le_bytes(
        bytes[4..8].try_into().map_err(|_| Error::RecoverableError("uuid[1]".into()))?,
    );
    let c = u32::from_le_bytes(
        bytes[8..12].try_into().map_err(|_| Error::RecoverableError("uuid[2]".into()))?,
    );
    let d = u32::from_le_bytes(
        bytes[12..16].try_into().map_err(|_| Error::RecoverableError("uuid[3]".into()))?,
    );
    let uuid = uuid_from_u32_quartet(a, b, c, d);

    let num_fills = u32::from_le_bytes(
        bytes[16..20]
            .try_into()
            .map_err(|_| Error::RecoverableError("fill count".into()))?,
    ) as usize;
    let fills = parse_fills_from_bytes(&bytes[20..], num_fills);

    with_state_mut!(state, {
        state.shapes.set_fill_modifier(uuid, fills);
        state.touch_shape(uuid);
    });

    mem::free_bytes()?;
    Ok(())
}

/// Remove all fill overrides, invalidate tile caches for affected shapes, and
/// mark them as touched so the next render re-draws them without the modifier.
#[no_mangle]
pub extern "C" fn clean_fill_modifiers() {
    with_state_mut!(state, {
        let uuids = state.shapes.clean_fill_modifiers();
        for uuid in uuids {
            state.touch_shape(uuid);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_raw_fill_data_layout() {
        assert_eq!(
            std::mem::size_of::<RawFillData>(),
            4 + std::mem::size_of::<gradient::RawGradientData>()
        );
        assert_eq!(std::mem::align_of::<RawFillData>(), 4);
    }

    #[test]
    fn test_raw_fill_data_from_bytes_to_solid_fill() {
        let mut bytes = vec![0x00; std::mem::size_of::<RawFillData>()];
        bytes[0] = 0x00;
        bytes[4..8].copy_from_slice(&0xfffabada_u32.to_le_bytes());

        let raw_fill = RawFillData::try_from(&bytes[..]);

        assert!(raw_fill.is_ok());
        assert_eq!(
            raw_fill.unwrap(),
            RawFillData::Solid(solid::RawSolidData { color: 0xfffabada })
        );
    }
}
