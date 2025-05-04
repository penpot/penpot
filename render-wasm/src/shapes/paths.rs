use skia_safe::{self as skia, Matrix};
use std::array::TryFromSliceError;

type Point = (f32, f32);

fn stringify_slice_err(_: TryFromSliceError) -> String {
    format!("Error deserializing path")
}

#[derive(Debug)]
pub struct RawPathData {
    pub data: [u8; 28],
}

impl RawPathData {
    fn command(&self) -> Result<u16, String> {
        let cmd = u16::from_be_bytes(self.data[0..2].try_into().map_err(stringify_slice_err)?);
        Ok(cmd)
    }

    fn xy(&self) -> Result<Point, String> {
        let x = f32::from_be_bytes(self.data[20..24].try_into().map_err(stringify_slice_err)?);
        let y = f32::from_be_bytes(self.data[24..].try_into().map_err(stringify_slice_err)?);
        Ok((x, y))
    }

    fn c1(&self) -> Result<Point, String> {
        let c1_x = f32::from_be_bytes(self.data[4..8].try_into().map_err(stringify_slice_err)?);
        let c1_y = f32::from_be_bytes(self.data[8..12].try_into().map_err(stringify_slice_err)?);

        Ok((c1_x, c1_y))
    }

    fn c2(&self) -> Result<Point, String> {
        let c2_x = f32::from_be_bytes(self.data[12..16].try_into().map_err(stringify_slice_err)?);
        let c2_y = f32::from_be_bytes(self.data[16..20].try_into().map_err(stringify_slice_err)?);

        Ok((c2_x, c2_y))
    }
}

const MOVE_TO: u16 = 1;
const LINE_TO: u16 = 2;
const CURVE_TO: u16 = 3;
const CLOSE: u16 = 4;

#[derive(Debug, PartialEq, Copy, Clone)]
enum Segment {
    MoveTo(Point),
    LineTo(Point),
    CurveTo((Point, Point, Point)),
    Close,
}

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

#[derive(Debug, Clone, PartialEq)]
pub struct Path {
    segments: Vec<Segment>,
    skia_path: skia::Path,
    open: bool,
}

impl Default for Path {
    fn default() -> Self {
        Path::try_from(Vec::new()).unwrap()
    }
}

impl TryFrom<Vec<RawPathData>> for Path {
    type Error = String;

    fn try_from(value: Vec<RawPathData>) -> Result<Self, Self::Error> {
        let mut open = true;
        let segments = value
            .into_iter()
            .map(|raw| Segment::try_from(raw))
            .collect::<Result<Vec<Segment>, String>>()?;

        let mut skia_path = skia::Path::new();
        let mut start = None;

        for segment in segments.iter() {
            let destination = match *segment {
                Segment::MoveTo(xy) => {
                    start = Some(xy);
                    skia_path.move_to(xy);
                    None
                }
                Segment::LineTo(xy) => {
                    skia_path.line_to(xy);
                    Some(xy)
                }
                Segment::CurveTo((c1, c2, xy)) => {
                    skia_path.cubic_to(c1, c2, xy);
                    Some(xy)
                }
                Segment::Close => {
                    skia_path.close();
                    open = false;
                    None
                }
            };
            if let (Some(start), Some(destination)) = (start, destination) {
                if destination == start {
                    skia_path.close();
                    open = false;
                }
            }
        }
        Ok(Path {
            segments,
            skia_path,
            open,
        })
    }
}

impl Path {
    pub fn to_skia_path(&self) -> skia::Path {
        self.skia_path.snapshot()
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn transform(&mut self, mtx: &Matrix) {
        self.skia_path.transform(mtx);
    }
}
