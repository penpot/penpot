use skia::gpu::{self, gl::FramebufferInfo, DirectContext};
use skia::Contains;
use skia_safe as skia;
use std::collections::HashMap;
use uuid::Uuid;

use crate::debug;
use crate::math::Rect;
use crate::shapes::{draw_image_in_container, Fill, Image, Kind, Shape};
use crate::view::Viewbox;

struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

impl GpuState {
    fn new() -> Self {
        let interface = skia::gpu::gl::Interface::new_native().unwrap();
        let context = skia::gpu::direct_contexts::make_gl(interface, None).unwrap();
        let framebuffer_info = {
            let mut fboid: gl::types::GLint = 0;
            unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

            FramebufferInfo {
                fboid: fboid.try_into().unwrap(),
                format: skia::gpu::gl::Format::RGBA8.into(),
                protected: skia::gpu::Protected::No,
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
            skia::gpu::SurfaceOrigin::BottomLeft,
            skia::ColorType::RGBA8888,
            None,
            None,
        )
        .unwrap()
    }
}

pub(crate) struct CachedSurfaceImage {
    pub image: Image,
    pub viewbox: Viewbox,
    has_all_shapes: bool,
}

impl CachedSurfaceImage {
    fn is_dirty(&self, viewbox: &Viewbox) -> bool {
        !self.has_all_shapes && !self.viewbox.area.contains(viewbox.area)
    }
}

#[derive(Debug, Copy, Clone, PartialEq)]
struct RenderOptions {
    debug_flags: u32,
    dpr: Option<f32>,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            debug_flags: 0x00,
            dpr: None,
        }
    }
}

impl RenderOptions {
    pub fn is_debug_visible(&self) -> bool {
        self.debug_flags & debug::DEBUG_VISIBLE == debug::DEBUG_VISIBLE
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub final_surface: skia::Surface,
    pub drawing_surface: skia::Surface,
    pub debug_surface: skia::Surface,
    pub cached_surface_image: Option<CachedSurfaceImage>,
    options: RenderOptions,
    pub viewbox: Viewbox,
    images: HashMap<Uuid, Image>,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let mut final_surface = gpu_state.create_target_surface(width, height);
        let drawing_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
        let debug_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();

        RenderState {
            gpu_state,
            final_surface,
            drawing_surface,
            debug_surface,
            cached_surface_image: None,
            options: RenderOptions::default(),
            viewbox: Viewbox::new(width as f32, height as f32),
            images: HashMap::with_capacity(2048),
        }
    }

    pub fn add_image(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        let image_data = skia::Data::new_copy(image_data);
        let image = Image::from_encoded(image_data).ok_or("Error decoding image data")?;

        self.images.insert(id, image);
        Ok(())
    }

    pub fn has_image(&mut self, id: &Uuid) -> bool {
        self.images.contains_key(id)
    }

    pub fn set_debug_flags(&mut self, debug: u32) {
        self.options.debug_flags = debug;
    }

    pub fn set_dpr(&mut self, dpr: f32) {
        if Some(dpr) != self.options.dpr {
            self.options.dpr = Some(dpr);
            self.resize(
                self.viewbox.width.floor() as i32,
                self.viewbox.height.floor() as i32,
            );
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;

        let surface = self.gpu_state.create_target_surface(dpr_width, dpr_height);
        self.final_surface = surface;
        self.drawing_surface = self
            .final_surface
            .new_surface_with_dimensions((dpr_width, dpr_height))
            .unwrap();
        self.debug_surface = self
            .final_surface
            .new_surface_with_dimensions((dpr_width, dpr_height))
            .unwrap();

        self.viewbox.set_wh(width as f32, height as f32);
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
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
        self.final_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
        self.debug_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn render_single_shape(&mut self, shape: &Shape) {
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

        let mut center = shape.selrect.center();
        matrix.post_translate(center);
        center.negate();
        matrix.pre_translate(center);

        self.drawing_surface.canvas().concat(&matrix);

        for fill in shape.fills().rev() {
            self.render_fill(fill, shape.selrect, &shape.kind);
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(shape.blend_mode.into());
        paint.set_alpha_f(shape.opacity);
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

    pub fn navigate(&mut self, shapes: &HashMap<Uuid, Shape>) -> Result<(), String> {
        if let Some(cached_surface_image) = self.cached_surface_image.as_ref() {
            if cached_surface_image.is_dirty(&self.viewbox) {
                self.render_all(shapes, true);
            } else {
                self.render_all_from_cache()?;
            }
        }

        Ok(())
    }

    pub fn render_all(
        &mut self,
        shapes: &HashMap<Uuid, Shape>,
        generate_cached_surface_image: bool,
    ) {
        self.reset_canvas();
        self.scale(
            self.viewbox.zoom * self.options.dpr(),
            self.viewbox.zoom * self.options.dpr(),
        );
        self.translate(self.viewbox.pan_x, self.viewbox.pan_y);

        let is_complete = self.render_shape_tree(&Uuid::nil(), shapes);
        if generate_cached_surface_image || self.cached_surface_image.is_none() {
            self.cached_surface_image = Some(CachedSurfaceImage {
                image: self.final_surface.image_snapshot(),
                viewbox: self.viewbox,
                has_all_shapes: is_complete,
            });
        }

        if self.options.is_debug_visible() {
            self.render_debug();
        }

        self.flush();
    }

    fn render_fill(&mut self, fill: &Fill, selrect: Rect, kind: &Kind) {
        match (fill, kind) {
            (Fill::Image(image_fill), kind) => {
                let image = self.images.get(&image_fill.id());
                if let Some(image) = image {
                    draw_image_in_container(
                        &self.drawing_surface.canvas(),
                        &image,
                        image_fill.size(),
                        kind,
                        &fill.to_paint(&selrect),
                    );
                }
            }
            (_, Kind::Rect(rect)) => {
                self.drawing_surface
                    .canvas()
                    .draw_rect(rect, &fill.to_paint(&selrect));
            }
            (_, Kind::Path(path)) => {
                self.drawing_surface
                    .canvas()
                    .draw_path(&path.to_skia_path(), &fill.to_paint(&selrect));
            }
        }
    }

    fn render_all_from_cache(&mut self) -> Result<(), String> {
        self.reset_canvas();

        let cached = self
            .cached_surface_image
            .as_ref()
            .ok_or("Uninitialized cached surface image")?;

        let image = &cached.image;
        let paint = skia::Paint::default();
        self.final_surface.canvas().save();
        self.drawing_surface.canvas().save();

        let navigate_zoom = self.viewbox.zoom / cached.viewbox.zoom;
        let navigate_x = cached.viewbox.zoom * (self.viewbox.pan_x - cached.viewbox.pan_x);
        let navigate_y = cached.viewbox.zoom * (self.viewbox.pan_y - cached.viewbox.pan_y);

        self.final_surface
            .canvas()
            .scale((navigate_zoom, navigate_zoom));
        self.final_surface.canvas().translate((
            navigate_x * self.options.dpr(),
            navigate_y * self.options.dpr(),
        ));
        self.final_surface
            .canvas()
            .draw_image(image.clone(), (0, 0), Some(&paint));

        self.final_surface.canvas().restore();
        self.drawing_surface.canvas().restore();

        self.flush();

        Ok(())
    }

    fn render_debug_view(&mut self) {
        let mut paint = skia::Paint::default();
        paint.set_style(skia::PaintStyle::Stroke);
        paint.set_color(skia::Color::from_argb(255, 255, 0, 255));
        paint.set_stroke_width(1.);

        let mut scaled_rect = self.viewbox.area.clone();
        let x = 100. + scaled_rect.x() * 0.2;
        let y = 100. + scaled_rect.y() * 0.2;
        let width = scaled_rect.width() * 0.2;
        let height = scaled_rect.height() * 0.2;
        scaled_rect.set_xywh(x, y, width, height);

        self.debug_surface.canvas().draw_rect(scaled_rect, &paint);
    }

    fn render_debug_shape(&mut self, shape: &Shape, intersected: bool) {
        let mut paint = skia::Paint::default();
        paint.set_style(skia::PaintStyle::Stroke);
        paint.set_color(if intersected {
            skia::Color::from_argb(255, 255, 255, 0)
        } else {
            skia::Color::from_argb(255, 0, 255, 255)
        });
        paint.set_stroke_width(1.);

        let mut scaled_rect = shape.selrect.clone();
        let x = 100. + scaled_rect.x() * 0.2;
        let y = 100. + scaled_rect.y() * 0.2;
        let width = scaled_rect.width() * 0.2;
        let height = scaled_rect.height() * 0.2;
        scaled_rect.set_xywh(x, y, width, height);

        self.debug_surface.canvas().draw_rect(scaled_rect, &paint);
    }

    fn render_debug(&mut self) {
        let paint = skia::Paint::default();
        self.render_debug_view();
        self.debug_surface.draw(
            &mut self.final_surface.canvas(),
            (0.0, 0.0),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            Some(&paint),
        );
    }

    // Returns a boolean indicating if the viewbox contains the rendered shapes
    fn render_shape_tree(&mut self, id: &Uuid, shapes: &HashMap<Uuid, Shape>) -> bool {
        let shape = shapes.get(&id).unwrap();
        let mut is_complete = self.viewbox.area.contains(shape.selrect);

        if !id.is_nil() {
            if !shape.selrect.intersects(self.viewbox.area) || shape.hidden {
                self.render_debug_shape(shape, false);
                // TODO: This means that not all the shapes are renderer so we
                // need to call a render_all on the zoom out.
                return is_complete; // TODO return is_complete or return false??
            } else {
                self.render_debug_shape(shape, true);
            }
        }

        // This is needed so the next non-children shape does not carry this shape's transform
        self.final_surface.canvas().save();
        self.drawing_surface.canvas().save();

        if !id.is_nil() {
            self.render_single_shape(shape);
        }

        // draw all the children shapes
        let shape_ids = shape.children.iter();
        for shape_id in shape_ids {
            is_complete = self.render_shape_tree(shape_id, shapes) && is_complete;
        }

        self.final_surface.canvas().restore();
        self.drawing_surface.canvas().restore();
        return is_complete;
    }
}
