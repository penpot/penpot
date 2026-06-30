//! Ruler overlay rendered on `SurfaceId::UI`.
//!
//! Mirrors the SVG implementation in
//! `frontend/src/app/main/ui/workspace/viewport/rulers.cljs`. Coordinates are
//! in document space; the caller has already applied the world-space
//! transform (`scale(zoom*dpr) + translate(-vbox.left,-vbox.top)`), so all
//! sizes that should look constant on screen are multiplied by
//! `zoom_inverse = 1.0 / zoom`.

use skia_safe::{self as skia, Color, Font, Paint, PaintStyle, PathFillType, Point, RRect, Rect};

use super::fonts::FontStore;
use crate::state::RulerState;
use crate::view::Viewbox;

pub const RULER_AREA_SIZE: f32 = 22.0;
const RULER_TICK_OFFSET: f32 = 15.0;
const RULER_TICK_LEN: f32 = 4.0;
const RULER_TICK_GAP: f32 = 2.0;
const FONT_SIZE: f32 = 12.0;
const SELECTION_FILL_OPACITY: f32 = 0.3;
const CANVAS_BORDER_RADIUS: f32 = 12.0;

// Baseline of selection labels inside the 22-px bar. Empirical value from
// the SVG (`rulers.cljs`): the only place this number is justified is "it
// looks right for a 12-px font in a 22-px bar". Different from the regular
// tick-label baseline (`RULER_TICK_OFFSET - 1.0 = 14.0`); the SVG uses
// distinct offsets for the two and we mirror that.
const SELECTION_LABEL_BASELINE: f32 = 13.6;

// Selection-label gradient mask: fades the tick labels behind the selection
// band. The mask is `OVER_NUMBER_SIZE` screen pixels long. `OVER_NUMBER_PERCENT`
// is the fraction of the mask that sits *outside* the band; at 1.0 the mask's
// inner edge lands exactly on the band edge, so the dark shadow begins where
// the green selection area ends and fades outward (it never bleeds under the
// band). `GRADIENT_FADE_FRACTION` is the share of the mask spent on the
// transparent→opaque ramp at the outer end.
const OVER_NUMBER_SIZE: f32 = 100.0;
const OVER_NUMBER_PERCENT: f32 = 1.0;
const GRADIENT_FADE_FRACTION: f32 = 0.4;
const OVER_NUMBER_OPACITY: f32 = 0.8;

fn calculate_step_size(zoom: f32) -> f32 {
    if zoom <= 0.0 {
        return 1.0;
    }
    if zoom < 0.008 {
        10000.0
    } else if zoom < 0.015 {
        5000.0
    } else if zoom < 0.04 {
        2500.0
    } else if zoom < 0.07 {
        1000.0
    } else if zoom < 0.2 {
        500.0
    } else if zoom < 0.5 {
        250.0
    } else if zoom < 1.0 {
        100.0
    } else if zoom <= 2.0 {
        50.0
    } else if zoom < 4.0 {
        25.0
    } else if zoom < 6.0 {
        10.0
    } else if zoom < 15.0 {
        5.0
    } else if zoom < 25.0 {
        2.0
    } else {
        1.0
    }
}

fn format_label(value: f32) -> String {
    // Match `format-number` in app.main.ui.formats: round to integer if whole,
    // else 2 decimals. Tick steps are integers in our table, so this is the
    // common path.
    let rounded = value.round();
    if (value - rounded).abs() < 1e-3 {
        format!("{}", rounded as i64)
    } else {
        format!("{:.2}", value)
    }
}

fn with_alpha(color: Color, alpha_fraction: f32) -> Color {
    let a = (alpha_fraction.clamp(0.0, 1.0) * 255.0) as u8;
    Color::from_argb(a, color.r(), color.g(), color.b())
}

/// Per-frame draw context: the canvas, the ruler state, the (constant-size)
/// label font, the viewport top-left in document coords, and the cached
/// derived sizes `bar` and `zi`. Bundled so the helpers don't blow past
/// clippy's `too_many_arguments` threshold.
struct RenderCtx<'a> {
    canvas: &'a skia::Canvas,
    state: &'a RulerState,
    font: &'a Font,
    vx: f32,
    vy: f32,
    bar: f32,
    zi: f32,
}

pub fn render(canvas: &skia::Canvas, viewbox: Viewbox, fonts: &FontStore, state: &RulerState) {
    let zoom = viewbox.zoom;
    if zoom <= 0.0 {
        return;
    }
    let zi = 1.0 / zoom;
    let area = viewbox.area;
    let vw = area.width();
    let vh = area.height();

    // Keep the font at a constant rasterization size and apply the
    // inverse-scale (`zi`) at draw time. Pre-scaling the font size by `zi`
    // makes Skia rasterize at smaller and smaller sizes as we zoom in,
    // which rounds glyph advances to whole device pixels — the canvas then
    // scales the rounded gaps back up and the spacing looks too wide.
    // Subpixel positioning + a stable font size keeps spacing consistent.
    let mut font: Font = fonts.ui_font().clone();
    font.set_size(FONT_SIZE);
    font.set_subpixel(true);

    // When rulers are hidden we still draw the viewport frame (the rounded
    // canvas border) with bar=0, matching the SVG viewport-frame* behavior
    // which always renders the frame regardless of ruler visibility.
    let bar = if state.visible {
        RULER_AREA_SIZE * zi
    } else {
        0.0
    };

    let ctx = RenderCtx {
        canvas,
        state,
        font: &font,
        vx: area.left,
        vy: area.top,
        bar,
        zi,
    };

    if state.frame {
        draw_background(&ctx, vw, vh);
    }

    if state.visible {
        let step = calculate_step_size(zoom);
        if step > 0.0 && step.is_finite() {
            draw_ticks_x(&ctx, vw, step, state.offset_x);
            draw_ticks_y(&ctx, vh, step, state.offset_y);
        }

        if let Some(sel) = state.selection {
            draw_selection_x(&ctx, sel, state.offset_x);
            draw_selection_y(&ctx, sel, state.offset_y);
        }
    }
}

/// Draws the L-shaped ruler chrome with a rounded inner edge.
///
/// Mirrors the SVG `viewport-frame*` pattern:
/// 1. Stroke the inner rounded rect (this is the visible border between
///    the rulers and the canvas; bg fill covers the outer half later).
/// 2. Fill an `outer ∪ inner` path with even-odd, so only the L-shape
///    (outer minus inner) gets the bg color. The rounded corners of the
///    inner rect carve small bg-color fillets at the four corners of the
///    viewport, including the top-left intersection where the two bars
///    meet.
fn draw_background(ctx: &RenderCtx, vw: f32, vh: f32) {
    let radius = CANVAS_BORDER_RADIUS * ctx.zi;
    let inner_rect = Rect::from_ltrb(ctx.vx + ctx.bar, ctx.vy + ctx.bar, ctx.vx + vw, ctx.vy + vh);
    let inner_rrect = RRect::new_rect_xy(inner_rect, radius, radius);

    let mut border = Paint::default();
    border.set_anti_alias(true);
    border.set_style(PaintStyle::Stroke);
    border.set_stroke_width(4.0 * ctx.zi);
    border.set_color(ctx.state.border_color);
    ctx.canvas.draw_rrect(inner_rrect, &border);

    let outer_rect = Rect::from_xywh(ctx.vx, ctx.vy, vw, vh);
    let mut pb = skia::PathBuilder::new();
    pb.add_rect(outer_rect, None, None);
    pb.add_rrect(inner_rrect, None, None);
    let mut path = pb.detach();
    path.set_fill_type(PathFillType::EvenOdd);

    let mut bg = Paint::default();
    bg.set_anti_alias(true);
    bg.set_style(PaintStyle::Fill);
    bg.set_color(ctx.state.bg_color);
    ctx.canvas.draw_path(&path, &bg);
}

fn draw_ticks_x(ctx: &RenderCtx, vw: f32, step: f32, offset: f32) {
    let canvas = ctx.canvas;
    let zi = ctx.zi;

    canvas.save();
    // Clip out the corner so labels do not bleed under the Y bar.
    let clip = Rect::from_xywh(ctx.vx + ctx.bar, ctx.vy, (vw - ctx.bar).max(0.0), ctx.bar);
    canvas.clip_rect(clip, None, false);

    let mut paint = Paint::default();
    paint.set_color(ctx.state.label_color);
    paint.set_anti_alias(true);
    paint.set_stroke_width(zi);

    let start = ctx.vx;
    let end = ctx.vx + vw;
    let minv = (start.max(-100_000.0) / step).ceil() * step + (offset % step);
    let maxv = (end.min(100_000.0) / step).floor() * step + (offset % step);

    let tick_top = ctx.vy + (RULER_TICK_OFFSET + RULER_TICK_GAP) * zi;
    let tick_bottom = tick_top + RULER_TICK_LEN * zi;
    let text_y = ctx.vy + (RULER_TICK_OFFSET - 1.0) * zi;

    let mut v = minv;
    while v <= maxv {
        canvas.draw_line(Point::new(v, tick_top), Point::new(v, tick_bottom), &paint);
        let label = format_label(v - offset);
        let (w, _) = ctx.font.measure_str(&label, None);
        canvas.save();
        canvas.translate((v, text_y));
        canvas.scale((zi, zi));
        canvas.draw_str(&label, Point::new(-w / 2.0, 0.0), ctx.font, &paint);
        canvas.restore();
        v += step;
    }
    canvas.restore();
}

fn draw_ticks_y(ctx: &RenderCtx, vh: f32, step: f32, offset: f32) {
    let canvas = ctx.canvas;
    let zi = ctx.zi;

    canvas.save();
    let clip = Rect::from_xywh(ctx.vx, ctx.vy + ctx.bar, ctx.bar, (vh - ctx.bar).max(0.0));
    canvas.clip_rect(clip, None, false);

    let mut paint = Paint::default();
    paint.set_color(ctx.state.label_color);
    paint.set_anti_alias(true);
    paint.set_stroke_width(zi);

    let start = ctx.vy;
    let end = ctx.vy + vh;
    let minv = (start.max(-100_000.0) / step).ceil() * step + (offset % step);
    let maxv = (end.min(100_000.0) / step).floor() * step + (offset % step);

    let tick_left = ctx.vx + (RULER_TICK_OFFSET + RULER_TICK_GAP) * zi;
    let tick_right = tick_left + RULER_TICK_LEN * zi;
    let text_x = ctx.vx + (RULER_TICK_OFFSET - 1.0) * zi;

    let mut v = minv;
    while v <= maxv {
        canvas.draw_line(Point::new(tick_left, v), Point::new(tick_right, v), &paint);

        let label = format_label(v - offset);
        let (w, _) = ctx.font.measure_str(&label, None);
        // Rotate -90° around (text_x, v) so the label reads bottom-to-top
        // along the Y axis, matching the SVG `transform="rotate(-90 …)"`.
        // The scale(zi) brings the constant-size font down to 12 CSS px on
        // screen after the outer world-space transform.
        canvas.save();
        canvas.translate((text_x, v));
        canvas.rotate(-90.0, None);
        canvas.scale((zi, zi));
        canvas.draw_str(&label, Point::new(-w / 2.0, 0.0), ctx.font, &paint);
        canvas.restore();
        v += step;
    }
    canvas.restore();
}

fn draw_selection_x(ctx: &RenderCtx, sel: Rect, offset: f32) {
    let canvas = ctx.canvas;
    let zi = ctx.zi;

    // Render order matches the SVG: outer gradient masks first (so their
    // bg color paints over the regular tick labels behind), then the
    // semi-transparent band on top of the masked area, then the selection
    // labels on top of everything.
    let mask_w = OVER_NUMBER_SIZE * zi;
    let left_x = sel.left - OVER_NUMBER_SIZE * OVER_NUMBER_PERCENT * zi;
    draw_mask(
        ctx,
        Rect::from_xywh(left_x, ctx.vy, mask_w, ctx.bar),
        MaskAxis::Horizontal,
        false,
    );
    let right_x = sel.right - OVER_NUMBER_SIZE * (1.0 - OVER_NUMBER_PERCENT) * zi;
    draw_mask(
        ctx,
        Rect::from_xywh(right_x, ctx.vy, mask_w, ctx.bar),
        MaskAxis::Horizontal,
        true,
    );

    let mut fill = Paint::default();
    fill.set_anti_alias(false);
    fill.set_style(PaintStyle::Fill);
    fill.set_color(with_alpha(ctx.state.accent_color, SELECTION_FILL_OPACITY));
    canvas.draw_rect(
        Rect::from_xywh(sel.left, ctx.vy, sel.width(), ctx.bar),
        &fill,
    );

    let text_y = ctx.vy + SELECTION_LABEL_BASELINE * zi;
    // Both labels use the same half-bar gap from the band so the start (left)
    // and end (right) are spaced symmetrically.
    let gap = (RULER_AREA_SIZE / 2.0) * zi;
    let left_label = format_label(sel.left - offset);
    let right_label = format_label(sel.right - offset);
    let (lw_font, _) = ctx.font.measure_str(&left_label, None);
    let lx = sel.left - gap - lw_font * zi;
    let rx = sel.right + gap;

    let mut text_paint = Paint::default();
    text_paint.set_color(ctx.state.accent_color);
    text_paint.set_anti_alias(true);

    // 1. Left label
    canvas.save();
    canvas.translate((lx, text_y));
    canvas.scale((zi, zi));
    canvas.draw_str(&left_label, Point::new(0.0, 0.0), ctx.font, &text_paint);
    canvas.restore();

    // 2. Right label
    canvas.save();
    canvas.translate((rx, text_y));
    canvas.scale((zi, zi));
    canvas.draw_str(&right_label, Point::new(0.0, 0.0), ctx.font, &text_paint);
    canvas.restore();
}

enum MaskAxis {
    Horizontal,
    Vertical,
}

/// Fills `rect` with a `bg_color` gradient along `axis` that fades the tick
/// labels behind the selection band. `fade_to_end` flips it from
/// transparent→opaque (before the band) to opaque→transparent (after).
fn draw_mask(ctx: &RenderCtx, rect: Rect, axis: MaskAxis, fade_to_end: bool) {
    let opaque = with_alpha(ctx.state.bg_color, OVER_NUMBER_OPACITY);
    let transparent = with_alpha(ctx.state.bg_color, 0.0);
    let (colors, offsets): (&[skia::Color; 3], &[f32; 3]) = if fade_to_end {
        (
            &[opaque, opaque, transparent],
            &[0.0, 1.0 - GRADIENT_FADE_FRACTION, 1.0],
        )
    } else {
        (
            &[transparent, opaque, opaque],
            &[0.0, GRADIENT_FADE_FRACTION, 1.0],
        )
    };
    let end = match axis {
        MaskAxis::Horizontal => (rect.right, rect.top),
        MaskAxis::Vertical => (rect.left, rect.bottom),
    };
    let shader = skia::gradient_shader::linear(
        ((rect.left, rect.top), end),
        &colors[..],
        Some(&offsets[..]),
        skia::TileMode::Clamp,
        None,
        None,
    );
    let mut paint = Paint::default();
    paint.set_anti_alias(false);
    paint.set_style(PaintStyle::Fill);
    paint.set_shader(shader);
    ctx.canvas.draw_rect(rect, &paint);
}

fn draw_selection_y(ctx: &RenderCtx, sel: Rect, offset: f32) {
    let canvas = ctx.canvas;
    let zi = ctx.zi;

    let top_label = format_label(sel.top - offset);
    let bottom_label = format_label(sel.bottom - offset);
    // Both labels sit a half-bar gap from the band; only the bottom label's
    // origin depends on its own width (it reads toward the band).
    let (bw_font, _) = ctx.font.measure_str(&bottom_label, None);

    // Mask first (gradient bg over tick labels behind), then band, then
    // labels — same order as SVG.
    let mask_h = OVER_NUMBER_SIZE * zi;
    let top_y = sel.top - OVER_NUMBER_SIZE * OVER_NUMBER_PERCENT * zi;
    draw_mask(
        ctx,
        Rect::from_xywh(ctx.vx, top_y, ctx.bar, mask_h),
        MaskAxis::Vertical,
        false,
    );
    let bottom_y = sel.bottom - OVER_NUMBER_SIZE * (1.0 - OVER_NUMBER_PERCENT) * zi;
    draw_mask(
        ctx,
        Rect::from_xywh(ctx.vx, bottom_y, ctx.bar, mask_h),
        MaskAxis::Vertical,
        true,
    );

    let mut fill = Paint::default();
    fill.set_anti_alias(false);
    fill.set_style(PaintStyle::Fill);
    fill.set_color(with_alpha(ctx.state.accent_color, SELECTION_FILL_OPACITY));
    canvas.draw_rect(
        Rect::from_xywh(ctx.vx, sel.top, ctx.bar, sel.height()),
        &fill,
    );

    let text_x = ctx.vx + SELECTION_LABEL_BASELINE * zi;

    let mut text_paint = Paint::default();
    text_paint.set_color(ctx.state.accent_color);
    text_paint.set_anti_alias(true);
    // Both labels read bottom-to-top on screen
    // 1. Top label
    canvas.save();
    canvas.translate((text_x, sel.top));
    canvas.rotate(-90.0, None);
    canvas.scale((zi, zi));
    canvas.draw_str(
        &top_label,
        Point::new(RULER_AREA_SIZE / 2.0, 0.0),
        ctx.font,
        &text_paint,
    );
    canvas.restore();
    // 2. Bottom label
    canvas.save();
    canvas.translate((text_x, sel.bottom));
    canvas.rotate(-90.0, None);
    canvas.scale((zi, zi));
    canvas.draw_str(
        &bottom_label,
        Point::new(-bw_font - RULER_AREA_SIZE / 2.0, 0.0),
        ctx.font,
        &text_paint,
    );
    canvas.restore();
}
