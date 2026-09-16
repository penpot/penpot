use crate::error::{Error, Result};
use crate::get_gpu_state;
use crate::get_resources;
use crate::mem;
use crate::shapes::Fill;
use crate::state::State;
use crate::uuid::Uuid;
use crate::with_state;
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
const FLAG_HAS_TRANSFORM: u8 = 1 << 1;
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
    _pad: u16,
    width: i32,
    height: i32,
    transform_x: f32,
    transform_y: f32,
    transform_w: f32,
    transform_h: f32,
}

impl From<&ImageFill> for RawImageFillData {
    fn from(image_fill: &ImageFill) -> Self {
        let id = image_fill.id();
        let (a, b, c, d) = crate::utils::uuid_to_u32_quartet(&id);
        let mut flags = if image_fill.keep_aspect_ratio() {
            FLAG_KEEP_ASPECT_RATIO
        } else {
            0
        };
        let (tx, ty, tw, th) = if let Some(tf) = image_fill.transform() {
            flags |= FLAG_HAS_TRANSFORM;
            (tf.x, tf.y, tf.width, tf.height)
        } else {
            (0.0, 0.0, 1.0, 1.0)
        };

        Self {
            a,
            b,
            c,
            d,
            opacity: image_fill.opacity(),
            flags,
            _pad: 0,
            width: image_fill.width(),
            height: image_fill.height(),
            transform_x: tx,
            transform_y: ty,
            transform_w: tw,
            transform_h: th,
        }
    }
}

impl From<RawImageFillData> for ImageFill {
    fn from(value: RawImageFillData) -> Self {
        let id = uuid_from_u32_quartet(value.a, value.b, value.c, value.d);
        let keep_aspect_ratio = value.flags & FLAG_KEEP_ASPECT_RATIO != 0;
        let transform = if value.flags & FLAG_HAS_TRANSFORM != 0 {
            Some(crate::shapes::ImageFillTransform {
                x: value.transform_x,
                y: value.transform_y,
                width: value.transform_w,
                height: value.transform_h,
            })
        } else {
            None
        };

        Self::new_with_transform(
            id,
            value.opacity,
            value.width,
            value.height,
            keep_aspect_ratio,
            transform,
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

    with_state!(state, {
        if let Err(msg) = get_resources()
            .images
            .add(ids.image_id, is_thumbnail, image_bytes)
        {
            eprintln!("{}", msg);
        }
        touch_shapes_with_image(state, ids.image_id);
    });

    mem::free_bytes()?;
    Ok(())
}

/// Registers the public URL an image was loaded from for SVG export.
///
/// Layout: UTF-8 URL bytes in the alloc buffer. The image UUID is passed as
/// the four u32 arguments (same quartet as `store_image` / `is_image_cached`).
#[no_mangle]
#[wasm_error]
pub extern "C" fn store_image_url(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    let id = uuid_from_u32_quartet(a, b, c, d);
    let url_bytes = mem::bytes();
    let url = String::from_utf8(url_bytes)
        .map_err(|_| Error::CriticalError("Invalid UTF-8 in image source URL".to_string()))?;
    mem::free_bytes()?;
    get_resources().images.set_source_url(id, url);
    Ok(())
}

/// A GL texture handed over from JS, as written by `store-image-texture` /
/// `upload-video-frame!` on the CLJS side.
struct TextureUpload {
    ids: ShapeImageIds,
    is_thumbnail: bool,
    texture_id: u32,
    width: i32,
    height: i32,
}

const TEXTURE_UPLOAD_SIZE: usize = 48; // header + texture id + width + height

/// Reads a `TextureUpload` out of the shared buffer. The buffer is freed by the
/// caller on success and here on failure, so a malformed payload cannot leak it.
fn read_texture_upload() -> Result<TextureUpload> {
    let bytes = mem::bytes();

    if bytes.len() < TEXTURE_UPLOAD_SIZE {
        // FIXME: Review if this should be an critical or a recoverable error.
        eprintln!("read_texture_upload: insufficient data");
        mem::free_bytes()?;
        return Err(Error::RecoverableError(
            "read_texture_upload: insufficient data".to_string(),
        ));
    }

    let ids = ShapeImageIds::try_from(&bytes[0..IMAGE_IDS_SIZE])
        .map_err(|_| Error::CriticalError("Invalid image ids".to_string()))?;

    // FIXME: read bytes in a safe way
    let read_u32 = |range: std::ops::Range<usize>, what: &str| -> Result<u32> {
        Ok(u32::from_le_bytes((&bytes[range]).try_into().map_err(
            |_| Error::CriticalError(format!("Invalid bytes for {}", what)),
        )?))
    };

    let is_thumbnail = read_u32(IMAGE_IDS_SIZE..IMAGE_HEADER_SIZE, "is_thumbnail flag")? != 0;
    let texture_id = read_u32(36..40, "texture id")?;
    let width = read_u32(40..44, "width")? as i32;
    let height = read_u32(44..48, "height")? as i32;

    Ok(TextureUpload {
        ids,
        is_thumbnail,
        texture_id,
        width,
        height,
    })
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
    let upload = read_texture_upload()?;

    with_state!(state, {
        if let Err(msg) = get_resources().images.add_image_from_gl_texture(
            upload.ids.image_id,
            upload.is_thumbnail,
            upload.texture_id,
            upload.width,
            upload.height,
        ) {
            // FIXME: Review if we should return a RecoverableError
            eprintln!("store_image_from_texture error: {}", msg);
        }
        touch_shapes_with_image(state, upload.ids.image_id);
    });

    mem::free_bytes()?;
    Ok(())
}

/// Rebinds an already-stored image to the current contents of a GL texture and
/// invalidates the tiles of every shape that paints it. Same memory layout as
/// `store_image_from_texture`.
///
/// This is the per-frame entry point for video: the CLJS side owns one texture
/// per playing video, uploads the decoded frame into it and calls this.
#[no_mangle]
#[wasm_error]
pub extern "C" fn update_image_from_texture() -> Result<()> {
    let upload = read_texture_upload()?;

    // The caller just bound and wrote the texture from JS, which Skia has no
    // way to observe. Drop its cached bindings before it samples the texture.
    get_gpu_state().reset_texture_bindings();

    with_state!(state, {
        if let Err(msg) = get_resources().images.rebind_gl_texture(
            upload.ids.image_id,
            upload.is_thumbnail,
            upload.texture_id,
            upload.width,
            upload.height,
        ) {
            eprintln!("update_image_from_texture error: {}", msg);
        }
        touch_shapes_with_image(state, upload.ids.image_id);
    });

    mem::free_bytes()?;
    Ok(())
}
