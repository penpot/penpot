mod gradient;
mod image;
mod solid;

use crate::mem;
use crate::shapes;
use crate::with_current_shape;
use crate::STATE;

#[repr(C)]
#[repr(align(4))]
#[repr(u8)]
#[derive(Debug, PartialEq, Clone, Copy)]
enum RawFillData {
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

impl TryFrom<&[u8]> for RawFillData {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        if bytes.len() < std::mem::size_of::<RawFillData>() {
            return Err("Invalid fill data".to_string());
        }

        let fill_type = bytes[0];
        match fill_type {
            0x00 => Ok(RawFillData::Solid(solid::RawSolidData::try_from(
                &bytes[1..],
            )?)),
            0x01 => Ok(RawFillData::Linear(gradient::RawGradientData::try_from(
                &bytes[1..],
            )?)),
            0x02 => Ok(RawFillData::Radial(gradient::RawGradientData::try_from(
                &bytes[1..],
            )?)),
            0x03 => Ok(RawFillData::Image(image::RawImageFillData::try_from(
                &bytes[1..],
            )?)),
            _ => Err("Invalid fill type".to_string()),
        }
    }
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
pub extern "C" fn add_shape_solid_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let solid_color =
            shapes::SolidColor::try_from(&bytes[..]).expect("Invalid solid color data");

        shape.add_fill(shapes::Fill::Solid(solid_color));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_linear_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");
        shape.add_fill(shapes::Fill::LinearGradient(gradient));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_radial_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let gradient = shapes::Gradient::try_from(&bytes[..]).expect("Invalid gradient data");
        shape.add_fill(shapes::Fill::RadialGradient(gradient));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_image_fill() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let image_fill = shapes::ImageFill::try_from(&bytes[..]).expect("Invalid image fill data");

        shape.add_fill(shapes::Fill::Image(image_fill));
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
        bytes[1..=4].copy_from_slice(&0xfffabada_u32.to_le_bytes());

        let raw_fill = RawFillData::try_from(&bytes[..]);

        assert!(raw_fill.is_ok());
        assert_eq!(
            raw_fill.unwrap(),
            RawFillData::Solid(solid::RawSolidData { color: 0xfffabada })
        );
    }
}
