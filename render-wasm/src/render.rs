use skia_safe as skia;
use skia_safe::gpu::{self, gl::FramebufferInfo, DirectContext};
use uuid::Uuid;

use crate::shapes::Shape;
use crate::state::State;

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
    fn create_surface(&mut self, width: i32, height: i32) -> skia::Surface {
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

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub surface: skia::Surface,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let surface = gpu_state.create_surface(width, height);

        RenderState { gpu_state, surface }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let surface = self.gpu_state.create_surface(width, height);
        self.surface = surface;
    }
}

pub(crate) fn flush(state: &mut State) {
    state
        .render_state
        .gpu_state
        .context
        .flush_and_submit_surface(&mut state.render_state.surface, None);
}

pub(crate) fn translate(state: &mut State, dx: f32, dy: f32) {
    state.render_state.surface.canvas().translate((dx, dy));
}

pub(crate) fn scale(state: &mut State, sx: f32, sy: f32) {
    state.render_state.surface.canvas().scale((sx, sy));
}

pub(crate) fn render_shape_tree(state: &mut State, id: Uuid) {
    let shape = state.shapes.get(&id).unwrap();

    // This is needed so the next non-children shape does not carry this shape's transform
    state.render_state.surface.canvas().save();

    render_single_shape(&mut state.render_state.surface, shape);

    // draw all the children shapes
    let shape_ids = shape.children.clone();
    for shape_id in shape_ids {
        render_shape_tree(state, shape_id);
    }

    state.render_state.surface.canvas().restore();
}

fn render_single_shape(surface: &mut skia::Surface, shape: &Shape) {
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

    surface.canvas().concat(&matrix);

    // TODO: use blend mode for the shape as a whole, not in each fill
    for fill in shape.fills().rev() {
        let mut p = fill.to_paint();
        p.set_blend_mode(shape.blend_mode.into());
        surface.canvas().draw_rect(r, &p);
    }
}
