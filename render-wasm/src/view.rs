use crate::math::{Matrix, Point, Rect, Size};
use std::ops::Mul;

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

/// Scale `dpr` down so `floor(css * dpr)` fits in `max_dim` on both axes.
/// Used when a large viewport combined with a high DPR would exceed the GPU
/// (or our surface cap) on either axis.
pub fn clamp_dpr_for_surface(css_w: f32, css_h: f32, dpr: f32, max_dim: i32) -> f32 {
    let css_w = css_w.max(1.0);
    let css_h = css_h.max(1.0);
    let dpr = dpr.max(0.0);
    let max_dim = max_dim.max(1) as f32;
    let raw_w = (css_w * dpr).floor().max(1.0);
    let raw_h = (css_h * dpr).floor().max(1.0);
    let scale = (max_dim / raw_w).min(max_dim / raw_h).min(1.0);
    dpr * scale
}

#[cfg(test)]
mod tests {
    use super::clamp_dpr_for_surface;

    #[test]
    fn clamp_dpr_keeps_hidpi_viewport_under_cap() {
        let dpr = clamp_dpr_for_surface(2560.0, 1440.0, 2.0, 8192);
        assert!((dpr - 2.0).abs() < 1e-5);
        assert!((2560.0 * dpr).floor() <= 8192.0);
    }

    #[test]
    fn clamp_dpr_caps_very_large_viewport_at_dpr2() {
        // 10240×5760 CSS at DPR 2 → 20480 px unclamped on the long edge.
        let dpr = clamp_dpr_for_surface(10240.0, 5760.0, 2.0, 8192);
        assert!((10240.0 * dpr).floor() <= 8192.0);
        assert!((5760.0 * dpr).floor() <= 8192.0);
        assert!(dpr < 2.0);
    }

    #[test]
    fn clamp_dpr_caps_large_viewport_at_dpr2() {
        let dpr = clamp_dpr_for_surface(5120.0, 2880.0, 2.0, 8192);
        assert!((5120.0 * dpr).floor() <= 8192.0);
        assert!(dpr < 2.0);
        assert!(dpr > 1.0);
    }

    #[test]
    fn clamp_dpr_physical_size_is_floor_of_css_times_dpr() {
        let css_w = 5120.0;
        let css_h = 2880.0;
        let dpr = clamp_dpr_for_surface(css_w, css_h, 2.0, 8192);
        let phys_w = (css_w * dpr).floor();
        let phys_h = (css_h * dpr).floor();
        assert!(phys_w <= 8192.0);
        assert!(phys_h <= 8192.0);
        assert!((css_w * dpr - phys_w).abs() < 1.0);
        assert!((css_h * dpr - phys_h).abs() < 1.0);
    }
}
