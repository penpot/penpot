use crate::math;
use skia_safe as skia;
use std::collections::HashMap;
use uuid::Uuid;

use crate::render::BlendMode;

mod blurs;
mod bools;
mod fills;
mod matrix;
mod paths;
mod shadows;
mod strokes;
mod svgraw;

pub use blurs::*;
pub use bools::*;
pub use fills::*;
use matrix::*;
pub use paths::*;
pub use shadows::*;
pub use strokes::*;
pub use svgraw::*;

pub type CornerRadius = skia::Point;
pub type Corners = [CornerRadius; 4];

#[derive(Debug, Clone, PartialEq)]
pub enum Kind {
    Rect(math::Rect, Option<Corners>),
    Circle(math::Rect),
    Path(Path),
    Bool(BoolType, Path),
    SVGRaw(SVGRaw),
}

pub type Color = skia::Color;

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct Shape {
    pub id: Uuid,
    pub children: Vec<Uuid>,
    pub kind: Kind,
    pub selrect: math::Rect,
    pub transform: Matrix,
    pub rotation: f32,
    pub clip_content: bool,
    pub fills: Vec<Fill>,
    pub strokes: Vec<Stroke>,
    pub blend_mode: BlendMode,
    pub blur: Blur,
    pub opacity: f32,
    pub hidden: bool,
    pub svg: Option<skia::svg::Dom>,
    pub svg_attrs: HashMap<String, String>,
    shadows: Vec<Shadow>,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            children: Vec::<Uuid>::new(),
            kind: Kind::Rect(math::Rect::new_empty(), None),
            selrect: math::Rect::new_empty(),
            transform: Matrix::identity(),
            rotation: 0.,
            clip_content: true,
            fills: vec![],
            strokes: vec![],
            blend_mode: BlendMode::default(),
            opacity: 1.,
            hidden: false,
            blur: Blur::default(),
            svg: None,
            svg_attrs: HashMap::new(),
            shadows: vec![],
        }
    }

    pub fn kind(&self) -> Kind {
        self.kind.clone()
    }

    pub fn set_selrect(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        self.selrect.set_ltrb(left, top, right, bottom);
        match self.kind {
            Kind::Rect(_, corners) => {
                self.kind = Kind::Rect(self.selrect.to_owned(), corners);
            }
            Kind::Circle(_) => {
                self.kind = Kind::Circle(self.selrect.to_owned());
            }
            _ => {}
        };
    }

    pub fn set_kind(&mut self, kind: Kind) {
        self.kind = kind;
    }

    pub fn set_clip(&mut self, value: bool) {
        self.clip_content = value;
    }

    pub fn set_rotation(&mut self, angle: f32) {
        self.rotation = angle;
    }

    pub fn set_transform(&mut self, a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
        self.transform = Matrix::new(a, b, c, d, e, f);
    }

    pub fn set_opacity(&mut self, opacity: f32) {
        self.opacity = opacity;
    }

    pub fn set_hidden(&mut self, value: bool) {
        self.hidden = value;
    }

    pub fn set_blur(&mut self, blur_type: u8, hidden: bool, value: f32) {
        self.blur = Blur::new(blur_type, hidden, value);
    }

    pub fn add_child(&mut self, id: Uuid) {
        self.children.push(id);
    }

    pub fn clear_children(&mut self) {
        self.children.clear();
    }

    pub fn fills(&self) -> std::slice::Iter<Fill> {
        self.fills.iter()
    }

    pub fn add_fill(&mut self, f: Fill) {
        self.fills.push(f)
    }

    pub fn clear_fills(&mut self) {
        self.fills.clear();
    }

    pub fn add_fill_gradient_stops(&mut self, buffer: Vec<RawStopData>) -> Result<(), String> {
        let fill = self.fills.last_mut().ok_or("Shape has no fills")?;
        let gradient = match fill {
            Fill::LinearGradient(g) => Ok(g),
            Fill::RadialGradient(g) => Ok(g),
            _ => Err("Active fill is not a gradient"),
        }?;

        for stop in buffer.into_iter() {
            gradient.add_stop(stop.color(), stop.offset());
        }

        Ok(())
    }

    pub fn strokes(&self) -> std::slice::Iter<Stroke> {
        self.strokes.iter()
    }

    pub fn add_stroke(&mut self, s: Stroke) {
        self.strokes.push(s)
    }

    pub fn set_stroke_fill(&mut self, f: Fill) -> Result<(), String> {
        let stroke = self.strokes.last_mut().ok_or("Shape has no strokes")?;
        stroke.fill = f;
        Ok(())
    }

    pub fn add_stroke_gradient_stops(&mut self, buffer: Vec<RawStopData>) -> Result<(), String> {
        let stroke = self.strokes.last_mut().ok_or("Shape has no strokes")?;
        let fill = &mut stroke.fill;
        let gradient = match fill {
            Fill::LinearGradient(g) => Ok(g),
            Fill::RadialGradient(g) => Ok(g),
            _ => Err("Active stroke is not a gradient"),
        }?;

        for stop in buffer.into_iter() {
            gradient.add_stop(stop.color(), stop.offset());
        }

        Ok(())
    }

    pub fn clear_strokes(&mut self) {
        self.strokes.clear();
    }

    pub fn set_path_segments(&mut self, buffer: Vec<RawPathData>) -> Result<(), String> {
        let p = Path::try_from(buffer)?;
        let kind = match &self.kind {
            Kind::Bool(bool_type, _) => Kind::Bool(*bool_type, p),
            _ => Kind::Path(p),
        };
        self.kind = kind;

        Ok(())
    }

    pub fn set_path_attr(&mut self, name: String, value: String) {
        match &mut self.kind {
            Kind::Path(_) => {
                self.set_svg_attr(name, value);
            }
            Kind::Rect(_, _) | Kind::Circle(_) | Kind::SVGRaw(_) | Kind::Bool(_, _) => todo!(),
        };
    }

    pub fn set_svg_raw_content(&mut self, content: String) -> Result<(), String> {
        self.kind = Kind::SVGRaw(SVGRaw::from_content(content));
        Ok(())
    }

    pub fn set_blend_mode(&mut self, mode: BlendMode) {
        self.blend_mode = mode;
    }

    pub fn set_bool_type(&mut self, bool_type: BoolType) {
        let kind = match &self.kind {
            Kind::Bool(_, path) => Kind::Bool(bool_type, path.clone()),
            _ => Kind::Bool(bool_type, Path::default()),
        };

        self.kind = kind;
    }

    pub fn set_corners(&mut self, raw_corners: (f32, f32, f32, f32)) {
        let (r1, r2, r3, r4) = raw_corners;
        let are_straight_corners = r1.abs() <= f32::EPSILON
            && r2.abs() <= f32::EPSILON
            && r3.abs() <= f32::EPSILON
            && r4.abs() <= f32::EPSILON;

        let corners = if are_straight_corners {
            None
        } else {
            Some([
                (r1, r1).into(),
                (r2, r2).into(),
                (r3, r3).into(),
                (r4, r4).into(),
            ])
        };

        self.kind = Kind::Rect(self.selrect, corners);
    }

    pub fn set_svg(&mut self, svg: skia::svg::Dom) {
        self.svg = Some(svg);
    }

    pub fn set_svg_attr(&mut self, name: String, value: String) {
        self.svg_attrs.insert(name, value);
    }

    pub fn blend_mode(&self) -> crate::render::BlendMode {
        self.blend_mode
    }

    pub fn opacity(&self) -> f32 {
        self.opacity
    }

    pub fn hidden(&self) -> bool {
        self.hidden
    }

    pub fn bounds(&self) -> math::Rect {
        self.selrect
    }

    pub fn clip(&self) -> bool {
        self.clip_content
    }

    pub fn children_ids(&self) -> Vec<Uuid> {
        if let Kind::Bool(_, _) = self.kind {
            vec![]
        } else {
            self.children.clone()
        }
    }

    pub fn image_filter(&self, scale: f32) -> Option<skia::ImageFilter> {
        if !self.blur.hidden {
            match self.blur.blur_type {
                BlurType::None => None,
                BlurType::Layer => skia::image_filters::blur(
                    (self.blur.value * scale, self.blur.value * scale),
                    None,
                    None,
                    None,
                ),
            }
        } else {
            None
        }
    }

    pub fn is_recursive(&self) -> bool {
        !matches!(self.kind, Kind::SVGRaw(_))
    }

    pub fn add_shadow(&mut self, shadow: Shadow) {
        self.shadows.push(shadow);
    }

    pub fn clear_shadows(&mut self) {
        self.shadows.clear();
    }

    pub fn drop_shadows(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .filter(|shadow| shadow.style() == ShadowStyle::Drop)
    }

    pub fn to_path_transform(&self) -> Option<skia::Matrix> {
        match self.kind {
            Kind::Path(_) | Kind::Bool(_, _) => {
                let center = self.bounds().center();
                let mut matrix = skia::Matrix::new_identity();
                matrix.pre_translate(center);
                matrix.pre_concat(&self.transform.no_translation().to_skia_matrix().invert()?);
                matrix.pre_translate(-center);

                Some(matrix)
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn any_shape() -> Shape {
        Shape::new(Uuid::nil())
    }

    #[test]
    fn add_fill_pushes_a_new_fill() {
        let mut shape = any_shape();
        assert_eq!(shape.fills.len(), 0);

        shape.add_fill(Fill::Solid(Color::TRANSPARENT));
        assert_eq!(shape.fills.get(0), Some(&Fill::Solid(Color::TRANSPARENT)))
    }
}
