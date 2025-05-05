use crate::shapes::{Color, Gradient};

const MAX_GRADIENT_STOPS: usize = 16;
const BASE_GRADIENT_DATA_SIZE: usize = 28;
const RAW_GRADIENT_DATA_SIZE: usize =
    BASE_GRADIENT_DATA_SIZE + RAW_STOP_DATA_SIZE * MAX_GRADIENT_STOPS;

#[derive(Debug, PartialEq, Clone, Copy)]
#[repr(C)]
pub struct RawGradientData {
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
    stop_count: u8,
    _pad: [u8; 3],
    stops: [RawStopData; MAX_GRADIENT_STOPS],
}

impl From<[u8; RAW_GRADIENT_DATA_SIZE]> for RawGradientData {
    fn from(bytes: [u8; RAW_GRADIENT_DATA_SIZE]) -> Self {
        Self {
            start_x: f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            start_y: f32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            end_x: f32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            end_y: f32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            opacity: f32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
            width: f32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
            stop_count: bytes[24],
            _pad: [0; 3],
            // FIXME: 2025-04-22: use `array_chunks` once the next release is out
            //        and we update our devenv.
            // See https://github.com/rust-lang/rust/issues/74985
            stops: bytes[28..]
                .chunks_exact(RAW_STOP_DATA_SIZE)
                .map(|chunk| RawStopData::try_from(chunk).unwrap())
                .collect::<Vec<_>>()
                .try_into()
                .unwrap(),
        }
    }
}

impl TryFrom<&[u8]> for RawGradientData {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_GRADIENT_DATA_SIZE] = bytes
            .try_into()
            .map_err(|_| "Invalid gradient data".to_string())?;
        Ok(RawGradientData::from(data))
    }
}

impl RawGradientData {
    pub fn start(&self) -> (f32, f32) {
        (self.start_x, self.start_y)
    }

    pub fn end(&self) -> (f32, f32) {
        (self.end_x, self.end_y)
    }
}

pub const RAW_STOP_DATA_SIZE: usize = 8;

#[derive(Debug, PartialEq, Clone, Copy)]
#[repr(C)]
struct RawStopData {
    color: u32,
    offset: f32,
}

impl RawStopData {
    pub fn color(&self) -> Color {
        Color::from(self.color)
    }

    pub fn offset(&self) -> f32 {
        self.offset
    }
}

impl From<[u8; RAW_STOP_DATA_SIZE]> for RawStopData {
    fn from(bytes: [u8; RAW_STOP_DATA_SIZE]) -> Self {
        Self {
            color: u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            offset: f32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
        }
    }
}

// FIXME: We won't need this once we use `array_chunks`. See comment above.
impl TryFrom<&[u8]> for RawStopData {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_STOP_DATA_SIZE] = bytes
            .try_into()
            .map_err(|_| "Invalid stop data".to_string())?;
        Ok(RawStopData::from(data))
    }
}

impl From<RawGradientData> for Gradient {
    fn from(raw_gradient: RawGradientData) -> Self {
        let stops = raw_gradient
            .stops
            .iter()
            .take(raw_gradient.stop_count as usize)
            .map(|stop| (stop.color(), stop.offset()))
            .collect::<Vec<_>>();

        Gradient::new(
            raw_gradient.start(),
            raw_gradient.end(),
            (raw_gradient.opacity * 255.) as u8,
            raw_gradient.width,
            &stops,
        )
    }
}

impl TryFrom<&[u8]> for Gradient {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let raw_gradient_bytes: [u8; RAW_GRADIENT_DATA_SIZE] = bytes[0..RAW_GRADIENT_DATA_SIZE]
            .try_into()
            .map_err(|_| "Invalid gradient data".to_string())?;
        let gradient = RawGradientData::from(raw_gradient_bytes).into();

        Ok(gradient)
    }
}
