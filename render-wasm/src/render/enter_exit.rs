use std::borrow::Cow;

use skia_safe as skia;

use crate::error::Result;
use crate::shapes::{radius_to_sigma, Shape, StrokeKind, Type};

use super::layer_blur;
use super::{ClipStack, NodeRenderState, RenderState, SurfaceId};

pub fn render_shape_enter(
    render_state: &mut RenderState,
    element: &Shape,
    mask: bool,
    clip_bounds: Option<&ClipStack>,
    target_surface: SurfaceId,
) {
    if let Type::Group(group) = element.shape_type {
        let fills = &element.fills;
        let shadows = &element.shadows;
        render_state.nested_fills.push(fills.to_vec());
        render_state.nested_shadows.push(shadows.to_vec());

        if group.masked {
            let mask_group_blur = element.masked_group_layer_blur().is_some();
            if mask_group_blur {
                render_state.surfaces.canvas(target_surface).save();
                if let Some(clips) = clip_bounds {
                    let scale = render_state.get_scale();
                    let antialias = element
                            .should_use_antialias(scale, render_state.options.antialias_threshold);
                    render_state.clip_target_surface_to_stack(
                        clips,
                        target_surface,
                        scale,
                        antialias,
                    );
                }
            }

            let mut paint = skia::Paint::default();
            if let Some(blur) = element.masked_group_layer_blur() {
                let scale = render_state.get_scale();
                let sigma = radius_to_sigma(blur.value * scale);
                if let Some(filter) =
                    skia::image_filters::blur((sigma, sigma), None, None, None)
                {
                    paint.set_image_filter(filter);
                }
            }

            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            render_state
                .surfaces
                .canvas(target_surface)
                .save_layer(&layer_rec);
        }
    }

    if let Type::Frame(_) = element.shape_type {
        render_state.nested_fills.push(Vec::new());
    }

    if mask {
        let mut mask_paint = skia::Paint::default();
        mask_paint.set_blend_mode(skia::BlendMode::DstIn);
        let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
        render_state
            .surfaces
            .canvas(target_surface)
            .save_layer(&mask_rec);
    }

    let needs_layer = element.needs_layer();

    if needs_layer {
        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        if let Some(frame_blur) = layer_blur::frame_clip_layer_blur(element) {
            let scale = render_state.get_scale();
            let sigma = radius_to_sigma(frame_blur.value * scale);
            if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
                paint.set_image_filter(filter);
            }
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        render_state
            .surfaces
            .canvas(target_surface)
            .save_layer(&layer_rec);
    }

    render_state.focus_mode.enter(&element.id);
}

pub fn render_shape_exit(
    render_state: &mut RenderState,
    element: &Shape,
    visited_mask: bool,
    clip_bounds: Option<ClipStack>,
    target_surface: SurfaceId,
) -> Result<()> {
    if visited_mask {
        if let Type::Group(group) = element.shape_type {
            if group.masked {
                render_state.surfaces.canvas(target_surface).restore();
            }
        }
    } else if let Type::Group(group) = element.shape_type {
        if group.masked {
            render_state.pending_nodes.push(NodeRenderState {
                id: element.id,
                visited_children: true,
                clip_bounds: None,
                visited_mask: true,
                mask: false,
                flattened: false,
            });
            if let Some(&mask_id) = element.mask_id() {
                render_state.pending_nodes.push(NodeRenderState {
                    id: mask_id,
                    visited_children: false,
                    clip_bounds: None,
                    visited_mask: false,
                    mask: true,
                    flattened: false,
                });
            }
        }
    }

    match element.shape_type {
        Type::Frame(_) | Type::Group(_) => {
            render_state.nested_fills.pop();
            render_state.nested_blurs.pop();
            render_state.cached_layer_blur = None;
            render_state.nested_shadows.pop();
        }
        _ => {}
    }

    let needs_exit_strokes = render_state.focus_mode.is_active()
        && (element.clip()
            || (matches!(element.shape_type, Type::Frame(_)) && element.has_inner_stroke()));

    if needs_exit_strokes {
        let mut element_strokes: Cow<Shape> = Cow::Borrowed(element);
        element_strokes.to_mut().clear_fills();
        element_strokes.to_mut().clear_shadows();
        element_strokes.to_mut().clip_content = false;

        if !element.clip() {
            let is_open = element.is_open();
            element_strokes
                .to_mut()
                .strokes
                .retain(|s| s.render_kind(is_open) == StrokeKind::Inner);
        }

        if layer_blur::frame_clip_layer_blur(element).is_some() {
            element_strokes.to_mut().set_blur(None);
        }
        render_state.render_shape(
            &element_strokes,
            clip_bounds,
            SurfaceId::Fills,
            SurfaceId::Strokes,
            SurfaceId::InnerShadows,
            SurfaceId::TextDropShadows,
            true,
            None,
            None,
            None,
            target_surface,
        )?;
    }

    let needs_layer = element.needs_layer();

    if needs_layer {
        render_state.surfaces.canvas(target_surface).restore();
    }

    if visited_mask && element.masked_group_layer_blur().is_some() {
        render_state.surfaces.canvas(target_surface).restore();
    }

    render_state.focus_mode.exit(&element.id);
    Ok(())
}
