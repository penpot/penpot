use super::{RenderState, SurfaceId};
use skia_safe::{self as skia, canvas::SaveLayerRec};

pub fn render(
    render_state: &mut RenderState,
    paths: &Vec<(skia::TextBlob, skia::Point, skia::Paint)>,
    surface_id: Option<SurfaceId>,
    paint: Option<skia::Paint>,
) {
    let mask_paint = paint.unwrap_or_default();
    let mask = SaveLayerRec::default().paint(&mask_paint);
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    canvas.save_layer(&mask);

    for (text_blob, origin, paint) in paths {
        canvas.draw_text_blob(text_blob, *origin, paint);
    }
    canvas.restore();
}
