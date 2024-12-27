use skia_safe as skia;
use uuid::Uuid;

use super::{Fill, Image, Kind, Path, Shape, Stroke, StrokeKind};
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

        for stroke in self.strokes().rev() {
            render_stroke(
                surface,
                images,
                stroke,
                self.selrect,
                &self.kind,
                self.to_path_transform().as_ref(),
            );
        }

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
                draw_image_fill_in_container(
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

fn render_stroke(
    surface: &mut skia::Surface,
    images: &ImageStore,
    stroke: &Stroke,
    selrect: Rect,
    kind: &Kind,
    path_transform: Option<&skia::Matrix>,
) {
    if let Fill::Image(image_fill) = &stroke.fill {
        if let Some(image) = images.get(&image_fill.id()) {
            draw_image_stroke_in_container(
                surface.canvas(),
                &image,
                stroke,
                image_fill.size(),
                kind,
                &selrect,
                path_transform,
            );
        }
    } else {
        match kind {
            Kind::Rect(rect) => draw_stroke_on_rect(surface.canvas(), stroke, rect, &selrect),
            Kind::Circle(rect) => draw_stroke_on_circle(surface.canvas(), stroke, rect, &selrect),
            Kind::Path(path) => {
                draw_stroke_on_path(surface.canvas(), stroke, path, &selrect, path_transform)
            }
        }
    }
}

fn draw_stroke_on_rect(canvas: &skia::Canvas, stroke: &Stroke, rect: &Rect, selrect: &Rect) {
    // Draw the different kind of strokes for a rect is perry straightforward, we just need apply a stroke to:
    // - The same rect if it's a center stroke
    // - A bigger rect if it's an outer stroke
    // - A smaller rect if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    canvas.draw_rect(&stroke_rect, &stroke.to_paint(selrect));
}

fn draw_stroke_on_circle(canvas: &skia::Canvas, stroke: &Stroke, rect: &Rect, selrect: &Rect) {
    // Draw the different kind of strokes for an oval is perry straightforward, we just need apply a stroke to:
    // - The same oval if it's a center stroke
    // - A bigger oval if it's an outer stroke
    // - A smaller oval if it's an outer stroke
    let stroke_rect = stroke.outer_rect(rect);
    canvas.draw_oval(&stroke_rect, &stroke.to_paint(selrect));
}

fn draw_stroke_on_path(
    canvas: &skia::Canvas,
    stroke: &Stroke,
    path: &Path,
    selrect: &Rect,
    path_transform: Option<&skia::Matrix>,
) {
    let mut skia_path = path.to_skia_path();
    skia_path.transform(path_transform.unwrap());

    let paint_stroke = stroke.to_stroked_paint(selrect);
    // Draw the different kind of strokes for a path requires different strategies:
    match stroke.kind {
        // For inner stroke we draw a center stroke (with double width) and clip to the original path (that way the extra outer stroke is removed)
        StrokeKind::InnerStroke => {
            canvas.clip_path(&skia_path, skia::ClipOp::Intersect, true);
            canvas.draw_path(&skia_path, &paint_stroke);
        }
        // For center stroke we don't need to do anything extra
        StrokeKind::CenterStroke => {
            canvas.draw_path(&skia_path, &paint_stroke);
        }
        // For outer stroke we draw a center stroke (with double width) and use another path with blend mode clear to remove the inner stroke added
        StrokeKind::OuterStroke => {
            let mut paint = skia::Paint::default();
            paint.set_blend_mode(skia::BlendMode::SrcOver);
            paint.set_anti_alias(true);
            let layer_rec = skia::canvas::SaveLayerRec::default().paint(&paint);
            canvas.save_layer(&layer_rec);

            canvas.draw_path(&skia_path, &paint_stroke);

            let mut clear_paint = skia::Paint::default();
            clear_paint.set_blend_mode(skia::BlendMode::Clear);
            clear_paint.set_anti_alias(true);
            canvas.draw_path(&skia_path, &clear_paint);

            canvas.restore();
        }
    }
}

fn calculate_scaled_rect(size: (i32, i32), container: &Rect, delta: f32) -> Rect {
    let (width, height) = (size.0 as f32, size.1 as f32);
    let image_aspect_ratio = width / height;

    // Container size
    let container_width = container.width();
    let container_height = container.height();
    let container_aspect_ratio = container_width / container_height;

    let scale = if image_aspect_ratio > container_aspect_ratio {
        container_height / height
    } else {
        container_width / width
    };

    let scaled_width = width * scale;
    let scaled_height = height * scale;

    Rect::from_xywh(
        container.left - delta - (scaled_width - container_width) / 2.0,
        container.top - delta - (scaled_height - container_height) / 2.0,
        scaled_width + (2. * delta) + (scaled_width - container_width),
        scaled_height + (2. * delta) + (scaled_width - container_width),
    )
}

pub fn draw_image_fill_in_container(
    canvas: &skia::Canvas,
    image: &Image,
    size: (i32, i32),
    kind: &Kind,
    paint: &skia::Paint,
    container: &Rect,
    path_transform: Option<&skia::Matrix>,
) {
    // Compute scaled rect
    let dest_rect = calculate_scaled_rect(size, container, 0.);

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

pub fn draw_image_stroke_in_container(
    canvas: &skia::Canvas,
    image: &Image,
    stroke: &Stroke,
    size: (i32, i32),
    kind: &Kind,
    container: &Rect,
    path_transform: Option<&skia::Matrix>,
) {
    // Helper to handle drawing based on kind
    fn draw_kind(
        canvas: &skia::Canvas,
        kind: &Kind,
        stroke: &Stroke,
        container: &Rect,
        path_transform: Option<&skia::Matrix>,
    ) {
        let outer_rect = stroke.outer_rect(container);
        match kind {
            Kind::Rect(rect) => draw_stroke_on_rect(canvas, stroke, rect, &outer_rect),
            Kind::Circle(rect) => draw_stroke_on_circle(canvas, stroke, rect, &outer_rect),
            Kind::Path(p) => {
                let mut path = p.to_skia_path();
                path.transform(path_transform.unwrap());
                if stroke.kind == StrokeKind::InnerStroke {
                    canvas.clip_path(&path, skia::ClipOp::Intersect, true);
                }
                let paint = stroke.to_stroked_paint(&outer_rect);
                canvas.draw_path(&path, &paint);
            }
        }
    }

    // Save canvas and layer state
    let mut pb = skia::Paint::default();
    pb.set_blend_mode(skia::BlendMode::SrcOver);
    pb.set_anti_alias(true);
    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&pb);
    canvas.save_layer(&layer_rec);

    // Draw the stroke based on the kind, we are using this stroke as a "selector" of the area of the image we want to show.
    draw_kind(canvas, kind, stroke, container, path_transform);

    // Draw the image. We are using now the SrcIn blend mode, so the rendered piece of image will the area of the stroke over the image.
    let mut image_paint = skia::Paint::default();
    image_paint.set_blend_mode(skia::BlendMode::SrcIn);
    image_paint.set_anti_alias(true);
    // Compute scaled rect and clip to it
    let dest_rect = calculate_scaled_rect(size, container, stroke.delta());
    canvas.clip_rect(dest_rect, skia::ClipOp::Intersect, true);
    canvas.draw_image_rect(image, None, dest_rect, &image_paint);

    // Clear outer stroke for paths if necessary. When adding an outer stroke we need to empty the stroke added too in the inner area.
    if let (Kind::Path(p), StrokeKind::OuterStroke) = (kind, &stroke.kind) {
        let mut path = p.to_skia_path();
        path.transform(path_transform.unwrap());
        let mut clear_paint = skia::Paint::default();
        clear_paint.set_blend_mode(skia::BlendMode::Clear);
        clear_paint.set_anti_alias(true);
        canvas.draw_path(&path, &clear_paint);
    }

    // Restore canvas state
    canvas.restore();
}
