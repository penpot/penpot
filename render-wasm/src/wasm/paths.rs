#![allow(unused_mut, unused_variables)]
use indexmap::IndexSet;
use mem::SerializableResult;
use uuid::Uuid;

use crate::math::bools;
use crate::shapes::{BoolType, Path, Segment, ToPath};
use crate::uuid;
use crate::{mem, with_current_shape, with_current_shape_mut, with_state, STATE};

const RAW_SEGMENT_DATA_SIZE: usize = size_of::<RawSegmentData>();

#[repr(C, u16, align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
#[allow(dead_code)]
enum RawSegmentData {
    MoveTo(RawMoveCommand) = 0x01,
    LineTo(RawLineCommand) = 0x02,
    CurveTo(RawCurveCommand) = 0x03,
    Close = 0x04,
}

impl RawSegmentData {
    pub fn from_segment(segment: Segment) -> Self {
        match segment {
            Segment::MoveTo(to) => RawSegmentData::MoveTo(RawMoveCommand::new(to)),
            Segment::LineTo(to) => RawSegmentData::LineTo(RawLineCommand::new(to)),
            Segment::CurveTo((c1, c2, to)) => {
                RawSegmentData::CurveTo(RawCurveCommand::new(c1, c2, to))
            }
            Segment::Close => RawSegmentData::Close,
        }
    }
}

impl From<[u8; size_of::<RawSegmentData>()]> for RawSegmentData {
    fn from(bytes: [u8; size_of::<RawSegmentData>()]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawSegmentData {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_SEGMENT_DATA_SIZE] = bytes
            .get(0..RAW_SEGMENT_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid path data".to_string())?;
        Ok(RawSegmentData::from(data))
    }
}

impl SerializableResult for RawSegmentData {
    type BytesType = [u8; RAW_SEGMENT_DATA_SIZE];

    fn from_bytes(bytes: Self::BytesType) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }

    fn as_bytes(&self) -> Self::BytesType {
        let ptr = self as *const RawSegmentData as *const u8;
        let bytes: &[u8] = unsafe { std::slice::from_raw_parts(ptr, RAW_SEGMENT_DATA_SIZE) };
        let mut result = [0; RAW_SEGMENT_DATA_SIZE];
        result.copy_from_slice(bytes);
        result
    }

    // The generic trait doesn't know the size of the array. This is why the
    // clone needs to be here even if it could be generic.
    fn clone_to_slice(&self, slice: &mut [u8]) {
        slice.clone_from_slice(&self.as_bytes());
    }
}

#[repr(C, align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
struct RawMoveCommand {
    _padding: [u32; 4],
    x: f32,
    y: f32,
}
impl RawMoveCommand {
    pub fn new((x, y): (f32, f32)) -> Self {
        Self {
            _padding: [0u32; 4],
            x,
            y,
        }
    }
}

#[repr(C, align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
struct RawLineCommand {
    _padding: [u32; 4],
    x: f32,
    y: f32,
}

impl RawLineCommand {
    pub fn new((x, y): (f32, f32)) -> Self {
        Self {
            _padding: [0u32; 4],
            x,
            y,
        }
    }
}

#[repr(C, align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
struct RawCurveCommand {
    c1_x: f32,
    c1_y: f32,
    c2_x: f32,
    c2_y: f32,
    x: f32,
    y: f32,
}

impl RawCurveCommand {
    pub fn new((c1_x, c1_y): (f32, f32), (c2_x, c2_y): (f32, f32), (x, y): (f32, f32)) -> Self {
        Self {
            c1_x,
            c1_y,
            c2_x,
            c2_y,
            x,
            y,
        }
    }
}

impl From<RawSegmentData> for Segment {
    fn from(value: RawSegmentData) -> Self {
        match value {
            RawSegmentData::MoveTo(cmd) => Segment::MoveTo((cmd.x, cmd.y)),
            RawSegmentData::LineTo(cmd) => Segment::LineTo((cmd.x, cmd.y)),
            RawSegmentData::CurveTo(cmd) => {
                Segment::CurveTo(((cmd.c1_x, cmd.c1_y), (cmd.c2_x, cmd.c2_y), (cmd.x, cmd.y)))
            }
            RawSegmentData::Close => Segment::Close,
        }
    }
}

impl From<Vec<RawSegmentData>> for Path {
    fn from(value: Vec<RawSegmentData>) -> Self {
        let segments = value.into_iter().map(Segment::from).collect();
        Path::new(segments)
    }
}

#[no_mangle]
pub extern "C" fn set_shape_path_content() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();

        let segments = bytes
            .chunks(size_of::<RawSegmentData>())
            .map(|chunk| RawSegmentData::try_from(chunk).expect("Invalid path data"))
            .map(Segment::from)
            .collect();

        shape.set_path_segments(segments);
    });
}

#[no_mangle]
pub extern "C" fn current_to_path() -> *mut u8 {
    let mut result = Vec::<RawSegmentData>::default();
    with_current_shape!(state, |shape: &Shape| {
        let path = shape.to_path(&state.shapes, &state.modifiers, &state.structure);
        result = path
            .segments()
            .iter()
            .copied()
            .map(RawSegmentData::from_segment)
            .collect();
    });

    mem::write_vec(result)
}

#[no_mangle]
pub extern "C" fn calculate_bool(raw_bool_type: u8) -> *mut u8 {
    let bytes = mem::bytes_or_empty();

    let entries: IndexSet<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::from_bytes(data.try_into().unwrap()))
        .collect();

    mem::free_bytes();

    let bool_type = BoolType::from(raw_bool_type);
    let result;
    with_state!(state, {
        let path = bools::bool_from_shapes(
            bool_type,
            &entries,
            &state.shapes,
            &state.modifiers,
            &state.structure,
        );
        result = path
            .segments()
            .iter()
            .copied()
            .map(RawSegmentData::from_segment)
            .collect();
    });
    mem::write_vec(result)
}

// Extracts a string from the bytes slice until the next null byte (0) and returns the result as a `String`.
// Updates the `start` index to the end of the extracted string.
fn extract_string(start: &mut usize, bytes: &[u8]) -> String {
    match bytes[*start..].iter().position(|&b| b == 0) {
        Some(pos) => {
            let end = *start + pos;
            let slice = &bytes[*start..end];
            *start = end + 1; // Move the `start` pointer past the null byte
                              // Call to unsafe function within an unsafe block
            unsafe { String::from_utf8_unchecked(slice.to_vec()) }
        }
        None => {
            *start = bytes.len(); // Move `start` to the end if no null byte is found
            String::new()
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_path_attrs(num_attrs: u32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let mut start = 0;
        for _ in 0..num_attrs {
            let name = extract_string(&mut start, &bytes);
            let value = extract_string(&mut start, &bytes);
            shape.set_path_attr(name, value);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_move_command_deserialization() {
        let mut bytes = [0x00; size_of::<RawSegmentData>()];
        bytes[0..2].copy_from_slice(&0x01_u16.to_le_bytes());
        bytes[20..24].copy_from_slice(&1.0_f32.to_le_bytes());
        bytes[24..28].copy_from_slice(&2.0_f32.to_le_bytes());

        let raw_segment = RawSegmentData::try_from(&bytes[..]).unwrap();
        let segment = Segment::from(raw_segment);

        assert_eq!(segment, Segment::MoveTo((1.0, 2.0)));
    }

    #[test]
    fn test_line_command_deserialization() {
        let mut bytes = [0x00; size_of::<RawSegmentData>()];
        bytes[0..2].copy_from_slice(&0x02_u16.to_le_bytes());
        bytes[20..24].copy_from_slice(&3.0_f32.to_le_bytes());
        bytes[24..28].copy_from_slice(&4.0_f32.to_le_bytes());

        let raw_segment = RawSegmentData::try_from(&bytes[..]).unwrap();
        let segment = Segment::from(raw_segment);

        assert_eq!(segment, Segment::LineTo((3.0, 4.0)));
    }

    #[test]
    fn test_curve_command_deserialization() {
        let mut bytes = [0x00; size_of::<RawSegmentData>()];
        bytes[0..2].copy_from_slice(&0x03_u16.to_le_bytes());
        bytes[4..8].copy_from_slice(&1.0_f32.to_le_bytes());
        bytes[8..12].copy_from_slice(&2.0_f32.to_le_bytes());
        bytes[12..16].copy_from_slice(&3.0_f32.to_le_bytes());
        bytes[16..20].copy_from_slice(&4.0_f32.to_le_bytes());
        bytes[20..24].copy_from_slice(&5.0_f32.to_le_bytes());
        bytes[24..28].copy_from_slice(&6.0_f32.to_le_bytes());

        let raw_segment = RawSegmentData::try_from(&bytes[..]).unwrap();
        let segment = Segment::from(raw_segment);

        assert_eq!(
            segment,
            Segment::CurveTo(((1.0, 2.0), (3.0, 4.0), (5.0, 6.0)))
        );
    }

    #[test]
    fn test_close_command_deserialization() {
        let mut bytes = [0x00; size_of::<RawSegmentData>()];
        bytes[0..2].copy_from_slice(&0x04_u16.to_le_bytes());

        let raw_segment = RawSegmentData::try_from(&bytes[..]).unwrap();
        let segment = Segment::from(raw_segment);

        assert_eq!(segment, Segment::Close);
    }
}
