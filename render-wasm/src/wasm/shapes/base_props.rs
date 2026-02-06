use crate::mem;
use crate::shapes::{BlendMode, ConstraintH, ConstraintV};
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;
use crate::wasm::blend::RawBlendMode;
use crate::wasm::layouts::constraints::{RawConstraintH, RawConstraintV};
use crate::{with_state_mut, STATE};

use super::RawShapeType;

/// Binary layout for batched shape base properties:
///
/// | Offset | Size | Field        | Type                              |
/// |--------|------|--------------|-----------------------------------|
/// | 0      | 16   | id           | UUID (4 × u32 LE)                 |
/// | 16     | 16   | parent_id    | UUID (4 × u32 LE)                 |
/// | 32     | 1    | shape_type   | u8                                |
/// | 33     | 1    | flags        | u8 (bit0: clip, bit1: hidden)     |
/// | 34     | 1    | blend_mode   | u8                                |
/// | 35     | 1    | constraint_h | u8 (0xFF = None)                  |
/// | 36     | 1    | constraint_v | u8 (0xFF = None)                  |
/// | 37     | 3    | padding      | -                                 |
/// | 40     | 4    | opacity      | f32 LE                            |
/// | 44     | 4    | rotation     | f32 LE                            |
/// | 48     | 24   | transform    | 6 × f32 LE (a,b,c,d,e,f)          |
/// | 72     | 16   | selrect      | 4 × f32 LE (x1,y1,x2,y2)          |
/// | 88     | 16   | corners      | 4 × f32 LE (r1,r2,r3,r4)          |
/// |--------|------|--------------|-----------------------------------|
/// | Total  | 104  |              |                                   |
pub const BASE_PROPS_SIZE: usize = 104;

const FLAG_CLIP_CONTENT: u8 = 0b0000_0001;
const FLAG_HIDDEN: u8 = 0b0000_0010;
const CONSTRAINT_NONE: u8 = 0xFF;

/// Reads a f32 from a byte slice at the given offset (little-endian)
#[inline]
fn read_f32_le(bytes: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

/// Reads a u32 from a byte slice at the given offset (little-endian)
#[inline]
fn read_u32_le(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

/// Parses UUID from bytes at given offset
#[inline]
fn read_uuid(bytes: &[u8], offset: usize) -> Uuid {
    uuid_from_u32_quartet(
        read_u32_le(bytes, offset),
        read_u32_le(bytes, offset + 4),
        read_u32_le(bytes, offset + 8),
        read_u32_le(bytes, offset + 12),
    )
}

#[no_mangle]
pub extern "C" fn set_shape_base_props() {
    let bytes = mem::bytes();

    if bytes.len() < BASE_PROPS_SIZE {
        return;
    }

    // Parse all fields from the buffer
    let id = read_uuid(&bytes, 0);
    let parent_id = read_uuid(&bytes, 16);
    let shape_type = bytes[32];
    let flags = bytes[33];
    let blend_mode = bytes[34];
    let constraint_h = bytes[35];
    let constraint_v = bytes[36];
    // bytes[37..40] are padding

    let opacity = read_f32_le(&bytes, 40);
    let rotation = read_f32_le(&bytes, 44);

    // Transform matrix (a, b, c, d, e, f)
    let transform_a = read_f32_le(&bytes, 48);
    let transform_b = read_f32_le(&bytes, 52);
    let transform_c = read_f32_le(&bytes, 56);
    let transform_d = read_f32_le(&bytes, 60);
    let transform_e = read_f32_le(&bytes, 64);
    let transform_f = read_f32_le(&bytes, 68);

    // Selrect (x1, y1, x2, y2)
    let selrect_x1 = read_f32_le(&bytes, 72);
    let selrect_y1 = read_f32_le(&bytes, 76);
    let selrect_x2 = read_f32_le(&bytes, 80);
    let selrect_y2 = read_f32_le(&bytes, 84);

    // Corners (r1, r2, r3, r4)
    let corner_r1 = read_f32_le(&bytes, 88);
    let corner_r2 = read_f32_le(&bytes, 92);
    let corner_r3 = read_f32_le(&bytes, 96);
    let corner_r4 = read_f32_le(&bytes, 100);

    // Decode flags
    let clip_content = (flags & FLAG_CLIP_CONTENT) != 0;
    let hidden = (flags & FLAG_HIDDEN) != 0;

    // Convert raw enum values
    let shape_type_enum = RawShapeType::from(shape_type);
    let blend_mode_enum: BlendMode = RawBlendMode::from(blend_mode).into();

    let constraint_h_opt: Option<ConstraintH> = if constraint_h == CONSTRAINT_NONE {
        None
    } else {
        Some(RawConstraintH::from(constraint_h).into())
    };

    let constraint_v_opt: Option<ConstraintV> = if constraint_v == CONSTRAINT_NONE {
        None
    } else {
        Some(RawConstraintV::from(constraint_v).into())
    };

    with_state_mut!(state, {
        // Select/create the shape
        state.use_shape(id);

        // Set parent relationship
        state.set_parent_for_current_shape(parent_id);

        // Mark shape as touched
        state.touch_current();

        // Apply all properties to the current shape
        if let Some(shape) = state.current_shape_mut() {
            // Type
            shape.set_shape_type(shape_type_enum.into());

            // Boolean flags
            shape.set_clip(clip_content);
            shape.set_hidden(hidden);

            // Blend mode and opacity
            shape.set_blend_mode(blend_mode_enum);
            shape.set_opacity(opacity);

            // Constraints
            shape.set_constraint_h(constraint_h_opt);
            shape.set_constraint_v(constraint_v_opt);

            // Transform
            shape.set_rotation(rotation);
            shape.set_transform(
                transform_a,
                transform_b,
                transform_c,
                transform_d,
                transform_e,
                transform_f,
            );

            // Geometry
            shape.set_selrect(selrect_x1, selrect_y1, selrect_x2, selrect_y2);
            shape.set_corners((corner_r1, corner_r2, corner_r3, corner_r4));
        }
    });
}
