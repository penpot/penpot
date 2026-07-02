use skia_safe::{self as skia, Canvas, Paint};

use crate::error::Result;
use crate::shapes::{radius_to_sigma, BlurType, Shape, Stroke, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::shape_renderer::ShapeRenderer;
use super::vector::{
    children_paint_order, clip_to_frame_content, render_leaf_content, VectorRenderer, VectorTarget,
};
use super::RenderState;

/// Renders a shape tree to a PDF document and returns the raw PDF bytes.
///
/// This is a dedicated vector-PDF render path that draws directly to a Skia
/// PDF canvas, bypassing the GPU surface system entirely.  The result is a
/// true vector PDF — paths, text and fills are represented as PDF drawing
/// operations rather than rasterised bitmaps.  Effects that are inherently
/// pixel-based (blur, shadows with blur) are rasterised internally by Skia's
/// PDF backend
pub fn render_to_pdf(
    shared: &mut RenderState,
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

    let metadata = skia::pdf::Metadata {
        creator: "Penpot".to_string(),
        producer: "Penpot (Skia PDF)".to_string(),
        ..Default::default()
    };

    let document = skia::pdf::new_document(&mut pdf_bytes, Some(&metadata));

    let mut on_page = document.begin_page((page_w, page_h), None);

    {
        let page_canvas = on_page.canvas();
        page_canvas.scale((scale, scale));
        page_canvas.translate((-bounds.left(), -bounds.top()));
        render_tree(shared, page_canvas, id, tree, scale, VectorTarget::Pdf)?;
    }

    let document = on_page.end_page();
    document.close();

    Ok(pdf_bytes)
}

// ---------------------------------------------------------------------------
// Tree traversal
// ---------------------------------------------------------------------------

/// Depth-first render of the shape tree rooted at `id` onto a Skia canvas.
///
/// This is the PDF export path: it draws straight to the (PDF) canvas and uses
/// `save_layer` for composite effects, which the PDF backend supports. SVG has
/// its own compositor (see the `svg` module) because `SkSVGDevice` drops
/// `save_layer` content.
fn render_tree(
    shared: &mut RenderState,
    canvas: &Canvas,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let Some(element) = tree.get(id) else {
        return Ok(());
    };

    if element.hidden {
        return Ok(());
    }

    match &element.shape_type {
        Type::Group(group) => {
            render_group(shared, canvas, element, group.masked, tree, scale, target)?;
        }
        Type::Frame(_) => {
            render_frame(shared, canvas, element, tree, scale, target)?;
        }
        // Leaf types listed explicitly (no `_`) so a new Type must be handled.
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Text(_)
        | Type::SVGRaw(_) => {
            render_leaf(shared, canvas, element, scale, target)?;
        }
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

fn render_group(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    masked: bool,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    // A group has no geometry of its own and does NOT propagate a transform to
    // its children: child shapes are stored in absolute coordinates and each
    // applies its own `centered_transform`. (Concatenating the group transform
    // here would double-apply it to children — visible on rotated/nested groups.)
    canvas.save();

    // Group drop shadow: subtree silhouette, below the opacity/mask layer.
    render_container_drop_shadows(shared, canvas, element, tree, scale, target, false)?;

    // Layer for opacity / blend mode / group layer blur (and masking).
    let needs_layer = element.needs_layer();
    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        if let Some(blur) = element
            .blur
            .filter(|b| !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0)
        {
            let sigma = radius_to_sigma(blur.value * scale);
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    let children = children_paint_order(tree, element);

    if masked {
        // Mirror the GPU mask: render the content children into a composition
        // layer, then re-draw the mask silhouette (the group's first child) with
        // `DstIn` so it clips everything to its alpha. This preserves soft/alpha
        // masks exactly (unlike a geometric clip). The SVG backend can't keep
        // `save_layer` content, so its compositor uses a `<clipPath>` instead.
        canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&Paint::default()));

        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale, target)?;
        }

        if let Some(mask_id) = element.mask_id() {
            let mut mask_paint = Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&mask_paint));
            render_tree(shared, canvas, mask_id, tree, scale, target)?;
            canvas.restore(); // mask layer
        }

        canvas.restore(); // composition layer
    } else {
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale, target)?;
        }
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore();
    Ok(())
}

// ---------------------------------------------------------------------------
// Frames
// ---------------------------------------------------------------------------

fn render_frame(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    // A frame's own geometry (background, clip, strokes) is placed by its
    // `centered_transform`, but — like groups — it does NOT propagate that
    // transform to its children, which are stored in absolute coordinates. So
    // the transform is applied only around the frame's own draws; children are
    // rendered untransformed.
    let matrix = element.centered_transform();

    canvas.save();

    // Frame drop shadow: background + subtree silhouette, below the clip layer
    // so it extends outside the frame bounds.
    render_container_drop_shadows(shared, canvas, element, tree, scale, target, true)?;

    let needs_layer = element.needs_layer();

    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        // Frame-level layer blur
        if let Some(blur) = element
            .blur
            .filter(|b| !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0)
        {
            let sigma = radius_to_sigma(blur.value * scale);
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    // Clip to frame bounds in the frame's own space, then undo the transform so
    // children draw at their absolute coords while staying clipped (mirrors the
    // GPU clip). Outset ~0.5px like the GPU clip to avoid an AA seam.
    if element.clip_content {
        canvas.concat(&matrix);
        clip_to_frame_content(canvas, element, scale);
        if let Some(inverse) = matrix.invert() {
            canvas.concat(&inverse);
        }
    }

    // Frame's own fills (background) + inner shadows, in the frame's space.
    if !element.fills.is_empty() {
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
        renderer.draw_fills(element, &element.fills)?;
        renderer.draw_fill_inner_shadows(element)?;
        canvas.restore();
    }

    // Children (absolute coords, no frame transform).
    let children = children_paint_order(tree, element);
    for child_id in &children {
        render_tree(shared, canvas, child_id, tree, scale, target)?;
    }

    // Strokes over children (clipped frames), in the frame's space.
    let visible_strokes: Vec<&Stroke> = element.visible_strokes().collect();
    if !visible_strokes.is_empty() {
        canvas.save();
        canvas.concat(&matrix);
        let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
        renderer.draw_strokes(element, &visible_strokes)?;
        canvas.restore();
    }

    if needs_layer {
        canvas.restore(); // opacity/blend layer
    }
    canvas.restore();
    Ok(())
}

/// Drop shadows for a container: render the subtree into a drop-shadow filter
/// layer (its alpha becomes the shadow). `draw_fills` includes the frame
/// background in the silhouette.
fn render_container_drop_shadows(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    tree: ShapesPoolRef,
    scale: f32,
    target: VectorTarget,
    draw_fills: bool,
) -> Result<()> {
    for shadow in element.drop_shadows_visible() {
        let Some(filter) = shadow.get_drop_shadow_filter() else {
            continue;
        };
        let mut paint = Paint::default();
        paint.set_image_filter(filter);
        canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(&paint));

        if draw_fills && !element.fills.is_empty() {
            let mut renderer = VectorRenderer::new(canvas, shared, scale, target);
            renderer.draw_fills(element, &element.fills)?;
        }

        let children = children_paint_order(tree, element);
        for child_id in &children {
            render_tree(shared, canvas, child_id, tree, scale, target)?;
        }

        canvas.restore();
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Leaf shapes (Rect, Circle, Path, Bool, Text, SVGRaw)
// ---------------------------------------------------------------------------

fn render_leaf(
    shared: &mut RenderState,
    canvas: &Canvas,
    element: &Shape,
    scale: f32,
    target: VectorTarget,
) -> Result<()> {
    let needs_layer = element.needs_layer();

    let matrix = element.centered_transform();

    canvas.save();
    canvas.concat(&matrix);

    // Layer for opacity/blend
    if needs_layer {
        let mut paint = Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());
        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        canvas.save_layer(&layer_rec);
    }

    let mut renderer = VectorRenderer::new(canvas, shared, scale, target);

    // Layer blur (non-text shapes)
    let blur_layer = if !matches!(element.shape_type, Type::Text(_)) {
        renderer.apply_blur_layer(element)
    } else {
        false
    };

    renderer.draw_drop_shadows(element)?;
    render_leaf_content(&mut renderer, element)?;

    if blur_layer {
        renderer.restore_blur_layer();
    }

    if needs_layer {
        canvas.restore();
    }

    canvas.restore();
    Ok(())
}
