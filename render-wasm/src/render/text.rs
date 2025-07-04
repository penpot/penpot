use super::{RenderState, Shape, SurfaceId};
use crate::shapes::VerticalAlign;
use skia_safe::{canvas::SaveLayerRec, textlayout::Paragraph, Paint, Path};

#[allow(dead_code)]
pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &[Vec<Paragraph>],
    surface_id: Option<SurfaceId>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    let container_height = shape.selrect().height();
    for group in paragraphs {
        let total_paragraphs_height: f32 = group.iter().map(|p| p.height()).sum();

        let mut offset_y = match shape.vertical_align() {
            VerticalAlign::Center => (container_height - total_paragraphs_height) / 2.0,
            VerticalAlign::Bottom => container_height - total_paragraphs_height,
            _ => 0.0,
        };

        for skia_paragraph in group {
            let xy = (shape.selrect().x(), shape.selrect.y() + offset_y);
            skia_paragraph.paint(canvas, xy);
            offset_y += skia_paragraph.height();
        }
    }
}

pub fn render_as_path(
    render_state: &mut RenderState,
    paths: &Vec<(Path, Paint)>,
    surface_id: Option<SurfaceId>,
    paint: Option<&Paint>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    if let Some(p) = paint {
        let mask = SaveLayerRec::default().paint(p);
        canvas.save_layer(&mask);
    }

    for (path, paint) in paths {
        // Note: path can be empty
        canvas.draw_path(path, paint);
    }
}
