use skia_safe::{self as skia, Contains, Matrix, RRect, Rect};
use std::collections::HashMap;
use uuid::Uuid;

use crate::view::Viewbox;

mod blend;
mod cache;
mod debug;
mod fills;
mod gpu_state;
mod images;
mod options;
mod shadows;
mod strokes;
mod surfaces;

use crate::shapes::{Corners, Shape, Type};
use cache::CachedSurfaceImage;
use gpu_state::GpuState;
use options::RenderOptions;
use surfaces::Surfaces;

pub use blend::BlendMode;
pub use images::*;

const DEFAULT_FONT_BYTES: &[u8] =
    include_bytes!("../../frontend/resources/fonts/RobotoMono-Regular.ttf");

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

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub options: RenderOptions,
    pub surfaces: Surfaces,
    pub sampling_options: skia::SamplingOptions,
    pub font_provider: skia::textlayout::TypefaceFontProvider,
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
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let surfaces = Surfaces::new(&mut gpu_state, (width, height));
        let mut font_provider = skia::textlayout::TypefaceFontProvider::new();
        let default_font = skia::FontMgr::default()
            .new_from_data(DEFAULT_FONT_BYTES, None)
            .expect("Failed to load font");
        font_provider.register_typeface(default_font, "robotomono-regular");

        // This is used multiple times everywhere so instead of creating new instances every
        // time we reuse this one.
        let sampling_options =
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest);

        RenderState {
            gpu_state,
            surfaces,
            cached_surface_image: None,
            font_provider,
            sampling_options,
            options: RenderOptions::default(),
            viewbox: Viewbox::new(width as f32, height as f32),
            images: ImageStore::new(),
            background_color: skia::Color::TRANSPARENT,
            render_request_id: None,
            render_in_progress: false,
            pending_nodes: vec![],
            render_complete: true,
        }
    }

    pub fn add_font(&mut self, family_name: String, font_data: &[u8]) -> Result<(), String> {
        let typeface = skia::FontMgr::default()
            .new_from_data(font_data, None)
            .expect("Failed to add font");
        self.font_provider
            .register_typeface(typeface, family_name.as_ref());
        Ok(())
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
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.surfaces.target, None);
    }

    pub fn reset_canvas(&mut self) {
        self.surfaces.shape.canvas().restore_to_count(1);
        self.surfaces.current.canvas().restore_to_count(1);
        self.surfaces
            .shape
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.surfaces
            .current
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.surfaces
            .shadow
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.surfaces
            .overlay
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.surfaces
            .debug
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn apply_render_to_final_canvas(&mut self) {
        self.surfaces.current.draw(
            &mut self.surfaces.target.canvas(),
            (0.0, 0.0),
            self.sampling_options,
            Some(&skia::Paint::default()),
        );
    }

    pub fn apply_drawing_to_render_canvas(&mut self) {
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.surfaces.shape, None);

        self.surfaces.shape.draw(
            &mut self.surfaces.current.canvas(),
            (0.0, 0.0),
            self.sampling_options,
            Some(&skia::Paint::default()),
        );

        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.surfaces.current, None);

        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.surfaces.overlay, None);

        self.surfaces.overlay.draw(
            &mut self.surfaces.current.canvas(),
            (0.0, 0.0),
            self.sampling_options,
            None,
        );

        self.surfaces
            .shadow
            .canvas()
            .clear(skia::Color::TRANSPARENT);

        self.surfaces
            .overlay
            .canvas()
            .clear(skia::Color::TRANSPARENT);

        self.surfaces.shape.canvas().clear(skia::Color::TRANSPARENT);
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
        self.surfaces.shape.canvas().save();
        if let Some((bounds, corners, transform)) = clip_bounds {
            self.surfaces.shape.canvas().concat(&transform);
            if let Some(corners) = corners {
                let rrect = RRect::new_rect_radii(bounds, &corners);
                self.surfaces
                    .shape
                    .canvas()
                    .clip_rrect(rrect, skia::ClipOp::Intersect, true);
            } else {
                self.surfaces
                    .shape
                    .canvas()
                    .clip_rect(bounds, skia::ClipOp::Intersect, true);
            }

            if self.options.is_debug_visible() {
                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_color(skia::Color::from_argb(255, 255, 0, 0));
                paint.set_stroke_width(4.);
                self.surfaces.shape.canvas().draw_rect(bounds, &paint);
            }

            self.surfaces
                .shape
                .canvas()
                .concat(&transform.invert().unwrap_or(Matrix::default()));
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
                    self.surfaces.shape.canvas().concat(&modifiers);
                }
                self.surfaces.shape.canvas().concat(&matrix);
                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.surfaces.shape.canvas())
                } else {
                    let font_manager = skia::FontMgr::from(self.font_provider.clone());
                    let dom_result = skia::svg::Dom::from_str(sr.content.to_string(), font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.surfaces.shape.canvas());
                            shape.set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }
            _ => {
                self.surfaces.shape.canvas().concat(&matrix);

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
                    );
                }

                for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
                    shadows::render_drop_shadow(
                        self,
                        shadow,
                        self.viewbox.zoom * self.options.dpr(),
                    );
                }
            }
        };

        self.apply_drawing_to_render_canvas();
        self.surfaces.shape.canvas().restore();
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
        self.surfaces.shape.canvas().scale((
            self.viewbox.zoom * self.options.dpr(),
            self.viewbox.zoom * self.options.dpr(),
        ));
        self.surfaces
            .shape
            .canvas()
            .translate((self.viewbox.pan_x, self.viewbox.pan_y));
        //
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
            .map_or(true, |img| img.invalid)
        {
            self.cached_surface_image = Some(CachedSurfaceImage {
                image: self.surfaces.current.image_snapshot(),
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
        self.surfaces.target.canvas().save();
        self.surfaces.shape.canvas().save();

        let navigate_zoom = self.viewbox.zoom / cached.viewbox.zoom;
        let navigate_x = cached.viewbox.zoom * (self.viewbox.pan_x - cached.viewbox.pan_x);
        let navigate_y = cached.viewbox.zoom * (self.viewbox.pan_y - cached.viewbox.pan_y);

        self.surfaces
            .target
            .canvas()
            .scale((navigate_zoom, navigate_zoom));
        self.surfaces.target.canvas().translate((
            navigate_x * self.options.dpr(),
            navigate_y * self.options.dpr(),
        ));
        self.surfaces.target.canvas().clear(self.background_color);
        self.surfaces
            .target
            .canvas()
            .draw_image(image, (0, 0), Some(&paint));

        self.surfaces.target.canvas().restore();
        self.surfaces.shape.canvas().restore();

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
                    self.surfaces.current.canvas().save_layer(&layer_rec);
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
            self.surfaces.current.canvas().save_layer(&mask_rec);
        }

        if let Some(image_filter) = element.image_filter(self.viewbox.zoom * self.options.dpr()) {
            paint.set_image_filter(image_filter);
        }

        let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
        self.surfaces.current.canvas().save_layer(&layer_rec);
    }

    pub fn render_shape_exit(&mut self, element: &mut Shape, visited_mask: bool) {
        if visited_mask {
            // Because masked groups needs two rendering passes (first drawing
            // the content and then drawing the mask), we need to do an
            // extra restore.
            match element.shape_type {
                Type::Group(group) => {
                    if group.masked {
                        self.surfaces.current.canvas().restore();
                    }
                }
                _ => {}
            }
        }
        self.surfaces.current.canvas().restore();
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
                self.apply_drawing_to_render_canvas();
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
                    (!node_render_state.id.is_nil() & element.clip()).then(|| {
                        let bounds = element.selrect();
                        let mut transform = element.transform;
                        transform.post_translate(bounds.center());
                        transform.pre_translate(-bounds.center());
                        if let Some(modifiers) = modifiers.get(&element.id) {
                            transform.post_concat(&modifiers);
                        }
                        let corners = match &element.shape_type {
                            Type::Rect(data) => data.corners,
                            Type::Frame(data) => data.corners,
                            _ => None,
                        };
                        (bounds, corners, transform)
                    });

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
