use skia_safe as skia;

use crate::mem;
use crate::shapes::noise::{NoiseEffect, NoiseSlot, SlotKind, MAX_NOISE_SLOTS};
use crate::{with_current_shape_mut, STATE};

/// Shared-memory layout (little-endian):
///   [u32 count] [u8 kind_0][u8 kind_1]…[u8 kind_{count-1}] [pad] [u32 c0][u32 c1]…
///
/// `count` is clamped to MAX_NOISE_SLOTS on both sides.
///
/// The `kinds` block is padded with zeros so the colors block starts on a 4-byte
/// boundary — matches the fills.rs writer pattern and avoids unaligned reads.
#[no_mangle]
pub extern "C" fn set_shape_noise(
    noise_size: f32,
    density: f32,
    softness: f32,
    apply_to_fill: bool,
    hidden: bool,
) {
    let bytes = mem::bytes();
    let count = if bytes.len() >= 4 {
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as usize
    } else {
        0
    };
    let count = count.min(MAX_NOISE_SLOTS);

    // Colors are written after [u32 count][kinds…][zero-padding to 4B].
    let colors_offset = 4 + ((count + 3) & !3);

    let mut slots: Vec<NoiseSlot> = Vec::with_capacity(count);
    for i in 0..count {
        let kind_byte = bytes.get(4 + i).copied().unwrap_or(0);
        let color_off = colors_offset + i * 4;
        if color_off + 4 > bytes.len() {
            break;
        }
        let raw = u32::from_le_bytes([
            bytes[color_off],
            bytes[color_off + 1],
            bytes[color_off + 2],
            bytes[color_off + 3],
        ]);
        slots.push(NoiseSlot {
            kind: SlotKind::from(kind_byte),
            color: skia::Color::new(raw),
        });
    }

    with_current_shape_mut!(state, |shape: &mut Shape| {
        let noise = NoiseEffect::new(
            slots.clone(),
            noise_size,
            density,
            softness,
            apply_to_fill,
            hidden,
        );
        shape.set_noise(Some(noise));
    });

    let _ = mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn clear_shape_noise() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_noise(None);
    });
}
