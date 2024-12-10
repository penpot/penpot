use skia_safe as skia;
use uuid::Uuid;

use super::{draw_image_in_container, Fill, Kind, Shape};
use crate::math::Rect;
use crate::render::{ImageStore, Renderable};

impl Renderable for Shape {
    fn render(&self, surface: &mut skia_safe::Surface, images: &ImageStore) -> Result<(), String> {
        let transform = self.transform.to_skia_matrix();

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let center = self.selrect.center();
        let mut matrix = skia::Matrix::new_identity();
        matrix.pre_translate(center);
        matrix.pre_concat(&transform);
        matrix.pre_translate(-center);

        surface.canvas().concat(&matrix);

        for fill in self.fills().rev() {
            render_fill(surface, images, fill, self.selrect, &self.kind);
        }

        let mut paint = skia::Paint::default();
        paint.set_blend_mode(self.blend_mode.into());
        paint.set_alpha_f(self.opacity);

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
}

fn render_fill(
    surface: &mut skia::Surface,
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
                    surface.canvas(),
                    &image,
                    image_fill.size(),
                    kind,
                    &fill.to_paint(&selrect),
                );
            }
        }
        (_, Kind::Rect(rect)) => {
            surface.canvas().draw_rect(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Circle(rect)) => {
            surface.canvas().draw_oval(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Path(path)) => {
            surface
                .canvas()
                .draw_path(&path.to_skia_path(), &fill.to_paint(&selrect));
        }
    }
}
