use crate::shapes::Shape;
use skia_safe::{self as skia, Rect};

use super::{tiles, RenderState, SurfaceId};

#[cfg(target_arch = "wasm32")]
use crate::run_script;

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
    render_state
        .surfaces
        .canvas(SurfaceId::Debug)
        .draw_rect(rect, &paint);
}

pub fn render_wasm_label(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let skia::ISize { width, height } = canvas.base_layer_size();
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::from_argb(100, 0, 0, 0));

    let str = if render_state.options.is_debug_visible() {
        "WASM RENDERER (DEBUG)"
    } else {
        "WASM RENDERER"
    };
    let (scalar, _) = render_state.fonts.debug_font().measure_str(str, None);
    let p = skia::Point::new(width as f32 - 25.0 - scalar, height as f32 - 25.0);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(str, p, &debug_font, &paint);
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

    let rect = get_debug_rect(element.extrect());
    render_state
        .surfaces
        .canvas(SurfaceId::Debug)
        .draw_rect(rect, &paint);
}

pub fn render_debug_tiles_for_viewbox(
    render_state: &mut RenderState,
    sx: i32,
    sy: i32,
    ex: i32,
    ey: i32,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 127));
    paint.set_stroke_width(1.);
    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(
        str_rect,
        skia::Point::new(100.0, 150.0),
        &debug_font,
        &paint,
    );
}

// Renders the tiles in the viewbox
pub fn render_debug_viewbox_tiles(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 127));
    paint.set_stroke_width(1.);

    let tile_size = tiles::get_tile_size(render_state.viewbox);
    let (sx, sy, ex, ey) = tiles::get_tiles_for_rect(render_state.viewbox.area, tile_size);
    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(
        str_rect,
        skia::Point::new(100.0, 100.0),
        &debug_font,
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
            let debug_font = render_state.fonts.debug_font();
            canvas.draw_str(str, p, &debug_font, &paint);
            canvas.draw_rect(&debug_rect, &paint);
        }
    }
}

pub fn render_debug_tiles(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(127, 0, 255));
    paint.set_stroke_width(1.);

    let tile_size = tiles::get_tile_size(render_state.viewbox);
    let (sx, sy, ex, ey) = tiles::get_tiles_for_rect(render_state.viewbox.area, tile_size);
    for y in sy..=ey {
        for x in sx..=ex {
            let tile = (x, y);
            let shape_count = render_state.tiles.get_shapes_at(tile).iter().len();
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

            let debug_font = render_state.fonts.debug_font();
            canvas.draw_str(str, p, &debug_font, &paint);
            canvas.draw_rect(&debug_rect, &paint);
        }
    }
}

pub fn render(render_state: &mut RenderState) {
    render_debug_view(render_state);
    render_debug_viewbox_tiles(render_state);
    render_debug_tiles(render_state);
    render_state.surfaces.draw_into(
        SurfaceId::Debug,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
}

#[cfg(target_arch = "wasm32")]
#[allow(dead_code)]
pub fn console_debug_surface(render_state: &mut RenderState, id: SurfaceId) {
    let base64_image = render_state.surfaces.base64_snapshot(id);

    run_script!(format!("console.log('%c ', 'font-size: 1px; background: url(data:image/png;base64,{base64_image}) no-repeat; padding: 100px; background-size: contain;')"))
}

#[allow(dead_code)]
#[cfg(target_arch = "wasm32")]
pub fn console_debug_surface_rect(render_state: &mut RenderState, id: SurfaceId, rect: skia::Rect) {
    let int_rect = skia::IRect::from_ltrb(
        rect.left as i32,
        rect.top as i32,
        rect.right as i32,
        rect.bottom as i32,
    );

    let base64_image = render_state.surfaces.base64_snapshot_rect(id, int_rect);

    if let Some(base64_image) = base64_image {
        run_script!(format!("console.log('%c ', 'font-size: 1px; background: url(data:image/png;base64,{base64_image}) no-repeat; padding: 100px; background-size: contain;')"))
    }
}

pub fn render_workspace_current_tile(
    render_state: &mut RenderState,
    prefix: String,
    tile: tiles::Tile,
    rect: skia::Rect,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Target);
    let mut p = skia::Paint::default();
    p.set_stroke_width(1.);
    p.set_style(skia::PaintStyle::Stroke);
    canvas.draw_rect(&rect, &p);

    let point = skia::Point::new(rect.x() + 10., rect.y() + 20.);
    p.set_stroke_width(1.);
    let str = format!("{prefix} {}:{}", tile.0, tile.1);
    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(str, point, &debug_font, &p);
}
