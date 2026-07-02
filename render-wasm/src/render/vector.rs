use skia_safe::{self as skia, Canvas, Paint, RRect};

use crate::error::Result;
use crate::shapes::{
    merge_fills, radius_to_sigma, BlurType, Fill, Frame, Rect, Shape, Stroke, StrokeKind, Type,
};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::shape_renderer::ShapeRenderer;
use super::text;
use super::{get_dest_rect, get_source_rect};
use super::text_shaping::TextShapingCtx;
use super::{FontStore, ImageProvider};

use crate::render::svg::{emit_linked_image_fill, emit_linked_image_stroke, SvgLayerCanvas};

// ---------------------------------------------------------------------------
// ExportState: GPU-free view of the renderer state for vector export
// ---------------------------------------------------------------------------

/// The subset of renderer state the vector export path (SVG/PDF) actually
/// touches. It decouples the exporters from [`super::RenderState`], whose
/// construction requires a live GPU context, so they can run on a plain CPU
/// Skia canvas.
///
/// In production it is built from a `RenderState` (see `render_to_svg` /
/// `render_to_pdf`); in tests it can be built from a standalone [`FontStore`].
/// Images are provided through the [`ImageProvider`] trait so image draws are
/// optional: production plugs in the GPU-backed `ImageStore`, headless tests can
/// inject a CPU-only fake, and passing `None` skips image draws entirely.
pub(crate) struct ExportState<'a> {
    pub fonts: &'a FontStore,
    pub images: Option<&'a mut (dyn ImageProvider + 'a)>,
    pub sampling_options: skia::SamplingOptions,
}

impl<'a> ExportState<'a> {
    pub fn text_ctx(&self, browser: crate::utils::Browser) -> TextShapingCtx<'a> {
        TextShapingCtx::new(self.fonts, browser)
    }
}

// ---------------------------------------------------------------------------
// VectorTarget: vector export backend selector
// ---------------------------------------------------------------------------

/// Vector export backend selector.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum VectorTarget {
    Pdf,
    Svg,
}

// ---------------------------------------------------------------------------
// VectorCanvas: Skia canvas or SVG layer builder (never both at once)
// ---------------------------------------------------------------------------

/// Where vector drawing happens. [`VectorCanvas::SvgLayer`] owns the fragment
/// canvas inside [`SvgLayerCanvas`]; linked-image export uses the builder
/// directly without also holding a separate canvas borrow.
pub(crate) enum VectorCanvas<'a> {
    Borrowed(&'a Canvas),
    SvgLayer(&'a mut SvgLayerCanvas),
}

impl<'a> VectorCanvas<'a> {
    pub fn skia_canvas(&mut self) -> &Canvas {
        match self {
            Self::Borrowed(c) => c,
            Self::SvgLayer(b) => b.canvas(),
        }
    }

    fn svg_layer_mut(&mut self) -> Option<&mut SvgLayerCanvas> {
        match self {
            Self::Borrowed(_) => None,
            Self::SvgLayer(b) => Some(b),
        }
    }
}

// ---------------------------------------------------------------------------
// VectorRenderer: ShapeRenderer for canvas-based vector export
// ---------------------------------------------------------------------------

/// Canvas-based vector render backend (CPU Skia canvas, no GPU surfaces).
pub(super) struct VectorRenderer<'a, 'b> {
    canvas: VectorCanvas<'a>,
    shared: &'a mut ExportState<'b>,
    scale: f32,
    target: VectorTarget,
}

impl<'a, 'b> VectorRenderer<'a, 'b> {
    pub fn new(
        canvas: &'a Canvas,
        shared: &'a mut ExportState<'b>,
        scale: f32,
        target: VectorTarget,
    ) -> Self {
        Self {
            canvas: VectorCanvas::Borrowed(canvas),
            shared,
            scale,
            target,
        }
    }

    pub fn new_svg_layer(
        builder: &'a mut SvgLayerCanvas,
        shared: &'a mut ExportState<'b>,
        scale: f32,
        target: VectorTarget,
    ) -> Self {
        Self {
            canvas: VectorCanvas::SvgLayer(builder),
            shared,
            scale,
            target,
        }
    }

    pub fn skia_canvas(&mut self) -> &Canvas {
        self.canvas.skia_canvas()
    }

    fn text_ctx(&self) -> TextShapingCtx<'b> {
        self.shared.text_ctx(crate::utils::Browser::Chrome)
    }

    fn is_svg(&self) -> bool {
        matches!(self.target, VectorTarget::Svg)
    }

    /// Whether the SVG compositor re-emits this stroke (the shared renderer skips
    /// it because `SkSVGDevice` drops the `save_layer` composition).
    fn defers_stroke_for_svg(shape: &Shape, stroke: &Stroke) -> bool {
        if matches!(shape.shape_type, Type::Path(_) | Type::Bool(_))
            && !shape.is_open()
            && stroke.render_kind(false) == StrokeKind::Outer
        {
            return true;
        }
        if matches!(shape.shape_type, Type::Rect(_) | Type::Circle) && stroke.clip_op().is_some() {
            return true;
        }
        // Solid/center image strokes only; dotted image strokes still need the
        // dropped `save_layer` silhouette before the compositor can mask them.
        matches!(stroke.fill, Fill::Image(_)) && stroke.clip_op().is_none()
    }

    /// Whether a blur `image_filter` may be attached to a paint. On the SVG
    /// compositor, layer blur is emitted as a `<g filter="feGaussianBlur">`
    /// wrapping the shape, and `SkSVGDevice` both ignores paint image filters
    /// and drops the implicit layer they trigger (the shape would vanish), so we
    /// must not set one there.
    fn paint_image_filter(&self, shape: &Shape) -> Option<skia::ImageFilter> {
        if self.is_svg() {
            None
        } else {
            shape.image_filter(1.)
        }
    }

    /// Renders a single text stroke fully opaque (no opacity layer, no
    /// inner-stroke masking), so the SVG compositor can wrap it in a native
    /// `<g opacity>` and/or `<g clip-path>`. Skia's SVG backend drops the
    /// `save_layer`s that the text opacity layer and inner-stroke mask would
    /// emit, so those effects are reproduced by the wrapping groups instead.
    /// For inner strokes the caller must supply the glyph-silhouette clip
    /// (see [`draw_text_glyph_silhouette`]); here the double-width stroke is
    /// drawn unclipped and the `<clipPath>` keeps only its inner half.
    pub(super) fn draw_text_stroke_opaque(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()> {
        let Type::Text(text_content) = &shape.shape_type else {
            return Ok(());
        };

        let text_content = text_content.new_bounds(shape.selrect());
        let blur_filter = self.paint_image_filter(shape);
        let stroke_blur_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);

        // The builders come out opaque (the opacity is meant to live in the
        // layer), so rendering with `layer_opacity = None` yields the fully
        // opaque stroke that the `<g opacity>` then fades. The exterior clip is
        // done by the compositor's `<mask>`.
        let text_ctx = self.text_ctx();
        let (mut stroke_paragraphs, _) = text::stroke_paragraph_builder_group_from_text(
            &text_content,
            &text_ctx,
            stroke,
            &shape.selrect(),
            None,
        );
        text::render_with_bounds_outset_overlay_emoji(
            self.skia_canvas(),
            shape,
            &mut stroke_paragraphs,
            None,
            blur_filter.as_ref(),
            stroke_blur_outset,
            None,
            None,
            &text_ctx,
        )?;
        Ok(())
    }

    /// Draws a stroke's opaque black silhouette (its geometry only, no fill),
    /// used by the SVG compositor as the source of a `<mask>` that confines an
    /// image-filled stroke to the stroke region (the vector equivalent of the
    /// `save_layer` + `SrcIn` composition used on the GPU/PDF backends).
    pub(super) fn draw_stroke_silhouette(
        &mut self,
        shape: &Shape,
        stroke: &Stroke,
        inner_clip: bool,
    ) -> Result<()> {
        let scale = self.scale;
        draw_stroke_geometry(self.skia_canvas(), scale, shape, stroke, true, inner_clip);
        Ok(())
    }

    /// Draws an image-filled stroke's texture over its destination rect, with no
    /// clipping/isolation of its own. The SVG compositor wraps this in a
    /// `<g mask>` (see [`draw_stroke_silhouette`]) so the image only shows in the
    /// stroke region; drawing it here without a `save_layer` keeps it out of the
    /// layer `SkSVGDevice` would drop.
    pub(super) fn draw_stroke_image(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()> {
        let Fill::Image(image_fill) = &stroke.fill else {
            return Ok(());
        };
        let is_svg = self.is_svg();
        let scale = self.scale;
        draw_image_stroke(
            &mut self.canvas,
            self.shared,
            scale,
            shape,
            stroke,
            image_fill,
            is_svg,
        )
    }

    /// Renders the opaque glyph silhouette (the text fill at full alpha), used
    /// by the SVG compositor as the geometry of a `<clipPath>` that clips an
    /// inner stroke to the glyph interior (the vector equivalent of the
    /// mask + `SrcIn` composition used on the GPU/PDF backends).
    pub(super) fn draw_text_glyph_silhouette(&mut self, shape: &Shape) -> Result<()> {
        let Type::Text(text_content) = &shape.shape_type else {
            return Ok(());
        };

        let text_content = text_content.new_bounds(shape.selrect());
        let text_ctx = self.text_ctx();
        let mut mask_builders = text_content.paragraph_builder_group_opaque(&text_ctx);
        text::render_overlay_emoji(
            self.skia_canvas(),
            shape,
            &mut mask_builders,
            None,
            None,
            None,
            None,
            &text_ctx,
        )?;
        Ok(())
    }
}

impl ShapeRenderer for VectorRenderer<'_, '_> {
    fn draw_fills(&mut self, shape: &Shape, fills: &[Fill]) -> Result<()> {
        if fills.is_empty() {
            return Ok(());
        }

        // Handle image fills individually
        let has_image_fills = fills.iter().any(|f| matches!(f, Fill::Image(_)));
        if has_image_fills {
            let is_svg = self.is_svg();
            for fill in fills.iter().rev() {
                match fill {
                    Fill::Image(image_fill) => {
                        draw_image_fill(
                            self.shared,
                            &mut self.canvas,
                            shape,
                            image_fill,
                            is_svg,
                        )?;
                    }
                    _ => {
                        let mut paint = fill.to_paint(&shape.selrect, true);
                        if let Some(filter) = self.paint_image_filter(shape) {
                            paint.set_image_filter(filter);
                        }
                        draw_shape_geometry(self.skia_canvas(), shape, &paint);
                    }
                }
            }
            return Ok(());
        }

        let mut paint = merge_fills(fills, shape.selrect);
        paint.set_anti_alias(true);

        if let Some(filter) = self.paint_image_filter(shape) {
            paint.set_image_filter(filter);
        }

        draw_shape_geometry(self.skia_canvas(), shape, &paint);
        Ok(())
    }

    fn draw_strokes(&mut self, shape: &Shape, strokes: &[&Stroke]) -> Result<()> {
        let is_svg = self.is_svg();

        for stroke in strokes.iter().rev() {
            // SkSVGDevice drops the save_layer composition; the SVG compositor
            // re-emits these strokes in `render::svg::{strokes,text}`.
            if is_svg && Self::defers_stroke_for_svg(shape, stroke) {
                continue;
            }
            draw_single_stroke(
                &mut self.canvas,
                self.shared,
                self.scale,
                shape,
                stroke,
                is_svg,
            )?;
        }
        Ok(())
    }

    fn draw_drop_shadows(&mut self, shape: &Shape) -> Result<()> {
        for shadow in shape.drop_shadows_visible() {
            if let Some(filter) = shadow.get_drop_shadow_filter() {
                let mut paint = Paint::default();
                paint.set_image_filter(filter);
                let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                self.skia_canvas().save_layer(&layer_rec);
                let mut fill_paint = Paint::default();
                fill_paint.set_anti_alias(true);
                fill_paint.set_color(skia::Color::BLACK);
                draw_shape_geometry(self.skia_canvas(), shape, &fill_paint);
                self.skia_canvas().restore();
            }
        }
        Ok(())
    }

    fn draw_fill_inner_shadows(&mut self, shape: &Shape) -> Result<()> {
        // On SVG the inner-shadow composition lives in a `save_layer` (+ image
        // filter) that `SkSVGDevice` drops, so it is re-emitted natively by the
        // compositor (`svg::shadows::render_inner_shadows`) as a `<g filter>`.
        if self.is_svg() {
            return Ok(());
        }
        if !shape.has_fills() {
            return Ok(());
        }
        for shadow in shape.inner_shadows_visible() {
            let paint = shadow.get_inner_shadow_paint(true, shape.image_filter(1.).as_ref());
            self.skia_canvas()
                .save_layer(&skia::canvas::SaveLayerRec::default().paint(&paint));
            let mut fill_paint = Paint::default();
            fill_paint.set_anti_alias(true);
            fill_paint.set_color(skia::Color::BLACK);
            draw_shape_geometry(self.skia_canvas(), shape, &fill_paint);
            self.skia_canvas().restore();
        }
        Ok(())
    }

    fn draw_stroke_inner_shadows(&mut self, shape: &Shape, stroke: &Stroke) -> Result<()> {
        // SVG: the image-filter draw is wrapped in an implicit layer dropped by
        // `SkSVGDevice`. Re-emitted by the compositor (`svg::shadows::render_inner_shadows`).
        if self.is_svg() {
            return Ok(());
        }
        let is_open = shape.is_open();
        for shadow in shape.inner_shadows_visible() {
            if let Some(filter) = shadow.get_inner_shadow_filter() {
                let mut paint = stroke.to_stroked_paint(
                    is_open,
                    &shape.selrect,
                    shape.svg_attrs.as_ref(),
                    true,
                );
                paint.set_image_filter(filter);
                draw_shape_geometry(self.skia_canvas(), shape, &paint);
            }
        }
        Ok(())
    }

    fn draw_text(&mut self, shape: &Shape) -> Result<()> {
        let Type::Text(text_content) = &shape.shape_type else {
            return Ok(());
        };

        let text_content = text_content.new_bounds(shape.selrect());
        let text_ctx = self.text_ctx();
        let mut paragraph_builders = text_content.paragraph_builder_group_from_text(&text_ctx, None);
        // On the SVG compositor, layer blur is emitted as a `<g filter>` wrapping
        // the whole leaf, so text must not also carry its own blur (which would
        // double-blur and, worse, be dropped by `SkSVGDevice`).
        let blur_filter = self.paint_image_filter(shape);
        let is_svg = self.is_svg();
        let canvas = self.skia_canvas();

        // Text drop shadows: one filter layer per shadow over fill + stroke
        // silhouettes (mirrors GPU `render_text_shadows`).
        let drop_shadows = shape.drop_shadow_paints();
        if !drop_shadows.is_empty() {
            let shadow_stroke_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);
            let mut shadow_paragraphs =
                text_content.paragraph_builder_group_from_text(&text_ctx, Some(true));
            let mut stroke_shadow_groups: Vec<(StrokeKind, _)> = shape
                .visible_strokes()
                .rev()
                .map(|stroke| {
                    (
                        stroke.render_kind(false),
                        text::stroke_paragraph_builder_group_from_text(
                            &text_content,
                            &text_ctx,
                            stroke,
                            &shape.selrect(),
                            Some(true),
                        )
                        .0,
                    )
                })
                .collect();

            for shadow_paint in &drop_shadows {
                canvas.save_layer(&skia::canvas::SaveLayerRec::default().paint(shadow_paint));

                text::render_overlay_emoji(
                    canvas,
                    shape,
                    &mut shadow_paragraphs,
                    None,
                    blur_filter.as_ref(),
                    None,
                    None,
                    &text_ctx,
                )?;

                for (kind, stroke_paragraphs) in &mut stroke_shadow_groups {
                    if *kind == StrokeKind::Inner {
                        // Inner stroke masked by the glyph fill (outset 0 here).
                        let mut fill_builders =
                            text_content.paragraph_builder_group_from_text(&text_ctx, Some(true));
                        text::render_inner_stroke(
                            None,
                            Some(canvas),
                            shape,
                            stroke_paragraphs,
                            &mut fill_builders,
                            None,
                            blur_filter.as_ref(),
                            0.0,
                            None,
                            &text_ctx,
                        )?;
                    } else if *kind == StrokeKind::Outer {
                        text::render_outer_stroke(
                            None,
                            Some(canvas),
                            shape,
                            stroke_paragraphs,
                            None,
                            blur_filter.as_ref(),
                            0.0,
                            None,
                            &text_ctx,
                        )?;
                    } else {
                        text::render_with_bounds_outset_overlay_emoji(
                            canvas,
                            shape,
                            stroke_paragraphs,
                            None,
                            blur_filter.as_ref(),
                            shadow_stroke_outset,
                            None,
                            None,
                            &text_ctx,
                        )?;
                    }
                }

                canvas.restore();
            }
        }

        text::render_overlay_emoji(
            canvas,
            shape,
            &mut paragraph_builders,
            None,
            blur_filter.as_ref(),
            None,
            None,
            &text_ctx,
        )?;

        // Strokes for text
        let stroke_blur_outset = Stroke::max_bounds_width(shape.visible_strokes(), false);

        for stroke in shape.visible_strokes().rev() {
            let (mut stroke_paragraphs, layer_opacity) =
                text::stroke_paragraph_builder_group_from_text(
                    &text_content,
                    &text_ctx,
                    stroke,
                    &shape.selrect(),
                    None,
                );
            if stroke.render_kind(false) == StrokeKind::Inner {
                // Inner text stroke: clip to the glyph fill, else it bleeds out.
                let mut fill_builders =
                    text_content.paragraph_builder_group_from_text(&text_ctx, None);
                text::render_inner_stroke(
                    None,
                    Some(canvas),
                    shape,
                    &mut stroke_paragraphs,
                    &mut fill_builders,
                    None,
                    blur_filter.as_ref(),
                    stroke_blur_outset,
                    layer_opacity,
                    &text_ctx,
                )?;
            } else if is_svg
                && (layer_opacity.is_some()
                    || stroke.render_kind(false) == StrokeKind::Outer)
            {
                // Re-emitted by the SVG compositor (their composition lives in a
                // `save_layer` that `SkSVGDevice` drops):
                // - semi-transparent strokes: the opacity layer (would vanish);
                // - outer strokes: the double-width stroke must be masked to the
                //   glyph exterior. Drawn inline it would paint at full double
                //   width (twice the intended outer width, bleeding over the
                //   fill), so the compositor masks it to keep only the outer half.
                continue;
            } else if stroke.render_kind(false) == StrokeKind::Outer {
                text::render_outer_stroke(
                    None,
                    Some(canvas),
                    shape,
                    &mut stroke_paragraphs,
                    None,
                    blur_filter.as_ref(),
                    stroke_blur_outset,
                    layer_opacity,
                    &text_ctx,
                )?;
            } else {
                text::render_with_bounds_outset_overlay_emoji(
                    canvas,
                    shape,
                    &mut stroke_paragraphs,
                    None,
                    blur_filter.as_ref(),
                    stroke_blur_outset,
                    None,
                    layer_opacity,
                    &text_ctx,
                )?;
            }
        }

        // Inner shadows for text. On SVG the shadow paint's image filter forces
        // an implicit layer that `SkSVGDevice` drops, so the compositor re-emits
        // them natively over the glyph silhouette (`svg::shadows::render_inner_shadows`).
        let inner_shadows: Vec<_> = if is_svg {
            Vec::new()
        } else {
            shape.inner_shadows_visible().collect()
        };
        if !inner_shadows.is_empty() {
            let mut shadow_paragraphs =
                text_content.paragraph_builder_group_from_text(&text_ctx, Some(true));
            for shadow in &inner_shadows {
                let shadow_paint = shadow.get_inner_shadow_paint(true, blur_filter.as_ref());
                text::render_overlay_emoji(
                    canvas,
                    shape,
                    &mut shadow_paragraphs,
                    Some(&shadow_paint),
                    blur_filter.as_ref(),
                    None,
                    None,
                    &text_ctx,
                )?;
            }
        }

        Ok(())
    }

    fn draw_svg(&mut self, shape: &Shape) -> Result<()> {
        let Type::SVGRaw(sr) = &shape.shape_type else {
            return Ok(());
        };

        let font_provider = self.shared.fonts.font_provider().clone();
        let canvas = self.skia_canvas();

        if let Some(svg_transform) = shape.svg_transform() {
            canvas.concat(&svg_transform);
        }
        if let Some(svg) = shape.svg.as_ref() {
            svg.render(canvas);
        } else {
            let font_manager = skia::FontMgr::from(font_provider);
            if let Ok(dom) = skia::svg::Dom::from_str(&sr.content, font_manager) {
                dom.render(canvas);
            }
        }

        Ok(())
    }

    fn apply_blur_layer(&mut self, shape: &Shape) -> bool {
        let blur = match shape.blur {
            Some(b) if !b.hidden && b.blur_type == BlurType::LayerBlur && b.value > 0.0 => b,
            _ => return false,
        };

        let sigma = radius_to_sigma(blur.value * self.scale);
        if let Some(filter) = skia::image_filters::blur((sigma, sigma), None, None, None) {
            let mut paint = Paint::default();
            paint.set_image_filter(filter);
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            self.skia_canvas().save_layer(&layer_rec);
            true
        } else {
            false
        }
    }

    fn restore_blur_layer(&mut self) {
        self.skia_canvas().restore();
    }
}

// ---------------------------------------------------------------------------
// Child paint order
// ---------------------------------------------------------------------------

/// Children of `element` in the exact paint order (bottom-to-top) the GPU
/// renderer uses, so the vector export matches on-canvas z-order.
///
/// The GPU pushes `sort_z_index(children_ids_iter(false))` onto a LIFO stack and
/// therefore paints nodes in the reverse of that list. Crucially, `sort_z_index`
/// reorders children by their layout `z-index` for layout containers (flex/grid),
/// which is what places absolutely-positioned items (e.g. a background gradient
/// with a lower z-index) behind the flow content. Iterating children in plain
/// stored order ignored this and drew such items on top. We replicate the GPU
/// ordering here.
pub(super) fn children_paint_order(tree: ShapesPoolRef, element: &Shape) -> Vec<Uuid> {
    let ids: Vec<Uuid> = element.children_ids_iter(false).copied().collect();
    let mut ids = super::sort_z_index(tree, element, ids);
    ids.reverse();
    ids
}

/// Single source of truth for leaf content draw order/gating (fills, inner
/// shadows, strokes), generic over [`ShapeRenderer`]. Drop shadows and layer
/// blur are excluded; they wrap the content and are sequenced per backend.
pub(super) fn render_leaf_content<R: ShapeRenderer + ?Sized>(
    renderer: &mut R,
    shape: &Shape,
) -> Result<()> {
    match &shape.shape_type {
        Type::Text(_) => renderer.draw_text(shape)?,
        Type::SVGRaw(_) => renderer.draw_svg(shape)?,
        // Group/Frame never reach here; listed so a new Type must be handled.
        Type::Rect(_)
        | Type::Circle
        | Type::Path(_)
        | Type::Bool(_)
        | Type::Group(_)
        | Type::Frame(_) => {
            renderer.draw_fills(shape, &shape.fills)?;
            renderer.draw_fill_inner_shadows(shape)?;

            let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
            if !visible_strokes.is_empty() {
                renderer.draw_strokes(shape, &visible_strokes)?;

                // Stroke inner shadows only when there are no fills (matches GPU).
                if !shape.has_fills() {
                    for stroke in &visible_strokes {
                        renderer.draw_stroke_inner_shadows(shape, stroke)?;
                    }
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Private helpers (canvas-only)
// ---------------------------------------------------------------------------

fn draw_image_fill(
    shared: &mut ExportState,
    canvas: &mut VectorCanvas,
    shape: &Shape,
    image_fill: &crate::shapes::ImageFill,
    is_svg: bool,
) -> Result<()> {
    let sampling_options = shared.sampling_options;

    if is_svg {
        let Some(builder) = canvas.svg_layer_mut() else {
            return Ok(());
        };
        let Some(url) = shared
            .images
            .as_ref()
            .and_then(|images| images.source_url(&image_fill.id()))
        else {
            return Ok(());
        };
        return emit_linked_image_fill(builder, shape, image_fill, url, shape.selrect);
    }

    let Some(images) = shared.images.as_deref_mut() else {
        return Ok(());
    };

    let Some(image) = images.get_cpu_image(&image_fill.id()) else {
        return Ok(());
    };

    let size = image.dimensions();
    let container = &shape.selrect;

    let src_rect = get_source_rect(size, container, image_fill);
    let dest_rect = container;

    let skia = canvas.skia_canvas();
    skia.save();

    // Clip to shape
    clip_to_shape(skia, shape, true);

    let mut paint = Paint::default();
    paint.set_anti_alias(true);
    if !is_svg {
        if let Some(filter) = shape.image_filter(1.) {
            paint.set_image_filter(filter);
        }
    }

    skia.draw_image_rect_with_sampling_options(
        &image,
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        sampling_options,
        &paint,
    );

    skia.restore();
    Ok(())
}

fn draw_single_stroke(
    canvas: &mut VectorCanvas,
    shared: &mut ExportState,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
    is_svg: bool,
) -> Result<()> {
    // Image-fill strokes: the stroke masks the visible area of the image.
    if let Fill::Image(image_fill) = &stroke.fill {
        return draw_image_stroke(
            canvas,
            shared,
            scale,
            shape,
            stroke,
            image_fill,
            is_svg,
        );
    }

    draw_stroke_geometry(canvas.skia_canvas(), scale, shape, stroke, false, true);
    Ok(())
}

/// Draws a stroke's geometry by shape type, kind and dash style. Rect/Circle
/// reuse the GPU stroke fns (dash/alignment parity); Path/Bool use double-width
/// + clip/clear + caps. `opaque` forces black for an image-stroke silhouette.
/// `inner_clip` applies the shape-interior clip for inner strokes; callers
/// building SVG `<mask>` fragments for rotated paths should pass `false` and
/// supply a rotated `<clipPath>` instead (`SkSVGDevice` drops the canvas CTM
/// from canvas clips when serializing mask defs).
fn draw_stroke_geometry(
    canvas: &Canvas,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
    opaque: bool,
    inner_clip: bool,
) {
    let svg_attrs = shape.svg_attrs.as_ref();
    let is_open = shape.is_open();

    match &shape.shape_type {
        shape_type @ (Type::Rect(_) | Type::Frame(_)) => {
            let corners = shape_type.corners();
            let mut paint = stroke.to_paint(&shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            super::strokes::draw_stroke_on_rect(
                canvas,
                stroke,
                &shape.selrect,
                &corners,
                &paint,
                scale,
                None,
                None,
                true,
            );
        }
        Type::Circle => {
            let mut paint = stroke.to_paint(&shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            super::strokes::draw_stroke_on_circle(
                canvas,
                stroke,
                &shape.selrect,
                &paint,
                scale,
                None,
                None,
                true,
            );
        }
        Type::Path(_) | Type::Bool(_) => {
            let mut paint = stroke.to_stroked_paint(is_open, &shape.selrect, svg_attrs, true);
            if opaque {
                paint.set_shader(None);
                paint.set_color(skia::Color::BLACK);
            }
            draw_stroke_kind_aware(canvas, shape, stroke, &paint, inner_clip);

            if is_open {
                if let Some(cap_path) = transformed_skia_path(shape) {
                    super::strokes::handle_stroke_caps(
                        &cap_path, stroke, canvas, is_open, &paint, None, true,
                    );
                }
            }
        }
        // Text strokes go through draw_text; groups/svg never carry strokes.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {}
    }
}

/// Draws a stroked `paint` honoring the stroke kind (inner clip / outer
/// layer+clear / center).
fn draw_stroke_kind_aware(
    canvas: &Canvas,
    shape: &Shape,
    stroke: &Stroke,
    paint: &Paint,
    inner_clip: bool,
) {
    match stroke.render_kind(shape.is_open()) {
        StrokeKind::Inner => {
            if inner_clip {
                canvas.save();
                clip_to_shape(canvas, shape, true);
                draw_shape_geometry(canvas, shape, paint);
                canvas.restore();
            } else {
                draw_shape_geometry(canvas, shape, paint);
            }
        }
        StrokeKind::Outer => {
            canvas.save();
            canvas.save_layer(&skia::canvas::SaveLayerRec::default());
            draw_shape_geometry(canvas, shape, paint);
            let mut clear_paint = Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            clear_paint.set_style(skia::PaintStyle::Fill);
            draw_shape_geometry(canvas, shape, &clear_paint);
            canvas.restore(); // layer
            canvas.restore();
        }
        StrokeKind::Center => {
            draw_shape_geometry(canvas, shape, paint);
        }
    }
}

/// Image-filled stroke: draw the stroke silhouette in a layer, then paint the
/// CPU image over it with `SrcIn` so only the stroke area shows the image.
fn draw_image_stroke(
    canvas: &mut VectorCanvas,
    shared: &mut ExportState,
    scale: f32,
    shape: &Shape,
    stroke: &Stroke,
    image_fill: &crate::shapes::ImageFill,
    is_svg: bool,
) -> Result<()> {
    let sampling_options = shared.sampling_options;

    let container = shape.selrect;
    let dest_rect = get_dest_rect(&container, stroke.delta());

    if is_svg {
        let Some(builder) = canvas.svg_layer_mut() else {
            return Ok(());
        };
        let Some(url) = shared
            .images
            .as_ref()
            .and_then(|images| images.source_url(&image_fill.id()))
        else {
            return Ok(());
        };
        return emit_linked_image_stroke(builder, shape, image_fill, url, dest_rect);
    }

    let Some(images) = shared.images.as_deref_mut() else {
        return Ok(());
    };

    let Some(image) = images.get_cpu_image(&image_fill.id()) else {
        return Ok(());
    };
    let size = image.dimensions();

    let skia = canvas.skia_canvas();
    skia.save();
    skia.save_layer(&skia::canvas::SaveLayerRec::default());

    // Opaque stroke silhouette; the SrcIn image draw below fills it.
    draw_stroke_geometry(skia, scale, shape, stroke, true, true);

    let mut image_paint = Paint::default();
    image_paint.set_blend_mode(skia::BlendMode::SrcIn);
    image_paint.set_anti_alias(true);
    if let Some(filter) = shape.image_filter(1.) {
        image_paint.set_image_filter(filter);
    }

    let src_rect = get_source_rect(size, &container, image_fill);
    let dest_rect = get_dest_rect(&container, stroke.delta());
    skia.draw_image_rect_with_sampling_options(
        &image,
        Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
        dest_rect,
        sampling_options,
        &image_paint,
    );

    skia.restore(); // layer
    skia.restore();
    Ok(())
}

fn transformed_skia_path(shape: &Shape) -> Option<skia::Path> {
    if !matches!(shape.shape_type, Type::Path(_) | Type::Bool(_)) {
        return None;
    }
    shape.get_skia_path()
}

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

/// Draws the shape's geometry (rect/rrect/oval/path) with the given paint.
pub(super) fn draw_shape_geometry(canvas: &Canvas, shape: &Shape, paint: &Paint) {
    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            if let Some(corners) = shape.shape_type.corners() {
                let rrect = RRect::new_rect_radii(shape.selrect, &corners);
                canvas.draw_rrect(rrect, paint);
            } else {
                canvas.draw_rect(shape.selrect, paint);
            }
        }
        Type::Circle => {
            canvas.draw_oval(shape.selrect, paint);
        }
        Type::Path(_) | Type::Bool(_) => {
            if let Some(path) = shape.get_skia_path() {
                canvas.draw_path(&path, paint);
            }
        }
        // Not plain geometry (drawn via draw_text / draw_svg / traversal).
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {}
    }
}

/// Clips the canvas to a frame's content bounds, outset by ~0.5 device px so
/// the hard (non-AA) clip edge doesn't shave off edge pixels and leave a seam.
pub(super) fn clip_to_frame_content(canvas: &Canvas, shape: &Shape, scale: f32) {
    let outset = 0.5 / scale.max(1e-6);
    let mut rect = shape.selrect;
    rect.outset((outset, outset));
    match shape.shape_type.corners() {
        Some(corners) => {
            let rrect = RRect::new_rect_radii(rect, &corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, false);
        }
        None => {
            canvas.clip_rect(rect, skia::ClipOp::Intersect, false);
        }
    }
}

/// Clips the canvas to the shape's geometry.
fn clip_to_shape(canvas: &Canvas, shape: &Shape, antialias: bool) {
    let container = &shape.selrect;
    match &shape.shape_type {
        Type::Rect(Rect {
            corners: Some(corners),
        })
        | Type::Frame(Frame {
            corners: Some(corners),
            ..
        }) => {
            let rrect = RRect::new_rect_radii(*container, corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
        }
        Type::Rect(_) | Type::Frame(_) => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
        Type::Circle => {
            let mut pb = skia::PathBuilder::new();
            pb.add_oval(*container, None, None);
            canvas.clip_path(&pb.detach(), skia::ClipOp::Intersect, antialias);
        }
        Type::Path(_) | Type::Bool(_) => {
            if let Some(path) = shape.get_skia_path() {
                canvas.clip_path(&path, skia::ClipOp::Intersect, antialias);
            }
        }
        // Fallback to the bounding rect.
        Type::Text(_) | Type::SVGRaw(_) | Type::Group(_) => {
            canvas.clip_rect(*container, skia::ClipOp::Intersect, antialias);
        }
    }
}

