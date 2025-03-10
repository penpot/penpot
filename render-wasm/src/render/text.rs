use super::{RenderState, SurfaceId};
use crate::shapes::TextContent;

pub fn render(render_state: &mut RenderState, text: &TextContent) {
    let mut offset_y = 0.0;
    for mut skia_paragraph in text.to_paragraphs(&render_state.fonts().font_collection()) {
        skia_paragraph.layout(text.width());

        let xy = (text.x(), text.y() + offset_y);
        skia_paragraph.paint(render_state.surfaces.canvas(SurfaceId::Fills), xy);
        offset_y += skia_paragraph.height();
    }
}
