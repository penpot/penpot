use crate::error::Result;
use crate::shapes::{Fill, Shape, Stroke};

/// Trait that both GPU and PDF render backends must implement.
///
/// Adding a method here produces a compile error until both backends
/// handle it, ensuring new rendering features are never silently
/// missing from PDF export.
pub trait ShapeRenderer {
    /// Draw fills for a shape (solid, gradient, image).
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()>;

    /// Draw strokes for a shape (inner, outer, center).
    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()>;

    /// Draw drop shadows (offset shadow behind the shape silhouette).
    fn draw_drop_shadows(&mut self, shape: &Shape) -> Result<()>;

    /// Draw inner shadows on filled geometry.
    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()>;

    /// Draw inner shadows on stroked geometry.
    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()>;

    /// Render a text shape (fills, strokes, shadows — full text pipeline).
    fn draw_text(&mut self, shape: &Shape) -> Result<()>;

    /// Render an SVG raw shape.
    fn draw_svg(&mut self, shape: &Shape) -> Result<()>;

    /// Apply a layer blur effect. Returns `true` if a save_layer was pushed
    /// (caller must call `restore_blur_layer`).
    fn apply_blur_layer(&mut self, shape: &Shape) -> bool;

    /// Restore the layer pushed by `apply_blur_layer`.
    fn restore_blur_layer(&mut self);
}
