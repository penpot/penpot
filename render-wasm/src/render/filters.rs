use skia_safe::{self as skia, ImageFilter, Rect};

use super::{RenderState, SurfaceId};

/// Composes two image filters, returning a combined filter if both are present,
/// or the individual filter if only one is present, or None if neither is present.
///
/// # Parameters
/// - `filter_a`: The first optional image filter.
/// - `filter_b`: The second optional image filter.
///
/// # Returns
/// - `Some(ImageFilter)`: The composed or single filter.
/// - `None`: If both filters are `None`.
pub fn compose_filters(
    filter_a: Option<&ImageFilter>,
    filter_b: Option<&ImageFilter>,
) -> Option<ImageFilter> {
    match (filter_a, filter_b) {
        (Some(fa), Some(fb)) => ImageFilter::compose(fa, fb),
        (Some(fa), None) => Some(fa.clone()),
        (None, Some(fb)) => Some(fb.clone()),
        (None, None) => None,
    }
}

/// Renders filtered content offscreen and composites it back into the target surface.
///
/// This helper is meant for shapes that rely on blur/filters that should be evaluated
/// in document space, regardless of the zoom level currently applied on the main canvas.
/// It draws the filtered content into `SurfaceId::Filter`, optionally downscales the
/// offscreen canvas when the requested bounds exceed the filter surface dimensions, and
/// then draws the resulting image into `target_surface`, scaling it back up if needed.
pub fn render_with_filter_surface<F>(
    render_state: &mut RenderState,
    bounds: Rect,
    target_surface: SurfaceId,
    draw_fn: F,
) -> bool
where
    F: FnOnce(&mut RenderState, SurfaceId),
{
    if let Some((image, scale)) = render_into_filter_surface(render_state, bounds, draw_fn) {
        let canvas = render_state.surfaces.canvas(target_surface);

        // If we scaled down, we need to scale the source rect and adjust the destination
        if scale < 1.0 {
            // The image was rendered at a smaller scale, so we need to scale it back up
            let scaled_width = bounds.width() * scale;
            let scaled_height = bounds.height() * scale;
            let src_rect = skia::Rect::from_xywh(0.0, 0.0, scaled_width, scaled_height);

            canvas.save();
            canvas.scale((1.0 / scale, 1.0 / scale));
            canvas.draw_image_rect_with_sampling_options(
                image,
                Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
                skia::Rect::from_xywh(
                    bounds.left * scale,
                    bounds.top * scale,
                    scaled_width,
                    scaled_height,
                ),
                render_state.sampling_options,
                &skia::Paint::default(),
            );
            canvas.restore();
        } else {
            // No scaling needed, draw normally
            let src_rect = skia::Rect::from_xywh(0.0, 0.0, bounds.width(), bounds.height());
            canvas.draw_image_rect_with_sampling_options(
                image,
                Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
                bounds,
                render_state.sampling_options,
                &skia::Paint::default(),
            );
        }
        true
    } else {
        false
    }
}

/// Creates/clears `SurfaceId::Filter`, prepares it for drawing the filtered content,
/// and executes the provided `draw_fn`.
///
/// If the requested bounds are larger than the filter surface, the canvas is scaled
/// down so that everything fits; the returned `scale` tells the caller how much the
/// content was reduced so it can be re-scaled on compositing. The `draw_fn` should
/// render the untransformed shape (i.e. in document coordinates) onto `SurfaceId::Filter`.
pub fn render_into_filter_surface<F>(
    render_state: &mut RenderState,
    bounds: Rect,
    draw_fn: F,
) -> Option<(skia::Image, f32)>
where
    F: FnOnce(&mut RenderState, SurfaceId),
{
    if !bounds.is_finite() || bounds.width() <= 0.0 || bounds.height() <= 0.0 {
        return None;
    }

    let filter_id = SurfaceId::Filter;
    let (filter_width, filter_height) = render_state.surfaces.filter_size();
    let bounds_width = bounds.width().ceil().max(1.0) as i32;
    let bounds_height = bounds.height().ceil().max(1.0) as i32;

    // Calculate scale factor if bounds exceed filter surface size
    let scale = if bounds_width > filter_width || bounds_height > filter_height {
        let scale_x = filter_width as f32 / bounds_width as f32;
        let scale_y = filter_height as f32 / bounds_height as f32;
        // Use the smaller scale to ensure everything fits
        scale_x.min(scale_y).max(0.1) // Clamp to minimum 0.1 to avoid extreme scaling
    } else {
        1.0
    };

    {
        let canvas = render_state.surfaces.canvas(filter_id);
        canvas.clear(skia::Color::TRANSPARENT);
        canvas.save();
        // Apply scale first, then translate
        canvas.scale((scale, scale));
        canvas.translate((-bounds.left, -bounds.top));
    }

    draw_fn(render_state, filter_id);

    render_state.surfaces.canvas(filter_id).restore();

    Some((render_state.surfaces.snapshot(filter_id), scale))
}
