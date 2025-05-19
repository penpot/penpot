use super::{RenderState, Shape, SurfaceId};
use skia_safe::{self as skia, canvas::SaveLayerRec, textlayout::Paragraph};

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &[Vec<Paragraph>],
    surface_id: Option<SurfaceId>,
    paint: Option<skia::Paint>,
) {
    let mask_paint = paint.unwrap_or_default();
    let mask = SaveLayerRec::default().paint(&mask_paint);
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    canvas.save_layer(&mask);
    for group in paragraphs {
        let mut offset_y = 0.0;
        for skia_paragraph in group {
            let xy = (shape.selrect().x(), shape.selrect.y() + offset_y);
            skia_paragraph.paint(canvas, xy);
            offset_y += skia_paragraph.height();
        }
    }
    canvas.restore();
}
