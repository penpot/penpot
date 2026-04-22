use std::cell::OnceCell;

use skia_safe::{self as skia, ColorChannel, RuntimeEffect};

use super::{RenderState, SurfaceId};
use crate::shapes::{Shape, Stroke, TextureEffect};
use crate::state::ShapesPoolRef;
use crate::tiles;

pub const RADIUS_SCALE: f32 = 3.0;

/// Cap the `displacement_map` filter's `scale` parameter (= 2× max per-axis
/// shift) at 2 tiles of filter pixels. That bounds the displacement reach at
/// **1 tile per side** regardless of zoom or UI radius, so the offscreen
/// scratch stays at most `viewport + 2 tiles` — fits easily inside any
/// modern GPU's texture size budget.
///
/// Under this cap: displacement magnitude is world-anchored (Option B) up to
/// roughly zoom ~3×, then caps in screen-pixel terms (Option A behavior) —
/// the extreme-zoom dead-zone is where users wouldn't see the difference
/// anyway.
pub const DISPLACEMENT_CAP_PX: f32 = 2.0 * tiles::TILE_SIZE;

/// Cap the **max noise grain** at the same magnitude — one cycle spans at
/// most 2 tiles of filter pixels. This keeps the noise texture-looking
/// (visible variation in the viewport) even at extreme zoom, where an
/// uncapped world-unit grain would be a single uniform color across the
/// whole visible area.
pub const NOISE_GRAIN_CAP_PX: f32 = 2.0 * tiles::TILE_SIZE;

/// SkSL wrapper that samples a child noise shader in **shape-local,
/// world-unit** coordinates.
///
/// The displacement filter runs in filter-pixel space, so without any
/// coord transform the noise would be sampled at `fractal_noise(filter_px)`
/// — and `filter_px` depends on the current render scale. When we zoom in,
/// the filter-pixel position of the shape's top-left changes, so the noise
/// value "under" each shape-relative point changes → the pattern appears
/// to shift/change on the shape as the user zooms.
///
/// By dividing `(p - shape_tl) * inv_scale`, we convert filter pixels back
/// into **shape-local world units** — a coord frame that depends only on
/// the shape's internal geometry, not on zoom. So `noise(dx, dy)` at a
/// given shape-local point stays constant across all zoom levels: the
/// pattern rides along with the shape, unchanged.
///
/// `base_freq = 1/size` then means "1 cycle per `size` world units" —
/// the UI "Grain Size" is interpreted consistently at any zoom.
const NOISE_SHAPE_LOCAL_SKSL: &str = "
uniform shader noise_child;
uniform float2 shape_tl;
uniform float inv_scale;

half4 main(float2 p) {
    return noise_child.eval((p - shape_tl) * inv_scale);
}
";

thread_local! {
    static NOISE_SHAPE_LOCAL_EFFECT: OnceCell<RuntimeEffect> = const { OnceCell::new() };
}

/// Builds a displacement-map image filter that warps its input using a
/// Perlin noise field. The filter is applied via `save_layer` around the
/// shape's full offscreen render so the entire shape is distorted
/// coherently — matching Figma's `TextureEffect`, which displaces pixels
/// rather than masking them.
///
/// Uses `fractal_noise` (not `turbulence`) because Skia's displacement_map
/// computes the offset as `scale * (n.channel - 0.5)`. Fractal noise has a
/// mean near 0.5 per channel, giving roughly zero net drift; turbulence is
/// the absolute value of the noise and its mean sits well below 0.5, which
/// would bias every pixel in the same direction and shift the whole shape.
///
/// ## Coord spaces used here
///
/// The caller (`render_and_filter_to_image`) opens the save_layer on a
/// **Filter surface at identity CTM**. That means the displacement filter
/// operates in **Filter pixel coordinates** — which, under the per-tile
/// convention we emulate, map to world coords as:
///
/// ```text
///     filter_pixel = scale * (world - extrect.origin) + margin
///     world        = (filter_pixel - margin) / scale + extrect.origin
/// ```
///
/// `extrect` here is whatever rect the caller decides to build the scratch
/// over — the viewport-clipped rect, not the full scheduler-extrect.
/// Because `shape_tl` and the `clip_to_shape` crop rect are expressed
/// relative to this `extrect.origin`, the shape-local coords remain valid
/// whichever `extrect` we pass in.
///
/// - `texture.noise_size` is "grain size in **world** units"; anchored in
///   shape-local coords so the pattern is zoom-stable.
/// - `texture.radius` is the UI slider value (0..100); the actual pixel
///   shift is `radius * RADIUS_SCALE` in layer pixels.
/// - `clip_to_shape = true` passes a crop rect in **filter-pixel** coords
///   (selrect mapped through the per-tile transform).
pub fn build_displacement_filter(
    texture: &TextureEffect,
    shape: &Shape,
    extrect: skia::Rect,
    scale: f32,
    margins: skia::ISize,
) -> Option<skia::ImageFilter> {
    if texture.hidden || texture.radius <= 0.0 {
        return None;
    }

    // Noise grain: user-requested size in world units. Cap it at
    // `NOISE_GRAIN_CAP_PX` filter pixels so a 100-world-unit grain doesn't
    // turn into a 25,600-pixel near-uniform blob at zoom 256×. Above the
    // cap the effective grain shrinks in world units with zoom, keeping
    // visible noise variation in the viewport.
    let raw_grain_world = texture.noise_size.max(1.0);
    let grain_cap_world = NOISE_GRAIN_CAP_PX / scale.max(1e-6);
    let effective_grain_world = raw_grain_world.min(grain_cap_world);
    let base_freq = 1.0 / effective_grain_world;

    // Shape-anchored, zoom-invariant noise via a SkSL wrapper that samples
    // `noise((p - shape_tl) / scale)`. See `NOISE_SHAPE_LOCAL_SKSL`.
    let noise_shader = skia::shaders::fractal_noise((base_freq, base_freq), 4, 0.0, None)?;

    let shape_tl_x = scale * (shape.selrect().left - extrect.left) + margins.width as f32;
    let shape_tl_y = scale * (shape.selrect().top - extrect.top) + margins.height as f32;
    let inv_scale = 1.0 / scale.max(1e-6);

    let anchored_shader = NOISE_SHAPE_LOCAL_EFFECT.with(|cell| {
        let effect = cell.get_or_init(|| {
            RuntimeEffect::make_for_shader(NOISE_SHAPE_LOCAL_SKSL, None)
                .expect("NOISE_SHAPE_LOCAL_SKSL compile failed")
        });

        let uniform_size = effect.uniform_size();
        let mut uniforms = vec![0u8; uniform_size];
        for u in effect.uniforms().iter() {
            let off = u.offset();
            match u.name().as_ref() {
                "shape_tl" => {
                    uniforms[off..off + 4].copy_from_slice(&shape_tl_x.to_ne_bytes());
                    uniforms[off + 4..off + 8].copy_from_slice(&shape_tl_y.to_ne_bytes());
                }
                "inv_scale" => {
                    uniforms[off..off + 4].copy_from_slice(&inv_scale.to_ne_bytes());
                }
                _ => {}
            }
        }

        let children = [skia::runtime_effect::ChildPtr::Shader(noise_shader)];
        effect.make_shader(skia::Data::new_copy(&uniforms), &children, None)
    })?;

    let displacement_source = skia::image_filters::shader(anchored_shader, None)?;

    // R drives X offset, G drives Y offset. The filter's `scale` parameter
    // is the max shift in the layer's pixel space (filter pixels). Max
    // per-axis offset = scale / 2.
    //
    // World-anchored target (Option B): `radius * RADIUS_SCALE` world units
    // of max shift, expressed in filter pixels as `radius*RADIUS_SCALE*scale`.
    // Shape looks identical at any zoom up to the cap.
    //
    // Cap: `DISPLACEMENT_CAP_PX = 2 × TILE_SIZE`. Beyond this, the shift
    // stops scaling with zoom — Option A behavior kicks in. This keeps the
    // offscreen scratch bounded at `viewport + 2 tiles` regardless of zoom,
    // which is what makes 256× zoom actually render without allocating
    // impossibly-huge textures.
    //
    // At the cap the max per-axis shift is exactly `TILE_SIZE` filter
    // pixels = 1 tile. In world units that's `TILE_SIZE / scale` — a full
    // tile at zoom 1, 1/256 of a tile at zoom 256×. Still visible (it's a
    // meaningful fraction of the viewport), just scales with zoom instead
    // of with shape size past the threshold.
    let s_target_px = texture.radius * RADIUS_SCALE * scale;
    let displacement_magnitude_px = s_target_px.min(DISPLACEMENT_CAP_PX);

    if texture.clip_to_shape {
        let selrect = shape.selrect();
        let crop = skia::Rect::from_xywh(
            scale * (selrect.left - extrect.left) + margins.width as f32,
            scale * (selrect.top - extrect.top) + margins.height as f32,
            scale * selrect.width(),
            scale * selrect.height(),
        );
        skia::image_filters::displacement_map(
            (ColorChannel::R, ColorChannel::G),
            displacement_magnitude_px,
            displacement_source,
            None,
            crop,
        )
    } else {
        skia::image_filters::displacement_map(
            (ColorChannel::R, ColorChannel::G),
            displacement_magnitude_px,
            displacement_source,
            None,
            None,
        )
    }
}

/// Returns the rect over which to build the scatter scratch for `shape` —
/// the intersection of the shape's full effect-expanded extrect with a
/// padded viewport, so huge shapes don't allocate huge scratches.
///
/// Padding = the shape's per-side effect outset (recovered from the
/// scheduler extrect minus `selrect` — `shape.extrect` already folds every
/// effect's kernel in via `apply_blur_bounds` / `apply_shadow_bounds` /
/// `apply_scatter_bounds` in `shapes.rs`) plus the spiral cache ring width
/// (`tile_viewbox.interest` tiles worth, so tiles already cached beyond
/// the visible viewport aren't missing shape content when the user pans
/// into them).
///
/// Returns `None` when the intersection is empty (shape entirely outside
/// the viewport) — caller treats this as "nothing to render".
///
/// **Export bypass:** when `export_context` is active, we skip the clip
/// and return the full `scheduler_extrect` — PNG/PDF export has no visible
/// viewport to key off, and we want the full shape rendered.
fn viewport_clipped_extrect(
    render_state: &RenderState,
    shape: &Shape,
    scheduler_extrect: skia::Rect,
    scale: f32,
) -> Option<skia::Rect> {
    // Export must render the full shape, regardless of workspace viewport.
    if render_state.export_context.is_some() {
        return Some(scheduler_extrect);
    }

    let selrect = shape.selrect();

    // Max of the four per-side deltas between scheduler_extrect and selrect.
    // `apply_*_bounds` in shapes.rs only ever outset `selrect`, so these
    // deltas are all non-negative in practice; clamp for safety.
    let pad_per_side = [
        selrect.left - scheduler_extrect.left,
        selrect.top - scheduler_extrect.top,
        scheduler_extrect.right - selrect.right,
        scheduler_extrect.bottom - selrect.bottom,
    ]
    .into_iter()
    .fold(0.0_f32, f32::max);

    // One `tile_viewbox.interest` ring of tiles, in world units. Covers the
    // spiral-cache margin so cached tiles outside the visible viewport
    // still pick up this shape's contribution when revealed by a pan.
    let tile_world = crate::tiles::get_tile_size(scale);
    let spiral_pad_world = render_state.tile_viewbox.interest as f32 * tile_world;

    let pad = pad_per_side + spiral_pad_world;
    let padded_viewport = render_state.viewbox.area.with_outset((pad, pad));

    let mut clipped = scheduler_extrect;
    if !clipped.intersect(padded_viewport) {
        return None;
    }
    if !clipped.is_finite() || clipped.width() <= 0.0 || clipped.height() <= 0.0 {
        return None;
    }
    Some(clipped)
}

/// Renders the shape's content (background blur, glass, fills, strokes,
/// noise) into the Filter surface, applies the displacement filter once,
/// and returns `(image, clipped_extrect)` — where `clipped_extrect` is the
/// rect the image represents in **world** coordinates and is also what the
/// caller should use as the `dst` when blitting the image to target tiles.
///
/// The scratch is sized to `viewport ⊕ max_effect_outset ⊕ spiral_pad ∩
/// shape.extrect` (see [`viewport_clipped_extrect`]). That keeps memory
/// and fill-rate O(viewport) regardless of how large the shape is, which
/// removes the previous accumulated workarounds (dynamic Filter swap with
/// `GL_MAX_TEXTURE_SIZE` clamp, scale downscale with viewbox.zoom override,
/// backdrop-image rescale to match) — they're all dead now.
///
/// `glass_backdrop` is an optional pre-snapshotted Target image the caller
/// supplies for glass refraction. The scatter scratch starts empty, so
/// without this glass would sample transparent black. Matches the
/// non-scatter path's glass backdrop handling.
///
/// Returns `None` when the shape has no active texture, the extrect is
/// empty/non-finite, falls entirely outside the viewport, or the filter
/// couldn't be built. In any of those cases the caller skips the scatter
/// branch.
pub fn render_and_filter_to_image(
    render_state: &mut RenderState,
    shape: &Shape,
    tree: ShapesPoolRef,
    glass_backdrop: Option<skia::Image>,
) -> Option<(skia::Image, skia::Rect)> {
    render_in_displacement_scratch(render_state, shape, tree, glass_backdrop, |state| {
        // Leaf path: render the shape's own fills, strokes, and noise into
        // the scratch. Inner/drop shadows are applied after the per-tile
        // blit (see tile_grid.rs) using the already-displaced silhouette.
        let antialias = shape.should_use_antialias(
            state.get_scale(),
            state.options.antialias_threshold,
        );

        let fill_result = crate::render::fills::render(
            state,
            shape,
            &shape.fills,
            antialias,
            SurfaceId::Filter,
            None,
        );
        let stroke_result = if fill_result.is_ok() {
            let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
            crate::render::strokes::render(
                state,
                shape,
                &visible_strokes,
                Some(SurfaceId::Filter),
                antialias,
                None,
            )
        } else {
            Ok(())
        };

        // Noise effect (shape.noise) — separate from the texture's internal
        // displacement noise. Normally `render_shape` runs this after
        // fills/strokes; since the scatter branch bypasses `render_shape`,
        // we call it explicitly so the noise lands inside the displacement
        // save_layer and gets warped with the rest.
        crate::render::noise::render_shape_noise(state, shape, SurfaceId::Filter);

        fill_result.and(stroke_result)
    })
}

/// Container variant of [`render_and_filter_to_image`]: renders the shape
/// **and its descendants** into the displacement scratch, so a textured
/// frame warps its whole subtree as one unit. Children with their own
/// visible texture get wrapped in a nested `save_layer(filter)` so their
/// warp composes on top of the outer one (Skia stacks image filters).
///
/// Fall through to this path only for `is_recursive()` shapes — the emitter
/// should already have skipped recursive scheduling of the descendants so
/// they aren't also blitted independently on the main canvas.
pub fn render_and_filter_subtree_to_image(
    render_state: &mut RenderState,
    shape: &Shape,
    tree: ShapesPoolRef,
    glass_backdrop: Option<skia::Image>,
) -> Option<(skia::Image, skia::Rect)> {
    render_in_displacement_scratch(render_state, shape, tree, glass_backdrop, |state| {
        render_shape_into_filter(state, shape, tree, true)
    })
}

/// Recursively render `shape` (and its children, if any) into
/// `SurfaceId::Filter`, wrapping non-root scatter shapes in their own
/// displacement `save_layer` so nested textures stack naturally.
///
/// The caller is expected to have already opened the outer displacement
/// `save_layer` and pushed the scratch CTM (scale + translate) on Filter.
fn render_shape_into_filter(
    state: &mut RenderState,
    shape: &Shape,
    tree: ShapesPoolRef,
    is_root: bool,
) -> crate::error::Result<()> {
    if shape.hidden {
        return Ok(());
    }

    // Non-root shapes with a visible texture open a nested displacement
    // layer so their warp composes into the outer one. The root's
    // displacement is provided by the outer `save_layer` opened in
    // `render_in_displacement_scratch`.
    let nested_layer = if !is_root {
        shape
            .texture
            .as_ref()
            .filter(|t| !t.hidden && t.radius > 0.0)
            .and_then(|texture| {
                let scale = state.get_scale();
                let extrect = shape.extrect(tree, scale);
                build_displacement_filter(texture, shape, extrect, scale, skia::ISize::new(0, 0))
            })
    } else {
        None
    };
    let nested_open = if let Some(filter) = nested_layer {
        let mut paint = skia::Paint::default();
        paint.set_image_filter(filter);
        let rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        state.surfaces.canvas(SurfaceId::Filter).save_layer(&rec);
        true
    } else {
        false
    };

    // Apply the shape's own transform (rotated/skewed frames and their
    // descendants). Matches the `canvas.concat(&matrix)` pattern used by
    // `render_shape`'s slow path.
    let center = shape.center();
    let mut matrix = shape.transform;
    matrix.post_translate(center);
    matrix.pre_translate(-center);

    state.surfaces.canvas_and_mark_dirty(SurfaceId::Filter).save();
    state.surfaces.canvas(SurfaceId::Filter).concat(&matrix);

    let antialias =
        shape.should_use_antialias(state.get_scale(), state.options.antialias_threshold);

    let fill_result = crate::render::fills::render(
        state,
        shape,
        &shape.fills,
        antialias,
        SurfaceId::Filter,
        None,
    );
    let stroke_result = if fill_result.is_ok() {
        let visible_strokes: Vec<&Stroke> = shape.visible_strokes().collect();
        crate::render::strokes::render(
            state,
            shape,
            &visible_strokes,
            Some(SurfaceId::Filter),
            antialias,
            None,
        )
    } else {
        Ok(())
    };
    crate::render::noise::render_shape_noise(state, shape, SurfaceId::Filter);

    state.surfaces.canvas(SurfaceId::Filter).restore();

    let subtree_result: crate::error::Result<()> = if shape.is_recursive() {
        let mut result = Ok(());
        for child_id in shape.children_ids(false) {
            if let Some(child) = tree.get(&child_id) {
                if let Err(e) = render_shape_into_filter(state, child, tree, false) {
                    result = Err(e);
                    break;
                }
            }
        }
        result
    } else {
        Ok(())
    };

    if nested_open {
        state.surfaces.canvas(SurfaceId::Filter).restore();
    }

    fill_result.and(stroke_result).and(subtree_result)
}

/// Shared scatter-scratch setup/teardown for both the leaf and the subtree
/// scatter paths. Opens the displacement `save_layer` on Filter, pushes the
/// scratch CTM, invokes `render_inside` for the per-variant content, then
/// closes the layer and snapshots.
fn render_in_displacement_scratch<F>(
    render_state: &mut RenderState,
    shape: &Shape,
    tree: ShapesPoolRef,
    glass_backdrop: Option<skia::Image>,
    render_inside: F,
) -> Option<(skia::Image, skia::Rect)>
where
    F: FnOnce(&mut RenderState) -> crate::error::Result<()>,
{
    let texture = shape.texture.as_ref()?;
    if texture.hidden || texture.radius <= 0.0 {
        return None;
    }

    let scale = render_state.get_scale();
    let scheduler_extrect = shape.extrect(tree, scale);
    if !scheduler_extrect.is_finite()
        || scheduler_extrect.width() <= 0.0
        || scheduler_extrect.height() <= 0.0
    {
        return None;
    }

    // Clip to viewport + pad. `scheduler_extrect` stays unchanged (the tile
    // scheduler still uses it for tile placement); `extrect` below is the
    // clipped rect we actually render into the scratch and blit against.
    let extrect = viewport_clipped_extrect(render_state, shape, scheduler_extrect, scale)?;

    let margins = render_state.surfaces.margins();
    let (filter_w, filter_h) = render_state.surfaces.filter_size();

    // **Zero-margin scratch layout.** In the per-tile convention, content
    // is placed at filter pixel `margin + scale*(world - area.origin)` —
    // the extra `margin` is bleed room so per-tile blur kernels at tile
    // edges have valid samples beyond their tile. None of that applies to
    // the scatter scratch: we render the whole clipped extrect as ONE
    // continuous region, no tile boundary for kernels to cross, and
    // `extrect` already includes the full effect outset in world units.
    //
    // So this pass places content at filter pixel `scale*(world -
    // extrect.origin)` (no `+ margin` offset). A viewport-sized shape then
    // fits in a viewport-sized Filter exactly — no dyn-scratch needed for
    // the common case.
    //
    // Glass and bg_blur compute their own translations via
    // `get_render_context_translation(render_area, scale)` which adds
    // `margin/scale`. To neutralize that without touching the global
    // `surfaces.margins` (which still governs per-tile bleed caps
    // elsewhere, e.g. blur-sigma capping at render.rs:506), we pass them a
    // **shifted** `render_area` whose origin is `extrect.origin +
    // margin/scale`. Their `-shifted.origin + margin/scale` collapses to
    // `-extrect.origin`, and glass's backdrop local-matrix analogously
    // sheds its `-margin` term. See the `shifted_area` assignment below.
    //
    // For `build_displacement_filter` we pass `ISize::new(0, 0)` as
    // `margins` so its crop rect and shape-local SkSL anchor land at
    // `scale*(selrect - extrect.origin)` — matching where the shape content
    // actually sits in the zero-margin layout.
    let scatter_margins = skia::ISize::new(0, 0);

    let needed_w = (extrect.width() * scale).ceil() as i32;
    let needed_h = (extrect.height() * scale).ceil() as i32;
    let overflows_filter = needed_w > filter_w || needed_h > filter_h;

    // Dyn-scratch is still a safety net — e.g. for shapes whose extrect
    // slightly exceeds the viewport because the effect outset pushed past
    // the viewport edge. Much less often triggered than before zero-margin.
    let max_dyn_scratch = render_state.gpu_state.max_scratch_texture_size();
    let fits_dyn_scratch = needed_w <= max_dyn_scratch && needed_h <= max_dyn_scratch;

    let saved_filter_surface = if overflows_filter {
        if !fits_dyn_scratch {
            return None;
        }
        let dyn_scratch = render_state
            .surfaces
            .create_filter_sized(needed_w, needed_h)?;
        Some(render_state.surfaces.swap_filter(dyn_scratch))
    } else {
        None
    };

    let disp_filter =
        match build_displacement_filter(texture, shape, extrect, scale, scatter_margins) {
            Some(f) => f,
            None => {
                if let Some(orig) = saved_filter_surface {
                    let _ = render_state.surfaces.swap_filter(orig);
                }
                return None;
            }
        };

    // Coord convention inside the scratch
    // -----------------------------------
    // We want **zero-margin** filter-pixel placement: shape content at
    // `scale * (world - extrect.origin)`. Callees (glass, bg_blur, fills)
    // compute their own translations via
    // `get_render_context_translation(render_area, scale)`, which returns
    // `-render_area.origin + margin/scale`.
    //
    // To make that collapse to `-extrect.origin` without touching the
    // global `surfaces.margins`, override `render_area` to a rect whose
    // origin is `extrect.origin + margin/scale`. Then:
    //   translation = -(extrect.origin + margin/scale) + margin/scale
    //              = -extrect.origin.   ← zero-margin.
    //
    // Same mechanism kills the `-margin` term in glass's
    // `backdrop_local_matrix`:
    //   offset = -margin + (shifted.origin - viewbox.origin) * scale
    //          = -margin + scale*(extrect.origin - viewbox.origin) + margin
    //          = scale*(extrect.origin - viewbox.origin).
    //
    // Per-tile rendering elsewhere is untouched because the global
    // `surfaces.margins` field is unchanged — this is a local render_area
    // tweak that only affects calls made within this save_layer.
    let margin_w_world = margins.width as f32 / scale.max(1e-6);
    let margin_h_world = margins.height as f32 / scale.max(1e-6);
    let shifted_area = skia::Rect::from_xywh(
        extrect.left + margin_w_world,
        extrect.top + margin_h_world,
        extrect.width(),
        extrect.height(),
    );

    let saved_render_area = render_state.render_area;
    let saved_render_area_with_margins = render_state.render_area_with_margins;
    render_state.render_area = shifted_area;
    render_state.render_area_with_margins = shifted_area;

    // Clear Filter and reset to identity CTM.
    {
        let canvas = render_state.surfaces.canvas(SurfaceId::Filter);
        canvas.clear(skia::Color::TRANSPARENT);
        canvas.reset_matrix();
    }

    // Open the displacement save_layer on Filter.
    let mut layer_paint = skia::Paint::default();
    layer_paint.set_image_filter(disp_filter);
    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&layer_paint);
    render_state
        .surfaces
        .canvas(SurfaceId::Filter)
        .save_layer(&layer_rec);

    // Background blur (Filter CTM still identity — blur applies its own
    // scale+translate against `render_state.render_area` = extrect).
    render_state.render_background_blur(shape, SurfaceId::Filter);

    // Glass — `backdrop_id = Target` activates glass's backdrop local_matrix
    // branch (see `render/glass.rs`), which maps Filter device pixels to
    // Target device pixels for correct refraction sampling.
    if let Some(glass) = shape.glass.as_ref().filter(|g| !g.hidden) {
        crate::render::glass::render_glass_with_backdrop_image(
            render_state,
            shape,
            glass,
            SurfaceId::Filter,
            SurfaceId::Target,
            glass_backdrop,
        );
    }

    // Push zero-margin CTM on Filter, delegate rendering to the caller's
    // closure, then pop. The translation below equals `-extrect.origin` —
    // content lands at filter pixel `scale*(world - extrect.origin)`,
    // matching the layout glass and bg_blur produce via the `shifted_area`
    // override.
    //
    // Inner shadows are applied AFTER the scratch in `tile_grid.rs` using
    // the already-displaced image as the silhouette. Drawing them here via
    // `paint.set_image_filter(inner_shadow)` nests a paint filter inside
    // the `displacement_map` save_layer — Skia samples the paint-filter's
    // small intermediate bitmap at `pos + disp`, and at radius > ~1 that
    // sample lands outside the bitmap, so the shadow vanishes.
    let render_result: crate::error::Result<()> = {
        let translation = render_state
            .surfaces
            .get_render_context_translation(shifted_area, scale);
        {
            let canvas = render_state.surfaces.canvas_and_mark_dirty(SurfaceId::Filter);
            canvas.save();
            canvas.scale((scale, scale));
            canvas.translate(translation);
        }

        let inner_result = render_inside(render_state);

        render_state.surfaces.canvas(SurfaceId::Filter).restore();

        inner_result
    };

    // Close the displacement layer — filter is applied now.
    render_state.surfaces.canvas(SurfaceId::Filter).restore();

    // Restore render_area so subsequent tiles see the normal context.
    render_state.render_area = saved_render_area;
    render_state.render_area_with_margins = saved_render_area_with_margins;

    // Snapshot the filter-pixel region holding the clipped extrect.
    // Under zero-margin layout the extrect's top-left is at filter pixel
    // (0, 0) and bottom-right at (scale*extrect.w, scale*extrect.h).
    let w = needed_w.max(1);
    let h = needed_h.max(1);
    let bounds = skia::IRect::from_xywh(0, 0, w, h);
    let mut surface = render_state.surfaces.surface_clone(SurfaceId::Filter);
    let image = surface.image_snapshot_with_bounds(bounds);

    // If we grew the Filter surface for this build, swap the original back.
    // Dropping the oversized scratch frees its GPU texture.
    if let Some(orig) = saved_filter_surface {
        let _ = render_state.surfaces.swap_filter(orig);
    }

    let image = image?;
    render_result.ok()?;
    Some((image, extrect))
}
