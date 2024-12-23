use edit_xml::Document;
use skia_safe as skia;
use uuid::Uuid;

use super::{draw_image_in_container, Fill, Kind, Shape};
use crate::math::Rect;
use crate::render::{ImageStore, Renderable};

impl Renderable for Shape {
    fn render(
        &self,
        surface: &mut skia_safe::Surface,
        images: &ImageStore,
        font_provider: &skia::textlayout::TypefaceFontProvider,
    ) -> Result<(), String> {
        let transform = self.transform.to_skia_matrix();

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let center = self.selrect.center();
        let mut matrix = skia::Matrix::new_identity();
        matrix.pre_translate(center);
        matrix.pre_concat(&transform);
        matrix.pre_translate(-center);

        surface.canvas().concat(&matrix);

        match &self.kind {
            Kind::SVGRaw(sr) => {
                println!("sr.content.to_string() {:?}", sr.content.to_string());
                let dom = skia::svg::Dom::from_str(
                    sr.content.to_string(),
                    skia::FontMgr::from(font_provider.clone()),
                )
                .unwrap();
                dom.render(surface.canvas());
            }
            Kind::Path(_) => {
                //TODO: only doing this if we have svg_attrs
                let canvas = skia::svg::Canvas::new(
                    skia::Rect::from_size((
                        self.selrect.right - self.selrect.left + 1.,
                        self.selrect.bottom - self.selrect.top + 1.,
                    )),
                    None,
                );
                // SVG canvas needs positive sizes
                canvas.concat(&skia::Matrix::translate(skia::Point::new(
                    -self.selrect.left,
                    -self.selrect.top,
                )));
                // We can have svg paths without fills with an svg stroke
                if self.fills().len() == 0 {
                    let color = skia::Color::from_argb(0, 0, 0, 0);
                    render_fill(
                        &canvas,
                        images,
                        &Fill::Solid(color),
                        self.selrect,
                        &self.kind,
                    );
                } else {
                    for fill in self.fills().rev() {
                        render_fill(&canvas, images, fill, self.selrect, &self.kind);
                    }
                }
                let svg_data = canvas.end();
                let svg = String::from_utf8_lossy(svg_data.as_bytes());

                let mut doc = Document::parse_str(&svg).unwrap();
                let root = doc.root_element().unwrap();

                if let Some(element) = root.find(&doc, "path") {
                    for (name, value) in &self.svg_attrs {
                        element.set_attribute(&mut doc, name, value);
                    }

                    let svg_mod = doc.write_str().unwrap();
                    let dom = skia::svg::Dom::from_str(
                        svg_mod,
                        skia::FontMgr::from(font_provider.clone()),
                    )
                    .unwrap();

                    surface
                        .canvas()
                        .concat(&skia::Matrix::translate(skia::Point::new(
                            self.selrect.left,
                            self.selrect.top,
                        )));
                    dom.render(surface.canvas());
                }
            }
            _ => {
                let canvas = surface.canvas();
                for fill in self.fills().rev() {
                    render_fill(canvas, images, fill, self.selrect, &self.kind);
                }
            }
        };
        Ok(())
    }

    fn blend_mode(&self) -> crate::render::BlendMode {
        self.blend_mode
    }

    fn opacity(&self) -> f32 {
        self.opacity
    }

    fn hidden(&self) -> bool {
        self.hidden
    }

    fn bounds(&self) -> Rect {
        self.selrect
    }

    fn clip(&self) -> bool {
        self.clip_content
    }

    fn children_ids(&self) -> Vec<Uuid> {
        self.children.clone()
    }

    fn is_recursive(&self) -> bool {
        !matches!(self.kind, Kind::SVGRaw(_))
    }
}

fn render_fill(
    canvas: &skia::Canvas,
    images: &ImageStore,
    fill: &Fill,
    selrect: Rect,
    kind: &Kind,
) {
    match (fill, kind) {
        (Fill::Image(image_fill), kind) => {
            let image = images.get(&image_fill.id());
            if let Some(image) = image {
                draw_image_in_container(
                    canvas,
                    &image,
                    image_fill.size(),
                    kind,
                    &fill.to_paint(&selrect),
                );
            }
        }
        (_, Kind::Rect(rect)) => {
            canvas.draw_rect(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Circle(rect)) => {
            canvas.draw_oval(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Path(path)) => {
            canvas.draw_path(&path.to_skia_path(), &fill.to_paint(&selrect));
        }
        (_, Kind::SVGRaw(_sr)) => {
            // NOOP
        }
    }
}
