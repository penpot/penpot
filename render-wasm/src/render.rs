use skia::Contains;
use skia_safe as skia;
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

use crate::shapes::{Kind, Shape};
use cache::CachedSurfaceImage;
use gpu_state::GpuState;
use options::RenderOptions;

pub use blend::BlendMode;
pub use images::*;

const DEFAULT_FONT_BYTES: &[u8] =
    include_bytes!("../../frontend/resources/fonts/RobotoMono-Regular.ttf");

pub(crate) struct RenderState {
    gpu_state: GpuState,
    options: RenderOptions,

    // TODO: Probably we're going to need
    // a surface stack like the one used
    // by SVG: https://www.w3.org/TR/SVG2/render.html
    pub final_surface: skia::Surface,
    pub drawing_surface: skia::Surface,
    pub shadow_surface: skia::Surface,
    pub debug_surface: skia::Surface,
    pub font_provider: skia::textlayout::TypefaceFontProvider,
    pub cached_surface_image: Option<CachedSurfaceImage>,
    pub viewbox: Viewbox,
    pub images: ImageStore,
    pub background_color: skia::Color,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let mut final_surface = gpu_state.create_target_surface(width, height);
        let shadow_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
        let drawing_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
        let debug_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();

        let mut font_provider = skia::textlayout::TypefaceFontProvider::new();
        let default_font = skia::FontMgr::default()
            .new_from_data(DEFAULT_FONT_BYTES, None)
            .expect("Failed to load font");
        font_provider.register_typeface(default_font, "robotomono-regular");

        RenderState {
            gpu_state,
            final_surface,
            shadow_surface,
            drawing_surface,
            debug_surface,
            cached_surface_image: None,
            font_provider,
            options: RenderOptions::default(),
            viewbox: Viewbox::new(width as f32, height as f32),
            images: ImageStore::new(),
            background_color: skia::Color::TRANSPARENT,
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
        let _ = self.render_all_from_cache();
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;

        let surface = self.gpu_state.create_target_surface(dpr_width, dpr_height);
        self.final_surface = surface;
        self.shadow_surface = self
            .final_surface
            .new_surface_with_dimensions((dpr_width, dpr_height))
            .unwrap();
        self.drawing_surface = self
            .final_surface
            .new_surface_with_dimensions((dpr_width, dpr_height))
            .unwrap();
        self.debug_surface = self
            .final_surface
            .new_surface_with_dimensions((dpr_width, dpr_height))
            .unwrap();

        self.viewbox.set_wh(width as f32, height as f32);
    }

    pub fn flush(&mut self) {
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.final_surface, None);
    }

    pub fn translate(&mut self, dx: f32, dy: f32) {
        self.drawing_surface.canvas().translate((dx, dy));
    }

    pub fn scale(&mut self, sx: f32, sy: f32) {
        self.drawing_surface.canvas().scale((sx, sy));
    }

    pub fn reset_canvas(&mut self) {
        self.drawing_surface
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.shadow_surface
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.final_surface
            .canvas()
            .clear(self.background_color)
            .reset_matrix();
        self.debug_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn apply_drawing_to_final_canvas(&mut self) {
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.drawing_surface, None);

        self.drawing_surface.draw(
            &mut self.final_surface.canvas(),
            (0.0, 0.0),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            Some(&skia::Paint::default()),
        );

        self.shadow_surface.canvas().clear(skia::Color::TRANSPARENT);

        self.drawing_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT);
    }

    pub fn render_shape(&mut self, shape: &mut Shape, clip: bool) {
        let transform = shape.transform.to_skia_matrix();

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let center = shape.bounds().center();
        let mut matrix = skia::Matrix::new_identity();
        matrix.pre_translate(center);
        matrix.pre_concat(&transform);
        matrix.pre_translate(-center);

        self.drawing_surface.canvas().concat(&matrix);

        match &shape.kind {
            Kind::SVGRaw(sr) => {
                if let Some(svg) = shape.svg.as_ref() {
                    svg.render(self.drawing_surface.canvas())
                } else {
                    let font_manager = skia::FontMgr::from(self.font_provider.clone());
                    let dom_result = skia::svg::Dom::from_str(sr.content.to_string(), font_manager);
                    match dom_result {
                        Ok(dom) => {
                            dom.render(self.drawing_surface.canvas());
                            shape.set_svg(dom);
                        }
                        Err(e) => {
                            eprintln!("Error parsing SVG. Error: {}", e);
                        }
                    }
                }
            }
            _ => {
                for fill in shape.fills().rev() {
                    fills::render(self, shape, fill);
                }

                for stroke in shape.strokes().rev() {
                    strokes::render(self, shape, stroke);
                }
            }
        };

        if clip {
            self.drawing_surface
                .canvas()
                .clip_rect(shape.bounds(), skia::ClipOp::Intersect, true);
        }

        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            shadows::render_drop_shadow(self, shadow, self.viewbox.zoom * self.options.dpr());
        }

        self.apply_drawing_to_final_canvas();
    }

    pub fn zoom(&mut self, tree: &HashMap<Uuid, Shape>) -> Result<(), String> {
        if let Some(cached_surface_image) = self.cached_surface_image.as_mut() {
            let is_dirty = cached_surface_image.is_dirty_for_zooming(&self.viewbox);
            if is_dirty {
                self.render_all(tree, true);
            } else {
                self.render_all_from_cache()?;
            }
        }

        Ok(())
    }

    pub fn pan(&mut self, tree: &HashMap<Uuid, Shape>) -> Result<(), String> {
        if let Some(cached_surface_image) = self.cached_surface_image.as_mut() {
            let is_dirty = cached_surface_image.is_dirty_for_panning(&self.viewbox);
            if is_dirty {
                self.render_all(tree, true);
            } else {
                self.render_all_from_cache()?;
            }
        }

        Ok(())
    }

    pub fn render_all(&mut self, tree: &HashMap<Uuid, Shape>, generate_cached_surface_image: bool) {
        self.reset_canvas();
        self.scale(
            self.viewbox.zoom * self.options.dpr(),
            self.viewbox.zoom * self.options.dpr(),
        );
        self.translate(self.viewbox.pan_x, self.viewbox.pan_y);

        // Reset shape tree
        let is_complete = self.render_shape_tree(&Uuid::nil(), tree);
        if generate_cached_surface_image || self.cached_surface_image.is_none() {
            self.cached_surface_image = Some(CachedSurfaceImage {
                image: self.final_surface.image_snapshot(),
                viewbox: self.viewbox,
                has_all_shapes: is_complete,
            });
        }

        if self.options.is_debug_visible() {
            self.render_debug();
        }

        debug::render_wasm_label(self);

        self.flush();
    }

    fn render_all_from_cache(&mut self) -> Result<(), String> {
        self.reset_canvas();

        let cached = self
            .cached_surface_image
            .as_ref()
            .ok_or("Uninitialized cached surface image")?;

        let image = &cached.image;
        let paint = skia::Paint::default();
        self.final_surface.canvas().save();
        self.drawing_surface.canvas().save();

        let navigate_zoom = self.viewbox.zoom / cached.viewbox.zoom;
        let navigate_x = cached.viewbox.zoom * (self.viewbox.pan_x - cached.viewbox.pan_x);
        let navigate_y = cached.viewbox.zoom * (self.viewbox.pan_y - cached.viewbox.pan_y);

        self.final_surface
            .canvas()
            .scale((navigate_zoom, navigate_zoom));
        self.final_surface.canvas().translate((
            navigate_x * self.options.dpr(),
            navigate_y * self.options.dpr(),
        ));
        self.final_surface
            .canvas()
            .draw_image(image.clone(), (0, 0), Some(&paint));

        self.final_surface.canvas().restore();
        self.drawing_surface.canvas().restore();

        self.flush();

        Ok(())
    }

    fn render_debug(&mut self) {
        debug::render(self);
    }

    // Returns a boolean indicating if the viewbox contains the rendered shapes
    fn render_shape_tree(&mut self, root_id: &Uuid, tree: &HashMap<Uuid, Shape>) -> bool {
        if let Some(element) = tree.get(&root_id) {
            let mut is_complete = self.viewbox.area.contains(element.bounds());

            if !root_id.is_nil() {
                if !element.bounds().intersects(self.viewbox.area) || element.hidden() {
                    debug::render_debug_element(self, element, false);
                    // TODO: This means that not all the shapes are rendered so we
                    // need to call a render_all on the zoom out.
                    return is_complete; // TODO return is_complete or return false??
                } else {
                    debug::render_debug_element(self, element, true);
                }
            }

            let mut paint = skia::Paint::default();
            paint.set_blend_mode(element.blend_mode().into());
            paint.set_alpha_f(element.opacity());
            let filter = element.image_filter(self.viewbox.zoom * self.options.dpr());
            if let Some(image_filter) = filter {
                paint.set_image_filter(image_filter);
            }

            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            // This is needed so the next non-children shape does not carry this shape's transform
            self.final_surface.canvas().save_layer(&layer_rec);

            self.drawing_surface.canvas().save();
            if !root_id.is_nil() {
                self.render_shape(&mut element.clone(), element.clip());
            } else {
                self.apply_drawing_to_final_canvas();
            }

            self.drawing_surface.canvas().restore();

            // draw all the children shapes
            if element.is_recursive() {
                for id in element.children_ids() {
                    self.drawing_surface.canvas().save();
                    is_complete = self.render_shape_tree(&id, tree) && is_complete;
                    self.drawing_surface.canvas().restore();
                }
            }

            self.final_surface.canvas().restore();

            return is_complete;
        } else {
            eprintln!("Error: Element with root_id {root_id} not found in the tree.");
            return false;
        }
    }
}
