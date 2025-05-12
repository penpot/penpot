use super::{RenderState, Shape, SurfaceId};
use skia_safe::{self as skia, canvas::SaveLayerRec, paint, textlayout::Paragraph};

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &[Paragraph],
    surface_id: Option<SurfaceId>,
    paint: Option<&paint::Paint>,
) {
    let mut offset_y = 0.0;
    let default_paint = skia::Paint::default();
    let mask = SaveLayerRec::default().paint(&paint.unwrap_or(&default_paint));
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    canvas.save_layer(&mask);
    for skia_paragraph in paragraphs {
        let xy = (shape.selrect().x(), shape.selrect.y() + offset_y);
        skia_paragraph.paint(canvas, xy);
        offset_y += skia_paragraph.height();
    }
    canvas.restore();
}
