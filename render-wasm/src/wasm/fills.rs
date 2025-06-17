mod gradient;
mod image;
mod solid;

use crate::mem;
use crate::shapes;
use crate::with_current_shape;
use crate::STATE;

const RAW_FILL_DATA_SIZE: usize = std::mem::size_of::<RawFillData>();

#[repr(C, u8, align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
#[allow(dead_code)]
pub enum RawFillData {
    Solid(solid::RawSolidData) = 0x00,
    Linear(gradient::RawGradientData) = 0x01,
    Radial(gradient::RawGradientData) = 0x02,
    Image(image::RawImageFillData) = 0x03,
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
pub extern "C" fn set_shape_fills() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let fills = parse_fills_from_bytes(&bytes, bytes.len() / RAW_FILL_DATA_SIZE);
        shape.set_fills(fills);
    });
}

#[no_mangle]
pub extern "C" fn add_shape_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let raw_fill = RawFillData::try_from(&bytes[..]).expect("Invalid fill data");
        shape.add_fill(raw_fill.into());
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_fills();
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
