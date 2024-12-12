use crate::math::Rect;

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
        let mut res = Self::default();
        res.width = width;
        res.height = height;
        res.area.set_xywh(0., 0., width, height);

        res
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

    pub fn set_zoom(&mut self, zoom: f32) {
        self.zoom = zoom;
        self.area
            .set_wh(self.width / self.zoom, self.height / self.zoom);
    }

    pub fn set_pan_xy(&mut self, pan_x: f32, pan_y: f32) {
        self.pan_x = pan_x;
        self.pan_y = pan_y;
        self.area.left = -pan_x;
        self.area.top = -pan_y;
    }

    pub fn set_wh(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
        self.area
            .set_wh(self.width / self.zoom, self.height / self.zoom);
    }
}
