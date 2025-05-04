use skia_safe::{self as skia};

use crate::render::BlendMode;
use crate::uuid::Uuid;
use std::collections::{HashMap, HashSet};

mod blurs;
mod bools;
mod corners;
mod fills;
mod fonts;
mod frames;
mod groups;
mod layouts;
mod modifiers;
mod paths;
mod rects;
mod shadows;
mod strokes;
mod svgraw;
mod text;
mod transform;

pub use blurs::*;
pub use bools::*;
pub use corners::*;
pub use fills::*;
pub use fonts::*;
pub use frames::*;
pub use groups::*;
pub use layouts::*;
pub use modifiers::*;
pub use paths::*;
pub use rects::*;
pub use shadows::*;
pub use strokes::*;
pub use svgraw::*;
pub use text::*;
pub use transform::*;

use crate::math;
use crate::math::{Bounds, Matrix, Point};
use indexmap::IndexSet;

const MIN_VISIBLE_SIZE: f32 = 2.0;
const ANTIALIAS_THRESHOLD: f32 = 15.0;

#[derive(Debug, Clone, PartialEq)]
pub enum Type {
    Frame(Frame),
    Group(Group),
    Bool(Bool),
    Rect(Rect),
    Path(Path),
    Circle,
    SVGRaw(SVGRaw),
    Text(TextContent),
}

impl Type {
    pub fn from(value: u8) -> Self {
        match value {
            0 => Type::Frame(Frame::default()),
            1 => Type::Group(Group::default()),
            2 => Type::Bool(Bool::default()),
            3 => Type::Rect(Rect::default()),
            4 => Type::Path(Path::default()),
            5 => Type::Text(TextContent::default()),
            6 => Type::Circle,
            7 => Type::SVGRaw(SVGRaw::default()),
            _ => Type::Rect(Rect::default()),
        }
    }

    pub fn corners(&self) -> Option<Corners> {
        match self {
            Type::Rect(Rect { corners, .. }) => *corners,
            Type::Frame(Frame { corners, .. }) => *corners,
            _ => None,
        }
    }

    pub fn set_corners(&mut self, corners: Corners) {
        match self {
            Type::Rect(data) => {
                data.corners = Some(corners);
            }
            Type::Frame(data) => {
                data.corners = Some(corners);
            }
            _ => {}
        }
    }

    pub fn path(&self) -> Option<&Path> {
        match self {
            Type::Path(path) => Some(path),
            Type::Bool(Bool { path, .. }) => Some(path),
            _ => None,
        }
    }

    pub fn path_mut(&mut self) -> Option<&mut Path> {
        match self {
            Type::Path(path) => Some(path),
            Type::Bool(Bool { path, .. }) => Some(path),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
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

#[derive(Debug, Clone, PartialEq, Copy)]
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
pub struct Shape {
    pub id: Uuid,
    pub parent_id: Option<Uuid>,
    pub shape_type: Type,
    pub children: IndexSet<Uuid>,
    pub selrect: math::Rect,
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
    pub shadows: Vec<Shadow>,
    pub layout_item: Option<LayoutItem>,
}

impl Shape {
    pub fn new(id: Uuid) -> Self {
        Self {
            id,
            parent_id: None,
            shape_type: Type::Rect(Rect::default()),
            children: IndexSet::<Uuid>::new(),
            selrect: math::Rect::new_empty(),
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
            layout_item: None,
        }
    }

    pub fn set_parent(&mut self, id: Uuid) {
        self.parent_id = Some(id);
    }

    pub fn set_shape_type(&mut self, shape_type: Type) {
        self.shape_type = shape_type;
    }

    #[allow(dead_code)]
    pub fn is_frame(&self) -> bool {
        matches!(self.shape_type, Type::Frame(_))
    }

    pub fn is_group_like(&self) -> bool {
        matches!(self.shape_type, Type::Group(_)) || matches!(self.shape_type, Type::Bool(_))
    }

    pub fn has_layout(&self) -> bool {
        match self.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => true,
            _ => false,
        }
    }

    pub fn set_selrect(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        self.selrect.set_ltrb(left, top, right, bottom);
        match self.shape_type {
            Type::Text(ref mut text) => {
                text.set_xywh(left, top, right - left, bottom - top);
            }
            _ => {}
        }
    }

    pub fn set_masked(&mut self, masked: bool) {
        match &mut self.shape_type {
            Type::Group(data) => {
                data.masked = masked;
            }
            _ => {}
        }
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

    pub fn set_flex_layout_child_data(
        &mut self,
        margin_top: f32,
        margin_right: f32,
        margin_bottom: f32,
        margin_left: f32,
        h_sizing: Sizing,
        v_sizing: Sizing,
        max_h: Option<f32>,
        min_h: Option<f32>,
        max_w: Option<f32>,
        min_w: Option<f32>,
        align_self: Option<AlignSelf>,
        is_absolute: bool,
        z_index: i32,
    ) {
        self.layout_item = Some(LayoutItem {
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing,
            v_sizing,
            max_h,
            min_h,
            max_w,
            min_w,
            is_absolute,
            z_index,
            align_self,
        });
    }

    pub fn set_flex_layout_data(
        &mut self,
        direction: FlexDirection,
        row_gap: f32,
        column_gap: f32,
        align_items: AlignItems,
        align_content: AlignContent,
        justify_items: JustifyItems,
        justify_content: JustifyContent,
        wrap_type: WrapType,
        padding_top: f32,
        padding_right: f32,
        padding_bottom: f32,
        padding_left: f32,
    ) {
        match &mut self.shape_type {
            Type::Frame(data) => {
                let layout_data = LayoutData {
                    align_items,
                    align_content,
                    justify_items,
                    justify_content,
                    padding_top,
                    padding_right,
                    padding_bottom,
                    padding_left,
                    row_gap,
                    column_gap,
                };

                let flex_data = FlexData {
                    direction,
                    wrap_type,
                };

                data.layout = Some(Layout::FlexLayout(layout_data, flex_data));
            }
            _ => {}
        }
    }

    pub fn set_grid_layout_data(
        &mut self,
        direction: GridDirection,
        row_gap: f32,
        column_gap: f32,
        align_items: AlignItems,
        align_content: AlignContent,
        justify_items: JustifyItems,
        justify_content: JustifyContent,
        padding_top: f32,
        padding_right: f32,
        padding_bottom: f32,
        padding_left: f32,
    ) {
        match &mut self.shape_type {
            Type::Frame(data) => {
                let layout_data = LayoutData {
                    align_items,
                    align_content,
                    justify_items,
                    justify_content,
                    padding_top,
                    padding_right,
                    padding_bottom,
                    padding_left,
                    row_gap,
                    column_gap,
                };

                let mut grid_data = GridData::default();
                grid_data.direction = direction;
                data.layout = Some(Layout::GridLayout(layout_data, grid_data));
            }
            _ => {}
        }
    }

    pub fn set_grid_columns(&mut self, tracks: Vec<RawGridTrack>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.columns = tracks.iter().map(GridTrack::from_raw).collect();
    }

    pub fn set_grid_rows(&mut self, tracks: Vec<RawGridTrack>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.rows = tracks.iter().map(GridTrack::from_raw).collect();
    }

    pub fn set_grid_cells(&mut self, cells: Vec<RawGridCell>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.cells = cells.iter().map(GridCell::from_raw).collect();
    }

    pub fn set_blur(&mut self, blur_type: u8, hidden: bool, value: f32) {
        self.blur = Blur::new(blur_type, hidden, value);
    }

    pub fn add_child(&mut self, id: Uuid) {
        self.children.insert(id);
    }

    pub fn compute_children_differences(
        &mut self,
        children: &IndexSet<Uuid>,
    ) -> (IndexSet<Uuid>, IndexSet<Uuid>) {
        let added = children.difference(&self.children).cloned().collect();
        let removed = self.children.difference(children).cloned().collect();
        (added, removed)
    }

    pub fn fills(&self) -> std::slice::Iter<Fill> {
        self.fills.iter()
    }

    pub fn add_fill(&mut self, f: Fill) {
        self.fills.push(f);
    }

    pub fn clear_fills(&mut self) {
        self.fills.clear();
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

    pub fn clear_strokes(&mut self) {
        self.strokes.clear();
    }

    pub fn set_path_segments(&mut self, buffer: Vec<RawPathData>) -> Result<(), String> {
        let path = Path::try_from(buffer)?;

        match &mut self.shape_type {
            Type::Bool(Bool { bool_type, .. }) => {
                self.shape_type = Type::Bool(Bool {
                    bool_type: *bool_type,
                    path,
                });
            }
            Type::Path(_) => {
                self.shape_type = Type::Path(path);
            }
            _ => {}
        };
        Ok(())
    }

    pub fn set_path_attr(&mut self, name: String, value: String) {
        match self.shape_type {
            Type::Path(_) => {
                self.set_svg_attr(name, value);
            }
            _ => unreachable!("This shape should have path attrs"),
        };
    }

    pub fn set_svg_raw_content(&mut self, content: String) -> Result<(), String> {
        self.shape_type = Type::SVGRaw(SVGRaw::from_content(content));
        Ok(())
    }

    pub fn set_blend_mode(&mut self, mode: BlendMode) {
        self.blend_mode = mode;
    }

    pub fn set_bool_type(&mut self, bool_type: BoolType) {
        self.shape_type = match &self.shape_type {
            Type::Bool(Bool { path, .. }) => Type::Bool(Bool {
                bool_type,
                path: path.clone(),
            }),
            _ => Type::Bool(Bool {
                bool_type,
                path: Path::default(),
            }),
        };
    }

    pub fn set_corners(&mut self, raw_corners: (f32, f32, f32, f32)) {
        if let Some(corners) = make_corners(raw_corners) {
            self.shape_type.set_corners(corners);
        }
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

    #[allow(dead_code)]
    pub fn width(&self) -> f32 {
        self.selrect.width()
    }

    pub fn visually_insignificant(&self, scale: f32) -> bool {
        self.selrect.width() * scale < MIN_VISIBLE_SIZE
            || self.selrect.height() * scale < MIN_VISIBLE_SIZE
    }

    pub fn should_use_antialias(&self, scale: f32) -> bool {
        self.selrect.width() * scale > ANTIALIAS_THRESHOLD
            || self.selrect.height() * scale > ANTIALIAS_THRESHOLD
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

    pub fn selrect(&self) -> math::Rect {
        self.selrect
    }

    pub fn extrect(&self) -> math::Rect {
        let mut rect = self.bounds().to_rect();
        for shadow in self.shadows.iter() {
            let (x, y) = shadow.offset;
            let mut shadow_rect = rect.clone();
            shadow_rect.left += x;
            shadow_rect.right += x;
            shadow_rect.top += y;
            shadow_rect.bottom += y;

            shadow_rect.left -= shadow.blur;
            shadow_rect.top -= shadow.blur;
            shadow_rect.right += shadow.blur;
            shadow_rect.bottom += shadow.blur;

            rect.join(shadow_rect);
        }
        if self.blur.blur_type != blurs::BlurType::None {
            rect.left -= self.blur.value;
            rect.top -= self.blur.value;
            rect.right += self.blur.value;
            rect.bottom += self.blur.value;
        }

        rect
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

    pub fn children_ids(&self) -> IndexSet<Uuid> {
        if let Type::Bool(_) = self.shape_type {
            IndexSet::<Uuid>::new()
        } else if let Type::Group(group) = self.shape_type {
            if group.masked {
                self.children.iter().skip(1).cloned().collect()
            } else {
                self.children.clone().into_iter().collect()
            }
        } else {
            self.children.clone().into_iter().collect()
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
        matches!(
            self.shape_type,
            Type::Frame(_) | Type::Group(_) | Type::Bool(_)
        )
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
        match self.shape_type {
            Type::Path(_) | Type::Bool(_) => {
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

    pub fn add_paragraph(&mut self, paragraph: Paragraph) -> Result<(), String> {
        match self.shape_type {
            Type::Text(ref mut text) => {
                text.add_paragraph(paragraph);
                Ok(())
            }
            _ => Err("Shape is not a text".to_string()),
        }
    }

    pub fn clear_text(&mut self) {
        match self.shape_type {
            Type::Text(_) => {
                let new_text_content = TextContent::new(self.selrect);
                self.shape_type = Type::Text(new_text_content);
            }
            _ => {}
        }
    }

    pub fn get_skia_path(&self) -> Option<skia::Path> {
        if let Some(path) = self.shape_type.path() {
            let mut skia_path = path.to_skia_path();
            if let Some(path_transform) = self.to_path_transform() {
                skia_path.transform(&path_transform);
            }
            if let Some("evenodd") = self.svg_attrs.get("fill-rule").map(String::as_str) {
                skia_path.set_fill_type(skia::PathFillType::EvenOdd);
            }
            Some(skia_path)
        } else {
            None
        }
    }

    fn transform_selrect(&mut self, transform: &Matrix) {
        let mut center = self.selrect.center();
        center = transform.map_point(center);

        let bounds = self.bounds().transform(&transform);
        self.transform = bounds.transform_matrix().unwrap_or(Matrix::default());

        let width = bounds.width();
        let height = bounds.height();

        let new_selrect = math::Rect::from_xywh(
            center.x - width / 2.0,
            center.y - height / 2.0,
            width,
            height,
        );
        self.selrect = new_selrect;
    }

    pub fn apply_transform(&mut self, transform: &Matrix) {
        self.transform_selrect(&transform);
        match &mut self.shape_type {
            shape_type @ (Type::Path(_) | Type::Bool(_)) => {
                if let Some(path) = shape_type.path_mut() {
                    path.transform(&transform);
                }
            }
            _ => {}
        }
    }

    pub fn is_absolute(&self) -> bool {
        match &self.layout_item {
            Some(LayoutItem { is_absolute, .. }) => *is_absolute,
            _ => false,
        }
    }

    pub fn z_index(&self) -> i32 {
        match &self.layout_item {
            Some(LayoutItem { z_index, .. }) => *z_index,
            _ => 0,
        }
    }

    pub fn is_layout_vertical_auto(&self) -> bool {
        match &self.layout_item {
            Some(LayoutItem { v_sizing, .. }) => v_sizing == &Sizing::Auto,
            _ => false,
        }
    }

    pub fn is_layout_vertical_fill(&self) -> bool {
        match &self.layout_item {
            Some(LayoutItem { v_sizing, .. }) => v_sizing == &Sizing::Fill,
            _ => false,
        }
    }

    pub fn is_layout_horizontal_auto(&self) -> bool {
        match &self.layout_item {
            Some(LayoutItem { h_sizing, .. }) => h_sizing == &Sizing::Auto,
            _ => false,
        }
    }

    pub fn is_layout_horizontal_fill(&self) -> bool {
        match &self.layout_item {
            Some(LayoutItem { h_sizing, .. }) => h_sizing == &Sizing::Fill,
            _ => false,
        }
    }

    pub fn has_fills(&self) -> bool {
        !self.fills.is_empty()
    }
}

/*
  Returns the list of children taking into account the structure modifiers
*/
pub fn modified_children_ids(
    element: &Shape,
    structure: Option<&Vec<StructureEntry>>,
) -> IndexSet<Uuid> {
    if let Some(structure) = structure {
        let mut result: Vec<Uuid> = Vec::from_iter(element.children_ids().iter().map(|id| *id));
        let mut to_remove = HashSet::<&Uuid>::new();

        for st in structure {
            match st.entry_type {
                StructureEntryType::AddChild => {
                    result.insert(st.index as usize, st.id);
                }
                StructureEntryType::RemoveChild => {
                    to_remove.insert(&st.id);
                }
            }
        }

        let ret: IndexSet<Uuid> = result
            .iter()
            .filter(|id| !to_remove.contains(id))
            .map(|id| *id)
            .collect();

        ret
    } else {
        element.children_ids()
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

    #[test]
    fn test_set_corners() {
        let mut shape = any_shape();
        shape.set_corners((10.0, 20.0, 30.0, 40.0));
        if let Type::Rect(Rect { corners, .. }) = shape.shape_type {
            assert_eq!(
                corners,
                Some([
                    Point { x: 10.0, y: 10.0 },
                    Point { x: 20.0, y: 20.0 },
                    Point { x: 30.0, y: 30.0 },
                    Point { x: 40.0, y: 40.0 }
                ])
            );
        } else {
            assert!(false);
        }
    }

    #[test]
    fn test_set_masked() {
        let mut shape = any_shape();
        shape.set_shape_type(Type::Group(Group { masked: false }));
        shape.set_masked(true);

        if let Type::Group(Group { masked, .. }) = shape.shape_type {
            assert_eq!(masked, true);
        } else {
            assert!(false);
        }
    }

    #[test]
    fn test_apply_transform() {
        let mut shape = Shape::new(Uuid::new_v4());
        shape.set_shape_type(Type::Rect(Rect::default()));
        shape.set_selrect(0.0, 10.0, 10.0, 0.0);
        shape.apply_transform(&Matrix::scale((2.0, 2.0)));

        assert_eq!(shape.selrect().width(), 20.0);
        assert_eq!(shape.selrect().height(), 20.0);
    }
}
