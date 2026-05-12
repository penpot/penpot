use skia_safe::{self as skia, Rect};

#[derive(Debug, Clone, Copy)]
pub struct RulerState {
    pub visible: bool,
    pub offset_x: f32,
    pub offset_y: f32,
    pub selection: Option<Rect>,
    pub bg_color: skia::Color,
    pub border_color: skia::Color,
    pub label_color: skia::Color,
    pub accent_color: skia::Color,
}

impl Default for RulerState {
    fn default() -> Self {
        Self {
            visible: false,
            offset_x: 0.0,
            offset_y: 0.0,
            selection: None,
            bg_color: skia::Color::from_argb(0xff, 0x18, 0x18, 0x1a),
            border_color: skia::Color::from_argb(0xff, 0x2e, 0x2e, 0x36),
            label_color: skia::Color::from_argb(0xff, 0xb1, 0xb2, 0xb5),
            accent_color: skia::Color::from_argb(0xff, 0x91, 0xff, 0x11),
        }
    }
}

impl RulerState {
    pub fn set_selection(&mut self, has: bool, x: f32, y: f32, w: f32, h: f32) {
        self.selection = if has {
            Some(Rect::from_xywh(x, y, w, h))
        } else {
            None
        };
    }
}
