use std::collections::HashMap;

use super::filters;
use super::{RenderState, SurfaceId};
use crate::error::Result;
use crate::get_resources;
use crate::render::strokes;
use crate::render::text;
use crate::shapes::radius_to_sigma;
use crate::shapes::{ParagraphBuilderGroup, Shadow, Shape, Stroke, StrokeKind, TextContent, Type};
use crate::uuid::Uuid;
use skia_safe::{self as skia, canvas::SaveLayerRec, Contains, Paint, Path, Rect};

// ---------------------------------------------------------------------------
// Direct frame drop shadows (fast inline blur + filter-surface cache fallback)
// ---------------------------------------------------------------------------

pub(crate) struct DropShadowFilterCache {
    entries: HashMap<DropShadowFilterCacheKey, CachedDropShadowFilter>,
    ellipses: HashMap<EllipseMaskKey, CachedDropShadowFilter>,
    ellipse_texels: usize,
}

#[derive(Hash, PartialEq, Eq, Clone, Copy)]
pub(crate) struct DropShadowFilterCacheKey {
    shape_id: Uuid,
    blur_bits: u32,
    spread_bits: u32,
    offset_x_bits: u32,
    offset_y_bits: u32,
    scale_bits: u32,
    transform_a_bits: u32,
    transform_b_bits: u32,
    transform_c_bits: u32,
    transform_d_bits: u32,
    transform_e_bits: u32,
    transform_f_bits: u32,
    layer_blur_bits: u32,
}

/// The shadow silhouette of a filled, unstroked ellipse is black regardless
/// of its fill. At full filter resolution its geometry is in document space.
/// Record all inputs that can change the native mask across camera updates.
#[derive(Hash, PartialEq, Eq)]
pub(super) struct EllipseMaskKey {
    device_oval: [u32; 4],
    local_size: [u32; 2],
    mask_size: [u32; 2],
    reflection: [u32; 2],
    blur: u32,
    antialias: bool,
}

impl EllipseMaskKey {
    pub(super) fn for_render(
        state: &mut RenderState,
        shape: &Shape,
        shadow: &Shadow,
        bounds: Rect,
        clips: Option<&super::ClipStack>,
        target: SurfaceId,
    ) -> Option<Self> {
        let transform = &shape.transform;
        // Once document pixels are magnified, draw their native coverage at
        // the actual position. Even one rounding level is visible in pixel QA.
        if state.get_scale() > 1.0
            || !matches!(shape.shape_type, Type::Circle)
            || !shape.has_fills()
            || shape.inherits_fills()
            || !shape.strokes.is_empty()
            || transform.has_perspective()
            || transform.skew_x() != 0.0
            || transform.skew_y() != 0.0
            || transform.scale_x().abs() != 1.0
            || transform.scale_y().abs() != 1.0
            || state.options.is_debug_visible()
            || state.options.is_fast_mode()
            || target == SurfaceId::Export
            || shadow.blur > state.options.blur_downscale_threshold
            || !bounds.is_finite()
            || bounds.is_empty()
            || bounds.width() > 192.0
            || bounds.height() > 192.0
        {
            return None;
        }
        // A nearby or transformed clip can change the silhouette between
        // zooms. Keep the normal filter path whenever it could affect a texel.
        let guarded = bounds.with_outset((2.0, 2.0));
        if !clips.into_iter().flatten().all(|(rect, corners, matrix)| {
            matrix.is_identity()
                && match corners {
                    Some(radii) => skia::RRect::new_rect_radii(*rect, radii).contains(guarded),
                    None => rect.contains(guarded),
                }
        }) {
            return None;
        }

        let (w, h) = state.surfaces.filter_size();
        let canvas = state.surfaces.canvas(SurfaceId::Filter);
        if bounds.width().ceil() > w as f32
            || bounds.height().ceil() > h as f32
            || !canvas.local_to_device_as_3x3().is_identity()
            || !canvas.is_clip_rect()
            || canvas.device_clip_bounds() != Some(skia::IRect::from_wh(w, h))
        {
            return None;
        }
        let aa = shape.should_use_antialias(state.get_scale(), state.options.antialias_threshold);
        Some(Self::for_shape(shape, shadow, bounds, aa))
    }

    fn for_shape(shape: &Shape, shadow: &Shadow, bounds: Rect, antialias: bool) -> Self {
        // The filter canvas starts at unit density with its origin at the
        // crop's upper left. Mirror the draw_oval CTM and spread exactly.
        let mut matrix = shape.centered_transform();
        matrix.pre_translate(shadow.offset);
        let mut device = skia::Matrix::translate((-bounds.left, -bounds.top));
        device.pre_concat(&matrix);
        let spread = shadow.spread.max(0.0);
        let oval = device
            .map_rect(shape.selrect.with_outset((spread, spread)))
            .0;
        Self {
            device_oval: [oval.left, oval.top, oval.right, oval.bottom].map(f32::to_bits),
            local_size: [shape.selrect.width(), shape.selrect.height()].map(f32::to_bits),
            mask_size: [bounds.width(), bounds.height()].map(f32::to_bits),
            reflection: [matrix.scale_x(), matrix.scale_y()].map(f32::to_bits),
            blur: shadow.blur.to_bits(),
            antialias,
        }
    }
}

pub(crate) struct CachedDropShadowFilter {
    bounds: Rect,
    filter_scale: f32,
    image: skia::Image,
}

impl CachedDropShadowFilter {
    pub(super) fn placed_at(&self, bounds: Rect) -> Self {
        Self {
            bounds,
            filter_scale: self.filter_scale,
            image: self.image.clone(),
        }
    }

    pub(crate) fn new(bounds: Rect, filter_scale: f32, surface: &mut skia::Surface) -> Self {
        // The reusable filter surface can be viewport-sized. Retaining all of it
        // for every shadow keeps large, mostly transparent textures alive.
        // Filter drawing translates bounds to the origin before applying scale.
        let width = (bounds.width() * filter_scale)
            .ceil()
            .clamp(1.0, surface.width() as f32) as i32;
        let height = (bounds.height() * filter_scale)
            .ceil()
            .clamp(1.0, surface.height() as f32) as i32;
        let subset = skia::IRect::from_wh(width, height);
        let info = surface.image_info().with_dimensions((width, height));
        let owned = surface.direct_context().and_then(|mut ctx| {
            skia::gpu::surfaces::render_target(
                &mut ctx,
                skia::gpu::Budgeted::Yes,
                &info,
                None,
                skia::gpu::SurfaceOrigin::BottomLeft,
                None,
                true,
                false,
            )
        });
        let image = if let Some(mut mask) = owned {
            // Preserve the filtered bytes exactly. Ganesh owns the mip levels
            // beside the cropped mask, so each minified blit can reuse them.
            let mut copy = Paint::default();
            copy.set_blend_mode(skia::BlendMode::Src);
            surface.draw(
                mask.canvas(),
                (0., 0.),
                skia::SamplingOptions::default(),
                Some(&copy),
            );
            mask.image_snapshot()
        } else {
            surface
                .image_snapshot_with_bounds(subset)
                .expect("shadow bounds must intersect the filter surface")
        };
        Self {
            bounds,
            filter_scale,
            image,
        }
    }
}

impl DropShadowFilterCacheKey {
    pub(crate) fn for_shape(
        shape_id: Uuid,
        shadow: &Shadow,
        scale: f32,
        transform: &skia::Matrix,
        layer_blur: f32,
    ) -> Self {
        Self::new(shape_id, shadow, scale, transform, layer_blur)
    }

    fn new(
        shape_id: Uuid,
        shadow: &Shadow,
        scale: f32,
        transform: &skia::Matrix,
        layer_blur: f32,
    ) -> Self {
        Self {
            shape_id,
            blur_bits: shadow.blur.to_bits(),
            spread_bits: shadow.spread.to_bits(),
            offset_x_bits: shadow.offset.0.to_bits(),
            offset_y_bits: shadow.offset.1.to_bits(),
            scale_bits: scale.to_bits(),
            transform_a_bits: transform[0].to_bits(),
            transform_b_bits: transform[1].to_bits(),
            transform_c_bits: transform[2].to_bits(),
            transform_d_bits: transform[3].to_bits(),
            transform_e_bits: transform[4].to_bits(),
            transform_f_bits: transform[5].to_bits(),
            layer_blur_bits: layer_blur.to_bits(),
        }
    }
}

impl DropShadowFilterCache {
    pub fn new() -> Self {
        Self {
            entries: HashMap::default(),
            ellipses: HashMap::default(),
            ellipse_texels: 0,
        }
    }

    pub fn clear_pass(&mut self) {
        self.entries.clear();
    }

    pub(super) fn clear_ellipses(&mut self) {
        self.ellipses.clear();
        self.ellipse_texels = 0;
    }

    pub(super) fn ellipse(&self, key: &EllipseMaskKey) -> Option<&CachedDropShadowFilter> {
        self.ellipses.get(key)
    }

    pub(super) fn store_ellipse(&mut self, key: EllipseMaskKey, mask: CachedDropShadowFilter) {
        let texels = mask.image.width() as usize * mask.image.height() as usize;
        // Mask storage is shared across zoom levels and bounded independently
        // of the document's size. A full cache continues to serve previous hits.
        const MAX_ENTRIES: usize = 384;
        const MAX_BASE_TEXELS: usize = 2 * 1024 * 1024;
        if self.ellipses.len() < MAX_ENTRIES && self.ellipse_texels + texels <= MAX_BASE_TEXELS {
            self.ellipse_texels += texels;
            self.ellipses.insert(key, mask);
        }
    }

    pub(crate) fn lookup(&self, key: &DropShadowFilterCacheKey) -> Option<&CachedDropShadowFilter> {
        self.entries.get(key)
    }

    pub(crate) fn store(&mut self, key: DropShadowFilterCacheKey, value: CachedDropShadowFilter) {
        self.entries.insert(key, value);
    }
}

/// Renders a direct frame drop shadow: inline blur on the tile when the kernel
/// fits the margin, otherwise a cached filter-surface pass shared across tiles.
///
/// Does not apply the caller's clip stack; clip is applied when compositing
/// `DropShadows` onto the target surface.
pub(crate) fn render_direct_frame_drop_shadow(
    state: &mut RenderState,
    frame: &Shape,
    shape_bounds: &Rect,
    shadow: &Shadow,
    scale: f32,
) -> Result<()> {
    let margin = state.surfaces.margins().width as f32;
    let sigma_device = radius_to_sigma(shadow.blur) * scale;
    if sigma_device <= margin / 3.0 {
        render_inline_frame_shadow(state, frame, shadow, scale)
    } else {
        render_cached_filter_frame_shadow(state, frame, shape_bounds, shadow, scale)
    }
}

fn frame_shadow_antialias(state: &RenderState, frame: &Shape, scale: f32) -> bool {
    !state.options.is_fast_mode()
        && frame.should_use_antialias(scale, state.options.antialias_threshold)
}

fn spread_outset(spread: f32) -> Option<f32> {
    Some(spread).filter(|&s| s > 0.0)
}

fn spread_inset(spread: f32) -> Option<f32> {
    Some(-spread).filter(|&s| s > 0.0)
}

fn blur_layer_paint(blur: f32, sigma_scale: f32) -> skia::Paint {
    let mut paint = skia::Paint::default();
    if blur > 0.0 {
        let sigma = radius_to_sigma(blur) * sigma_scale;
        paint.set_image_filter(skia::image_filters::blur((sigma, sigma), None, None, None));
    }
    paint.set_blend_mode(skia::BlendMode::SrcOver);
    paint
}

fn draw_frame_shadow_rect(
    surfaces: &mut super::Surfaces,
    surface_id: SurfaceId,
    frame: &Shape,
    shadow: &Shadow,
    antialias: bool,
) {
    let mut fill_paint = skia::Paint::default();
    fill_paint.set_color(skia::Color::BLACK);
    fill_paint.set_anti_alias(antialias);
    surfaces.draw_rect_to(
        surface_id,
        frame,
        &fill_paint,
        spread_outset(shadow.spread),
        spread_inset(shadow.spread),
    );
}

fn shadow_filter_bounds(
    shadow: &Shadow,
    shape_bounds: &Rect,
    world_offset: (f32, f32),
) -> Option<Rect> {
    let mut shadow_cull = *shadow;
    shadow_cull.color = skia::Color::BLACK;
    shadow_cull.offset = (0.0, 0.0);
    let drop_filter = shadow_cull.get_drop_shadow_filter()?;
    let mut bounds = drop_filter.compute_fast_bounds(*shape_bounds);
    bounds.offset(world_offset);
    Some(bounds)
}

/// Local draw matrix for frame shadow geometry: centered shape transform plus
/// shadow offset in local space (matches `render_shape` with `Some(offset)`).
fn frame_shadow_draw_matrix(frame: &Shape, shadow: &Shadow) -> skia::Matrix {
    let mut matrix = frame.centered_transform();
    matrix.pre_translate((shadow.offset.0, shadow.offset.1));
    matrix
}

/// Shadow offset mapped to world space (for bounds culling and cache blit).
fn shadow_world_offset(frame: &Shape, shadow: &Shadow) -> (f32, f32) {
    let mapped = frame
        .centered_transform()
        .map_vector((shadow.offset.0, shadow.offset.1));
    (mapped.x, mapped.y)
}

/// When bounds fit in the filter surface, skip blur downscale to avoid banding
/// at high zoom. The tile cache makes a single full-res pass affordable.
fn blur_downscale_for_frame_shadow(
    blur: f32,
    bounds: Rect,
    filter_width: i32,
    filter_height: i32,
    threshold: f32,
) -> f32 {
    let bounds_w = bounds.width().ceil().max(1.0) as i32;
    let bounds_h = bounds.height().ceil().max(1.0) as i32;
    if bounds_w <= filter_width && bounds_h <= filter_height {
        return 1.0;
    }
    if blur > threshold {
        (threshold / blur).max(1.0 / threshold)
    } else {
        1.0
    }
}

pub(crate) fn blit_cached_drop_shadow_filter(
    surfaces: &mut super::Surfaces,
    cached: &CachedDropShadowFilter,
    layer_blur: Option<skia::ImageFilter>,
) {
    let sampling = get_resources().sampling_options;
    let mut paint = skia::Paint::default();
    if let Some(filter) = layer_blur {
        paint.set_image_filter(filter);
    }
    let drop_canvas = surfaces.canvas(SurfaceId::DropShadows);
    let dst = skia::Rect::from_wh(cached.image.width() as f32, cached.image.height() as f32);

    drop_canvas.save();
    drop_canvas.save();
    if cached.filter_scale < 1.0 {
        drop_canvas.scale((1.0 / cached.filter_scale, 1.0 / cached.filter_scale));
        drop_canvas.translate((
            cached.bounds.left * cached.filter_scale,
            cached.bounds.top * cached.filter_scale,
        ));
    } else {
        drop_canvas.translate((cached.bounds.left, cached.bounds.top));
    }
    drop_canvas.draw_image_rect_with_sampling_options(&cached.image, None, dst, sampling, &paint);
    drop_canvas.restore();
    drop_canvas.restore();
}

fn render_inline_frame_shadow(
    state: &mut RenderState,
    frame: &Shape,
    shadow: &Shadow,
    scale: f32,
) -> Result<()> {
    let antialias = frame_shadow_antialias(state, frame, scale);
    let layer_paint = blur_layer_paint(shadow.blur, 1.0);
    let draw_matrix = frame_shadow_draw_matrix(frame, shadow);

    {
        let drop_canvas = state.surfaces.canvas(SurfaceId::DropShadows);
        drop_canvas.save();
        drop_canvas.concat(&draw_matrix);
        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&layer_paint);
        drop_canvas.save_layer(&layer_rec);
    }

    draw_frame_shadow_rect(
        &mut state.surfaces,
        SurfaceId::DropShadows,
        frame,
        shadow,
        antialias,
    );

    {
        let drop_canvas = state.surfaces.canvas(SurfaceId::DropShadows);
        drop_canvas.restore();
        drop_canvas.restore();
    }

    Ok(())
}

fn render_cached_filter_frame_shadow(
    state: &mut RenderState,
    frame: &Shape,
    shape_bounds: &Rect,
    shadow: &Shadow,
    scale: f32,
) -> Result<()> {
    let draw_matrix = frame.centered_transform();
    let key = DropShadowFilterCacheKey::for_shape(frame.id, shadow, scale, &draw_matrix, 0.0);
    if let Some(cached) = state.drop_shadow_filter_cache.lookup(&key) {
        blit_cached_drop_shadow_filter(&mut state.surfaces, cached, None);
        return Ok(());
    }

    let world_offset = shadow_world_offset(frame, shadow);
    let Some(bounds) = shadow_filter_bounds(shadow, shape_bounds, world_offset) else {
        return Ok(());
    };

    let antialias = frame_shadow_antialias(state, frame, scale);
    let (filter_w, filter_h) = state.surfaces.filter_size();
    let blur_downscale = blur_downscale_for_frame_shadow(
        shadow.blur,
        bounds,
        filter_w,
        filter_h,
        state.options.blur_downscale_threshold,
    );
    let layer_paint = blur_layer_paint(shadow.blur, blur_downscale);
    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&layer_paint);

    let shadow_draw_matrix = frame_shadow_draw_matrix(frame, shadow);
    let filter_result = filters::render_into_filter_surface(
        state,
        bounds,
        blur_downscale,
        |state, temp_surface| {
            {
                let canvas = state.surfaces.canvas(temp_surface);
                canvas.save();
                canvas.concat(&shadow_draw_matrix);
                canvas.save_layer(&layer_rec);
            }
            draw_frame_shadow_rect(&mut state.surfaces, temp_surface, frame, shadow, antialias);
            {
                let canvas = state.surfaces.canvas(temp_surface);
                canvas.restore();
                canvas.restore();
            }
            Ok(())
        },
    )?;

    if let Some((mut surface, filter_scale)) = filter_result {
        let cached = CachedDropShadowFilter::new(bounds, filter_scale, &mut surface);
        blit_cached_drop_shadow_filter(&mut state.surfaces, &cached, None);
        state.drop_shadow_filter_cache.store(key, cached);
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Inner / text shadows
// ---------------------------------------------------------------------------

pub fn render_fill_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    antialias: bool,
    surface_id: SurfaceId,
) {
    // A masked group's inner shadows are applied to the masked result by its
    // layer filter (see `Shape::masked_group_layer_filter`).
    if !shape.has_fills() || shape.is_masked_group() || render_state.should_skip_drop_shadows() {
        return;
    }
    let scale = render_state.get_scale();
    let recursive = shape.is_recursive();
    for shadow in shape.inner_shadows_visible() {
        if !shadow.is_perceptible_at_scale_for(scale, recursive) {
            continue;
        }
        render_fill_inner_shadow(render_state, shape, shadow, antialias, surface_id);
    }
}

fn render_fill_inner_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
    surface_id: SurfaceId,
) {
    let paint = &shadow.get_inner_shadow_paint(antialias, shape.image_filter(1.).as_ref());
    render_shadow_paint(render_state, shape, paint, surface_id);
}

pub fn render_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    antialias: bool,
    surface_id: SurfaceId,
) -> Result<()> {
    if shape.has_fills() || render_state.should_skip_drop_shadows() {
        return Ok(());
    }
    let scale = render_state.get_scale();
    let recursive = shape.is_recursive();
    for shadow in shape.inner_shadows_visible() {
        if !shadow.is_perceptible_at_scale_for(scale, recursive) {
            continue;
        }
        let filter = shadow.get_inner_shadow_filter();
        strokes::render_single(
            render_state,
            shape,
            stroke,
            Some(surface_id),
            filter.as_ref(),
            antialias,
            None, // Inner shadows don't use spread
        )?;
    }
    Ok(())
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_path_stroke_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paths: &Vec<(Path, Paint)>,
    stroke: &Stroke,
    antialias: bool,
) {
    for shadow in shape.drop_shadows_visible() {
        let stroke_shadow = shadow.get_drop_shadow_filter();
        strokes::render_text_paths(
            render_state,
            shape,
            stroke,
            paths,
            Some(SurfaceId::DropShadows),
            stroke_shadow.as_ref(),
            antialias,
        );
    }
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_path_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paths: &Vec<(Path, Paint)>,
    stroke: &Stroke,
    antialias: bool,
) {
    for shadow in shape.inner_shadows_visible() {
        let stroke_shadow = shadow.get_inner_shadow_filter();
        strokes::render_text_paths(
            render_state,
            shape,
            stroke,
            paths,
            Some(SurfaceId::InnerShadows),
            stroke_shadow.as_ref(),
            antialias,
        );
    }
}

fn render_shadow_paint(
    render_state: &mut RenderState,
    shape: &Shape,
    paint: &Paint,
    surface_id: SurfaceId,
) {
    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            render_state
                .surfaces
                .draw_rect_to(surface_id, shape, paint, None, None);
        }
        Type::Circle => {
            render_state
                .surfaces
                .draw_circle_to(surface_id, shape, paint, None, None);
        }
        Type::Path(_) | Type::Bool(_) => {
            render_state
                .surfaces
                .draw_path_to(surface_id, shape, paint, None, None);
        }
        _ => {}
    }
}

#[allow(clippy::too_many_arguments)]
pub fn render_text_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [ParagraphBuilderGroup],
    stroke_paragraphs_group: &mut [Vec<ParagraphBuilderGroup>],
    surface_id: Option<SurfaceId>,
    shadows: &[Paint],
    blur_filter: &Option<skia_safe::ImageFilter>,
    stroke_kinds: &[StrokeKind],
    text_content: &TextContent,
) -> Result<()> {
    if stroke_paragraphs_group.is_empty() {
        return Ok(());
    }

    let canvas = render_state
        .surfaces
        .canvas_and_mark_dirty(surface_id.unwrap_or(SurfaceId::TextDropShadows));

    for shadow in shadows {
        let shadow_layer = SaveLayerRec::default().paint(shadow);
        canvas.save_layer(&shadow_layer);

        text::render(
            None,
            Some(canvas),
            shape,
            paragraphs,
            surface_id,
            None,
            blur_filter.as_ref(),
            None,
            None,
        )?;

        for (i, stroke_paragraphs) in stroke_paragraphs_group.iter_mut().enumerate() {
            if i < stroke_kinds.len() && stroke_kinds[i] == StrokeKind::Inner {
                let mut fill_builders = text_content.paragraph_builder_group_from_text(Some(true));
                text::render_inner_stroke(
                    None,
                    Some(canvas),
                    shape,
                    stroke_paragraphs,
                    &mut fill_builders,
                    surface_id,
                    blur_filter.as_ref(),
                    0.0,
                    None,
                )?;
            } else if i < stroke_kinds.len() && stroke_kinds[i] == StrokeKind::Outer {
                text::render_outer_stroke(
                    None,
                    Some(canvas),
                    shape,
                    stroke_paragraphs,
                    surface_id,
                    blur_filter.as_ref(),
                    0.0,
                    None,
                )?;
            } else {
                text::render(
                    None,
                    Some(canvas),
                    shape,
                    stroke_paragraphs,
                    surface_id,
                    None,
                    blur_filter.as_ref(),
                    None,
                    None,
                )?;
            }
        }

        canvas.restore();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn translated_ellipse_masks_match_only_with_identical_filter_pixels() {
        let mut s = Shape::new(Uuid::nil());
        s.shape_type = Type::Circle;
        s.set_selrect(100.25, 123.5, 106.25, 129.5);
        s.transform = skia::Matrix::scale((1.0, -1.0));
        let bounds = Rect::from_xywh(90.0, 112.0, 28.0, 30.0);
        let shadow = Shadow::new(
            skia::Color::BLACK,
            3.,
            1.,
            (0., 2.),
            crate::shapes::ShadowStyle::Drop,
            false,
        );
        let key = EllipseMaskKey::for_shape(&s, &shadow, bounds, true);
        assert!(key != EllipseMaskKey::for_shape(&s, &shadow, bounds, false));
        let mut changed = shadow;
        changed.blur = 2.;
        assert!(key != EllipseMaskKey::for_shape(&s, &changed, bounds, true));
        s.set_selrect(1100.25, -876.5, 1106.25, -870.5);
        let shifted = bounds.with_offset((1000., -1000.));
        assert!(key == EllipseMaskKey::for_shape(&s, &shadow, shifted, true));
        assert!(
            key != EllipseMaskKey::for_shape(&s, &shadow, shifted.with_offset((0.25, 0.)), true)
        );
        s.set_selrect(1100.25, -876.5, 1107.25, -870.5);
        assert!(key != EllipseMaskKey::for_shape(&s, &shadow, shifted, true));
    }

    #[test]
    fn cached_shadow_retains_only_its_scaled_bounds() {
        for (bounds, scale, expected) in [
            (Rect::from_xywh(100.0, 200.0, 50.5, 40.5), 1.0, (51, 41)),
            (Rect::from_xywh(100.0, 200.0, 50.5, 40.5), 0.5, (26, 21)),
            (Rect::from_xywh(0.0, 0.0, 2000.0, 1500.0), 1.0, (1024, 768)),
        ] {
            let mut surface = skia::surfaces::raster_n32_premul((1024, 768)).unwrap();
            let cached = CachedDropShadowFilter::new(bounds, scale, &mut surface);

            assert_eq!(cached.image.dimensions(), skia::ISize::from(expected));
        }
    }

    #[test]
    fn cached_shadow_survives_filter_surface_reuse() {
        let mut surface = skia::surfaces::raster_n32_premul((1024, 768)).unwrap();
        surface.canvas().clear(skia::Color::TRANSPARENT);
        let mut paint = Paint::default();
        paint.set_color(skia::Color::RED);
        surface
            .canvas()
            .draw_rect(Rect::from_xywh(4.0, 5.0, 10.0, 10.0), &paint);
        let cached = CachedDropShadowFilter::new(
            Rect::from_xywh(100.0, 200.0, 50.5, 40.5),
            1.0,
            &mut surface,
        );

        surface.canvas().clear(skia::Color::BLUE);

        let pixels = cached.image.peek_pixels().unwrap();
        assert_eq!(pixels.get_color((5, 6)), skia::Color::RED);
        assert_eq!(pixels.get_color((0, 0)), skia::Color::TRANSPARENT);
    }
}
