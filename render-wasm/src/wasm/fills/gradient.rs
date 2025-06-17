use crate::shapes::{Color, Gradient};

const MAX_GRADIENT_STOPS: usize = 16;

#[derive(Debug, PartialEq, Clone, Copy)]
#[repr(C)]
#[repr(align(4))]
pub struct RawGradientData {
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
    stop_count: u8,
    stops: [RawStopData; MAX_GRADIENT_STOPS],
}

impl RawGradientData {
    pub fn start(&self) -> (f32, f32) {
        (self.start_x, self.start_y)
    }

    pub fn end(&self) -> (f32, f32) {
        (self.end_x, self.end_y)
    }
}

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
