use skia_safe::{self as skia};

use crate::math::Rect;
use crate::ui::{Guide, GuideKind};

/// Renders the ruler guides, clipped out of the ruler bars.
pub fn render(
    canvas: &skia::Canvas,
    zoom: f32,
    dpr: f32,
    area: Rect,
    ruler_width: f32,
    horizontal: &[Guide],
    vertical: &[Guide],
) {
    // Horizontal guides: clip out the top strip (horizontal ruler)
    canvas.save();
    canvas.clip_rect(
        Rect::from_ltrb(area.left, area.top + ruler_width, area.right, area.bottom),
        None,
        false,
    );

    for guide in horizontal {
        render_guide(canvas, zoom, dpr, area, *guide);
    }

    canvas.restore();

    // Vertical guides: clip out the left strip (vertical ruler)
    canvas.save();
    canvas.clip_rect(
        Rect::from_ltrb(area.left + ruler_width, area.top, area.right, area.bottom),
        None,
        false,
    );

    for guide in vertical {
        render_guide(canvas, zoom, dpr, area, *guide);
    }

    canvas.restore();
}

pub fn render_guide(canvas: &skia::Canvas, zoom: f32, dpr: f32, area: Rect, guide: Guide) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(Into::<skia::Color>::into(guide.color));
    paint.set_alpha((0.7 * 255.0) as u8);
    paint.set_stroke_width(1.0 * dpr / zoom);
    // we disable antialias so the guides do not appear faint or blurry.
    paint.set_anti_alias(false);

    // The guide line spans the whole viewport, but when it belongs to a board
    // the solid part is clipped to that board's range (along the line
    // direction). The trimmed-out parts are not drawn here; the hover/drag
    // dashed decorations are rendered by the SVG overlay instead.
    let (full_start, full_end) = match guide.kind {
        GuideKind::Vertical(_) => (area.top, area.bottom),
        GuideKind::Horizontal(_) => (area.left, area.right),
    };

    let (start, end) = match guide.frame_range {
        Some((frame_start, frame_end)) => {
            let (lo, hi) = if frame_start <= frame_end {
                (frame_start, frame_end)
            } else {
                (frame_end, frame_start)
            };
            (lo.max(full_start), hi.min(full_end))
        }
        None => (full_start, full_end),
    };

    // The clipped range can fall entirely outside the viewport.
    if start > end {
        return;
    }

    let (x1, y1, x2, y2) = match guide.kind {
        GuideKind::Vertical(x) => (x, start, x, end),
        GuideKind::Horizontal(y) => (start, y, end, y),
    };

    canvas.draw_line((x1, y1), (x2, y2), &paint);
}
