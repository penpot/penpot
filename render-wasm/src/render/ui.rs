use skia_safe::{self as skia, Color4f};

use super::{RenderState, ShapesPoolRef, SurfaceId};
use crate::render::grid_layout;
use crate::shapes::{Layout, Type};
use crate::view::Viewbox;

mod themes;
use themes::UITheme;

#[allow(unused_imports)]
pub use themes::{DarkTheme, LightTheme};

pub fn render(render_state: &mut RenderState, shapes: ShapesPoolRef, theme: &impl UITheme) {
    let render_options = render_state.options();
    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    canvas.clear(Color4f::new(0.0, 0.0, 0.0, 0.0));
    canvas.save();

    let viewbox = render_state.viewbox;
    let zoom = viewbox.zoom * render_state.options.dpr();

    canvas.scale((zoom, zoom));

    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    let show_grid_id = render_state.show_grid;

    if let Some(id) = show_grid_id {
        if let Some(shape) = shapes.get(&id) {
            grid_layout::render_overlay(
                zoom,
                render_state.options.antialias_threshold,
                canvas,
                shape,
                shapes,
            );
        }
    }

    // Render overlays for empty grid frames
    for shape in shapes.iter() {
        if shape.id.is_nil() || !shape.children.is_empty() {
            continue;
        }

        if show_grid_id == Some(shape.id) {
            continue;
        }

        let Type::Frame(frame) = &shape.shape_type else {
            continue;
        };

        if !matches!(frame.layout, Some(Layout::GridLayout(_, _))) {
            continue;
        }

        if shape.deleted() {
            continue;
        }

        if let Some(shape) = shapes.get(&shape.id) {
            grid_layout::render_overlay(
                zoom,
                render_state.options.antialias_threshold,
                canvas,
                shape,
                shapes,
            );
        }
    }

    canvas.restore();

    if render_options.are_rulers_enabled() {
        render_rulers(canvas, theme, viewbox, render_options.dpr());
    }

    render_state.surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
}

fn render_rulers(canvas: &skia::Canvas, theme: &impl UITheme, viewbox: Viewbox, dpr: f32) {
    let width = viewbox.width;
    let height = viewbox.height;

    let offset_x = 22.0;
    let offset_y = 25.0;

    let mut paint = skia::Paint::default();
    paint.set_color(theme.panel_background_color());

    let full = skia::Rect::new(0.0, 0.0, width * dpr, height * dpr);
    // bottom-right content rect; top/left strips remain as ruler chrome.
    let inner = skia::Rect::new(offset_x * dpr, offset_y * dpr, width * dpr, height * dpr);
    let rr = 8.0 * dpr;
    let inner_radii = [
        skia::Point::new(rr, rr),
        skia::Point::new(rr, rr),
        skia::Point::new(rr, rr),
        skia::Point::new(rr, rr),
    ];
    let inner_rrect = skia::RRect::new_rect_radii(inner, &inner_radii);

    // draw a clipped background to build the rulers
    canvas.save();
    canvas.clip_rrect(inner_rrect, skia::ClipOp::Difference, true);
    canvas.draw_rect(full, &paint);
    canvas.restore();

    // paint the inner border on the clipping
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_stroke_width(2.0 * dpr);
    paint.set_color(theme.panel_border_color());
    canvas.draw_rrect(inner_rrect, &paint);
}
