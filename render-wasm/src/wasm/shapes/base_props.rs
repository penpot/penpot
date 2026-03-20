use crate::mem;
use crate::shapes::{BlendMode, ConstraintH, ConstraintV};
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;
use crate::wasm::blend::RawBlendMode;
use crate::wasm::layouts::constraints::{RawConstraintH, RawConstraintV};
use crate::{with_state_mut, STATE};

#[allow(unused_imports)]
use crate::error::{Error, Result};
use macros::wasm_error;

use super::RawShapeType;

const FLAG_CLIP_CONTENT: u8 = 0b0000_0001;
const FLAG_HIDDEN: u8 = 0b0000_0010;
const CONSTRAINT_NONE: u8 = 0xFF;

const RAW_BASE_PROPS_SIZE: usize = std::mem::size_of::<RawBasePropsData>();

/// Binary layout for batched shape base properties.
///
/// The struct fields directly mirror the binary protocol — the layout
/// documentation lives in the struct definition itself via `#[repr(C)]`.
#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawBasePropsData {
    // UUID id (16 bytes)
    id_a: u32,
    id_b: u32,
    id_c: u32,
    id_d: u32,
    // UUID parent_id (16 bytes)
    parent_a: u32,
    parent_b: u32,
    parent_c: u32,
    parent_d: u32,
    // Single-byte fields
    shape_type: u8,
    flags: u8,
    blend_mode: u8,
    constraint_h: u8,
    constraint_v: u8,
    padding: [u8; 3],
    // f32 fields
    opacity: f32,
    rotation: f32,
    // Transform matrix (a, b, c, d, e, f)
    transform_a: f32,
    transform_b: f32,
    transform_c: f32,
    transform_d: f32,
    transform_e: f32,
    transform_f: f32,
    // Selrect (x1, y1, x2, y2)
    selrect_x1: f32,
    selrect_y1: f32,
    selrect_x2: f32,
    selrect_y2: f32,
    // Corners (r1, r2, r3, r4)
    corner_r1: f32,
    corner_r2: f32,
    corner_r3: f32,
    corner_r4: f32,
}

impl RawBasePropsData {
    fn id(&self) -> Uuid {
        uuid_from_u32_quartet(self.id_a, self.id_b, self.id_c, self.id_d)
    }

    fn parent_id(&self) -> Uuid {
        uuid_from_u32_quartet(self.parent_a, self.parent_b, self.parent_c, self.parent_d)
    }

    fn clip_content(&self) -> bool {
        (self.flags & FLAG_CLIP_CONTENT) != 0
    }

    fn hidden(&self) -> bool {
        (self.flags & FLAG_HIDDEN) != 0
    }

    fn blend_mode(&self) -> BlendMode {
        RawBlendMode::from(self.blend_mode).into()
    }

    fn constraint_h(&self) -> Option<ConstraintH> {
        if self.constraint_h == CONSTRAINT_NONE {
            None
        } else {
            Some(RawConstraintH::from(self.constraint_h).into())
        }
    }

    fn constraint_v(&self) -> Option<ConstraintV> {
        if self.constraint_v == CONSTRAINT_NONE {
            None
        } else {
            Some(RawConstraintV::from(self.constraint_v).into())
        }
    }
}

impl From<[u8; RAW_BASE_PROPS_SIZE]> for RawBasePropsData {
    fn from(bytes: [u8; RAW_BASE_PROPS_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_base_props() -> Result<()> {
    let bytes = mem::bytes();

    if bytes.len() < RAW_BASE_PROPS_SIZE {
        return Ok(());
    }

    // FIXME: this should just be a try_from
    let data: [u8; RAW_BASE_PROPS_SIZE] = bytes[..RAW_BASE_PROPS_SIZE]
        .try_into()
        .map_err(|_| Error::CriticalError("Invalid bytes for base props".to_string()))?;
    let raw = RawBasePropsData::from(data);

    let id = raw.id();
    let parent_id = raw.parent_id();
    let shape_type = RawShapeType::from(raw.shape_type);

    with_state_mut!(state, {
        state.use_shape(id);
        state.set_parent_for_current_shape(parent_id);
        state.touch_current();

        if let Some(shape) = state.current_shape_mut() {
            shape.set_shape_type(shape_type.into());
            shape.set_clip(raw.clip_content());
            shape.set_hidden(raw.hidden());
            shape.set_blend_mode(raw.blend_mode());
            shape.set_opacity(raw.opacity);
            shape.set_constraint_h(raw.constraint_h());
            shape.set_constraint_v(raw.constraint_v());
            shape.set_rotation(raw.rotation);
            shape.set_transform(
                raw.transform_a,
                raw.transform_b,
                raw.transform_c,
                raw.transform_d,
                raw.transform_e,
                raw.transform_f,
            );
            shape.set_selrect(
                raw.selrect_x1,
                raw.selrect_y1,
                raw.selrect_x2,
                raw.selrect_y2,
            );
            shape.set_corners((raw.corner_r1, raw.corner_r2, raw.corner_r3, raw.corner_r4));
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: builds a 104-byte buffer with all zeros, then lets the
    /// caller poke specific offsets before transmuting.
    fn make_bytes() -> [u8; RAW_BASE_PROPS_SIZE] {
        [0u8; RAW_BASE_PROPS_SIZE]
    }

    fn raw_from(bytes: &[u8; RAW_BASE_PROPS_SIZE]) -> RawBasePropsData {
        RawBasePropsData::from(*bytes)
    }

    #[test]
    fn test_raw_base_props_layout() {
        assert_eq!(RAW_BASE_PROPS_SIZE, 104);
        assert_eq!(std::mem::align_of::<RawBasePropsData>(), 4);
    }

    #[test]
    fn test_field_offsets_match_binary_protocol() {
        // Verify that key struct fields sit at the documented byte offsets.
        assert_eq!(std::mem::offset_of!(RawBasePropsData, id_a), 0);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, parent_a), 16);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, shape_type), 32);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, flags), 33);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, blend_mode), 34);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, constraint_h), 35);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, constraint_v), 36);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, padding), 37);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, opacity), 40);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, rotation), 44);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, transform_a), 48);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, selrect_x1), 72);
        assert_eq!(std::mem::offset_of!(RawBasePropsData, corner_r1), 88);
    }

    #[test]
    fn test_full_deserialization() {
        let mut bytes = make_bytes();
        // id
        bytes[0..4].copy_from_slice(&1_u32.to_le_bytes());
        bytes[4..8].copy_from_slice(&2_u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&3_u32.to_le_bytes());
        bytes[12..16].copy_from_slice(&4_u32.to_le_bytes());
        // parent_id
        bytes[16..20].copy_from_slice(&5_u32.to_le_bytes());
        bytes[20..24].copy_from_slice(&6_u32.to_le_bytes());
        bytes[24..28].copy_from_slice(&7_u32.to_le_bytes());
        bytes[28..32].copy_from_slice(&8_u32.to_le_bytes());
        // shape_type = Rect (3)
        bytes[32] = 3;
        // flags = clip + hidden
        bytes[33] = FLAG_CLIP_CONTENT | FLAG_HIDDEN;
        // blend_mode = Overlay (15)
        bytes[34] = 15;
        // constraint_h = Center (3)
        bytes[35] = 3;
        // constraint_v = Scale (4)
        bytes[36] = 4;
        // opacity
        bytes[40..44].copy_from_slice(&0.5_f32.to_le_bytes());
        // rotation
        bytes[44..48].copy_from_slice(&90.0_f32.to_le_bytes());
        // transform (a=2, b=0, c=0, d=2, e=50, f=60)
        bytes[48..52].copy_from_slice(&2.0_f32.to_le_bytes());
        bytes[52..56].copy_from_slice(&0.0_f32.to_le_bytes());
        bytes[56..60].copy_from_slice(&0.0_f32.to_le_bytes());
        bytes[60..64].copy_from_slice(&2.0_f32.to_le_bytes());
        bytes[64..68].copy_from_slice(&50.0_f32.to_le_bytes());
        bytes[68..72].copy_from_slice(&60.0_f32.to_le_bytes());
        // selrect
        bytes[72..76].copy_from_slice(&0.0_f32.to_le_bytes());
        bytes[76..80].copy_from_slice(&0.0_f32.to_le_bytes());
        bytes[80..84].copy_from_slice(&100.0_f32.to_le_bytes());
        bytes[84..88].copy_from_slice(&200.0_f32.to_le_bytes());
        // corners
        bytes[88..92].copy_from_slice(&4.0_f32.to_le_bytes());
        bytes[92..96].copy_from_slice(&8.0_f32.to_le_bytes());
        bytes[96..100].copy_from_slice(&12.0_f32.to_le_bytes());
        bytes[100..104].copy_from_slice(&16.0_f32.to_le_bytes());

        let raw = raw_from(&bytes);

        assert_eq!(raw.id(), uuid_from_u32_quartet(1, 2, 3, 4));
        assert_eq!(raw.parent_id(), uuid_from_u32_quartet(5, 6, 7, 8));
        assert_eq!(raw.shape_type, 3); // Rect
        assert!(raw.clip_content());
        assert!(raw.hidden());
        assert_eq!(raw.blend_mode(), BlendMode(skia_safe::BlendMode::Overlay));
        assert_eq!(raw.constraint_h(), Some(ConstraintH::Center));
        assert_eq!(raw.constraint_v(), Some(ConstraintV::Scale));
        assert_eq!(raw.opacity, 0.5);
        assert_eq!(raw.rotation, 90.0);
        assert_eq!(raw.transform_a, 2.0);
        assert_eq!(raw.transform_e, 50.0);
        assert_eq!(raw.transform_f, 60.0);
        assert_eq!(raw.selrect_x1, 0.0);
        assert_eq!(raw.selrect_y2, 200.0);
        assert_eq!(raw.corner_r1, 4.0);
        assert_eq!(raw.corner_r4, 16.0);
    }
}
