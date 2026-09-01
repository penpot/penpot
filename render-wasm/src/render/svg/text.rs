use crate::error::Result;
use crate::shapes::Shape;

use super::document::SvgLayerCanvas;
use crate::render::text;

/// Emits a text shape's fill as native `<text>` elements.
///
/// The shared GPU/PDF renderer wraps text in `save_layer`, which `SkSVGDevice`
/// silently drops. Text strokes are handled separately in a later PR.
pub(super) fn render_text_fill(builder: &mut SvgLayerCanvas, element: &Shape) -> Result<()> {
    let matrix = element.centered_transform();
    let canvas = builder.canvas();
    canvas.save();
    canvas.concat(&matrix);
    text::paint_text_fill(canvas, element);
    canvas.restore();
    Ok(())
}
