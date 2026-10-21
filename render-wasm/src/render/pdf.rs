use skia_safe as skia;

use crate::error::Result;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::vector;
use super::RenderResources;

mod glyph_text;
mod to_unicode;

/// Renders a shape tree to a PDF document and returns the raw PDF bytes.
///
/// This is a dedicated vector-PDF render path that draws directly to a Skia
/// PDF canvas, bypassing the GPU surface system entirely.  The result is a
/// true vector PDF — paths, text and fills are represented as PDF drawing
/// operations rather than rasterised bitmaps.  Effects that are inherently
/// pixel-based (blur, shadows with blur) are rasterised internally by Skia's
/// PDF backend
pub fn render_to_pdf(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
) -> Result<Vec<u8>> {
    let shape = tree
        .get(id)
        .ok_or_else(|| crate::error::Error::CriticalError("Shape not found for PDF".to_string()))?;
    // Boards crop overflowing children (classic board box) but keep their own
    // strokes/shadows/blur. Other shapes use full extrect.
    let bounds = shape.export_root_bounds(tree, 1.0);

    let mut pdf_bytes: Vec<u8> = Vec::new();

    let metadata = skia::pdf::Metadata {
        creator: "Penpot".to_string(),
        producer: "Penpot (Skia PDF)".to_string(),
        raster_dpi: Some(300.0),
        ..Default::default()
    };

    let document = skia::pdf::new_document(&mut pdf_bytes, Some(&metadata));

    let mut on_page = document.begin_page((bounds.width(), bounds.height()), None);

    {
        let page_canvas = on_page.canvas();
        page_canvas.translate((-bounds.left(), -bounds.top()));
        vector::render_tree_pdf(shared, page_canvas, id, tree, bounds)?;
    }

    let document = on_page.end_page();
    document.close();

    // Skia maps ligatures and other substituted glyphs to U+0000; give them
    // back their text so it can be copied from the PDF.
    let glyph_texts = glyph_text::collect(tree, id);
    Ok(to_unicode::fix_to_unicode(pdf_bytes, &glyph_texts))
}
