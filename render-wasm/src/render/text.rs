use super::{RenderState, Shape, SurfaceId};
use skia_safe::textlayout::Paragraph;

pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &[Vec<Paragraph>],
    surface_id: Option<SurfaceId>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    for group in paragraphs {
        let mut offset_y = 0.0;
        for skia_paragraph in group {
            let xy = (shape.selrect().x(), shape.selrect.y() + offset_y);
            skia_paragraph.paint(canvas, xy);
            offset_y += skia_paragraph.height();
        }
    }
}
