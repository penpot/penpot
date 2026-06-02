#![allow(dead_code)]
use super::{tiles, RenderState, SurfaceId};

#[cfg(target_arch = "wasm32")]
use macros::wasm_error;

#[cfg(target_arch = "wasm32")]
use crate::get_render_state;

use skia_safe::{self as skia, Rect};

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
    paint.set_color(skia::Color::GREEN);
    paint.set_stroke_width(1.);

    let rect = get_debug_rect(render_state.viewbox.area);
    render_state
        .surfaces
        .canvas(SurfaceId::Debug)
        .draw_rect(rect, &paint);
}

pub fn render_debug_cache_surface(render_state: &mut RenderState) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    canvas.save();
    canvas.scale((0.1, 0.1));
    render_state
        .surfaces
        .draw_into(SurfaceId::Cache, SurfaceId::Debug, None);
    render_state.surfaces.canvas(SurfaceId::Debug).restore();
}

pub fn render_wasm_label(render_state: &mut RenderState) {
    if !render_state.options.show_wasm_info() {
        return;
    }

    let canvas = render_state.surfaces.canvas(SurfaceId::Target);
    let skia::ISize { width, height } = canvas.base_layer_size();
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::GRAY);

    let mut str = if render_state.options.is_debug_visible() {
        "WebGL rendering (debug)"
    } else {
        "WebGL rendering"
    };
    let (scalar, _) = render_state.fonts.debug_font().measure_str(str, None);
    let mut p = skia::Point::new(width as f32 - 25.0 - scalar, height as f32 - 25.0);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(str, p, debug_font, &paint);

    if render_state.options.is_text_editor_v3() {
        str = "TEXT EDITOR / V3";

        let (scalar, _) = render_state.fonts.debug_font().measure_str(str, None);
        p.x = width as f32 - 25.0 - scalar;
        p.y -= 20.0;
        canvas.draw_str(str, p, debug_font, &paint);
    }
}

pub fn render_debug_tiles_for_viewbox(render_state: &mut RenderState) {
    let tiles::TileRect(sx, sy, ex, ey) = render_state.tile_viewbox.interest_rect;
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::RED);
    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(str_rect, skia::Point::new(100.0, 150.0), debug_font, &paint);
}

// Renders the tiles in the viewbox
pub fn render_debug_viewbox_tiles(render_state: &mut RenderState) {
    let scale = render_state.get_scale();
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::MAGENTA);
    paint.set_stroke_width(1.);

    let tile_size = tiles::get_tile_size(scale);
    let tile_rect = tiles::get_tiles_for_rect(render_state.viewbox.area, tile_size);
    let tiles::TileRect(sx, sy, ex, ey) = tile_rect;

    let str_rect = format!("{} {} {} {}", sx, sy, ex, ey);

    let debug_font = render_state.fonts.debug_font();
    canvas.draw_str(str_rect, skia::Point::new(100.0, 100.0), debug_font, &paint);

    let tile_size = tiles::get_tile_size(scale);
    for tile in tile_rect.iter(true) {
        let tiles::Tile(x, y) = tile;
        let rect = tile.get_rect_with_size(tile_size);
        let debug_rect = get_debug_rect(rect);
        let p = skia::Point::new(debug_rect.x(), debug_rect.y() - 1.);
        let str = format!("{}:{}", x, y);
        let debug_font = render_state.fonts.debug_font();
        paint.set_style(skia::PaintStyle::Fill);
        canvas.draw_str(str, p, debug_font, &paint);
        canvas.draw_rect(debug_rect, &paint);
    }
}

pub fn render(render_state: &mut RenderState) {
    // DEBUG VIEWBOX - green rect - buggy?
    // render_debug_view(render_state);

    // DEBUG VIEWBOX TILES - magenta - buggy?
    // render_debug_viewbox_tiles(render_state);

    // DEBUG CACHE SURFACE - noisy - ?
    // render_debug_cache_surface(render_state);

    render_state.surfaces.draw_into(
        SurfaceId::Debug,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
}

pub fn render_workspace_current_tile(
    render_state: &mut RenderState,
    prefix: String,
    tile: tiles::Tile,
    rect: skia::Rect,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);
    let mut p = skia::Paint::default();
    p.set_stroke_width(2.);
    p.set_style(skia::PaintStyle::Stroke);
    canvas.draw_rect(rect, &p);

    let tile_position_origin = skia::Point::new(rect.x() + 10., rect.y() + 20.);
    p.set_style(skia::PaintStyle::Fill);
    let str = format!("{prefix} {}:{}", tile.x(), tile.y());
    let mut debug_font = render_state.fonts.debug_font().clone();
    debug_font.set_size(16.);
    canvas.draw_str(str, tile_position_origin, &debug_font, &p);
}

pub fn render_debug_shape(
    render_state: &mut RenderState,
    shape_selrect: Option<skia::Rect>,
    shape_extrect: Option<skia::Rect>,
) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Debug);

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::RED);
    paint.set_stroke_width(1.);

    if let Some(rect) = shape_selrect {
        canvas.draw_rect(rect, &paint);
    }

    if let Some(rect) = shape_extrect {
        paint.set_color(skia::Color::BLUE);
        canvas.draw_rect(rect, &paint);
    }
}

#[cfg(target_arch = "wasm32")]
pub fn trap() {
    run_script!("debugger");
}

#[cfg(target_arch = "wasm32")]
#[derive(Debug, PartialEq)]
pub enum SurfaceBackendKind {
    BackendTexture,      // GPU Framebuffer (Texture)
    BackendRenderTarget, // GPU Framebuffer (Renderbuffer)
    Raster,              // CPU
    Unknown,
}

#[cfg(target_arch = "wasm32")]
pub fn classify_surface_backend(surface: &mut skia::Surface) -> SurfaceBackendKind {
    if skia::gpu::surfaces::get_backend_texture(
        surface,
        skia_safe::surface::BackendHandleAccess::FlushRead,
    )
    .is_some()
    {
        return SurfaceBackendKind::BackendTexture;
    }

    if skia::gpu::surfaces::get_backend_render_target(
        surface,
        skia_safe::surface::BackendHandleAccess::FlushRead,
    )
    .is_some()
    {
        return SurfaceBackendKind::BackendRenderTarget;
    }

    if surface.peek_pixels().is_some() {
        return SurfaceBackendKind::Raster;
    }

    SurfaceBackendKind::Unknown
}

#[cfg(target_arch = "wasm32")]
pub fn console_debug_surface(render_state: &mut RenderState, id: SurfaceId) {
    let base64_image = render_state
        .surfaces
        .base64_snapshot(id)
        .expect("Failed to get base64 image");

    run_script!(format!("console.log('%c ', 'font-size: 1px; background: url(data:image/png;base64,{base64_image}) no-repeat; padding: 100px; background-size: contain;')"));
}

#[cfg(target_arch = "wasm32")]
pub fn console_debug_surface_base64(render_state: &mut RenderState, id: SurfaceId) {
    let base64_image = render_state
        .surfaces
        .base64_snapshot(id)
        .expect("Failed to get base64 image");

    println!("{}", base64_image);
}

#[cfg(target_arch = "wasm32")]
pub fn console_debug_surface_rect(render_state: &mut RenderState, id: SurfaceId, rect: skia::Rect) {
    let int_rect = skia::IRect::from_ltrb(
        rect.left as i32,
        rect.top as i32,
        rect.right as i32,
        rect.bottom as i32,
    );

    let base64_image = render_state
        .surfaces
        .base64_snapshot_rect(id, int_rect)
        .expect("Failed to get base64 image");

    if let Some(base64_image) = base64_image {
        run_script!(format!("console.log('%c ', 'font-size: 1px; background: url(data:image/png;base64,{base64_image}) no-repeat; padding: 100px; background-size: contain;')"))
    }
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn capture_frames(capture_frames: i32) -> Result<()> {
    get_render_state()
        .options
        .set_capture_frames(capture_frames);
    Ok(())
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn debug_cache_console() -> Result<()> {
    console_debug_surface(get_render_state(), SurfaceId::Cache);
    Ok(())
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn debug_cache_base64() -> Result<()> {
    console_debug_surface_base64(get_render_state(), SurfaceId::Cache);
    Ok(())
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn debug_atlas_console() -> Result<()> {
    console_debug_surface(get_render_state(), SurfaceId::Atlas);
    Ok(())
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn debug_atlas_base64() -> Result<()> {
    console_debug_surface_base64(get_render_state(), SurfaceId::Atlas);
    Ok(())
}

#[no_mangle]
#[wasm_error]
#[cfg(target_arch = "wasm32")]
pub extern "C" fn debug_surface_console(id: SurfaceId) -> Result<()> {
    console_debug_surface(get_render_state(), id);
    Ok(())
}
