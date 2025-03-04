use crate::shapes::Shape;
use skia_safe::{self as skia, Rect};

use super::{tiles, RenderState};

const DEBUG_SCALE: f32 = 0.2;

fn get_debug_rect(rect: Rect) -> Rect {
    skia::Rect::from_xywh(
        100. + rect.x() * DEBUG_SCALE,
        100. + rect.y() * DEBUG_SCALE,
        rect.width() * DEBUG_SCALE,
        rect.height() * DEBUG_SCALE,
    )
}

fn render_debug_view(render_state: &mut RenderState) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 255));
    paint.set_stroke_width(1.);

    let rect = get_debug_rect(render_state.viewbox.area.clone());
    render_state.surfaces.debug.canvas().draw_rect(rect, &paint);
}

pub fn render_wasm_label(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.target.canvas();

    let skia::ISize { width, height } = canvas.base_layer_size();
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::from_argb(100, 0, 0, 0));

    let str = if render_state.options.is_debug_visible() {
        "WASM RENDERER (DEBUG)"
    } else {
        "WASM RENDERER"
    };
    let (scalar, _) = render_state.debug_font.measure_str(str, None);
    let p = skia::Point::new(width as f32 - 25.0 - scalar, height as f32 - 25.0);

    canvas.draw_str(str, p, &render_state.debug_font, &paint);
}

pub fn render_debug_shape(render_state: &mut RenderState, element: &Shape, intersected: bool) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(if intersected {
        skia::Color::from_rgb(255, 255, 0)
    } else {
        skia::Color::from_rgb(0, 255, 255)
    });
    paint.set_stroke_width(1.);

    let rect = get_debug_rect(element.selrect());
    render_state.surfaces.debug.canvas().draw_rect(rect, &paint);
}

pub fn render_debug_tiles_for_viewbox(
    render_state: &mut RenderState,
    sx: i32,
    sy: i32,
    ex: i32,
    ey: i32,
) {
    let canvas = render_state.surfaces.debug.canvas();
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 127));
    paint.set_stroke_width(1.);
    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);
    canvas.draw_str(
        str_rect,
        skia::Point::new(100.0, 150.0),
        &render_state.debug_font,
        &paint,
    );
}

// Renders the tiles in the viewbox
pub fn render_debug_viewbox_tiles(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.debug.canvas();
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 127));
    paint.set_stroke_width(1.);

    let tile_size = tiles::get_tile_size(render_state.viewbox);
    let (sx, sy, ex, ey) = tiles::get_tiles_for_rect(render_state.viewbox.area, tile_size);
    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);
    canvas.draw_str(
        str_rect,
        skia::Point::new(100.0, 100.0),
        &render_state.debug_font,
        &paint,
    );
    for y in sy..=ey {
        for x in sx..=ex {
            let rect = Rect::from_xywh(
                x as f32 * tile_size,
                y as f32 * tile_size,
                tile_size,
                tile_size,
            );
            let debug_rect = get_debug_rect(rect);
            let p = skia::Point::new(debug_rect.x(), debug_rect.y() - 1.);
            let str = format!("{}:{}", x, y);
            canvas.draw_str(str, p, &render_state.debug_font, &paint);
            canvas.draw_rect(&debug_rect, &paint);
        }
    }
}

pub fn render_debug_tiles(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.debug.canvas();
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(127, 0, 255));
    paint.set_stroke_width(1.);

    let tile_size = tiles::get_tile_size(render_state.viewbox);
    let (sx, sy, ex, ey) = tiles::get_tiles_for_rect(render_state.viewbox.area, tile_size);
    for y in sy..=ey {
        for x in sx..=ex {
            let tile = (x, y);
            let shape_count = render_state.tiles.get_tile_shape_count(tile);
            if shape_count == 0 {
                continue;
            }

            let rect = Rect::from_xywh(
                x as f32 * tile_size,
                y as f32 * tile_size,
                tile_size,
                tile_size,
            );
            let debug_rect = get_debug_rect(rect);
            let p = skia::Point::new(debug_rect.x(), debug_rect.y() - 1.);
            let str = format!("{}:{} {}", x, y, shape_count);
            canvas.draw_str(str, p, &render_state.debug_font, &paint);
            canvas.draw_rect(&debug_rect, &paint);
        }
    }
}

pub fn render(render_state: &mut RenderState) {
    let paint = skia::Paint::default();
    render_debug_view(render_state);
    render_debug_viewbox_tiles(render_state);
    render_debug_tiles(render_state);
    render_state.surfaces.debug.draw(
        &mut render_state.surfaces.target.canvas(),
        (0.0, 0.0),
        render_state.sampling_options,
        Some(&paint),
    );
}
