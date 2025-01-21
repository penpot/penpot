use skia_safe::{self as skia};

use super::RenderState;
use crate::math::{RRect, Rect};
use crate::shapes::{Kind, Shadow};

pub fn render_drop_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    kind: &Kind,
    path_transform: Option<&skia::Matrix>,
) {
    let canvas = render_state.drawing_surface.canvas();

    match kind {
        Kind::Rect(rect, None) => {
            let shadow_paint = shadow.to_paint(false);
            let spread_rect = rect_with_spread(rect, shadow.spread());
            canvas.draw_rect(&spread_rect, &shadow_paint);
        }
        Kind::Rect(rect, Some(corners)) => {
            let shadow_paint = shadow.to_paint(false);
            let rrect = RRect::new_rect_radii(rect, corners);
            let spread_rrect = rrect_with_spread(&rrect, shadow.spread());
            canvas.draw_rrect(&spread_rrect, &shadow_paint);
        }
        Kind::Circle(rect) => {
            let shadow_paint = shadow.to_paint(false);
            let spread_rect = rect_with_spread(rect, shadow.spread());
            canvas.draw_oval(spread_rect, &shadow_paint);
        }
        Kind::Path(path) | Kind::Bool(_, path) => {
            let shadow_paint = shadow.to_paint(true);
            canvas.draw_path(
                &path.to_skia_path().transform(path_transform.unwrap()),
                &shadow_paint,
            );
        }
        _ => {}
    }
}

fn rect_with_spread(rect: &Rect, spread: f32) -> Rect {
    Rect::with_outset(rect, (spread, spread))
}

fn rrect_with_spread(rrect: &RRect, spread: f32) -> RRect {
    RRect::with_outset(rrect, (spread, spread))
}
