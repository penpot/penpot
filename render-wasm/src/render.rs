use skia_safe::gpu::{self, gl::FramebufferInfo, DirectContext};
use skia_safe::{self as skia};
use std::collections::HashMap;
use uuid::Uuid;

use crate::shapes::Shape;
use crate::view::View;
use crate::images::Image;

struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

impl GpuState {
    fn new() -> Self {
        let interface = skia_safe::gpu::gl::Interface::new_native().unwrap();
        let context = skia_safe::gpu::direct_contexts::make_gl(interface, None).unwrap();
        let framebuffer_info = {
            let mut fboid: gl::types::GLint = 0;
            unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

            FramebufferInfo {
                fboid: fboid.try_into().unwrap(),
                format: skia_safe::gpu::gl::Format::RGBA8.into(),
                protected: skia_safe::gpu::Protected::No,
            }
        };

        GpuState {
            context,
            framebuffer_info,
        }
    }

    /// Create a Skia surface that will be used for rendering.
    fn create_target_surface(&mut self, width: i32, height: i32) -> skia::Surface {
        let backend_render_target =
            gpu::backend_render_targets::make_gl((width, height), 1, 8, self.framebuffer_info);

        gpu::surfaces::wrap_backend_render_target(
            &mut self.context,
            &backend_render_target,
            skia_safe::gpu::SurfaceOrigin::BottomLeft,
            skia_safe::ColorType::RGBA8888,
            None,
            None,
        )
        .unwrap()
    }
}

pub(crate) struct CachedSurfaceImage {
  pub image: Image,
  pub view: View,
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub final_surface: skia::Surface,
    pub drawing_surface: skia::Surface,
    pub cached_surface_image: Option<CachedSurfaceImage>,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let mut final_surface = gpu_state.create_target_surface(width, height);
        let drawing_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();

        RenderState {
            gpu_state,
            final_surface,
            drawing_surface,
            cached_surface_image: None,
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let surface = self.gpu_state.create_target_surface(width, height);
        self.final_surface = surface;
        self.drawing_surface = self
            .final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
    }

    pub fn flush(&mut self) {
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.final_surface, None)
    }

    pub fn translate(&mut self, dx: f32, dy: f32) {
        self.drawing_surface.canvas().translate((dx, dy));
    }

    pub fn scale(&mut self, sx: f32, sy: f32) {
        self.drawing_surface.canvas().scale((sx, sy));
    }

    pub fn reset_canvas(&mut self) {
        self.drawing_surface
            .canvas()
            .clear(skia_safe::Color::TRANSPARENT)
            .reset_matrix();
        self.final_surface
            .canvas()
            .clear(skia_safe::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn render_single_shape(&mut self, shape: &Shape) {
        let r = skia::Rect::new(
            shape.selrect.x1,
            shape.selrect.y1,
            shape.selrect.x2,
            shape.selrect.y2,
        );

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let mut matrix = skia::Matrix::new_identity();
        let (translate_x, translate_y) = shape.translation();
        let (scale_x, scale_y) = shape.scale();
        let (skew_x, skew_y) = shape.skew();

        matrix.set_all(
            scale_x,
            skew_x,
            translate_x,
            skew_y,
            scale_y,
            translate_y,
            0.,
            0.,
            1.,
        );

        let mut center = r.center();
        matrix.post_translate(center);
        center.negate();
        matrix.pre_translate(center);

        self.drawing_surface.canvas().concat(&matrix);

        for fill in shape.fills().rev() {
            self.drawing_surface.canvas().draw_rect(r, &fill.to_paint());
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(shape.blend_mode.into());
        self.drawing_surface.draw(
            &mut self.final_surface.canvas(),
            (0.0, 0.0),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            Some(&paint),
        );
        self.drawing_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT);
    }

    pub fn navigate(
        &mut self,
        view: &View,
        shapes: &HashMap<Uuid, Shape>,
    ) {
        self.reset_canvas();

        if let Some(cached_surface_image) = &self.cached_surface_image {
            // If we are drawing something bigger than the visible let's do a redraw
            if (view.x > cached_surface_image.view.x) ||
                (-view.x + view.width > -cached_surface_image.view.x + cached_surface_image.view.width) ||
                (view.y > cached_surface_image.view.y) ||
                (-view.y + view.height > -cached_surface_image.view.y + cached_surface_image.view.height) {         
                self.draw_all_shapes(view, shapes);
            }
            else {

                let image = &cached_surface_image.image;
                let paint = skia::Paint::default();
                self.final_surface.canvas().save();
                self.drawing_surface.canvas().save();

                let navigate_zoom = view.zoom / cached_surface_image.view.zoom;          
                let navigate_x =  cached_surface_image.view.zoom * (view.x - cached_surface_image.view.x);
                let navigate_y =  cached_surface_image.view.zoom * (view.y - cached_surface_image.view.y);

                self.final_surface.canvas().scale((navigate_zoom, navigate_zoom));
                self.final_surface.canvas().translate((navigate_x, navigate_y));
                self.final_surface.canvas().draw_image(image.clone(), (0, 0), Some(&paint));

                self.final_surface.canvas().restore();
                self.drawing_surface.canvas().restore();
            }
        }

        self.flush();
    }

    pub fn draw_all_shapes(
        &mut self,
        view: &View,
        shapes: &HashMap<Uuid, Shape>,
    ) {
        self.reset_canvas();
        self.scale(view.zoom, view.zoom);
        self.translate(view.x, view.y);
        self.render_shape_tree(Uuid::nil(), shapes);

        self.cached_surface_image = Some(CachedSurfaceImage {
            image: self.final_surface.image_snapshot(),
            view: view.clone(),
        });

        self.flush();
    }

    fn render_shape_tree(&mut self, id: Uuid, shapes: &HashMap<Uuid, Shape>) {
        let shape = shapes.get(&id).unwrap();

        // This is needed so the next non-children shape does not carry this shape's transform
        self.final_surface.canvas().save();
        self.drawing_surface.canvas().save();

        if id != Uuid::nil() {
            self.render_single_shape(shape);
        }

        // draw all the children shapes
        let shape_ids = shape.children.clone();
        for shape_id in shape_ids {
            self.render_shape_tree(shape_id, shapes);
        }

        self.final_surface.canvas().restore();
        self.drawing_surface.canvas().restore();
    }
}
