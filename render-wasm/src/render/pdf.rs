use skia_safe as skia;

use crate::error::Result;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::vector::{self, VectorTarget};
use super::RenderState;

/// Renders a shape tree to a PDF document and returns the raw PDF bytes.
///
/// This is a dedicated vector-PDF render path that draws directly to a Skia
/// PDF canvas, bypassing the GPU surface system entirely.  The result is a
/// true vector PDF — paths, text and fills are represented as PDF drawing
/// operations rather than rasterised bitmaps.  Effects that are inherently
/// pixel-based (blur, shadows with blur) are rasterised internally by Skia's
/// PDF backend, which is the expected behaviour.
pub fn render_to_pdf(
    render_state: &mut RenderState,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<Vec<u8>> {
    let shape = tree
        .get(id)
        .ok_or_else(|| crate::error::Error::CriticalError("Shape not found for PDF".to_string()))?;
    let bounds = shape.extrect(tree, scale);

    let page_w = bounds.width() * scale;
    let page_h = bounds.height() * scale;

    let mut pdf_bytes: Vec<u8> = Vec::new();

    // Build PDF metadata
    let metadata = skia::pdf::Metadata {
        creator: "Penpot".to_string(),
        producer: "Penpot (Skia PDF)".to_string(),
        ..Default::default()
    };

    let document = skia::pdf::new_document(&mut pdf_bytes, Some(&metadata));

    // begin_page consumes the document and returns an OnPage state
    let mut on_page = document.begin_page((page_w, page_h), None);

    {
        let page_canvas = on_page.canvas();

        // Set up coordinate space: scale and translate so the shape's top-left
        // maps to the page origin.
        page_canvas.scale((scale, scale));
        page_canvas.translate((-bounds.left(), -bounds.top()));

        // Render the shape tree depth-first onto the PDF canvas.
        vector::render_tree(
            render_state,
            page_canvas,
            id,
            tree,
            scale,
            VectorTarget::Pdf,
        )?;
    }

    // end_page consumes OnPage and returns the open document
    let document = on_page.end_page();
    // close finalises the PDF and flushes to the writer
    document.close();

    Ok(pdf_bytes)
}
