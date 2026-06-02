use skia_safe::Rect;

use crate::math::{Matrix, Point};

#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox {
    pub pan_x: f32,
    pub pan_y: f32,
    pub width: f32,
    pub height: f32,
    pub zoom: f32,
    pub area: Rect,
}

impl Default for Viewbox {
    fn default() -> Self {
        Self {
            pan_x: 0.,
            pan_y: 0.,
            width: 0.0,
            height: 0.0,
            zoom: 1.0,
            area: Rect::new_empty(),
        }
    }
}

impl Viewbox {
    pub fn new(width: f32, height: f32) -> Self {
        let area = Rect::from_xywh(0., 0., width, height);
        Self {
            width,
            height,
            area,
            ..Self::default()
        }
    }

    pub fn set_all(&mut self, zoom: f32, pan_x: f32, pan_y: f32) {
        self.pan_x = pan_x;
        self.pan_y = pan_y;
        self.zoom = zoom;
        self.area.set_xywh(
            -self.pan_x,
            -self.pan_y,
            self.width / self.zoom,
            self.height / self.zoom,
        );
    }

    pub fn set_wh(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
        self.area
            .set_wh(self.width / self.zoom, self.height / self.zoom);
    }

    pub fn pan(&self) -> Point {
        Point::new(self.pan_x, self.pan_y)
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
