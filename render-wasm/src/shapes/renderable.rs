use skia_safe as skia;
use uuid::Uuid;

use super::{Fill, Image, Kind, Shape};
use crate::math::Rect;
use crate::render::{ImageStore, Renderable};

impl Renderable for Shape {
    fn render(&self, surface: &mut skia_safe::Surface, images: &ImageStore) -> Result<(), String> {
        let transform = self.transform.to_skia_matrix();

        // Check transform-matrix code from common/src/app/common/geom/shapes/transforms.cljc
        let center = self.bounds().center();
        let mut matrix = skia::Matrix::new_identity();
        matrix.pre_translate(center);
        matrix.pre_concat(&transform);
        matrix.pre_translate(-center);

        surface.canvas().concat(&matrix);

        for fill in self.fills().rev() {
            render_fill(
                surface,
                images,
                fill,
                self.selrect,
                &self.kind,
                self.to_path_transform().as_ref(),
            );
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
    path_transform: Option<&skia::Matrix>,
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
                    &selrect,
                    path_transform,
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
            surface.canvas().draw_path(
                &path.to_skia_path().transform(path_transform.unwrap()),
                &fill.to_paint(&selrect),
            );
        }
    }
}

pub fn draw_image_in_container(
    canvas: &skia::Canvas,
    image: &Image,
    size: (i32, i32),
    kind: &Kind,
    paint: &skia::Paint,
    container: &Rect,
    path_transform: Option<&skia::Matrix>,
) {
    let width = size.0 as f32;
    let height = size.1 as f32;
    let image_aspect_ratio = width / height;

    // Container size
    let container_width = container.width();
    let container_height = container.height();
    let container_aspect_ratio = container_width / container_height;

    // Calculate scale to ensure the image covers the container
    let scale = if image_aspect_ratio > container_aspect_ratio {
        // Image is wider, scale based on height to cover container
        container_height / height
    } else {
        // Image is taller, scale based on width to cover container
        container_width / width
    };

    // Scaled size of the image
    let scaled_width = width * scale;
    let scaled_height = height * scale;

    let dest_rect = Rect::from_xywh(
        container.left - (scaled_width - container_width) / 2.0,
        container.top - (scaled_height - container_height) / 2.0,
        scaled_width,
        scaled_height,
    );

    // Save the current canvas state
    canvas.save();

    // Set the clipping rectangle to the container bounds
    match kind {
        Kind::Rect(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
        Kind::Circle(_) => {
            let mut oval_path = skia::Path::new();
            oval_path.add_oval(container, None);
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, true);
        }
        Kind::Path(p) => {
            canvas.clip_path(
                &p.to_skia_path().transform(path_transform.unwrap()),
                skia::ClipOp::Intersect,
                true,
            );
        }
    }

    // Draw the image with the calculated destination rectangle
    canvas.draw_image_rect(image, None, dest_rect, &paint);

    // Restore the canvas to remove the clipping
    canvas.restore();
}
