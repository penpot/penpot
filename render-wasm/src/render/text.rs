use super::{RenderState, Shape, SurfaceId};
use crate::shapes::TextContent;

pub fn render(render_state: &mut RenderState, shape: &Shape, text: &TextContent) {
    let mut offset_y = 0.0;
    for mut skia_paragraph in text.to_paragraphs(&render_state.fonts().font_collection()) {
        skia_paragraph.layout(shape.width());

        let xy = (shape.selrect().x(), shape.selrect.y() + offset_y);
        skia_paragraph.paint(render_state.surfaces.canvas(SurfaceId::Fills), xy);
        offset_y += skia_paragraph.height();
    }
}
