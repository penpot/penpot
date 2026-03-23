use crate::error::Result;
use crate::shapes::{Fill, Shape, Stroke};

/// Trait implemented by canvas-based vector export backends (PDF, and
/// future SVG).
///
/// # Parity contract
///
/// This trait is the single declaration of the rendering *capabilities* a leaf
/// shape can have. Two rules keep vector export from drifting away from the
/// live (GPU) render:
///
/// 1. **New capability → new trait method.** Any new per-shape rendering
///    feature (a new effect, a new fill/stroke mode, etc.) MUST be added as a
///    method here, not inline in the GPU `render_shape`. Adding a method
///    produces a compile error in every backend until handled, so the feature
///    can never be silently missing from vector export.
/// 2. **Order lives in one place.** The draw order/gating of these methods is
///    encoded once in `vector::render_leaf_content` (generic over this trait),
///    so call-order can't diverge between backends that reuse it.
///
/// Shape-*type* coverage is enforced separately by exhaustive `match`es (no
/// `_ =>` arms) in `vector.rs`, so a new `Type` variant also fails to compile
/// until handled.
pub trait ShapeRenderer {
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()>;
    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()>;
    fn draw_drop_shadows(&mut self, shape: &Shape) -> Result<()>;
    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()>;
    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()>;
    fn draw_text(&mut self, shape: &Shape) -> Result<()>;
    fn draw_svg(&mut self, shape: &Shape) -> Result<()>;
    fn apply_blur_layer(&mut self, shape: &Shape) -> bool;
    fn restore_blur_layer(&mut self);
}
