use std::mem::ManuallyDrop;

use skia_safe as skia;

use crate::error::{Error, Result};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::vector;
use super::RenderResources;

mod glyph_text;
mod to_unicode;

/// In-flight multi-page PDF. The byte buffer and metadata are heap-pinned so
/// Skia's `Document` can hold `'static` write/metadata references across FFI
/// calls (skia-safe ties the document lifetime to both). Stored on
/// [`crate::state::State`], not a process-wide static.
pub(crate) struct PdfExportSession {
    bytes: *mut Vec<u8>,
    metadata: *mut skia::pdf::Metadata<'static>,
    document: ManuallyDrop<skia::Document<'static>>,
    roots: Vec<Uuid>,
}

fn pdf_metadata() -> skia::pdf::Metadata<'static> {
    skia::pdf::Metadata {
        creator: "Penpot".to_string(),
        producer: "Penpot (Skia PDF)".to_string(),
        raster_dpi: Some(300.0),
        ..Default::default()
    }
}

/// Starts a multi-page PDF session. Aborts any previous session in `slot` first.
pub fn export_begin(slot: &mut Option<PdfExportSession>) -> Result<()> {
    export_abort(slot);
    let bytes = Box::into_raw(Box::new(Vec::new()));
    let metadata = Box::into_raw(Box::new(pdf_metadata()));
    // SAFETY: `bytes` and `metadata` stay pinned until finish/abort. Document
    // writes through `&mut Vec<u8>`; reallocating the Vec's heap buffer is
    // fine because the reference targets the Vec, not its buffer.
    let document = unsafe {
        let bytes_ref: &'static mut Vec<u8> = &mut *bytes;
        let metadata_ref: &'static skia::pdf::Metadata<'static> = &*metadata;
        skia::pdf::new_document(bytes_ref, Some(metadata_ref))
    };
    *slot = Some(PdfExportSession {
        bytes,
        metadata,
        document: ManuallyDrop::new(document),
        roots: Vec::new(),
    });
    Ok(())
}

/// Appends one shape as a new page. Call between `export_begin` and
/// `export_finish`.
pub fn export_add_page(
    slot: &mut Option<PdfExportSession>,
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
) -> Result<()> {
    let session = slot
        .as_mut()
        .ok_or_else(|| Error::CriticalError("PDF export session not started".to_string()))?;

    let shape = tree
        .get(id)
        .ok_or_else(|| Error::CriticalError(format!("Shape not found for PDF: {id}")))?;
    // Boards crop overflowing children (classic board box) but keep their own
    // strokes/shadows/blur. Other shapes use full extrect.
    let bounds = shape.export_root_bounds(tree, 1.0);

    // SAFETY: document was created against session.bytes; we put it back.
    let document = unsafe { ManuallyDrop::take(&mut session.document) };
    let mut on_page = document.begin_page((bounds.width(), bounds.height()), None);
    {
        let page_canvas = on_page.canvas();
        page_canvas.translate((-bounds.left(), -bounds.top()));
        if let Err(err) = vector::render_tree_pdf(shared, page_canvas, id, tree, bounds) {
            // Restore the document so abort/finish can still run; do not
            // record this root — the page content failed.
            let document = on_page.end_page();
            session.document = ManuallyDrop::new(document);
            return Err(err);
        }
    }
    let document = on_page.end_page();
    session.document = ManuallyDrop::new(document);
    session.roots.push(*id);
    Ok(())
}

fn drop_session_buffers(bytes: *mut Vec<u8>, metadata: *mut skia::pdf::Metadata<'static>) {
    unsafe {
        drop(Box::from_raw(metadata));
        drop(Box::from_raw(bytes));
    }
}

/// Closes the session and returns the PDF bytes (with ToUnicode fixup).
pub fn export_finish(slot: &mut Option<PdfExportSession>, tree: ShapesPoolRef) -> Result<Vec<u8>> {
    let session = slot
        .take()
        .ok_or_else(|| Error::CriticalError("PDF export session not started".to_string()))?;

    if session.roots.is_empty() {
        let document = ManuallyDrop::into_inner(session.document);
        document.close();
        drop_session_buffers(session.bytes, session.metadata);
        return Err(Error::CriticalError(
            "No shapes for multi-page PDF".to_string(),
        ));
    }

    let roots = session.roots;
    let document = ManuallyDrop::into_inner(session.document);
    document.close();
    let pdf_bytes = unsafe { *Box::from_raw(session.bytes) };
    unsafe {
        drop(Box::from_raw(session.metadata));
    }

    // Skia maps ligatures and other substituted glyphs to U+0000; give them
    // back their text so it can be copied from the PDF.
    let glyph_texts = glyph_text::collect_many(tree, &roots);
    Ok(to_unicode::fix_to_unicode(pdf_bytes, &glyph_texts))
}

/// Drops an in-flight session without returning bytes.
pub fn export_abort(slot: &mut Option<PdfExportSession>) {
    let Some(session) = slot.take() else {
        return;
    };
    let document = ManuallyDrop::into_inner(session.document);
    document.close();
    drop_session_buffers(session.bytes, session.metadata);
}

/// Renders a shape tree to a single-page PDF and returns the raw PDF bytes.
pub fn render_to_pdf(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
) -> Result<Vec<u8>> {
    render_to_pdf_multi(shared, &[*id], tree)
}

/// Renders each shape root as a page of one PDF document, in `ids` order.
/// Uses a local session so it does not disturb a progressive export in
/// [`crate::state::State`].
pub fn render_to_pdf_multi(
    shared: &mut RenderResources,
    ids: &[Uuid],
    tree: ShapesPoolRef,
) -> Result<Vec<u8>> {
    if ids.is_empty() {
        return Err(Error::CriticalError(
            "No shapes for multi-page PDF".to_string(),
        ));
    }
    let mut session = None;
    export_begin(&mut session)?;
    for id in ids {
        if let Err(err) = export_add_page(&mut session, shared, id, tree) {
            export_abort(&mut session);
            return Err(err);
        }
    }
    match export_finish(&mut session, tree) {
        Ok(bytes) => Ok(bytes),
        Err(err) => {
            export_abort(&mut session);
            Err(err)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::shapes::{Fill, Rect, SolidColor, Type};
    use crate::state::ShapesPool;
    use crate::utils::uuid_from_u32_quartet;

    use skia_safe as skia;

    fn uid(n: u32) -> Uuid {
        uuid_from_u32_quartet(0, 0, 0, n)
    }

    fn add_solid_rect(
        pool: &mut ShapesPool,
        id: Uuid,
        (l, t, r, b): (f32, f32, f32, f32),
        color: skia::Color,
    ) {
        let shape = pool.add_shape(id);
        shape.set_parent(Uuid::nil());
        shape.set_shape_type(Type::Rect(Rect::default()));
        shape.set_selrect(l, t, r, b);
        shape.set_fills(vec![Fill::Solid(SolidColor(color))]);
    }

    /// Counts page objects: `/Type /Page` entries that are not `/Pages`.
    fn page_count(pdf: &[u8]) -> usize {
        let text = String::from_utf8_lossy(pdf);
        text.match_indices("/Type /Page")
            .filter(|(i, _)| !text[*i..].starts_with("/Type /Pages"))
            .count()
    }

    #[test]
    fn multi_page_pdf_has_one_page_per_shape() {
        let mut pool = ShapesPool::new();
        let a = uid(1);
        let b = uid(2);
        add_solid_rect(
            &mut pool,
            a,
            (0.0, 0.0, 100.0, 80.0),
            skia::Color::from_rgb(255, 0, 0),
        );
        add_solid_rect(
            &mut pool,
            b,
            (0.0, 0.0, 200.0, 120.0),
            skia::Color::from_rgb(0, 0, 255),
        );

        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

        let pdf = render_to_pdf_multi(&mut resources, &[a, b], &pool).expect("pdf");
        assert!(pdf.starts_with(b"%PDF"), "missing PDF header");
        assert_eq!(page_count(&pdf), 2, "expected two pages");
    }

    #[test]
    fn session_api_reports_progress_sized_pages() {
        let mut pool = ShapesPool::new();
        let a = uid(1);
        let b = uid(2);
        add_solid_rect(
            &mut pool,
            a,
            (0.0, 0.0, 100.0, 80.0),
            skia::Color::from_rgb(255, 0, 0),
        );
        add_solid_rect(
            &mut pool,
            b,
            (0.0, 0.0, 200.0, 120.0),
            skia::Color::from_rgb(0, 0, 255),
        );

        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

        let mut session = None;
        export_begin(&mut session).expect("begin");
        export_add_page(&mut session, &mut resources, &a, &pool).expect("page a");
        export_add_page(&mut session, &mut resources, &b, &pool).expect("page b");
        let pdf = export_finish(&mut session, &pool).expect("finish");
        assert_eq!(page_count(&pdf), 2);
    }

    #[test]
    fn empty_id_list_is_an_error() {
        let pool = ShapesPool::new();
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

        let err = render_to_pdf_multi(&mut resources, &[], &pool).expect_err("empty");
        assert!(
            matches!(err, Error::CriticalError(_)),
            "empty ids must fail critically: {err:?}"
        );
    }

    #[test]
    fn single_page_wrapper_still_works() {
        let mut pool = ShapesPool::new();
        let id = uid(1);
        add_solid_rect(
            &mut pool,
            id,
            (0.0, 0.0, 50.0, 50.0),
            skia::Color::from_rgb(0, 128, 0),
        );

        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

        let pdf = render_to_pdf(&mut resources, &id, &pool).expect("pdf");
        assert!(pdf.starts_with(b"%PDF"));
        assert_eq!(page_count(&pdf), 1);
    }
}
