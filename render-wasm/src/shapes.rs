use skia_safe::{self as skia, Matrix, Point, Rect};

use std::collections::HashMap;
use uuid::Uuid;

use crate::render::BlendMode;

mod blurs;
mod bools;
mod fills;
mod groups;
mod modifiers;
mod paths;
mod shadows;
mod strokes;
mod svgraw;
mod transform;

pub use blurs::*;
pub use bools::*;
pub use fills::*;
pub use groups::*;
pub use modifiers::*;
pub use paths::*;
pub use shadows::*;
pub use strokes::*;
pub use svgraw::*;
pub use transform::*;

use crate::math::Bounds;

pub type CornerRadius = Point;
pub type Corners = [CornerRadius; 4];

#[derive(Debug, Clone, PartialEq)]
pub enum Type {
    Frame,
    Group,
    Bool,
    Rect,
    Path,
    Text,
    Circle,
    SvgRaw,
    Image,
}

impl Type {
    pub fn from(value: u8) -> Self {
        match value {
            0 => Type::Frame,
            1 => Type::Group,
            2 => Type::Bool,
            3 => Type::Rect,
            4 => Type::Path,
            5 => Type::Text,
            6 => Type::Circle,
            7 => Type::SvgRaw,
            8 => Type::Image,
            _ => Type::Rect,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Kind {
    Rect(Rect, Option<Corners>),
    Circle(Rect),
    Path(Path),
    Bool(BoolType, Path),
    SVGRaw(SVGRaw),
    Group(Group),
}

#[derive(Debug, Clone, PartialEq)]
pub enum ConstraintH {
    Left,
    Right,
    LeftRight,
    Center,
    Scale,
}

impl ConstraintH {
    pub fn from(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Left),
            1 => Some(Self::Right),
            2 => Some(Self::LeftRight),
            3 => Some(Self::Center),
            4 => Some(Self::Scale),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum ConstraintV {
    Top,
    Bottom,
    TopBottom,
    Center,
    Scale,
}

impl ConstraintV {
    pub fn from(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Top),
            1 => Some(Self::Bottom),
            2 => Some(Self::TopBottom),
            3 => Some(Self::Center),
            4 => Some(Self::Scale),
            _ => None,
        }
    }
}

pub type Color = skia::Color;

#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct Shape {
    pub id: Uuid,
    pub shape_type: Type,
    pub children: Vec<Uuid>,
    pub kind: Kind,
    pub selrect: Rect,
    pub transform: Matrix,
    pub rotation: f32,
    pub constraint_h: Option<ConstraintH>,
    pub constraint_v: Option<ConstraintV>,
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
            shape_type: Type::Rect,
            children: Vec::<Uuid>::new(),
            kind: Kind::Rect(Rect::new_empty(), None),
            selrect: Rect::new_empty(),
            transform: Matrix::default(),
            rotation: 0.,
            constraint_h: None,
            constraint_v: None,
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

    pub fn set_shape_type(&mut self, shape_type: Type) {
        self.shape_type = shape_type;
    }

    pub fn is_frame(&self) -> bool {
        self.shape_type == Type::Frame
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

    pub fn kind(&self) -> Kind {
        self.kind.clone()
    }

    pub fn set_clip(&mut self, value: bool) {
        self.clip_content = value;
    }

    pub fn set_rotation(&mut self, angle: f32) {
        self.rotation = angle;
    }

    pub fn set_transform(&mut self, a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
        self.transform = Matrix::new_all(a, c, e, b, d, f, 0.0, 0.0, 1.0);
    }

    pub fn set_opacity(&mut self, opacity: f32) {
        self.opacity = opacity;
    }

    pub fn set_constraint_h(&mut self, constraint: Option<ConstraintH>) {
        self.constraint_h = constraint;
    }

    pub fn constraint_h(&self, default: ConstraintH) -> ConstraintH {
        self.constraint_h.clone().unwrap_or(default)
    }

    pub fn set_constraint_v(&mut self, constraint: Option<ConstraintV>) {
        self.constraint_v = constraint;
    }

    pub fn constraint_v(&self, default: ConstraintV) -> ConstraintV {
        self.constraint_v.clone().unwrap_or(default)
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
            Kind::Rect(_, _)
            | Kind::Circle(_)
            | Kind::SVGRaw(_)
            | Kind::Bool(_, _)
            | Kind::Group(_) => unreachable!("This shape should have path attrs"),
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

    // TODO: Maybe store this inside the shape
    pub fn bounds(&self) -> Bounds {
        let mut bounds = Bounds::new(
            Point::new(self.selrect.x(), self.selrect.y()),
            Point::new(self.selrect.x() + self.selrect.width(), self.selrect.y()),
            Point::new(
                self.selrect.x() + self.selrect.width(),
                self.selrect.y() + self.selrect.height(),
            ),
            Point::new(self.selrect.x(), self.selrect.y() + self.selrect.height()),
        );

        let center = self.center();
        let mut matrix = self.transform.clone();
        matrix.post_translate(center);
        matrix.pre_translate(-center);

        bounds.transform_mut(&matrix);

        bounds
    }

    pub fn selrect(&self) -> Rect {
        self.selrect
    }

    pub fn center(&self) -> Point {
        self.selrect.center()
    }

    pub fn clip(&self) -> bool {
        self.clip_content
    }

    pub fn mask_id(&self) -> Option<&Uuid> {
        self.children.first()
    }

    pub fn children_ids(&self) -> Vec<Uuid> {
        if let Kind::Bool(_, _) = self.kind {
            vec![]
        } else if let Kind::Group(group) = self.kind {
            if group.masked {
                self.children[1..self.children.len()].to_vec()
            } else {
                self.children.clone()
            }
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

    pub fn inner_shadows(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .filter(|shadow| shadow.style() == ShadowStyle::Inner)
    }

    pub fn to_path_transform(&self) -> Option<Matrix> {
        match self.kind {
            Kind::Path(_) | Kind::Bool(_, _) => {
                let center = self.center();
                let mut matrix = Matrix::new_identity();
                matrix.pre_translate(center);
                matrix.pre_concat(&self.transform.invert()?);
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
