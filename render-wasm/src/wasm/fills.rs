use macros::{wasm_error, ToJs};

use crate::mem;
use crate::shapes;
use crate::with_current_shape_mut;
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

impl TryFrom<&shapes::Fill> for RawFillData {
    type Error = String;

    fn try_from(fill: &shapes::Fill) -> Result<Self, Self::Error> {
        match fill {
            shapes::Fill::Solid(shapes::SolidColor(color)) => {
                Ok(RawFillData::Solid(solid::RawSolidData {
                    color: ((color.a() as u32) << 24)
                        | ((color.r() as u32) << 16)
                        | ((color.g() as u32) << 8)
                        | (color.b() as u32),
                }))
            }
            shapes::Fill::LinearGradient(_) => {
                Err("LinearGradient serialization is not implemented".to_string())
            }
            shapes::Fill::RadialGradient(_) => {
                Err("RadialGradient serialization is not implemented".to_string())
            }
            shapes::Fill::Image(_) => Err("Image fill serialization is not implemented".to_string()),
        }
    }
}

impl From<[u8; RAW_FILL_DATA_SIZE]> for RawFillData {
    fn from(bytes: [u8; RAW_FILL_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl From<RawFillData> for [u8; RAW_FILL_DATA_SIZE] {
    fn from(fill_data: RawFillData) -> Self {
        unsafe { std::mem::transmute(fill_data) }
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
pub fn read_fills_from_bytes(buffer: &[u8], num_fills: usize) -> Vec<shapes::Fill> {
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

/// Serializes raw fills to bytes using the same fixed-size chunk layout consumed by
/// `read_fills_from_bytes`.
pub fn write_fills_to_bytes(fills: Vec<shapes::Fill>) -> Vec<u8> {
    fills
    .iter()
    .map(|fill| RawFillData::try_from(fill).expect("Unsupported fill type for serialization"))
    .flat_map(|raw_fill| <[u8; RAW_FILL_DATA_SIZE]>::from(raw_fill))
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
        let fills = read_fills_from_bytes(&bytes[4..], num_fills);
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

    #[test]
    fn test_write_fills_to_bytes_single_solid() {
        let fills = vec![shapes::Fill::Solid(shapes::SolidColor(shapes::Color::new(
            0xfffabada,
        )))];

        let bytes = write_fills_to_bytes(fills);

        assert_eq!(bytes.len(), RAW_FILL_DATA_SIZE);
        assert_eq!(bytes[0], 0x00);
        assert_eq!(
            RawFillData::try_from(&bytes[..]).unwrap(),
            RawFillData::Solid(solid::RawSolidData { color: 0xfffabada })
        );
    }

    #[test]
    fn test_write_fills_to_bytes_roundtrip_multiple() {
        let fills = vec![
            shapes::Fill::Solid(shapes::SolidColor(shapes::Color::new(0xfffabada))),
            shapes::Fill::Solid(shapes::SolidColor(shapes::Color::new(0xff112233))),
        ];

        let bytes = write_fills_to_bytes(fills);
        let decoded: Vec<RawFillData> = bytes
            .chunks_exact(RAW_FILL_DATA_SIZE)
            .map(|chunk| RawFillData::try_from(chunk).unwrap())
            .collect();

        assert_eq!(
            decoded,
            vec![
                RawFillData::Solid(solid::RawSolidData { color: 0xfffabada }),
                RawFillData::Solid(solid::RawSolidData { color: 0xff112233 }),
            ]
        );
    }
}
