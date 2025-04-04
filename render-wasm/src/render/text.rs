use super::{RenderState, Shape, SurfaceId};
use crate::shapes::TextContent;
use skia_safe::{textlayout::TextStyle, Color};


pub fn render(render_state: &mut RenderState, shape: &Shape, text: &TextContent) {
    let mut offset_y = 0.0;

    // TODO: delete, just for testing purposes
    let mut color = Color::BLACK;
    for stroke in shape.strokes().rev() {
        color = stroke.fill.color()
    }

    for mut skia_paragraph in text.to_paragraphs(&render_state.fonts().font_collection(), color) {
        skia_paragraph.layout(shape.width());

        let xy = (shape.selrect().x(), shape.selrect().y() + offset_y);
        skia_paragraph.paint(render_state.surfaces.canvas(SurfaceId::Fills), xy);
        offset_y += skia_paragraph.height();
    }
}
