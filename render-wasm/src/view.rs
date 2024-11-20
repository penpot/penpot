use skia_safe as skia;

#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox
{
  pub x: f32,
  pub y: f32,
  pub width: f32,
  pub height: f32,
  pub zoom: f32,
  pub area: skia::Rect,
}

impl Viewbox {
  pub fn set_all(&mut self, zoom: f32, x: f32, y: f32) -> &Self {
    self.x = x;
    self.y = y;
    self.zoom = zoom;
    self.area.set_xywh(
        -self.x,
        -self.y,
        self.width / self.zoom,
        self.height / self.zoom
    );
    self
  }

  pub fn set_zoom(&mut self, zoom: f32) -> &Self {
    self.zoom = zoom;
    self.area.set_wh(
      self.width / self.zoom,
      self.height / self.zoom
    );
    self
  }

  pub fn set_xy(&mut self, x: f32, y: f32) -> &Self {
    self.x = x;
    self.y = y;
    self.area.left = -x;
    self.area.top = -y;
    self
  }

  pub fn set_wh(&mut self, width: f32, height: f32) -> &Self {
    self.width = width;
    self.height = height;
    self.area.set_wh(
      self.width / self.zoom,
      self.height / self.zoom
    );
    self
  }
}

