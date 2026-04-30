use crate::error::{Error, Result};
use crate::mem;
use crate::shapes::Fill;
use crate::state::State;
use crate::uuid::Uuid;
use crate::with_state_mut;
use crate::STATE;
use crate::{shapes::ImageFill, utils::uuid_from_u32_quartet};
use macros::wasm_error;

fn touch_shapes_with_image(state: &mut State, image_id: Uuid) {
    let ids: Vec<Uuid> = state
        .shapes
        .iter()
        .filter(|shape| {
            shape
                .fills()
                .any(|f| matches!(f, Fill::Image(i) if i.id() == image_id))
                || shape
                    .strokes
                    .iter()
                    .any(|s| matches!(&s.fill, Fill::Image(i) if i.id() == image_id))
        })
        .map(|shape| shape.id)
        .collect();

    for id in ids {
        state.touch_shape(id);
    }
}

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

impl TryFrom<&[u8]> for ShapeImageIds {
    type Error = Error;

    fn try_from(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < IMAGE_IDS_SIZE {
            return Err(Error::CriticalError(format!(
                "Invalid image ids byte length: expected at least {}, got {}",
                IMAGE_IDS_SIZE,
                bytes.len()
            )));
        }
        let shape_id = Uuid::try_from(&bytes[0..16]).map_err(Error::CriticalError)?;
        let image_id = Uuid::try_from(&bytes[16..32]).map_err(Error::CriticalError)?;
        Ok(ShapeImageIds { shape_id, image_id })
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn store_image() -> Result<()> {
    let bytes = mem::bytes();
    let ids = ShapeImageIds::try_from(&bytes[0..IMAGE_IDS_SIZE])?;

    // Read is_thumbnail flag (4 bytes as u32)
    let is_thumbnail_bytes = &bytes[IMAGE_IDS_SIZE..IMAGE_HEADER_SIZE];
    let is_thumbnail_value =
        u32::from_le_bytes(is_thumbnail_bytes.try_into().map_err(|_| {
            Error::CriticalError("Invalid bytes for is_thumbnail flag".to_string())
        })?);
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
        touch_shapes_with_image(state, ids.image_id);
    });

    mem::free_bytes()?;
    Ok(())
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
#[wasm_error]
pub extern "C" fn store_image_from_texture() -> Result<()> {
    let bytes = mem::bytes();

    // FIXME: where does this 48 come from?
    if bytes.len() < 48 {
        // FIXME: Review if this should be an critical or a recoverable error.
        eprintln!("store_image_from_texture: insufficient data");
        mem::free_bytes()?;
        return Err(Error::RecoverableError(
            "store_image_from_texture: insufficient data".to_string(),
        ));
    }

    let ids = ShapeImageIds::try_from(&bytes[0..IMAGE_IDS_SIZE])
        .map_err(|_| Error::CriticalError("Invalid image ids".to_string()))?;

    // FIXME: read bytes in a safe way

    // Read is_thumbnail flag (4 bytes as u32)
    let is_thumbnail_bytes = &bytes[IMAGE_IDS_SIZE..IMAGE_HEADER_SIZE];
    let is_thumbnail_value =
        u32::from_le_bytes(is_thumbnail_bytes.try_into().map_err(|_| {
            Error::CriticalError("Invalid bytes for is_thumbnail flag".to_string())
        })?);
    let is_thumbnail = is_thumbnail_value != 0;

    // Read GL texture ID (4 bytes as u32)
    let texture_id_bytes = &bytes[36..40];
    let texture_id = u32::from_le_bytes(
        texture_id_bytes
            .try_into()
            .map_err(|_| Error::CriticalError("Invalid bytes for texture id".to_string()))?,
    );

    // Read width and height (8 bytes as two i32s)
    let width_bytes = &bytes[40..44];
    let width = i32::from_le_bytes(
        width_bytes
            .try_into()
            .map_err(|_| Error::CriticalError("Invalid bytes for width".to_string()))?,
    );

    let height_bytes = &bytes[44..48];
    let height = i32::from_le_bytes(
        height_bytes
            .try_into()
            .map_err(|_| Error::CriticalError("Invalid bytes for height".to_string()))?,
    );

    with_state_mut!(state, {
        if let Err(msg) = state.render_state_mut().add_image_from_gl_texture(
            ids.image_id,
            is_thumbnail,
            texture_id,
            width,
            height,
        ) {
            // FIXME: Review if we should return a RecoverableError
            eprintln!("store_image_from_texture error: {}", msg);
        }
        touch_shapes_with_image(state, ids.image_id);
    });

    mem::free_bytes()?;
    Ok(())
}
