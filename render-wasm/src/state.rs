use skia_safe as skia;
use std::collections::HashMap;
use uuid::Uuid;

use crate::render::RenderState;
use crate::shapes::Shape;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub current_shape: Option<&'a mut Shape>,
    pub shapes: HashMap<Uuid, Shape>,
}

impl<'a> State<'a> {
    pub fn with_capacity(width: i32, height: i32, capacity: usize) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
        }
    }

    pub fn render_state(&'a mut self) -> &'a mut RenderState {
        &mut self.render_state
    }

    pub fn draw_all_shapes(&mut self, zoom: f32, pan_x: f32, pan_y: f32) {
        self.render_state.reset_canvas();

        self.render_state.scale(zoom, zoom);
        self.render_state.translate(pan_x, pan_y);

        self.render_shape_tree(Uuid::nil());

        self.render_state.flush();
    }

    fn render_shape_tree(&mut self, id: Uuid) {
        let shape = self.shapes.get(&id).unwrap();

        // This is needed so the next non-children shape does not carry this shape's transform
        self.render_state.surface.canvas().save();

        render_single_shape(&mut self.render_state.surface, shape);

        // draw all the children shapes
        let shape_ids = shape.children.clone();
        for shape_id in shape_ids {
            self.render_shape_tree(shape_id);
        }

        self.render_state.surface.canvas().restore();
    }

    pub fn use_shape(&'a mut self, id: Uuid) {
        if !self.shapes.contains_key(&id) {
            let new_shape = Shape::new(id);
            self.shapes.insert(id, new_shape);
        }

        self.current_id = Some(id);
        self.current_shape = self.shapes.get_mut(&id);
    }

    pub fn current_shape(&'a mut self) -> Option<&'a mut Shape> {
        self.current_shape.as_deref_mut()
    }
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
