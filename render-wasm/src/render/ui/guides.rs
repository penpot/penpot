use skia_safe::{self as skia};

use crate::math::Rect;
use crate::ui::{Guide, GuideKind};

/// Renders the ruler guides overlay using the guides provided by the host
/// (ClojureScript) and stored in the render state.
pub fn render(canvas: &skia::Canvas, zoom: f32, area: Rect, guides: &[Guide]) {
    for guide in guides {
        render_guide(canvas, zoom, area, *guide);
    }
}

pub fn render_guide(canvas: &skia::Canvas, zoom: f32, area: Rect, guide: Guide) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(Into::<skia::Color>::into(guide.color));
    paint.set_anti_alias(true);
    paint.set_stroke_width(1.0 / zoom);

    let (x1, y1, x2, y2) = match guide.kind {
        GuideKind::Vertical(x) => (x, area.top, x, area.bottom),
        GuideKind::Horizontal(y) => (area.left, y, area.right, y),
    };

    canvas.draw_line((x1, y1), (x2, y2), &paint);
}
