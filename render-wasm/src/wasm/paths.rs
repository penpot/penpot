use std::array::TryFromSliceError;

use crate::shapes::{Path, Segment};
use crate::{mem, with_current_shape, STATE};

type Point = (f32, f32);

fn stringify_slice_err(_: TryFromSliceError) -> String {
    "Error deserializing path".to_string()
}

#[derive(Debug)]
pub struct RawPathData {
    pub data: [u8; 28],
}

impl RawPathData {
    fn command(&self) -> Result<u16, String> {
        let cmd = u16::from_le_bytes(self.data[0..2].try_into().map_err(stringify_slice_err)?);
        Ok(cmd)
    }

    fn xy(&self) -> Result<Point, String> {
        let x = f32::from_le_bytes(self.data[20..24].try_into().map_err(stringify_slice_err)?);
        let y = f32::from_le_bytes(self.data[24..].try_into().map_err(stringify_slice_err)?);
        Ok((x, y))
    }

    fn c1(&self) -> Result<Point, String> {
        let c1_x = f32::from_le_bytes(self.data[4..8].try_into().map_err(stringify_slice_err)?);
        let c1_y = f32::from_le_bytes(self.data[8..12].try_into().map_err(stringify_slice_err)?);

        Ok((c1_x, c1_y))
    }

    fn c2(&self) -> Result<Point, String> {
        let c2_x = f32::from_le_bytes(self.data[12..16].try_into().map_err(stringify_slice_err)?);
        let c2_y = f32::from_le_bytes(self.data[16..20].try_into().map_err(stringify_slice_err)?);

        Ok((c2_x, c2_y))
    }
}

const MOVE_TO: u16 = 1;
const LINE_TO: u16 = 2;
const CURVE_TO: u16 = 3;
const CLOSE: u16 = 4;

impl TryFrom<RawPathData> for Segment {
    type Error = String;
    fn try_from(value: RawPathData) -> Result<Self, Self::Error> {
        let cmd = value.command()?;
        match cmd {
            MOVE_TO => Ok(Segment::MoveTo(value.xy()?)),
            LINE_TO => Ok(Segment::LineTo(value.xy()?)),
            CURVE_TO => Ok(Segment::CurveTo((value.c1()?, value.c2()?, value.xy()?))),
            CLOSE => Ok(Segment::Close),
            _ => Err(format!(
                "Error deserializing path. Unknown command/flags: {:#010x}",
                cmd
            )),
        }
    }
}

impl TryFrom<Vec<RawPathData>> for Path {
    type Error = String;

    fn try_from(value: Vec<RawPathData>) -> Result<Self, Self::Error> {
        let segments = value
            .into_iter()
            .map(Segment::try_from)
            .collect::<Result<Vec<Segment>, String>>()?;

        Ok(Path::new(segments))
    }
}

#[no_mangle]
pub extern "C" fn set_shape_path_content() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let raw_segments = bytes
            .chunks(size_of::<RawPathData>())
            .map(|data| RawPathData {
                data: data.try_into().unwrap(),
            })
            .collect();
        shape.set_path_segments(raw_segments).unwrap();
    });
}
