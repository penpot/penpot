use skia_safe::{self as skia};

use crate::math::Rect;
use crate::state::Guide;

const LABEL_TEXT_COLOR: Color = Color::new(255, 255, 255);
const LABEL_FONT_SIZE: f32 = 12.0;

/// Renders the ruler guides overlay.
///
/// NOTE: temporary hardcoded implementation while we figure out exactly which
/// data we need to receive from ClojureScript. For now it draws a single
/// vertical 1px magenta (#ff00ff) guide at x=100, spanning the visible area.
pub fn render(canvas: &skia::Canvas, zoom: f32, area: Rect) {
    let guides = vec![
        Guide::new(GuideKind::Vertical(100.0), Color::new(255, 0, 255)),
        Guide::new(GuideKind::Horizontal(200.0), Color::new(0, 255, 0)),
    ];
    for guide in guides {
        render_guide(canvas, zoom, area, guide);
    }
}

pub fn render_guide(canvas: &skia::Canvas, zoom: f32, area: Rect, guide: Guide) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(guide.color.into());
    paint.set_anti_alias(true);
    paint.set_stroke_width(1.0 / zoom);

    let (x1, y1, x2, y2) = match guide {
        Guide::Vertical(x) => (x, area.top, x, area.bottom),
        Guide::Horizontal(y) => (area.left, y, area.right, y),
    };

    canvas.draw_line((x1, y1), (x2, y2), &paint);
}
