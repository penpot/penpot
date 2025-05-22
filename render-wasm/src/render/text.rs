use super::{RenderState, SurfaceId};
use skia_safe::{self as skia, canvas::SaveLayerRec};

pub fn render(
    render_state: &mut RenderState,
    paths: &Vec<(skia::Path, skia::Paint)>,
    surface_id: Option<SurfaceId>,
    paint: Option<skia::Paint>,
) {
    let mask_paint = paint.unwrap_or_default();
    let mask = SaveLayerRec::default().paint(&mask_paint);
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    canvas.save_layer(&mask);

    for (path, paint) in paths {
        if path.is_empty() {
            eprintln!("Warning: Empty path detected");
        }
        canvas.draw_path(path, paint);
    }
    canvas.restore();
}
