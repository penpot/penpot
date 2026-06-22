use crate::error::Result;
use crate::shapes::{Fill, Shape, Stroke};

/// Capabilities a leaf shape can render, implemented by the canvas-based vector
/// export backend (`vector::VectorRenderer`, used for PDF and future SVG).
///
/// New per-shape features must be added as a method here (compile error until
/// the backend handles it, so nothing is silently missing from vector export);
/// draw order/gating lives once in `vector::render_leaf_content`.
pub trait ShapeRenderer {
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()>;
    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()>;
    fn draw_drop_shadows(&mut self, shape: &Shape) -> Result<()>;
    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()>;
    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()>;
    fn draw_text(&mut self, shape: &Shape) -> Result<()>;
    fn draw_svg(&mut self, shape: &Shape) -> Result<()>;
    /// Returns `true` if a layer was pushed; caller must `restore_blur_layer`.
    fn apply_blur_layer(&mut self, shape: &Shape) -> bool;
    fn restore_blur_layer(&mut self);
}
