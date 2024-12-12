use skia::Contains;
use skia_safe as skia;
use std::collections::HashMap;
use uuid::Uuid;

use crate::math;
use crate::view::Viewbox;

mod blend;
mod gpu_state;
mod images;
mod options;

use gpu_state::GpuState;
use options::RenderOptions;

pub use blend::BlendMode;
pub use images::*;

pub trait Renderable {
    fn render(
        &self,
        surface: &mut skia::Surface,
        images: &ImageStore,
        font_provider: &skia::textlayout::TypefaceFontProvider,
    ) -> Result<(), String>;
    fn blend_mode(&self) -> BlendMode;
    fn opacity(&self) -> f32;
    fn bounds(&self) -> math::Rect;
    fn hidden(&self) -> bool;
    fn clip(&self) -> bool;
    fn children_ids(&self) -> Vec<Uuid>;
}

pub(crate) struct CachedSurfaceImage {
    pub image: Image,
    pub viewbox: Viewbox,
    has_all_shapes: bool,
}

impl CachedSurfaceImage {
    fn is_dirty(&self, viewbox: &Viewbox) -> bool {
        !self.has_all_shapes && !self.viewbox.area.contains(viewbox.area)
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub final_surface: skia::Surface,
    pub drawing_surface: skia::Surface,
    pub debug_surface: skia::Surface,
    pub font_provider: skia::textlayout::TypefaceFontProvider,
    pub cached_surface_image: Option<CachedSurfaceImage>,
    options: RenderOptions,
    pub viewbox: Viewbox,
    images: ImageStore,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let mut final_surface = gpu_state.create_target_surface(width, height);
        let drawing_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
        let debug_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();

        let mut font_provider = skia::textlayout::TypefaceFontProvider::new();
        let default_font = skia::FontMgr::default()
            .new_from_data(include_bytes!("fonts/RobotoMono-Regular.ttf"), None)
            .expect("Failed to load font");
        font_provider.register_typeface(default_font, "robotomono-regular");

        RenderState {
            gpu_state,
            final_surface,
            drawing_surface,
            debug_surface,
            cached_surface_image: None,
            font_provider,
            options: RenderOptions::default(),
            viewbox: Viewbox::new(width as f32, height as f32),
            images: ImageStore::new(),
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

    pub fn resize(&mut self, width: i32, height: i32) {
        let dpr_width = (width as f32 * self.options.dpr()).floor() as i32;
        let dpr_height = (height as f32 * self.options.dpr()).floor() as i32;

        let surface = self.gpu_state.create_target_surface(dpr_width, dpr_height);
        self.final_surface = surface;
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
            .flush_and_submit_surface(&mut self.final_surface, None)
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
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
        self.final_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
        self.debug_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }

    pub fn render_single_element(&mut self, element: &impl Renderable) {
        element
            .render(&mut self.drawing_surface, &self.images, &self.font_provider)
            .unwrap();

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(element.blend_mode().into());
        paint.set_alpha_f(element.opacity());

        self.drawing_surface.draw(
            &mut self.final_surface.canvas(),
            (0.0, 0.0),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            Some(&paint),
        );
        // self.drawing_surface.draw(
        //     &svg_canvas,
        //     (0.0, 0.0),
        //     skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
        //     Some(&paint),
        // );

        self.drawing_surface
            .canvas()
            .clear(skia::Color::TRANSPARENT);
    }

    pub fn navigate(&mut self, tree: &HashMap<Uuid, impl Renderable>) -> Result<(), String> {
        if let Some(cached_surface_image) = self.cached_surface_image.as_ref() {
            if cached_surface_image.is_dirty(&self.viewbox) {
                self.render_all(tree, true);
            } else {
                self.render_all_from_cache()?;
            }
        }

        Ok(())
    }

    pub fn render_all(
        &mut self,
        tree: &HashMap<Uuid, impl Renderable>,
        generate_cached_surface_image: bool,
    ) {
        self.reset_canvas();
        self.scale(
            self.viewbox.zoom * self.options.dpr(),
            self.viewbox.zoom * self.options.dpr(),
        );
        self.translate(self.viewbox.pan_x, self.viewbox.pan_y);

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

    fn render_debug_view(&mut self) {
        let mut paint = skia::Paint::default();
        paint.set_style(skia::PaintStyle::Stroke);
        paint.set_color(skia::Color::from_argb(255, 255, 0, 255));
        paint.set_stroke_width(1.);

        let mut scaled_rect = self.viewbox.area.clone();
        let x = 100. + scaled_rect.x() * 0.2;
        let y = 100. + scaled_rect.y() * 0.2;
        let width = scaled_rect.width() * 0.2;
        let height = scaled_rect.height() * 0.2;
        scaled_rect.set_xywh(x, y, width, height);

        self.debug_surface.canvas().draw_rect(scaled_rect, &paint);
    }

    fn render_debug_element(&mut self, element: &impl Renderable, intersected: bool) {
        let mut paint = skia::Paint::default();
        paint.set_style(skia::PaintStyle::Stroke);
        paint.set_color(if intersected {
            skia::Color::from_argb(255, 255, 255, 0)
        } else {
            skia::Color::from_argb(255, 0, 255, 255)
        });
        paint.set_stroke_width(1.);

        let mut scaled_rect = element.bounds();
        let x = 100. + scaled_rect.x() * 0.2;
        let y = 100. + scaled_rect.y() * 0.2;
        let width = scaled_rect.width() * 0.2;
        let height = scaled_rect.height() * 0.2;
        scaled_rect.set_xywh(x, y, width, height);

        self.debug_surface.canvas().draw_rect(scaled_rect, &paint);
    }

    fn render_debug(&mut self) {
        let paint = skia::Paint::default();
        self.render_debug_view();
        self.debug_surface.draw(
            &mut self.final_surface.canvas(),
            (0.0, 0.0),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            Some(&paint),
        );
    }

    // Returns a boolean indicating if the viewbox contains the rendered shapes
    fn render_shape_tree(&mut self, root_id: &Uuid, tree: &HashMap<Uuid, impl Renderable>) -> bool {
        let element = tree.get(&root_id).unwrap();
        let mut is_complete = self.viewbox.area.contains(element.bounds());

        if !root_id.is_nil() {
            if !element.bounds().intersects(self.viewbox.area) || element.hidden() {
                self.render_debug_element(element, false);
                // TODO: This means that not all the shapes are renderer so we
                // need to call a render_all on the zoom out.
                return is_complete; // TODO return is_complete or return false??
            } else {
                self.render_debug_element(element, true);
            }
        }

        // This is needed so the next non-children shape does not carry this shape's transform
        self.final_surface.canvas().save();
        self.drawing_surface.canvas().save();

        if !root_id.is_nil() {
            self.render_single_element(element);
            if element.clip() {
                self.drawing_surface.canvas().clip_rect(
                    element.bounds(),
                    skia::ClipOp::Intersect,
                    true,
                );
            }
        }

        // draw all the children shapes
        /*
        if !matches!(&(element as Shape).kind, Kind::SVGRaw(_)) {
            for id in element.children_ids() {
                is_complete = self.render_shape_tree(&id, tree) && is_complete;
            }
        }
        */
        for id in element.children_ids() {
            is_complete = self.render_shape_tree(&id, tree) && is_complete;
        }

        self.final_surface.canvas().restore();
        self.drawing_surface.canvas().restore();

        return is_complete;
    }
}
