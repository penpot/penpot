use std::ops::{Mul};
use crate::math::{Matrix, Point, Rect, Size};

#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox {
    pub pan: Point,
    pub size: Size,
    pub zoom: f32,
    pub dpr: f32,
    pub area: Rect,
}

impl Default for Viewbox {
    fn default() -> Self {
        Self {
            pan: Point::new(0.0, 0.0),
            size: Size::new(0.0, 0.0),
            zoom: 1.0,
            dpr: 1.0,
            area: Rect::new_empty(),
        }
    }
}

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

    pub fn set_dpr(&mut self, dpr: f32) -> f32 {
        self.dpr = dpr;
        dpr
    }

    pub fn set_all(&mut self, zoom: f32, pan_x: f32, pan_y: f32) {
        self.pan.set(pan_x, pan_y);
        self.zoom = zoom;
        self.area.set_xywh(
            -self.pan.x,
            -self.pan.y,
            self.size.width / self.zoom,
            self.size.height / self.zoom,
        );
    }

    pub fn set_wh(&mut self, width: f32, height: f32) {
        self.size.set(width, height);
        self.area
            .set_wh(self.size.width / self.zoom, self.size.height / self.zoom);
    }

    pub fn pan(&self) -> Point {
        self.pan
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
}
