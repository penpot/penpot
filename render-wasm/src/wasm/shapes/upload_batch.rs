//! Multi-shape / enlarged cold-upload batch protocol.
//!
//! Buffer layout:
//! ```text
//! [u32 shape_count]
//! repeat shape_count times:
//!   [u32 payload_len]          // bytes after this u32
//!   [104 base props]           // RawBasePropsData
//!   [u32 section_mask]
//!   optional sections (presence via mask; order is FIXED, not bit-numeric):
//!     CHILDREN:    [u32 n][n × 16 uuid]
//!     BLUR_LAYER:  [u8 hidden][u8;3 pad][f32 value]
//!     BLUR_BG:     same
//!     SHADOWS:     [u32 n][n × 24]
//!     MASKED:      [u8 value][u8;3 pad]
//!     BOOL_TYPE:   [u8 value][u8;3 pad]
//!     GROW_TYPE:   [u8 value][u8;3 pad]
//!     FLEX:        32 bytes  (clears container layout, then sets flex)
//!     LAYOUT_ITEM: 40 bytes  (must follow FLEX so clear_layout does not wipe it)
//!     FILLS:       [u8 n][u8;3][n × RawFillData]  (same as set_shape_fills)
//!     STROKES:     [u32 n][n × (36-byte header + RawFillData)]
//! ```
//!
//! Text, path geometry, and grid tracks/cells stay on the legacy
//! per-shape FFI path after the batch flush.

use skia_safe as skia;

use crate::mem;
use crate::shapes::{Blur, BlurType, Shadow, ShadowStyle, Stroke, Type};
use crate::utils::{decode_optional_f32, uuid_from_u32_quartet};
use crate::uuid::Uuid;
use crate::wasm::fills::{read_fills_from_bytes, RawFillData, RAW_FILL_DATA_SIZE};
use crate::wasm::layouts::{
    RawAlignContent, RawAlignItems, RawAlignSelf, RawFlexDirection, RawJustifyContent,
    RawJustifyItems, RawSizing, RawWrapType,
};
use crate::wasm::paths::bools::RawBoolType;
use crate::wasm::shadows::RawShadowStyle;
use crate::wasm::shapes::base_props::{apply_base_props, RawBasePropsData, RAW_BASE_PROPS_SIZE};
use crate::wasm::strokes::{RawStrokeCap, RawStrokeStyle};
use crate::wasm::text::RawGrowType;
use crate::with_current_shape_mut;
use crate::with_state;

#[allow(unused_imports)]
use crate::error::{Error, Result};
use macros::wasm_error;

const SECTION_CHILDREN: u32 = 1 << 0;
const SECTION_BLUR_LAYER: u32 = 1 << 1;
const SECTION_BLUR_BG: u32 = 1 << 2;
const SECTION_SHADOWS: u32 = 1 << 3;
const SECTION_MASKED: u32 = 1 << 4;
const SECTION_BOOL_TYPE: u32 = 1 << 5;
const SECTION_GROW_TYPE: u32 = 1 << 6;
const SECTION_LAYOUT_ITEM: u32 = 1 << 7;
const SECTION_FLEX: u32 = 1 << 8;
const SECTION_FILLS: u32 = 1 << 9;
const SECTION_STROKES: u32 = 1 << 10;

const STROKE_ALIGN_INNER: u8 = 1;
const STROKE_ALIGN_OUTER: u8 = 2;

struct Cursor<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.remaining() < n {
            return Err(Error::CriticalError(
                "upload_batch: truncated buffer".to_string(),
            ));
        }
        let slice = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(slice)
    }

    fn u32(&mut self) -> Result<u32> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn f32(&mut self) -> Result<f32> {
        let b = self.take(4)?;
        Ok(f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn i32(&mut self) -> Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn uuid(&mut self) -> Result<Uuid> {
        let a = self.u32()?;
        let b = self.u32()?;
        let c = self.u32()?;
        let d = self.u32()?;
        Ok(uuid_from_u32_quartet(a, b, c, d))
    }
}

fn read_base_props(cur: &mut Cursor<'_>) -> Result<RawBasePropsData> {
    let bytes = cur.take(RAW_BASE_PROPS_SIZE)?;
    let arr: [u8; RAW_BASE_PROPS_SIZE] = bytes
        .try_into()
        .map_err(|_| Error::CriticalError("upload_batch: bad base props".to_string()))?;
    Ok(RawBasePropsData::from(arr))
}

fn apply_blur(layer: bool, hidden: bool, value: f32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let blur_type = if layer {
            BlurType::LayerBlur
        } else {
            BlurType::BackgroundBlur
        };
        let blur = Some(Blur::new(blur_type, hidden, value));
        if layer {
            shape.set_blur(blur);
        } else {
            shape.set_background_blur(blur);
        }
    });
}

fn clear_blur(layer: bool) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if layer {
            shape.set_blur(None);
        } else {
            shape.set_background_blur(None);
        }
    });
}

fn apply_shadows(cur: &mut Cursor<'_>) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_shadows();
    });
    let n = cur.u32()? as usize;
    for _ in 0..n {
        let rgba = cur.u32()?;
        let blur = cur.f32()?;
        let spread = cur.f32()?;
        let x = cur.f32()?;
        let y = cur.f32()?;
        let style = cur.u8()?;
        let hidden = cur.u8()? != 0;
        let _pad = cur.take(2)?;
        with_current_shape_mut!(state, |shape: &mut Shape| {
            let color = skia::Color::new(rgba);
            let style: ShadowStyle = RawShadowStyle::from(style).into();
            shape.add_shadow(Shadow::new(color, blur, spread, (x, y), style, hidden));
        });
    }
    Ok(())
}

fn apply_layout_item(cur: &mut Cursor<'_>) -> Result<()> {
    let margin_top = cur.f32()?;
    let margin_right = cur.f32()?;
    let margin_bottom = cur.f32()?;
    let margin_left = cur.f32()?;
    let h_sizing = cur.u8()?;
    let v_sizing = cur.u8()?;
    let flags = cur.u8()?;
    let align_self = cur.u8()?;
    let max_h = cur.f32()?;
    let min_h = cur.f32()?;
    let max_w = cur.f32()?;
    let min_w = cur.f32()?;
    let z_index = cur.i32()?;

    let has_max_h = (flags & 0x01) != 0;
    let has_min_h = (flags & 0x02) != 0;
    let has_max_w = (flags & 0x04) != 0;
    let has_min_w = (flags & 0x08) != 0;
    let is_absolute = (flags & 0x10) != 0;

    let h_sizing = RawSizing::from(h_sizing);
    let v_sizing = RawSizing::from(v_sizing);
    let max_h = has_max_h.then(|| max_h.max(0.01));
    let min_h = has_min_h.then(|| min_h.clamp(0.01, max_h.unwrap_or(f32::INFINITY)));
    let max_w = has_max_w.then(|| max_w.max(0.01));
    let min_w = has_min_w.then(|| min_w.clamp(0.01, max_w.unwrap_or(f32::INFINITY)));
    let z_index = if z_index != 0 { Some(z_index) } else { None };
    let align_self = RawAlignSelf::from(align_self).try_into().ok();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_child_data(
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing.into(),
            v_sizing.into(),
            max_h,
            min_h,
            max_w,
            min_w,
            align_self,
            is_absolute,
            z_index,
        );
    });
    Ok(())
}

fn apply_flex(cur: &mut Cursor<'_>) -> Result<()> {
    let dir = cur.u8()?;
    let align_items = cur.u8()?;
    let align_content = cur.u8()?;
    let justify_items = cur.u8()?;
    let justify_content = cur.u8()?;
    let wrap_type = cur.u8()?;
    let _pad = cur.take(2)?;
    let row_gap = cur.f32()?;
    let column_gap = cur.f32()?;
    let padding_top = cur.f32()?;
    let padding_right = cur.f32()?;
    let padding_bottom = cur.f32()?;
    let padding_left = cur.f32()?;

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_layout();
        shape.set_flex_layout_data(
            RawFlexDirection::from(dir).into(),
            row_gap,
            column_gap,
            RawAlignItems::from(align_items).into(),
            RawAlignContent::from(align_content).into(),
            RawJustifyItems::from(justify_items).into(),
            RawJustifyContent::from(justify_content).into(),
            RawWrapType::from(wrap_type).into(),
            padding_top,
            padding_right,
            padding_bottom,
            padding_left,
        );
    });
    Ok(())
}

fn apply_shape_payload(payload: &[u8]) -> Result<()> {
    let mut cur = Cursor::new(payload);
    let raw = read_base_props(&mut cur)?;
    let mask = cur.u32()?;
    apply_base_props(&raw)?;

    if mask & SECTION_CHILDREN != 0 {
        let n = cur.u32()? as usize;
        let mut entries = Vec::with_capacity(n);
        for _ in 0..n {
            entries.push(cur.uuid()?);
        }
        with_state!(state, {
            state.set_current_shape_children(entries)?;
        });
    }

    if mask & SECTION_BLUR_LAYER != 0 {
        let hidden = cur.u8()? != 0;
        let _ = cur.take(3)?;
        let value = cur.f32()?;
        apply_blur(true, hidden, value);
    } else {
        clear_blur(true);
    }

    if mask & SECTION_BLUR_BG != 0 {
        let hidden = cur.u8()? != 0;
        let _ = cur.take(3)?;
        let value = cur.f32()?;
        apply_blur(false, hidden, value);
    } else {
        clear_blur(false);
    }

    if mask & SECTION_SHADOWS != 0 {
        apply_shadows(&mut cur)?;
    } else {
        with_current_shape_mut!(state, |shape: &mut Shape| {
            shape.clear_shadows();
        });
    }

    if mask & SECTION_MASKED != 0 {
        let masked = cur.u8()? != 0;
        let _ = cur.take(3)?;
        with_current_shape_mut!(state, |shape: &mut Shape| {
            shape.set_masked(masked);
        });
    }

    if mask & SECTION_BOOL_TYPE != 0 {
        let raw_bool = cur.u8()?;
        let _ = cur.take(3)?;
        with_current_shape_mut!(state, |shape: &mut Shape| {
            shape.set_bool_type(RawBoolType::from(raw_bool).into());
        });
    }

    if mask & SECTION_GROW_TYPE != 0 {
        let raw_grow = cur.u8()?;
        let _ = cur.take(3)?;
        with_current_shape_mut!(state, |shape: &mut Shape| {
            if let Type::Text(text_content) = &mut shape.shape_type {
                text_content.set_grow_type(RawGrowType::from(raw_grow).into());
            }
        });
    }

    // FLEX before LAYOUT_ITEM: clear_layout must not wipe the item we just set.
    // Only clear when this payload owns layout (workspace cold-load). Exporter /
    // serialize-shape! omit both bits and must not clobber existing layout.
    if mask & SECTION_FLEX != 0 {
        apply_flex(&mut cur)?;
    } else if mask & SECTION_LAYOUT_ITEM != 0 {
        with_current_shape_mut!(state, |shape: &mut Shape| {
            shape.clear_layout();
        });
    }

    if mask & SECTION_LAYOUT_ITEM != 0 {
        apply_layout_item(&mut cur)?;
    }

    let is_text_shape = with_state!(state, {
        state
            .current_shape()
            .is_some_and(|shape| matches!(shape.shape_type, Type::Text(_)))
    });

    if mask & SECTION_FILLS != 0 {
        let fills = parse_fills(&mut cur)?;
        if is_text_shape {
            with_current_shape_mut!(state, |shape: &mut Shape| {
                shape.set_deferred_batch_fills(fills);
            });
        } else {
            with_current_shape_mut!(state, |shape: &mut Shape| {
                shape.set_fills(fills);
            });
        }
    }

    if mask & SECTION_STROKES != 0 {
        let strokes = parse_strokes(&mut cur)?;
        if is_text_shape {
            with_current_shape_mut!(state, |shape: &mut Shape| {
                shape.set_deferred_batch_strokes(strokes);
            });
        } else {
            with_current_shape_mut!(state, |shape: &mut Shape| {
                shape.clear_strokes();
                for stroke in strokes {
                    shape.add_stroke(stroke);
                }
            });
        }
    }

    Ok(())
}

fn parse_fills(cur: &mut Cursor<'_>) -> Result<Vec<crate::shapes::Fill>> {
    let header = cur.take(4)?;
    let n = header[0] as usize;
    let bytes = if n == 0 {
        &[][..]
    } else {
        cur.take(n * RAW_FILL_DATA_SIZE)?
    };
    Ok(read_fills_from_bytes(bytes, n))
}

fn parse_strokes(cur: &mut Cursor<'_>) -> Result<Vec<Stroke>> {
    let n = cur.u32()? as usize;
    let mut strokes = Vec::with_capacity(n);
    for _ in 0..n {
        let width = cur.f32()?;
        let style = cur.u8()?;
        let align = cur.u8()?;
        let cap_start = cur.u8()?;
        let cap_end = cur.u8()?;
        let dash = cur.f32()?;
        let gap = cur.f32()?;
        let has_sides = cur.u8()? != 0;
        let _pad = cur.take(3)?;
        let top = cur.f32()?;
        let right = cur.f32()?;
        let bottom = cur.f32()?;
        let left = cur.f32()?;
        let fill_bytes = cur.take(RAW_FILL_DATA_SIZE)?;
        let fill = RawFillData::try_from(fill_bytes)
            .map_err(|e| Error::CriticalError(format!("upload_batch stroke fill: {e}")))?;

        let stroke_style = RawStrokeStyle::from(style);
        let cap_start = RawStrokeCap::from(cap_start);
        let cap_end = RawStrokeCap::from(cap_end);
        let dash = decode_optional_f32(dash);
        let gap = decode_optional_f32(gap);

        let mut stroke = match align {
            STROKE_ALIGN_INNER => Stroke::new_inner_stroke(
                width,
                stroke_style.into(),
                cap_start.try_into().ok(),
                cap_end.try_into().ok(),
                dash,
                gap,
            ),
            STROKE_ALIGN_OUTER => Stroke::new_outer_stroke(
                width,
                stroke_style.into(),
                cap_start.try_into().ok(),
                cap_end.try_into().ok(),
                dash,
                gap,
            ),
            _ => Stroke::new_center_stroke(
                width,
                stroke_style.into(),
                cap_start.try_into().ok(),
                cap_end.try_into().ok(),
                dash,
                gap,
            ),
        };
        if has_sides {
            stroke.widths = Some([top, right, bottom, left]);
        }
        stroke.fill = fill.into();
        strokes.push(stroke);
    }
    Ok(strokes)
}

/// Apply a multi-shape upload buffer previously written via `_alloc_bytes`.
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shapes_batch() -> Result<()> {
    let bytes = mem::bytes();
    if bytes.len() < 4 {
        return Ok(());
    }

    let mut cur = Cursor::new(&bytes);
    let count = cur.u32()? as usize;
    for _ in 0..count {
        let payload_len = cur.u32()? as usize;
        let payload = cur.take(payload_len)?;
        apply_shape_payload(payload)?;
    }

    Ok(())
}
