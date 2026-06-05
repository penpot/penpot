use skia_safe::{self as skia};

use crate::math::Rect;
use crate::ui::{Guide, GuideKind};

/// Renders the ruler guides overlay using the guides provided by the host
/// (ClojureScript) and stored in the render state.
pub fn render(
    canvas: &skia::Canvas,
    zoom: f32,
    area: Rect,
    horizontal: &[Guide],
    vertical: &[Guide],
) {
    for guide in horizontal {
        render_guide(canvas, zoom, area, *guide);
    }
    for guide in vertical {
        render_guide(canvas, zoom, area, *guide);
    }
}

pub fn render_guide(canvas: &skia::Canvas, zoom: f32, area: Rect, guide: Guide) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(Into::<skia::Color>::into(guide.color));
    // we disable antialias and increase the stroke thickness so the guides
    // do not appear faint or blurry.
    paint.set_anti_alias(false);
    paint.set_stroke_width(2.0 / zoom);

    let (x1, y1, x2, y2) = match guide.kind {
        GuideKind::Vertical(x) => (x, area.top, x, area.bottom),
        GuideKind::Horizontal(y) => (area.left, y, area.right, y),
    };

    canvas.draw_line((x1, y1), (x2, y2), &paint);
}
