use crate::mem;
use crate::mem::SerializableResult;
use crate::uuid::Uuid;
use crate::with_state_mut;
use crate::STATE;
use crate::{shapes::ImageFill, utils::uuid_from_u32_quartet};

const FLAG_KEEP_ASPECT_RATIO: u8 = 1 << 0;
const IMAGE_IDS_SIZE: usize = 32;
const IMAGE_HEADER_SIZE: usize = 36; // 32 bytes for IDs + 4 bytes for is_thumbnail flag

#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
#[repr(align(4))]
pub struct RawImageFillData {
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    opacity: u8,
    flags: u8,
    // 16-bit padding here, reserved for future use
    width: i32,
    height: i32,
}

impl From<RawImageFillData> for ImageFill {
    fn from(value: RawImageFillData) -> Self {
        let id = uuid_from_u32_quartet(value.a, value.b, value.c, value.d);
        let keep_aspect_ratio = value.flags & FLAG_KEEP_ASPECT_RATIO != 0;

        Self::new(
            id,
            value.opacity,
            value.width,
            value.height,
            keep_aspect_ratio,
        )
    }
}

#[repr(C)]
#[derive(Clone, Debug)]
pub struct ShapeImageIds {
    shape_id: Uuid,
    image_id: Uuid,
}

impl From<[u8; IMAGE_IDS_SIZE]> for ShapeImageIds {
    fn from(bytes: [u8; IMAGE_IDS_SIZE]) -> Self {
        let shape_id = Uuid::from_bytes(bytes[0..16].try_into().unwrap());
        let image_id = Uuid::from_bytes(bytes[16..32].try_into().unwrap());
        ShapeImageIds { shape_id, image_id }
    }
}

impl TryFrom<Vec<u8>> for ShapeImageIds {
    type Error = &'static str;

    fn try_from(value: Vec<u8>) -> Result<Self, Self::Error> {
        let mut arr = [0u8; IMAGE_IDS_SIZE];
        arr.copy_from_slice(&value);
        Ok(ShapeImageIds::from(arr))
    }
}

#[no_mangle]
pub extern "C" fn store_image() {
    let bytes = mem::bytes();
    let ids = ShapeImageIds::try_from(bytes[0..IMAGE_IDS_SIZE].to_vec()).unwrap();

    // Read is_thumbnail flag (4 bytes as u32)
    let is_thumbnail_bytes = &bytes[IMAGE_IDS_SIZE..IMAGE_HEADER_SIZE];
    let is_thumbnail_value = u32::from_le_bytes(is_thumbnail_bytes.try_into().unwrap());
    let is_thumbnail = is_thumbnail_value != 0;

    let image_bytes = &bytes[IMAGE_HEADER_SIZE..];

    with_state_mut!(state, {
        if let Err(msg) =
            state
                .render_state_mut()
                .add_image(ids.image_id, is_thumbnail, image_bytes)
        {
            eprintln!("{}", msg);
        }
        state.touch_shape(ids.shape_id);
    });

    mem::free_bytes();
}

/// Stores an image from an existing WebGL texture, avoiding re-decoding
/// Expected memory layout:
/// - bytes 0-15: shape UUID
/// - bytes 16-31: image UUID  
/// - bytes 32-35: is_thumbnail flag (u32)
/// - bytes 36-39: GL texture ID (u32)
/// - bytes 40-43: width (i32)
/// - bytes 44-47: height (i32)
#[no_mangle]
pub extern "C" fn store_image_from_texture() {
    let bytes = mem::bytes();

    if bytes.len() < 48 {
        eprintln!("store_image_from_texture: insufficient data");
        mem::free_bytes();
        return;
    }

    let ids = ShapeImageIds::try_from(bytes[0..IMAGE_IDS_SIZE].to_vec()).unwrap();

    // Read is_thumbnail flag (4 bytes as u32)
    let is_thumbnail_bytes = &bytes[IMAGE_IDS_SIZE..IMAGE_HEADER_SIZE];
    let is_thumbnail_value = u32::from_le_bytes(is_thumbnail_bytes.try_into().unwrap());
    let is_thumbnail = is_thumbnail_value != 0;

    // Read GL texture ID (4 bytes as u32)
    let texture_id_bytes = &bytes[36..40];
    let texture_id = u32::from_le_bytes(texture_id_bytes.try_into().unwrap());

    // Read width and height (8 bytes as two i32s)
    let width_bytes = &bytes[40..44];
    let width = i32::from_le_bytes(width_bytes.try_into().unwrap());

    let height_bytes = &bytes[44..48];
    let height = i32::from_le_bytes(height_bytes.try_into().unwrap());

    with_state_mut!(state, {
        if let Err(msg) = state.render_state_mut().add_image_from_gl_texture(
            ids.image_id,
            is_thumbnail,
            texture_id,
            width,
            height,
        ) {
            eprintln!("store_image_from_texture error: {}", msg);
        }
        state.touch_shape(ids.shape_id);
    });

    mem::free_bytes();
}
