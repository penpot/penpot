use std::borrow::Cow;

use skia_safe as skia;

use crate::error::Result;
use crate::shapes::{radius_to_sigma, Blur, Fill, Shadow, Shape, SolidColor, Type};
use crate::state::ShapesPoolRef;

use super::{
    filters, get_simplified_children, layer_blur, ClipStack, NodeRenderState, RenderState,
    SurfaceId,
};

#[allow(clippy::too_many_arguments)]
pub fn render_drop_black_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shape_bounds: &skia::Rect,
    shadow: &Shadow,
    clip_bounds: Option<ClipStack>,
    scale: f32,
    extra_layer_blur: Option<Blur>,
    target_surface: SurfaceId,
) -> Result<()> {
    let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);
    transformed_shadow.to_mut().offset = (0.0, 0.0);
    transformed_shadow.to_mut().color = skia::Color::BLACK;

    let mut plain_shape = Cow::Borrowed(shape);
    let combined_blur = layer_blur::combine_blur_values(
        layer_blur::combined_layer_blur(
            &render_state.nested_blurs,
            &mut render_state.cached_layer_blur,
            shape.blur,
        ),
        extra_layer_blur,
    );
    let blur_filter = combined_blur.and_then(|blur| {
        let sigma = blur.sigma();
        skia::image_filters::blur((sigma, sigma), None, None, None)
    });

    let use_low_zoom_path = scale <= 1.0 && combined_blur.is_none();
    let mut transform_matrix = shape.transform;
    let center = shape.center();
    transform_matrix.post_translate(center);
    transform_matrix.pre_translate(-center);

    let mapped = transform_matrix.map_vector((shadow.offset.0, shadow.offset.1));
    let world_offset = (mapped.x, mapped.y);

    let plain_shape_mut = plain_shape.to_mut();
    plain_shape_mut.clear_fills();
    if shape.has_fills() {
        plain_shape_mut.add_fill(Fill::Solid(SolidColor(skia::Color::BLACK)));
    }

    for stroke in plain_shape_mut.strokes.iter_mut() {
        stroke.fill = Fill::Solid(SolidColor(skia::Color::BLACK));
    }

    plain_shape_mut.clear_shadows();
    plain_shape_mut.blur = None;

    plain_shape_mut.clip_content = false;

    let Some(drop_filter) = transformed_shadow.get_drop_shadow_filter() else {
        return Ok(());
    };

    let mut bounds = drop_filter.compute_fast_bounds(shape_bounds);
    bounds.offset(world_offset);
    if !bounds.intersects(render_state.render_area_with_margins)
        && target_surface != SurfaceId::Export
    {
        return Ok(());
    }

    if scale > 1.0 && shadow.blur <= 0.0 {
        let drop_canvas = render_state.surfaces.canvas(SurfaceId::DropShadows);
        drop_canvas.save();

        render_state.with_nested_blurs_suppressed(|state| {
            state.render_shape(
                &plain_shape,
                clip_bounds,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                false,
                Some(shadow.offset),
                None,
                Some(shadow.spread),
                target_surface,
            )
        })?;

        render_state
            .surfaces
            .canvas(SurfaceId::DropShadows)
            .restore();
        return Ok(());
    }

    let blur_only_filter = if transformed_shadow.blur > 0.0 {
        let sigma = radius_to_sigma(transformed_shadow.blur);
        Some(skia::image_filters::blur((sigma, sigma), None, None, None))
    } else {
        None
    };

    let mut shadow_paint = skia::Paint::default();
    if let Some(blur_filter) = blur_only_filter {
        shadow_paint.set_image_filter(blur_filter);
    }
    shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&shadow_paint);

    if use_low_zoom_path {
        let drop_canvas = render_state.surfaces.canvas(SurfaceId::DropShadows);
        drop_canvas.save_layer(&layer_rec);

        render_state.with_nested_blurs_suppressed(|state| {
            state.render_shape(
                &plain_shape,
                clip_bounds,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                SurfaceId::DropShadows,
                false,
                Some(shadow.offset),
                None,
                Some(shadow.spread),
                target_surface,
            )
        })?;

        render_state
            .surfaces
            .canvas(SurfaceId::DropShadows)
            .restore();
        return Ok(());
    }

    let blur_downscale_threshold: f32 = render_state.options.blur_downscale_threshold;
    let min_blur_downscale: f32 = 1.0 / blur_downscale_threshold;
    let blur_downscale = if shadow.blur > blur_downscale_threshold {
        (blur_downscale_threshold / shadow.blur).max(min_blur_downscale)
    } else {
        1.0
    };

    let filter_result = filters::render_into_filter_surface(
        render_state,
        bounds,
        blur_downscale,
        |state, temp_surface| {
            let canvas = state.surfaces.canvas(temp_surface);
            canvas.save_layer(&layer_rec);

            state.with_nested_blurs_suppressed(|state| {
                state.render_shape(
                    &plain_shape,
                    clip_bounds,
                    temp_surface,
                    temp_surface,
                    temp_surface,
                    temp_surface,
                    false,
                    Some(shadow.offset),
                    None,
                    Some(shadow.spread),
                    target_surface,
                )
            })?;

            state.surfaces.canvas(temp_surface).restore();
            Ok(())
        },
    )?;

    if let Some((mut surface, filter_scale)) = filter_result {
        let drop_canvas = render_state.surfaces.canvas(SurfaceId::DropShadows);
        drop_canvas.save();
        let mut drop_paint = skia::Paint::default();
        drop_paint.set_image_filter(blur_filter.clone());

        if filter_scale < 1.0 {
            drop_canvas.save();
            drop_canvas.scale((1.0 / filter_scale, 1.0 / filter_scale));
            drop_canvas.translate((bounds.left * filter_scale, bounds.top * filter_scale));
            surface.draw(
                drop_canvas,
                (0.0, 0.0),
                render_state.sampling_options,
                Some(&drop_paint),
            );
            drop_canvas.restore();
        } else {
            drop_canvas.save();
            drop_canvas.translate((bounds.left, bounds.top));
            surface.draw(
                drop_canvas,
                (0.0, 0.0),
                render_state.sampling_options,
                Some(&drop_paint),
            );
            drop_canvas.restore();
        }
        drop_canvas.restore();
    }

    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn render_element_drop_shadows_and_composite(
    render_state: &mut RenderState,
    element: &Shape,
    tree: ShapesPoolRef,
    extrect: &mut Option<skia::Rect>,
    clip_bounds: Option<ClipStack>,
    scale: f32,
    node_render_state: &NodeRenderState,
    target_surface: SurfaceId,
) -> Result<()> {
    let element_extrect = extrect.get_or_insert_with(|| element.extrect(tree, scale));
    let inherited_layer_blur = match element.shape_type {
        Type::Frame(_) | Type::Group(_) => element.blur,
        _ => None,
    };

    for shadow in element.drop_shadows_visible() {
        let paint = skia::Paint::default();
        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        render_state
            .surfaces
            .canvas(SurfaceId::DropShadows)
            .save_layer(&layer_rec);

        render_drop_black_shadow(
            render_state,
            element,
            element_extrect,
            shadow,
            clip_bounds.clone(),
            scale,
            None,
            target_surface,
        )?;

        if !matches!(element.shape_type, Type::Bool(_)) {
            let mut shadow_children = Vec::new();
            if element.is_recursive() {
                get_simplified_children(tree, element, &mut shadow_children);
            }

            for shadow_shape_id in shadow_children.iter() {
                let Some(shadow_shape) = tree.get(shadow_shape_id) else {
                    continue;
                };
                if shadow_shape.hidden {
                    continue;
                }

                let nested_clip_bounds =
                    node_render_state.get_nested_shadow_clip_bounds(element, shadow);

                if !matches!(shadow_shape.shape_type, Type::Text(_)) {
                    render_drop_black_shadow(
                        render_state,
                        shadow_shape,
                        &shadow_shape.extrect(tree, scale),
                        shadow,
                        nested_clip_bounds,
                        scale,
                        inherited_layer_blur,
                        target_surface,
                    )?;
                } else {
                    let paint = skia::Paint::default();
                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                    render_state
                        .surfaces
                        .canvas(SurfaceId::DropShadows)
                        .save_layer(&layer_rec);

                    let mut transformed_shadow: Cow<Shadow> = Cow::Borrowed(shadow);
                    transformed_shadow.to_mut().color = skia::Color::BLACK;
                    transformed_shadow.to_mut().blur = transformed_shadow.blur;
                    transformed_shadow.to_mut().spread = transformed_shadow.spread;

                    let mut new_shadow_paint = skia::Paint::default();
                    new_shadow_paint.set_image_filter(transformed_shadow.get_drop_shadow_filter());
                    new_shadow_paint.set_blend_mode(skia::BlendMode::SrcOver);

                    render_state.with_nested_blurs_suppressed(|state| {
                        state.render_shape(
                            shadow_shape,
                            nested_clip_bounds,
                            SurfaceId::DropShadows,
                            SurfaceId::DropShadows,
                            SurfaceId::DropShadows,
                            SurfaceId::DropShadows,
                            true,
                            None,
                            Some(vec![new_shadow_paint.clone()]),
                            None,
                            target_surface,
                        )
                    })?;
                    render_state
                        .surfaces
                        .canvas(SurfaceId::DropShadows)
                        .restore();
                }
            }
        }

        let mut paint = skia::Paint::default();
        paint.set_color(shadow.color);
        paint.set_blend_mode(skia::BlendMode::SrcIn);
        render_state
            .surfaces
            .canvas(SurfaceId::DropShadows)
            .draw_paint(&paint);

        render_state
            .surfaces
            .canvas(SurfaceId::DropShadows)
            .restore();
    }

    if let Some(clips) = clip_bounds.as_ref() {
        let antialias = element.should_use_antialias(scale, render_state.options.antialias_threshold);
        render_state.surfaces.canvas(target_surface).save();
        render_state.clip_target_surface_to_stack(clips, target_surface, scale, antialias);
        render_state
            .surfaces
            .draw_into(SurfaceId::DropShadows, target_surface, None);
        render_state.surfaces.canvas(target_surface).restore();
    } else {
        render_state
            .surfaces
            .draw_into(SurfaceId::DropShadows, target_surface, None);
    }

    render_state
        .surfaces
        .canvas(SurfaceId::DropShadows)
        .clear(skia::Color::TRANSPARENT);
    Ok(())
}
