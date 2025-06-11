use super::{RenderState, Shape, SurfaceId};
use skia_safe::{textlayout::Paragraph, Paint, Path};

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

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_as_path(
    render_state: &mut RenderState,
    paths: &Vec<(Path, Paint)>,
    surface_id: Option<SurfaceId>,
) {
    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::Fills));

    for (path, paint) in paths {
        // Note: path can be empty
        canvas.draw_path(path, paint);
    }
}

// How to use it?
// Type::Text(text_content) => {
//     self.surfaces
//         .apply_mut(&[SurfaceId::Fills, SurfaceId::Strokes], |s| {
//             s.canvas().concat(&matrix);
//         });

//     let text_content = text_content.new_bounds(shape.selrect());
//     let paths = text_content.get_paths(antialias);

//     shadows::render_text_drop_shadows(self, &shape, &paths, antialias);
//     text::render(self, &paths, None, None);

//     for stroke in shape.strokes().rev() {
//         shadows::render_text_path_stroke_drop_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//         strokes::render_text_paths(self, &shape, stroke, &paths, None, None, antialias);
//         shadows::render_text_path_stroke_inner_shadows(
//             self, &shape, &paths, stroke, antialias,
//         );
//     }

//     shadows::render_text_inner_shadows(self, &shape, &paths, antialias);
// }
