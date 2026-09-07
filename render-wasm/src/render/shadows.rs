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
use skia_safe::{self as skia, canvas::SaveLayerRec, Paint, Path, Rect};

// ---------------------------------------------------------------------------
// Direct frame drop shadows (fast inline blur + filter-surface cache fallback)
// ---------------------------------------------------------------------------

pub(crate) struct DropShadowFilterCache {
    entries: HashMap<DropShadowFilterCacheKey, CachedDropShadowFilter>,
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

pub(crate) struct CachedDropShadowFilter {
    bounds: Rect,
    filter_scale: f32,
    image: skia::Image,
}

impl CachedDropShadowFilter {
    pub(crate) fn new(bounds: Rect, filter_scale: f32, image: skia::Image) -> Self {
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
        }
    }

    pub fn clear(&mut self) {
        self.entries.clear();
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
        let cached = CachedDropShadowFilter {
            bounds,
            filter_scale,
            image: surface.image_snapshot(),
        };
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
    if !shape.has_fills() || render_state.should_skip_drop_shadows() {
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
