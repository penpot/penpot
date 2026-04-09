use skia_safe::{self as skia};

use super::shape_renderer::ShapeRenderer;
use super::{fills, shadows, strokes, RenderState, SurfaceId};
use crate::error::Result;
use crate::shapes::{Fill, Shape, Stroke, Type};

/// GPU render backend — thin wrapper around the existing surface-based
/// rendering modules (`fills`, `strokes`, `shadows`).
///
/// This struct is not yet used in the hot path (`render_shape`), but it
/// implements [`ShapeRenderer`] so that adding a new trait method produces
/// a compile error here, forcing the GPU side to be updated alongside the
/// PDF backend.
#[allow(dead_code)]
pub struct GpuRenderer<'a> {
    pub render_state: &'a mut RenderState,
    pub fills_surface_id: SurfaceId,
    pub strokes_surface_id: SurfaceId,
    pub innershadows_surface_id: SurfaceId,
    pub antialias: bool,
    pub outset: Option<f32>,
    pub fast_mode: bool,
    pub scale: f32,
}

impl ShapeRenderer for GpuRenderer<'_> {
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()> {
        fills::render(
            self.render_state,
            shape,
            fills,
            self.antialias,
            self.fills_surface_id,
            self.outset,
        )
    }

    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()> {
        strokes::render(
            self.render_state,
            shape,
            strokes,
            Some(self.strokes_surface_id),
            self.antialias,
            self.outset,
        )
    }

    fn draw_drop_shadows(&mut self, _shape: &Shape) -> Result<()> {
        // GPU handles drop shadows at tree traversal level
        // (render_shape_tree_partial), not at the leaf level.
        Ok(())
    }

    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()> {
        if !self.fast_mode {
            shadows::render_fill_inner_shadows(
                self.render_state,
                shape,
                self.antialias,
                self.innershadows_surface_id,
            );
        }
        Ok(())
    }

    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()> {
        if !self.fast_mode {
            shadows::render_stroke_inner_shadows(
                self.render_state,
                shape,
                stroke,
                self.antialias,
                self.innershadows_surface_id,
            )?;
        }
        Ok(())
    }

    fn draw_text(&mut self, shape: &Shape) -> Result<()> {
        // GPU text rendering is orchestrated inline in render_shape
        // (render.rs). This wrapper delegates to the same text module.
        let Type::Text(text_content) = &shape.shape_type else {
            return Ok(());
        };

        let text_content = text_content.new_bounds(shape.selrect());
        let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
        let blur_filter = shape.image_filter(1.);

        super::text::render(
            Some(self.render_state),
            None,
            shape,
            &mut paragraph_builders,
            Some(self.fills_surface_id),
            None,
            blur_filter.as_ref(),
            None,
            None,
        )?;

        Ok(())
    }

    fn draw_svg(&mut self, shape: &Shape) -> Result<()> {
        let Type::SVGRaw(sr) = &shape.shape_type else {
            return Ok(());
        };

        if let Some(svg_transform) = shape.svg_transform() {
            self.render_state
                .surfaces
                .canvas_and_mark_dirty(self.fills_surface_id)
                .concat(&svg_transform);
        }

        if let Some(svg) = shape.svg.as_ref() {
            svg.render(
                self.render_state
                    .surfaces
                    .canvas_and_mark_dirty(self.fills_surface_id),
            );
        } else {
            let font_manager =
                skia::FontMgr::from(self.render_state.fonts().font_provider().clone());
            if let Ok(dom) = skia::svg::Dom::from_str(&sr.content, font_manager) {
                dom.render(
                    self.render_state
                        .surfaces
                        .canvas_and_mark_dirty(self.fills_surface_id),
                );
            }
        }

        Ok(())
    }

    fn apply_blur_layer(&mut self, shape: &Shape) -> bool {
        use crate::shapes::{radius_to_sigma, BlurType};

        let blur = match shape.blur {
            Some(b) if !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0 => b,
            _ => return false,
        };

        let sigma = radius_to_sigma(blur.value * self.scale);
        if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
            let mut paint = skia::Paint::default();
            paint.set_image_filter(filter);
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            self.render_state
                .surfaces
                .canvas(self.fills_surface_id)
                .save_layer(&layer_rec);
            true
        } else {
            false
        }
    }

    fn restore_blur_layer(&mut self) {
        self.render_state
            .surfaces
            .canvas(self.fills_surface_id)
            .restore();
    }
}
