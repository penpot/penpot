use crate::shapes::Shape;
use skia_safe as skia;

use super::{RenderState, SurfaceId};

fn render_debug_view(render_state: &mut RenderState) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_argb(255, 255, 0, 255));
    paint.set_stroke_width(1.);

    let mut scaled_rect = render_state.viewbox.area.clone();
    let x = 100. + scaled_rect.x() * 0.2;
    let y = 100. + scaled_rect.y() * 0.2;
    let width = scaled_rect.width() * 0.2;
    let height = scaled_rect.height() * 0.2;
    scaled_rect.set_xywh(x, y, width, height);

    render_state
        .surfaces
        .canvas(SurfaceId::Debug)
        .draw_rect(scaled_rect, &paint);
}

pub fn render_wasm_label(render_state: &mut RenderState) {
    let font_provider = render_state.fonts().font_provider();
    let typeface = font_provider
        .match_family_style("robotomono-regular", skia::FontStyle::default())
        .unwrap();

    let canvas = render_state.surfaces.canvas(SurfaceId::Current);
    let skia::ISize { width, height } = canvas.base_layer_size();
    let p = skia::Point::new(width as f32 - 100.0, height as f32 - 25.0);
    let mut paint = skia::Paint::default();
    paint.set_color(skia::Color::from_argb(100, 0, 0, 0));

    let font = skia::Font::new(typeface, 10.0);
    canvas.draw_str("WASM RENDERER", p, &font, &paint);
}

pub fn render_debug_shape(render_state: &mut RenderState, element: &Shape, intersected: bool) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(if intersected {
        skia::Color::from_argb(255, 255, 255, 0)
    } else {
        skia::Color::from_argb(255, 0, 255, 255)
    });
    paint.set_stroke_width(1.);

    let mut scaled_rect = element.selrect();
    let x = 100. + scaled_rect.x() * 0.2;
    let y = 100. + scaled_rect.y() * 0.2;
    let width = scaled_rect.width() * 0.2;
    let height = scaled_rect.height() * 0.2;
    scaled_rect.set_xywh(x, y, width, height);

    render_state
        .surfaces
        .canvas(SurfaceId::Debug)
        .draw_rect(scaled_rect, &paint);
}

pub fn render(render_state: &mut RenderState) {
    render_debug_view(render_state);
    render_state.surfaces.draw_into(
        SurfaceId::Debug,
        SurfaceId::Current,
        Some(&skia::Paint::default()),
    );
}
