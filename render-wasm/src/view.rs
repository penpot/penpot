use crate::math::{Matrix, Point, Rect, Size};
use std::ops::Mul;

#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox {
    pan: Point,
    size: Size,
    zoom: f32,
    dpr: f32,
    area: Rect,
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

    pub fn set_dpr(&mut self, dpr: f32) -> f32 {
        self.dpr = dpr;
        self.dpr
    }

    pub fn set_zoom(&mut self, new_zoom: f32) -> f32 {
        self.zoom = new_zoom;
        self.zoom
    }

    pub fn get_scale(&self) -> f32 {
        self.zoom * self.dpr
    }

    pub fn offset(&self) -> Point {
        self.area.tl().mul(self.get_scale())
    }

    pub fn area(&self) -> Rect {
        self.area
    }

    pub fn pan(&self) -> Point {
        self.pan
    }

    pub fn zoom(&self) -> f32 {
        self.zoom
    }

    pub fn scale(&self) -> (f32, f32) {
        (self.get_scale(), self.get_scale())
    }

    pub fn get_matrix(&self) -> Matrix {
        let mut matrix = Matrix::new_identity();
        matrix.post_translate(self.pan());
        matrix.post_scale((self.zoom, self.zoom), None);
        matrix
    }
}
