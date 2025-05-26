use super::{RenderState, SurfaceId};
use skia_safe::{self as skia, canvas::SaveLayerRec};

pub fn render(
    render_state: &mut RenderState,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>,
    surface_id: Option<SurfaceId>,
    paint: Option<skia::Paint>,
    _fonts: &skia::textlayout::FontCollection,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    if let Some(ref paint) = paint {
        let mut blended_paint = paint.clone();
        blended_paint.set_blend_mode(skia::BlendMode::SrcOver);
        let mask = SaveLayerRec::default().paint(&blended_paint);
        canvas.save_layer(&mask);
    } else {
        canvas.save_layer(&SaveLayerRec::default());
    }

    for (paragraph_builder, xy, width) in paragraphs.iter_mut() {
        let mut paragraph = paragraph_builder.build();
        paragraph.layout(*width);
        paragraph.paint(canvas, *xy);

        canvas.restore();
    }
}
