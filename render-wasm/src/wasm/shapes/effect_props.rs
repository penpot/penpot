use skia_safe as skia;

use crate::mem;
use crate::shapes::{Blur, Shadow};
use crate::wasm::blurs::RawBlurType;
use crate::wasm::shadows::RawShadowStyle;
use crate::{with_current_shape_mut, STATE};

const RAW_EFFECT_HEADER_SIZE: usize = std::mem::size_of::<RawEffectHeader>();
const RAW_SHADOW_ENTRY_SIZE: usize = std::mem::size_of::<RawShadowEntry>();

/// Binary layout for the effect header (blur + shadow count).
///
/// The struct fields directly mirror the binary protocol — the layout
/// documentation lives in the struct definition itself via `#[repr(C)]`.
#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawEffectHeader {
    blur_present: u8,
    blur_type: u8,
    blur_hidden: u8,
    padding: u8,
    blur_value: f32,
    shadow_count: u32,
}

impl RawEffectHeader {
    fn has_blur(&self) -> bool {
        self.blur_present != 0
    }

    fn is_blur_hidden(&self) -> bool {
        self.blur_hidden != 0
    }
}

impl From<[u8; RAW_EFFECT_HEADER_SIZE]> for RawEffectHeader {
    fn from(bytes: [u8; RAW_EFFECT_HEADER_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

/// Binary layout for a single shadow entry.
#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawShadowEntry {
    color: u32,
    blur: f32,
    spread: f32,
    offset_x: f32,
    offset_y: f32,
    style: u8,
    hidden: u8,
    padding: [u8; 2],
}

impl RawShadowEntry {
    fn is_hidden(&self) -> bool {
        self.hidden != 0
    }
}

impl From<[u8; RAW_SHADOW_ENTRY_SIZE]> for RawShadowEntry {
    fn from(bytes: [u8; RAW_SHADOW_ENTRY_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_effects() {
    let bytes = mem::bytes();

    if bytes.len() < RAW_EFFECT_HEADER_SIZE {
        return;
    }

    let header_bytes: [u8; RAW_EFFECT_HEADER_SIZE] =
        bytes[..RAW_EFFECT_HEADER_SIZE].try_into().unwrap();
    let header = RawEffectHeader::from(header_bytes);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        // Parse blur
        if header.has_blur() {
            let blur_type = RawBlurType::from(header.blur_type);
            shape.set_blur(Some(Blur::new(
                blur_type.into(),
                header.is_blur_hidden(),
                header.blur_value,
            )));
        } else {
            shape.set_blur(None);
        }

        // Parse shadows
        let shadow_count = header.shadow_count as usize;
        shape.clear_shadows();
        let shadows_data = &bytes[RAW_EFFECT_HEADER_SIZE..];
        for i in 0..shadow_count {
            let offset = i * RAW_SHADOW_ENTRY_SIZE;
            if offset + RAW_SHADOW_ENTRY_SIZE > shadows_data.len() {
                break;
            }

            let entry_bytes: [u8; RAW_SHADOW_ENTRY_SIZE] = shadows_data
                [offset..offset + RAW_SHADOW_ENTRY_SIZE]
                .try_into()
                .unwrap();
            let entry = RawShadowEntry::from(entry_bytes);

            let shadow = Shadow::new(
                skia::Color::new(entry.color),
                entry.blur,
                entry.spread,
                (entry.offset_x, entry.offset_y),
                RawShadowStyle::from(entry.style).into(),
                entry.is_hidden(),
            );
            shape.add_shadow(shadow);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_raw_effect_header_layout() {
        assert_eq!(RAW_EFFECT_HEADER_SIZE, 12);
        assert_eq!(std::mem::align_of::<RawEffectHeader>(), 4);
    }

    #[test]
    fn test_raw_shadow_entry_layout() {
        assert_eq!(RAW_SHADOW_ENTRY_SIZE, 24);
        assert_eq!(std::mem::align_of::<RawShadowEntry>(), 4);
    }

    #[test]
    fn test_header_field_offsets() {
        assert_eq!(std::mem::offset_of!(RawEffectHeader, blur_present), 0);
        assert_eq!(std::mem::offset_of!(RawEffectHeader, blur_type), 1);
        assert_eq!(std::mem::offset_of!(RawEffectHeader, blur_hidden), 2);
        assert_eq!(std::mem::offset_of!(RawEffectHeader, blur_value), 4);
        assert_eq!(std::mem::offset_of!(RawEffectHeader, shadow_count), 8);
    }

    #[test]
    fn test_shadow_entry_field_offsets() {
        assert_eq!(std::mem::offset_of!(RawShadowEntry, color), 0);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, blur), 4);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, spread), 8);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, offset_x), 12);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, offset_y), 16);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, style), 20);
        assert_eq!(std::mem::offset_of!(RawShadowEntry, hidden), 21);
    }

    #[test]
    fn test_header_deserialization() {
        let mut bytes = [0u8; RAW_EFFECT_HEADER_SIZE];
        bytes[0] = 1; // blur_present
        bytes[1] = 1; // blur_type = LayerBlur
        bytes[2] = 0; // blur_hidden = false
        bytes[4..8].copy_from_slice(&5.0_f32.to_le_bytes()); // blur_value
        bytes[8..12].copy_from_slice(&3_u32.to_le_bytes()); // shadow_count

        let header = RawEffectHeader::from(bytes);
        assert!(header.has_blur());
        assert!(!header.is_blur_hidden());
        assert_eq!(header.blur_type, 1);
        assert_eq!(header.blur_value, 5.0);
        assert_eq!(header.shadow_count, 3);
    }

    #[test]
    fn test_shadow_entry_deserialization() {
        let mut bytes = [0u8; RAW_SHADOW_ENTRY_SIZE];
        bytes[0..4].copy_from_slice(&0xFF0000FF_u32.to_le_bytes()); // color
        bytes[4..8].copy_from_slice(&4.0_f32.to_le_bytes()); // blur
        bytes[8..12].copy_from_slice(&2.0_f32.to_le_bytes()); // spread
        bytes[12..16].copy_from_slice(&10.0_f32.to_le_bytes()); // offset_x
        bytes[16..20].copy_from_slice(&20.0_f32.to_le_bytes()); // offset_y
        bytes[20] = 0; // style = DropShadow
        bytes[21] = 1; // hidden = true

        let entry = RawShadowEntry::from(bytes);
        assert_eq!(entry.color, 0xFF0000FF);
        assert_eq!(entry.blur, 4.0);
        assert_eq!(entry.spread, 2.0);
        assert_eq!(entry.offset_x, 10.0);
        assert_eq!(entry.offset_y, 20.0);
        assert_eq!(entry.style, 0);
        assert!(entry.is_hidden());
    }
}
