//! GPU-free headless boot + render test: `render_init_headless` builds the
//! render state on the CPU and renders a real text shape end-to-end, as the
//! exporter does after `init_headless`.

use super::*;
use crate::shapes::{
    Fill, FontFamily, FontStyle, GrowType, Paragraph, SolidColor, TextAlign, TextContent,
    TextDirection, TextSpan, Type,
};
use crate::uuid::Uuid;
use skia_safe as skia;

const PNG_MAGIC: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];

#[test]
fn headless_init_and_render_text_without_gpu() {
    render_init_headless(800, 600);
    text_editor_init();
    design_init();

    let state = get_design_state();
    let id = Uuid::new_v4();
    {
        let shape = state.shapes.add_shape(id);
        shape.selrect = skia::Rect::from_xywh(0.0, 0.0, 200.0, 60.0);

        // Default built-in font (registered by FontStore::try_new).
        let span = TextSpan::new(
            "Headless".to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            32.0,
            1.2,
            0.0,
            None,
            None,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            vec![Fill::Solid(SolidColor(skia::Color::BLACK))],
        );
        let paragraph = Paragraph::new(
            TextAlign::default(),
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![span],
        );
        let mut content =
            TextContent::new(skia::Rect::from_xywh(0.0, 0.0, 200.0, 60.0), GrowType::Fixed);
        content.add_paragraph(paragraph);
        shape.shape_type = Type::Text(content);
    }

    let (png, width, height) = state
        .render_shape_raster(&id, 1.0)
        .expect("headless raster render");

    assert!(width > 0 && height > 0, "non-empty dimensions");
    assert!(png.len() > PNG_MAGIC.len(), "non-empty PNG");
    assert_eq!(&png[..8], &PNG_MAGIC, "valid PNG signature");
}
