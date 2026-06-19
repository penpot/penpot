use crate::math::{Matrix, Point, Rect, Size};
use std::ops::Mul;

#[repr(u32)]
pub enum ViewboxUpdated {
    None = 0b0000,
    Position = 0b0001,
    Zoom = 0b0010,
    Size = 0b0100,
    All = 0b0111,
}

#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox {
    pub position: Point,
    pub size: Size,
    pub zoom: f32,
    pub dpr: f32,
    pub area: Rect,
    pub updated: u32,
}

impl Default for Viewbox {
    fn default() -> Self {
        Self {
            position: Point::new(0.0, 0.0),
            size: Size::new(0.0, 0.0),
            zoom: 1.0,
            dpr: 1.0,
            area: Rect::new_empty(),
            updated: ViewboxUpdated::All as u32,
        }
    }
}

#[allow(dead_code)]
impl Viewbox {
    pub fn new(width: f32, height: f32) -> Self {
        let size = Size::new(width, height);
        let area = Rect::from_size(size);
        Self {
            size,
            area,
            ..Self::default()
        }
    }

    pub fn dpr_width(&self) -> f32 {
        self.size.width * self.dpr
    }

    pub fn dpr_height(&self) -> f32 {
        self.size.height * self.dpr
    }

    pub fn width(&self) -> f32 {
        self.size.width
    }

    pub fn height(&self) -> f32 {
        self.size.height
    }

    pub fn set_all(&mut self, zoom: f32, x: f32, y: f32) {
        self.set_position(x, y);
        self.set_zoom(zoom);
        if self.updated != ViewboxUpdated::None as u32 {
            self.area.set_xywh(
                -self.position.x,
                -self.position.y,
                self.size.width / self.zoom,
                self.size.height / self.zoom,
            );
        }
    }

    pub fn set_position(&mut self, x: f32, y: f32) {
        if self.position.x != x {
            self.position.x = x;
            self.updated |= ViewboxUpdated::Position as u32;
        }
        if self.position.y != y {
            self.position.y = y;
            self.updated |= ViewboxUpdated::Position as u32;
        }
    }

    pub fn set_zoom(&mut self, zoom: f32) {
        if self.zoom != zoom {
            self.zoom = zoom;
            self.updated = ViewboxUpdated::Zoom as u32;
        }
    }

    pub fn set_wh(&mut self, width: f32, height: f32) {
        self.size.set(width, height);
        self.area
            .set_wh(self.size.width / self.zoom, self.size.height / self.zoom);
        self.updated = ViewboxUpdated::Size as u32;
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        self.dpr = dpr;
    }

    pub fn get_scale(&self) -> f32 {
        self.zoom * self.dpr
    }

    pub fn get_offset(&self) -> Point {
        self.area.tl().mul(self.get_scale())
    }

    pub fn pan(&self) -> Point {
        self.position
    }

    pub fn zoom(&self) -> f32 {
        self.zoom
    }

    pub fn get_matrix(&self) -> Matrix {
        let mut matrix = Matrix::new_identity();
        matrix.post_translate(self.pan());
        matrix.post_scale((self.zoom, self.zoom), None);
        matrix
    }

    pub fn is_updated(&self, flags: u32) -> bool {
        self.updated & flags == flags
    }

    pub fn is_zoom_changed(&self) -> bool {
        self.is_updated(ViewboxUpdated::Zoom as u32)
    }

    pub fn is_position_changed(&self) -> bool {
        self.is_updated(ViewboxUpdated::Position as u32)
    }

    pub fn is_size_changed(&self) -> bool {
        self.is_updated(ViewboxUpdated::Size as u32)
    }

    pub fn update_handled(&mut self) {
        self.updated = ViewboxUpdated::None as u32;
    }
}
