use std::collections::HashMap;
use crate::math::Rect;
use skia_safe::{self as skia, Color4f};
use crate::uuid::Uuid;

use crate::shapes::{Shape, StructureEntry};
use super::{RenderState, SurfaceId};
use crate::render::grid_layout;

pub fn render_v1(render_state: &mut RenderState) {
    let scale = render_state.get_scale();
    let translation = (
        -render_state.viewbox.area.left,
        -render_state.viewbox.area.top
    );

    let surfaces = &mut render_state.surfaces;
    surfaces.apply_mut(
        &[ SurfaceId::Target ],
        |s| {
            s.canvas().save();
            s.canvas().scale((scale, scale));
            s.canvas().translate(translation);
        },
    );

    surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
    surfaces.apply_mut(
        &[ SurfaceId::Target ],
        |s| {
            s.canvas().restore();
        },
    );
}

pub fn render(
    render_state: &mut RenderState,
    shapes: &HashMap<Uuid, &mut Shape>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    println!(">>render");
    let canvas = render_state
        .surfaces
        .canvas(SurfaceId::UI);

    canvas.clear(Color4f::new(0.0, 0.0, 0.0, 0.0));
    canvas.save();

    /*
    let viewbox = render_state.viewbox;
    let navigate_zoom = viewbox.zoom * render_state.options.dpr();

    canvas.scale(( navigate_zoom, navigate_zoom ));

    canvas.translate((
        -viewbox.area.left,
        -viewbox.area.top
    ));

    let rect = Rect::from_xywh(
        25.0,
        25.0,
        1880.0,
        1280.0);

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 0, 0));
    paint.set_stroke_width(1. / navigate_zoom);
    
    canvas.draw_rect(rect, &paint);
    canvas.restore();

    render_state.surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
);
     */


    grid_layout::render_overlay_all(render_state, shapes, structure);


    /*
    let surfaces = &mut render_state.surfaces;
    let canvas = surfaces.canvas(SurfaceId::UI);
    canvas.save();

    let viewbox = render_state.viewbox;
    let navigate_zoom = viewbox.zoom * render_state.options.dpr();

    canvas.scale(( navigate_zoom, navigate_zoom ));

    canvas.translate((
        -viewbox.area.left,
        -viewbox.area.top
    ));

    surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );

    canvas.restore();
    */
}
