use skia_safe::{self as skia, Contains, Matrix, RRect, Rect};
use std::collections::HashMap;
use uuid::Uuid;

use crate::view::Viewbox;

mod blend;
mod cache;
mod debug;
mod fills;
mod fonts;
mod gpu_state;
mod images;
mod options;
mod shadows;
mod strokes;
mod surfaces;
mod text;

use crate::shapes::{Corners, Shape, Type};
use cache::CachedSurfaceImage;
use gpu_state::GpuState;
use options::RenderOptions;
use surfaces::{SurfaceId, Surfaces};

pub use blend::BlendMode;
pub use fonts::*;
pub use images::*;

const MAX_BLOCKING_TIME_MS: i32 = 32;
const NODE_BATCH_THRESHOLD: i32 = 10;

extern "C" {
    fn emscripten_run_script(script: *const i8);
    fn emscripten_run_script_int(script: *const i8) -> i32;
}

fn get_time() -> i32 {
    let script = std::ffi::CString::new("performance.now()").unwrap();
    unsafe { emscripten_run_script_int(script.as_ptr()) }
}

pub struct NodeRenderState {
    pub id: Uuid,
    // We use this bool to keep that we've traversed all the children inside this node.
    pub visited_children: bool,
    // This is used to clip the content of frames.
    pub clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    // This is a flag to indicate that we've already drawn the mask of a masked group.
    pub visited_mask: bool,
    // This bool indicates that we're drawing the mask shape.
    pub mask: bool,
}

impl NodeRenderState {
    pub fn get_children_clip_bounds(
        &self,
        element: &Shape,
        modifiers: &HashMap<Uuid, Matrix>,
    ) -> Option<(Rect, Option<Corners>, Matrix)> {
        if self.id.is_nil() || !element.clip() {
            return self.clip_bounds;
        }

        let bounds = element.selrect();
        let mut transform = element.transform;
        transform.post_translate(bounds.center());
        transform.pre_translate(-bounds.center());

        if let Some(modifier) = modifiers.get(&element.id) {
            transform.post_concat(modifier);
        }

        let corners = match &element.shape_type {
            Type::Rect(data) => data.corners,
            Type::Frame(data) => data.corners,
            _ => None,
        };

        Some((bounds, corners, transform))
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub options: RenderOptions,
    pub surfaces: Surfaces,
    fonts: FontStore,
    pub cached_surface_image: Option<CachedSurfaceImage>,
    pub viewbox: Viewbox,
    pub images: ImageStore,
    pub background_color: skia::Color,
    // Identifier of the current requestAnimationFrame call, if any.
    pub render_request_id: Option<i32>,
    // Indicates whether the rendering process has pending frames.
    pub render_in_progress: bool,
    // Stack of nodes pending to be rendered.
    pub pending_nodes: Vec<NodeRenderState>,
    pub render_complete: bool,
    pub sampling_options: skia::SamplingOptions,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();

        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);
        let surfaces = Surfaces::new(&mut gpu_state, (width, height), sampling_options);

        let fonts = FontStore::new();

        // This is used multiple times everywhere so instead of creating new instances every
        // time we reuse this one.
        RenderState {
            gpu_state,
            surfaces,
            cached_surface_image: None,
            // font_provider,
            // font_collection,
            fonts,
            options: RenderOptions::default(),
            viewbox: Viewbox::new(width as f32, height as f32),
            images: ImageStore::new(),
            background_color: skia::Color::TRANSPARENT,
            render_request_id: None,
            render_in_progress: false,
            pending_nodes: vec![],
            render_complete: true,
            sampling_options,
        }
    }

    pub fn fonts(&self) -> &FontStore {
        &self.fonts
    }

    pub fn fonts_mut(&mut self) -> &mut FontStore {
        &mut self.fonts
    }

    pub fn add_image(&mut self, id: Uuid, image_data: &[u8]) -> Result<(), String> {
        self.images.add(id, image_data)
    }

    pub fn has_image(&mut self, id: &Uuid) -> bool {
        self.images.contains(id)
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

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.background_color = color;
        let _ = self.render_from_cache();
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;

        self.surfaces
            .resize(&mut self.gpu_state, dpr_width, dpr_height);
        self.viewbox.set_wh(width as f32, height as f32);
    }

    pub fn flush(&mut self) {
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Target);
    }

    pub fn reset_canvas(&mut self) {
        self.surfaces.canvas(SurfaceId::Fills).restore_to_count(1);
        self.surfaces
            .canvas(SurfaceId::DropShadows)
            .restore_to_count(1);
        self.surfaces.canvas(SurfaceId::Strokes).restore_to_count(1);
        self.surfaces.canvas(SurfaceId::Current).restore_to_count(1);

        self.surfaces.apply_mut(
            &[
                SurfaceId::Fills,
                SurfaceId::Strokes,
                SurfaceId::Current,
                SurfaceId::DropShadows,
                SurfaceId::Shadow,
                SurfaceId::Overlay,
            ],
            |s| {
                s.canvas().clear(self.background_color).reset_matrix();
            },
        );

        self.surfaces
            .canvas(SurfaceId::Debug)
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn apply_render_to_final_canvas(&mut self) {
        self.surfaces.draw_into(
            SurfaceId::Current,
            SurfaceId::Target,
            Some(&skia::Paint::default()),
        );
    }

    pub fn apply_drawing_to_render_canvas(&mut self, shape: &Shape) {
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Fills);

        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::DropShadows);

        self.surfaces.draw_into(
            SurfaceId::DropShadows,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        self.surfaces.draw_into(
            SurfaceId::Fills,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        let render_overlay_below_strokes = shape.fills().len() > 0;
        if render_overlay_below_strokes {
            self.surfaces
                .flush_and_submit(&mut self.gpu_state, SurfaceId::Overlay);
            self.surfaces.draw_into(
                SurfaceId::Overlay,
                SurfaceId::Current,
                Some(&skia::Paint::default()),
            );
        }

        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Strokes);
        self.surfaces.draw_into(
            SurfaceId::Strokes,
            SurfaceId::Current,
            Some(&skia::Paint::default()),
        );

        if !render_overlay_below_strokes {
            self.surfaces
                .flush_and_submit(&mut self.gpu_state, SurfaceId::Overlay);
            self.surfaces.draw_into(
                SurfaceId::Overlay,
                SurfaceId::Current,
                Some(&skia::Paint::default()),
            );
        }

        self.surfaces
            .draw_into(SurfaceId::Overlay, SurfaceId::Current, None);
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, SurfaceId::Current);

        self.surfaces.apply_mut(
            &[
                SurfaceId::Shadow,
                SurfaceId::DropShadows,
                SurfaceId::Overlay,
                SurfaceId::Fills,
                SurfaceId::Strokes,
            ],
            |s| {
                s.canvas().clear(skia::Color::TRANSPARENT);
            },
        );
    }

    pub fn invalidate_cache_if_needed(&mut self) {
        if let Some(ref mut cached_surface_image) = self.cached_surface_image {
            cached_surface_image.invalidate_if_dirty(&self.viewbox);
        }
    }

    pub fn render_shape(
        &mut self,
        shape: &mut Shape,
        modifiers: Option<&Matrix>,
        clip_bounds: Option<(Rect, Option<Corners>, Matrix)>,
    ) {
        let surface_ids = &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows];
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().save();
        });

        // set clipping
        if let Some((bounds, corners, transform)) = clip_bounds {
            self.surfaces.apply_mut(
                &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
                |s| {
                    s.canvas().concat(&transform);
                },
            );

            if let Some(corners) = corners {
                let rrect = RRect::new_rect_radii(bounds, &corners);
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().clip_rrect(rrect, skia::ClipOp::Intersect, true);
                });
            } else {
                self.surfaces.apply_mut(surface_ids, |s| {
                    s.canvas().clip_rect(bounds, skia::ClipOp::Intersect, true);
                });
            }

            if self.options.is_debug_visible() {
                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_color(skia::Color::from_argb(255, 255, 0, 0));
                paint.set_stroke_width(4.);
                self.surfaces
                    .canvas(SurfaceId::Fills)
                    .draw_rect(bounds, &paint);
            }

            self.surfaces.apply_mut(surface_ids, |s| {
                s.canvas()
                    .concat(&transform.invert().unwrap_or(Matrix::default()));
            });
        }

        // Clone so we don't change the value in the global state
        let mut shape = shape.clone();

        if let Some(modifiers) = modifiers {
            shape.apply_transform(&modifiers);
        }

        let center = shape.center();
        let mut matrix = shape.transform;
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        match &shape.shape_type {
            Type::SVGRaw(sr) => {
                if let Some(modifiers) = modifiers {
                    self.surfaces.canvas(SurfaceId::Fills).concat(&modifiers);
                }
                self.surfaces.canvas(SurfaceId::Fills).concat(&matrix);
                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.canvas(SurfaceId::Fills))
                } else {
                    let font_manager = skia::FontMgr::from(self.fonts().font_provider().clone());
                    let dom_result = skia::svg::Dom::from_str(sr.content.to_string(), font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.canvas(SurfaceId::Fills));
                            shape.set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }
            Type::Text(text_content) => {
                text::render(self, text_content);
            }
            _ => {
                self.surfaces.apply_mut(
                    &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
                    |s| {
                        s.canvas().concat(&matrix);
                    },
                );

                for fill in shape.fills().rev() {
                    fills::render(self, &shape, fill);
                }

                for stroke in shape.strokes().rev() {
                    strokes::render(self, &shape, stroke);
                }

                for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
                    shadows::render_inner_shadow(
                        self,
                        shadow,
                        self.viewbox.zoom * self.options.dpr(),
                        shape.fills().len() > 0,
                    );
                }

                shadows::render_drop_shadows(self, &shape);
            }
        };

        self.apply_drawing_to_render_canvas(&shape);
        self.surfaces.apply_mut(
            &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
            |s| {
                s.canvas().restore();
            },
        );
    }

    pub fn start_render_loop(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if self.render_in_progress {
            if let Some(frame_id) = self.render_request_id {
                self.cancel_animation_frame(frame_id);
            }
        }
        self.reset_canvas();
        self.surfaces.apply_mut(
            &[SurfaceId::Fills, SurfaceId::Strokes, SurfaceId::DropShadows],
            |s| {
                s.canvas().scale((
                    self.viewbox.zoom * self.options.dpr(),
                    self.viewbox.zoom * self.options.dpr(),
                ));
                s.canvas()
                    .translate((self.viewbox.pan_x, self.viewbox.pan_y));
            },
        );

        self.pending_nodes = vec![NodeRenderState {
            id: Uuid::nil(),
            visited_children: false,
            clip_bounds: None,
            visited_mask: false,
            mask: false,
        }];
        self.render_in_progress = true;
        self.process_animation_frame(tree, modifiers, timestamp)?;
        self.render_complete = true;
        Ok(())
    }

    pub fn request_animation_frame(&mut self) -> i32 {
        let script =
            std::ffi::CString::new("requestAnimationFrame(_process_animation_frame)").unwrap();
        unsafe { emscripten_run_script_int(script.as_ptr()) }
    }

    pub fn cancel_animation_frame(&mut self, frame_id: i32) {
        let cancel_script = format!("cancelAnimationFrame({})", frame_id);
        let c_cancel_script = std::ffi::CString::new(cancel_script).unwrap();
        unsafe {
            emscripten_run_script(c_cancel_script.as_ptr());
        }
    }

    pub fn process_animation_frame(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if self.render_in_progress {
            self.render_shape_tree(tree, modifiers, timestamp)?;
            if self.render_in_progress {
                if let Some(frame_id) = self.render_request_id {
                    self.cancel_animation_frame(frame_id);
                }
                self.render_request_id = Some(self.request_animation_frame());
            }
        }

        // self.render_in_progress can have changed
        if self.render_in_progress {
            if self.cached_surface_image.is_some() {
                self.render_from_cache()?;
            }
            return Ok(());
        }

        // Chech if cached_surface_image is not set or is invalid
        if self
            .cached_surface_image
            .as_ref()
            .is_none_or(|img| img.invalid)
        {
            self.cached_surface_image = Some(CachedSurfaceImage {
                image: self.surfaces.snapshot(SurfaceId::Current),
                viewbox: self.viewbox,
                invalid: false,
                has_all_shapes: self.render_complete,
            });
        }

        if self.options.is_debug_visible() {
            self.render_debug();
        }

        debug::render_wasm_label(self);
        self.apply_render_to_final_canvas();
        self.flush();
        Ok(())
    }

    pub fn clear_cache(&mut self) {
        self.cached_surface_image = None;
    }

    pub fn render_from_cache(&mut self) -> Result<(), String> {
        let cached = self
            .cached_surface_image
            .as_ref()
            .ok_or("Uninitialized cached surface image")?;

        let image = &cached.image;
        let paint = skia::Paint::default();
        self.surfaces.canvas(SurfaceId::Target).save();
        self.surfaces.canvas(SurfaceId::Fills).save();
        self.surfaces.canvas(SurfaceId::Strokes).save();
        self.surfaces.canvas(SurfaceId::DropShadows).save();

        let navigate_zoom = self.viewbox.zoom / cached.viewbox.zoom;
        let navigate_x = cached.viewbox.zoom * (self.viewbox.pan_x - cached.viewbox.pan_x);
        let navigate_y = cached.viewbox.zoom * (self.viewbox.pan_y - cached.viewbox.pan_y);

        self.surfaces
            .canvas(SurfaceId::Target)
            .scale((navigate_zoom, navigate_zoom));
        self.surfaces.canvas(SurfaceId::Target).translate((
            navigate_x * self.options.dpr(),
            navigate_y * self.options.dpr(),
        ));
        self.surfaces
            .canvas(SurfaceId::Target)
            .clear(self.background_color);
        self.surfaces
            .canvas(SurfaceId::Target)
            .draw_image(image, (0, 0), Some(&paint));

        self.surfaces.canvas(SurfaceId::Target).restore();
        self.surfaces.canvas(SurfaceId::Fills).restore();
        self.surfaces.canvas(SurfaceId::Strokes).restore();
        self.surfaces.canvas(SurfaceId::DropShadows).restore();

        self.flush();

        Ok(())
    }

    fn render_debug(&mut self) {
        debug::render(self);
    }

    pub fn render_shape_enter(&mut self, element: &mut Shape, mask: bool) {
        // Masked groups needs two rendering passes, the first one rendering
        // the content and the second one rendering the mask so we need to do
        // an extra save_layer to keep all the masked group separate from other
        // already drawn elements.
        match element.shape_type {
            Type::Group(group) => {
                if group.masked {
                    let paint = skia::Paint::default();
                    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
                    self.surfaces
                        .canvas(SurfaceId::Current)
                        .save_layer(&layer_rec);
                }
            }
            _ => {}
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        // When we're rendering the mask shape we need to set a special blend mode
        // called 'destination-in' that keeps the drawn content within the mask.
        // @see https://skia.org/docs/user/api/skblendmode_overview/
        if mask {
            let mut mask_paint = skia::Paint::default();
            mask_paint.set_blend_mode(skia::BlendMode::DstIn);
            let mask_rec = skia::canvas::SaveLayerRec::default().paint(&mask_paint);
            self.surfaces
                .canvas(SurfaceId::Current)
                .save_layer(&mask_rec);
        }

        if let Some(image_filter) = element.image_filter(self.viewbox.zoom * self.options.dpr()) {
            paint.set_image_filter(image_filter);
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        self.surfaces
            .canvas(SurfaceId::Current)
            .save_layer(&layer_rec);
    }

    pub fn render_shape_exit(&mut self, element: &mut Shape, visited_mask: bool) {
        if visited_mask {
            // Because masked groups needs two rendering passes (first drawing
            // the content and then drawing the mask), we need to do an
            // extra restore.
            match element.shape_type {
                Type::Group(group) => {
                    if group.masked {
                        self.surfaces.canvas(SurfaceId::Current).restore();
                    }
                }
                _ => {}
            }
        }
        self.surfaces.canvas(SurfaceId::Current).restore();
    }

    pub fn render_shape_tree(
        &mut self,
        tree: &mut HashMap<Uuid, Shape>,
        modifiers: &HashMap<Uuid, Matrix>,
        timestamp: i32,
    ) -> Result<(), String> {
        if !self.render_in_progress {
            return Ok(());
        }

        let mut i = 0;
        while let Some(node_render_state) = self.pending_nodes.pop() {
            let NodeRenderState {
                id: node_id,
                visited_children,
                clip_bounds,
                visited_mask,
                mask,
            } = node_render_state;
            let element = tree.get_mut(&node_id).ok_or(
                "Error: Element with root_id {node_render_state.id} not found in the tree."
                    .to_string(),
            )?;

            let render_complete = self.viewbox.area.contains(element.selrect());
            if visited_children {
                if !visited_mask {
                    match element.shape_type {
                        Type::Group(group) => {
                            // When we're dealing with masked groups we need to
                            // do a separate extra step to draw the mask (the last
                            // element of a masked group) and blend (using
                            // the blend mode 'destination-in') the content
                            // of the group and the mask.
                            if group.masked {
                                self.pending_nodes.push(NodeRenderState {
                                    id: node_id,
                                    visited_children: true,
                                    clip_bounds: None,
                                    visited_mask: true,
                                    mask: false,
                                });
                                if let Some(&mask_id) = element.mask_id() {
                                    self.pending_nodes.push(NodeRenderState {
                                        id: mask_id,
                                        visited_children: false,
                                        clip_bounds: None,
                                        visited_mask: false,
                                        mask: true,
                                    });
                                }
                            }
                        }
                        _ => {}
                    }
                }
                self.render_shape_exit(element, visited_mask);
                continue;
            }

            // If we didn't visited_children this shape, then we need to do
            if !node_render_state.id.is_nil() {
                if !element.selrect().intersects(self.viewbox.area) || element.hidden() {
                    debug::render_debug_shape(self, element, false);
                    self.render_complete = render_complete;
                    continue;
                } else {
                    debug::render_debug_shape(self, element, true);
                }
            }

            self.render_shape_enter(element, mask);
            if !node_render_state.id.is_nil() {
                self.render_shape(element, modifiers.get(&element.id), clip_bounds);
            } else {
                self.apply_drawing_to_render_canvas(&element);
            }

            // Set the node as visited_children before processing children
            self.pending_nodes.push(NodeRenderState {
                id: node_id,
                visited_children: true,
                clip_bounds: None,
                visited_mask: false,
                mask: mask,
            });

            if element.is_recursive() {
                let children_clip_bounds =
                    node_render_state.get_children_clip_bounds(element, &modifiers);

                for child_id in element.children_ids().iter().rev() {
                    self.pending_nodes.push(NodeRenderState {
                        id: *child_id,
                        visited_children: false,
                        clip_bounds: children_clip_bounds,
                        visited_mask: false,
                        mask: false,
                    });
                }
            }

            // We try to avoid doing too many calls to get_time
            if i % NODE_BATCH_THRESHOLD == 0 && get_time() - timestamp > MAX_BLOCKING_TIME_MS {
                return Ok(());
            }

            i += 1;
        }

        // If we finish processing every node rendering is complete
        self.render_in_progress = false;
        Ok(())
    }
}
