use skia_safe::{self as skia};

use crate::uuid::Uuid;
use std::borrow::Cow;
use std::cell::OnceCell;
use std::collections::HashSet;
use std::iter::once;

mod blend;
mod blurs;
mod bools;
mod corners;
mod fills;
mod fonts;
mod frames;
mod groups;
mod layouts;
pub mod modifiers;
mod paths;
mod rects;
mod shadows;
mod shape_to_path;
mod strokes;
mod svg_attrs;
mod svgraw;
mod text;
pub mod text_paths;
mod transform;

pub use blend::*;
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
pub use shape_to_path::*;
pub use strokes::*;
pub use svg_attrs::*;
pub use svgraw::*;
pub use text::*;
pub use transform::*;

use crate::math::bools as math_bools;
use crate::math::{self, Bounds, Matrix, Point};
use indexmap::IndexSet;

use crate::state::ShapesPoolRef;

const MIN_VISIBLE_SIZE: f32 = 2.0;
const ANTIALIAS_THRESHOLD: f32 = 15.0;
const MIN_STROKE_WIDTH: f32 = 0.001;

#[derive(Debug, Clone, PartialEq)]
pub enum Type {
    Frame(Frame),
    Group(Group),
    Bool(Bool),
    Rect(Rect),
    Path(Path),
    Text(TextContent),
    Circle, // FIXME: shouldn't this have a rect inside, like the Rect variant?
    SVGRaw(SVGRaw),
}

impl Type {
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

    pub fn scale_content(&mut self, value: f32) {
        match self {
            Type::Rect(Rect {
                corners: Some(corners),
                ..
            }) => {
                corners::scale_corners(corners, value);
            }
            Type::Frame(Frame { corners, layout }) => {
                if let Some(corners) = corners {
                    corners::scale_corners(corners, value);
                }
                if let Some(layout) = layout {
                    layout.scale_content(value);
                }
            }
            Type::Text(TextContent { paragraphs, .. }) => {
                paragraphs.iter_mut().for_each(|p| p.scale_content(value));
            }
            _ => {}
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

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum VerticalAlign {
    Top,
    Center,
    Bottom,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum ConstraintV {
    Top,
    Bottom,
    TopBottom,
    Center,
    Scale,
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
    pub vertical_align: VerticalAlign,
    pub blur: Option<Blur>,
    pub opacity: f32,
    pub hidden: bool,
    pub svg: Option<skia::svg::Dom>,
    pub svg_attrs: Option<SvgAttrs>,
    pub shadows: Vec<Shadow>,
    pub layout_item: Option<LayoutItem>,
    pub extrect: OnceCell<math::Rect>,
    pub bounds: OnceCell<math::Bounds>,
    pub svg_transform: Option<Matrix>,
}

// Returns all ancestor shapes of this shape, traversing up the parent hierarchy
//
// This function walks up the parent chain starting from this shape's parent,
// collecting all ancestor IDs. It stops when it reaches a nil UUID or when
// an ancestor is hidden (unless include_hidden is true).
//
// # Arguments
// * `shapes` - The shapes pool containing all shapes
// * `include_hidden` - Whether to include hidden ancestors in the result
//
// # Returns
// A set of ancestor UUIDs in traversal order (closest ancestor first)
pub fn all_with_ancestors(
    shapes: &[Uuid],
    shapes_pool: ShapesPoolRef,
    include_hidden: bool,
) -> IndexSet<Uuid> {
    let mut pending = Vec::from_iter(shapes.iter());
    let mut result = IndexSet::new();

    while !pending.is_empty() {
        let Some(current_id) = pending.pop() else {
            break;
        };

        result.insert(*current_id);

        let Some(parent_id) = shapes_pool.get(current_id).and_then(|s| s.parent_id) else {
            continue;
        };

        if parent_id == Uuid::nil() {
            continue;
        }

        if result.contains(&parent_id) {
            continue;
        }

        // Check if the ancestor is hidden
        let Some(parent) = shapes_pool.get(&parent_id) else {
            continue;
        };

        if !include_hidden && parent.hidden() {
            continue;
        }

        pending.push(&parent.id);
    }
    result
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
            fills: Vec::with_capacity(1),
            strokes: Vec::with_capacity(1),
            blend_mode: BlendMode::default(),
            vertical_align: VerticalAlign::Top,
            opacity: 1.,
            hidden: false,
            blur: None,
            svg: None,
            svg_attrs: None,
            shadows: Vec::with_capacity(1),
            layout_item: None,
            extrect: OnceCell::new(),
            bounds: OnceCell::new(),
            svg_transform: None,
        }
    }

    pub fn scale_content(&self, value: f32) -> Self {
        let mut result = self.clone();
        result.shape_type.scale_content(value);
        result
            .strokes
            .iter_mut()
            .for_each(|s| s.scale_content(value));
        result
            .shadows
            .iter_mut()
            .for_each(|s| s.scale_content(value));

        if let Some(blur) = result.blur.as_mut() {
            blur.scale_content(value);
        }

        result
            .layout_item
            .iter_mut()
            .for_each(|i| i.scale_content(value));
        result
    }

    pub fn invalidate_extrect(&mut self) {
        self.extrect = OnceCell::new();
    }

    pub fn invalidate_bounds(&mut self) {
        self.bounds = OnceCell::new();
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

    pub fn is_bool(&self) -> bool {
        matches!(self.shape_type, Type::Bool(_))
    }

    pub fn is_group_like(&self) -> bool {
        matches!(self.shape_type, Type::Group(_)) || matches!(self.shape_type, Type::Bool(_))
    }

    pub fn has_layout(&self) -> bool {
        matches!(
            self.shape_type,
            Type::Frame(Frame {
                layout: Some(_),
                ..
            })
        )
    }

    pub fn set_selrect(&mut self, left: f32, top: f32, right: f32, bottom: f32) {
        self.invalidate_extrect();
        self.invalidate_bounds();
        self.selrect.set_ltrb(left, top, right, bottom);
        if let Type::Text(ref mut text) = self.shape_type {
            text.update_layout(self.selrect);
            text.set_xywh(left, top, right - left, bottom - top);
        }
    }

    pub fn set_masked(&mut self, masked: bool) {
        if let Type::Group(data) = &mut self.shape_type {
            data.masked = masked;
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

    pub fn set_vertical_align(&mut self, align: VerticalAlign) {
        self.vertical_align = align;
    }

    pub fn vertical_align(&self) -> VerticalAlign {
        self.vertical_align
    }

    pub fn clear_constraints(&mut self) {
        self.constraint_h = None;
        self.constraint_v = None;
    }

    pub fn set_constraint_h(&mut self, constraint: Option<ConstraintH>) {
        self.constraint_h = constraint;
    }

    pub fn constraint_h(&self, default: ConstraintH) -> ConstraintH {
        self.constraint_h.unwrap_or(default)
    }

    pub fn set_constraint_v(&mut self, constraint: Option<ConstraintV>) {
        self.constraint_v = constraint;
    }

    pub fn constraint_v(&self, default: ConstraintV) -> ConstraintV {
        self.constraint_v.unwrap_or(default)
    }

    pub fn set_hidden(&mut self, value: bool) {
        self.hidden = value;
    }

    pub fn svg_transform(&self) -> Option<Matrix> {
        self.svg_transform
    }

    // FIXME: These arguments could be grouped or simplified
    #[allow(clippy::too_many_arguments)]
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

    pub fn clear_layout(&mut self) {
        self.layout_item = None;
        if let Type::Frame(data) = &mut self.shape_type {
            data.layout = None;
        }
    }

    // FIXME: These arguments could be grouped or simplified
    #[allow(clippy::too_many_arguments)]
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
        if let Type::Frame(data) = &mut self.shape_type {
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
    }

    // FIXME: These argumoents could be grouped or simplified
    #[allow(clippy::too_many_arguments)]
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
        if let Type::Frame(data) = &mut self.shape_type {
            if let Some(Layout::GridLayout(layout_data, grid_data)) = &mut data.layout {
                layout_data.align_items = align_items;
                layout_data.align_content = align_content;
                layout_data.justify_items = justify_items;
                layout_data.justify_content = justify_content;
                layout_data.padding_top = padding_top;
                layout_data.padding_right = padding_right;
                layout_data.padding_bottom = padding_bottom;
                layout_data.padding_left = padding_left;
                layout_data.row_gap = row_gap;
                layout_data.column_gap = column_gap;
                grid_data.direction = direction;
            } else {
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
        }
    }

    pub fn set_grid_columns(&mut self, tracks: Vec<GridTrack>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.columns = tracks;
    }

    pub fn set_grid_rows(&mut self, tracks: Vec<GridTrack>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.rows = tracks;
    }

    pub fn set_grid_cells(&mut self, cells: Vec<GridCell>) {
        let Type::Frame(frame_data) = &mut self.shape_type else {
            return;
        };
        let Some(Layout::GridLayout(_, grid_data)) = &mut frame_data.layout else {
            return;
        };
        grid_data.cells = cells;
    }

    pub fn set_blur(&mut self, blur: Option<Blur>) {
        self.invalidate_extrect();
        self.blur = blur;
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

    pub fn set_fills(&mut self, fills: Vec<Fill>) {
        self.fills = fills;
    }

    pub fn add_fill(&mut self, f: Fill) {
        self.fills.push(f);
    }

    pub fn clear_fills(&mut self) {
        self.fills.clear();
    }

    pub fn visible_strokes(&self) -> impl DoubleEndedIterator<Item = &Stroke> {
        self.strokes
            .iter()
            .filter(|stroke| stroke.width > MIN_STROKE_WIDTH)
    }

    pub fn has_visible_strokes(&self) -> bool {
        self.strokes
            .iter()
            .any(|stroke| stroke.width > MIN_STROKE_WIDTH)
    }

    pub fn add_stroke(&mut self, s: Stroke) {
        self.invalidate_extrect();
        self.strokes.push(s)
    }

    pub fn set_stroke_fill(&mut self, f: Fill) -> Result<(), String> {
        let stroke = self.strokes.last_mut().ok_or("Shape has no strokes")?;
        stroke.fill = f;
        Ok(())
    }

    pub fn clear_strokes(&mut self) {
        self.invalidate_extrect();
        self.strokes.clear();
    }

    pub fn set_path_segments(&mut self, segments: Vec<Segment>) {
        self.invalidate_extrect();
        let path = Path::new(segments);
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

    pub fn blend_mode(&self) -> BlendMode {
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

    pub fn visually_insignificant(&self, scale: f32, shapes_pool: ShapesPoolRef) -> bool {
        let extrect = self.extrect(shapes_pool);
        extrect.width() * scale < MIN_VISIBLE_SIZE && extrect.height() * scale < MIN_VISIBLE_SIZE
    }

    pub fn should_use_antialias(&self, scale: f32) -> bool {
        self.selrect.width() * scale > ANTIALIAS_THRESHOLD
            || self.selrect.height() * scale > ANTIALIAS_THRESHOLD
    }

    pub fn calculate_bounds(&self) -> Bounds {
        let mut bounds = Bounds::new(
            Point::new(self.selrect.x(), self.selrect.y()),
            Point::new(self.selrect.x() + self.selrect.width(), self.selrect.y()),
            Point::new(
                self.selrect.x() + self.selrect.width(),
                self.selrect.y() + self.selrect.height(),
            ),
            Point::new(self.selrect.x(), self.selrect.y() + self.selrect.height()),
        );

        // Apply this transformation only when self.transform
        // is not the identity matrix because if it is,
        // the result of applying this transformations would be
        // the same identity matrix.
        if !self.transform.is_identity() {
            let mut matrix = self.transform;
            let center = self.center();
            matrix.post_translate(center);
            matrix.pre_translate(-center);
            bounds.transform_mut(&matrix);
        }

        bounds
    }

    pub fn bounds(&self) -> Bounds {
        *self.bounds.get_or_init(|| self.calculate_bounds())
    }

    pub fn selrect(&self) -> math::Rect {
        self.selrect
    }

    pub fn extrect(&self, shapes_pool: ShapesPoolRef) -> math::Rect {
        *self
            .extrect
            .get_or_init(|| self.calculate_extrect(shapes_pool))
    }

    pub fn get_text_content(&self) -> &TextContent {
        match &self.shape_type {
            crate::shapes::Type::Text(text_content) => text_content,
            _ => panic!("Shape is not of type Text"),
        }
    }

    /// Calculates the bounding rectangle for a selrect shape's shadow, taking into account
    /// stroke widths and shadow properties.
    ///
    /// This method computes the expanded bounds that would be needed to fully render
    /// the shadow effect for a shape. It considers:
    /// - The base bounds (selection rectangle)
    /// - Maximum stroke width across all strokes, accounting for stroke rendering kind
    /// - Shadow offset (x, y displacement)
    /// - Shadow blur radius (expands bounds outward)
    /// - Whether the shadow is hidden
    ///
    /// # Arguments
    /// * `shadow` - The shadow configuration containing offset, blur, and visibility
    ///
    /// # Returns
    /// A `math::Rect` representing the bounding rectangle that encompasses the shadow.
    /// Returns an empty rectangle if the shadow is hidden.
    pub fn get_selrect_shadow_bounds(&self, shadow: &Shadow) -> math::Rect {
        let base_bounds = self.selrect();
        let mut rect = skia::Rect::new_empty();

        let mut max_stroke: Option<f32> = None;
        for stroke in self.strokes.iter() {
            let width = match stroke.render_kind(false) {
                StrokeKind::Inner => -stroke.width / 2.,
                StrokeKind::Center => 0.,
                StrokeKind::Outer => stroke.width,
            };
            max_stroke = Some(max_stroke.unwrap_or(f32::MIN).max(width));
        }
        if !shadow.hidden() {
            let (x, y) = shadow.offset;
            let mut shadow_rect = base_bounds;
            shadow_rect.left += x;
            shadow_rect.right += x;
            shadow_rect.top += y;
            shadow_rect.bottom += y;

            shadow_rect.left += shadow.blur;
            shadow_rect.top += shadow.blur;
            shadow_rect.right -= shadow.blur;
            shadow_rect.bottom -= shadow.blur;

            if let Some(max_stroke) = max_stroke {
                shadow_rect.left -= max_stroke;
                shadow_rect.right += max_stroke;
                shadow_rect.top -= max_stroke;
                shadow_rect.bottom += max_stroke;
            }
            rect.join(shadow_rect);
        }
        rect
    }

    fn apply_stroke_bounds(&self, rect: math::Rect, stroke_width: f32) -> math::Rect {
        let mut expanded_rect = rect;
        expanded_rect.left -= stroke_width;
        expanded_rect.right += stroke_width;
        expanded_rect.top -= stroke_width;
        expanded_rect.bottom += stroke_width;

        let mut result = rect;
        result.join(expanded_rect);
        result
    }

    fn apply_shadow_bounds(&self, mut rect: math::Rect) -> math::Rect {
        for shadow in self.shadows_visible() {
            if !shadow.hidden() {
                let (x, y) = shadow.offset;
                let mut shadow_rect = rect;
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
        }
        rect
    }

    fn apply_blur_bounds(&self, mut rect: math::Rect) -> math::Rect {
        let blur = self.blur.as_ref();
        if let Some(blur) = blur {
            if !blur.hidden {
                rect.left -= blur.value;
                rect.top -= blur.value;
                rect.right += blur.value;
                rect.bottom += blur.value;
            }
        }
        rect
    }

    fn apply_children_bounds(
        &self,
        mut rect: math::Rect,
        shapes_pool: ShapesPoolRef,
    ) -> math::Rect {
        let include_children = match self.shape_type {
            Type::Group(_) => true,
            Type::Frame(_) => !self.clip_content,
            _ => false,
        };

        if include_children {
            for child_id in self.children_ids(false) {
                if let Some(child_shape) = shapes_pool.get(&child_id) {
                    rect.join(child_shape.extrect(shapes_pool));
                }
            }
        }

        rect
    }

    pub fn calculate_extrect(&self, shapes_pool: ShapesPoolRef) -> math::Rect {
        let shape = self;
        let max_stroke = Stroke::max_bounds_width(shape.strokes.iter(), shape.is_open());

        let mut rect = match &shape.shape_type {
            Type::Path(_) | Type::Bool(_) => {
                if let Some(path) = shape.get_skia_path() {
                    return path
                        .compute_tight_bounds()
                        .with_outset((max_stroke, max_stroke));
                }
                shape.bounds().to_rect()
            }
            Type::Text(text_content) => {
                // FIXME: we need to recalculate the text bounds here because the shape's selrect
                let text_bounds = text_content.calculate_bounds(shape);
                text_bounds.to_rect()
            }
            _ => shape.bounds().to_rect(),
        };

        rect = self.apply_stroke_bounds(rect, max_stroke);
        rect = self.apply_shadow_bounds(rect);
        rect = self.apply_blur_bounds(rect);
        rect = self.apply_children_bounds(rect, shapes_pool);

        rect
    }

    pub fn left_top(&self) -> Point {
        Point::new(self.selrect.left, self.selrect.top)
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

    // TODO: Review this to use children_ids_iter instead
    pub fn children_ids(&self, include_hidden: bool) -> IndexSet<Uuid> {
        if include_hidden {
            return self.children.clone().into_iter().rev().collect();
        }

        if let Type::Bool(_) = self.shape_type {
            IndexSet::<Uuid>::new()
        } else if let Type::Group(group) = self.shape_type {
            if group.masked {
                self.children
                    .iter()
                    .rev()
                    .take(self.children.len() - 1)
                    .cloned()
                    .collect()
            } else {
                self.children.clone().into_iter().rev().collect()
            }
        } else {
            self.children.clone().into_iter().rev().collect()
        }
    }

    pub fn children_ids_iter(&self, include_hidden: bool) -> Box<dyn Iterator<Item = &Uuid> + '_> {
        if include_hidden {
            return Box::new(self.children.iter().rev());
        }

        if let Type::Bool(_) = self.shape_type {
            Box::new([].iter())
        } else if let Type::Group(group) = self.shape_type {
            if group.masked {
                Box::new(self.children.iter().rev().take(self.children.len() - 1))
            } else {
                Box::new(self.children.iter().rev())
            }
        } else {
            Box::new(self.children.iter().rev())
        }
    }

    pub fn all_children(
        &self,
        shapes: ShapesPoolRef,
        include_hidden: bool,
        include_self: bool,
    ) -> IndexSet<Uuid> {
        let all_children = self
            .children_ids(include_hidden)
            .into_iter()
            .flat_map(|id| {
                shapes
                    .get(&id)
                    .map(|s| s.all_children(shapes, include_hidden, true))
                    .unwrap_or_default()
            });

        if include_self {
            once(self.id).chain(all_children).collect()
        } else {
            all_children.collect()
        }
    }

    pub fn get_matrix(&self) -> Matrix {
        let mut matrix = Matrix::new_identity();
        matrix.post_translate(self.left_top());
        matrix.post_rotate(self.rotation, self.center());
        matrix
    }

    pub fn get_concatenated_matrix(&self, shapes: ShapesPoolRef) -> Matrix {
        let mut matrix = Matrix::new_identity();
        let mut current_id = self.id;
        while let Some(parent_id) = shapes.get(&current_id).and_then(|s| s.parent_id) {
            if parent_id == Uuid::nil() {
                break;
            }

            if let Some(parent) = shapes.get(&parent_id) {
                matrix.pre_concat(&parent.get_matrix());
                current_id = parent_id;
            } else {
                // FIXME: This should panic! I've removed it temporarily until
                // we fix the problems with shapes without parents.
                // panic!("Parent can't be found");
                break;
            }
        }
        matrix
    }

    pub fn image_filter(&self, scale: f32) -> Option<skia::ImageFilter> {
        self.blur
            .filter(|blur| !blur.hidden)
            .and_then(|blur| match blur.blur_type {
                BlurType::LayerBlur => skia::image_filters::blur(
                    (blur.value * scale, blur.value * scale),
                    None,
                    None,
                    None,
                ),
            })
    }

    #[allow(dead_code)]
    pub fn mask_filter(&self, scale: f32) -> Option<skia::MaskFilter> {
        self.blur
            .filter(|blur| !blur.hidden)
            .and_then(|blur| match blur.blur_type {
                BlurType::LayerBlur => {
                    skia::MaskFilter::blur(skia::BlurStyle::Normal, blur.value * scale, Some(true))
                }
            })
    }

    pub fn is_recursive(&self) -> bool {
        matches!(
            self.shape_type,
            Type::Frame(_) | Type::Group(_) | Type::Bool(_)
        )
    }

    pub fn is_open(&self) -> bool {
        matches!(&self.shape_type, Type::Path(p) if p.is_open())
    }

    pub fn add_shadow(&mut self, shadow: Shadow) {
        self.invalidate_extrect();
        self.shadows.push(shadow);
    }

    pub fn clear_shadows(&mut self) {
        self.invalidate_extrect();
        self.shadows.clear();
    }

    #[allow(dead_code)]
    pub fn drop_shadows(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .rev()
            .filter(|shadow| shadow.style() == ShadowStyle::Drop)
    }

    pub fn drop_shadows_visible(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .rev()
            .filter(|shadow| shadow.style() == ShadowStyle::Drop && !shadow.hidden())
    }

    #[allow(dead_code)]
    pub fn inner_shadows(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .rev()
            .filter(|shadow| shadow.style() == ShadowStyle::Inner)
    }

    pub fn inner_shadows_visible(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows
            .iter()
            .rev()
            .filter(|shadow| shadow.style() == ShadowStyle::Inner && !shadow.hidden())
    }

    pub fn shadows_visible(&self) -> impl DoubleEndedIterator<Item = &Shadow> {
        self.shadows.iter().rev().filter(|shadow| !shadow.hidden())
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
        self.invalidate_extrect();
        match self.shape_type {
            Type::Text(ref mut text) => {
                text.add_paragraph(paragraph);
                Ok(())
            }
            _ => Err("Shape is not a text".to_string()),
        }
    }

    pub fn clear_text(&mut self) {
        self.invalidate_extrect();
        if let Type::Text(old_text_content) = &self.shape_type {
            let new_text_content = TextContent::new(self.selrect, old_text_content.grow_type());
            self.shape_type = Type::Text(new_text_content);
        }
    }

    pub fn get_skia_path(&self) -> Option<skia::Path> {
        if let Some(path) = self.shape_type.path() {
            let mut skia_path = path.to_skia_path();
            if let Some(path_transform) = self.to_path_transform() {
                skia_path.transform(&path_transform);
            }
            if let Some(svg_attrs) = &self.svg_attrs {
                if svg_attrs.fill_rule == FillRule::Evenodd {
                    skia_path.set_fill_type(skia::PathFillType::EvenOdd);
                }
            }
            Some(skia_path)
        } else {
            None
        }
    }

    fn transform_selrect(&mut self, transform: &Matrix) {
        let mut center = self.selrect.center();
        center = transform.map_point(center);

        let bounds = self.bounds().transform(transform);
        self.transform = bounds.transform_matrix().unwrap_or_default();

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
        self.transform_selrect(transform);

        // We don't need to invalidate this? we can just transform it
        self.invalidate_extrect();
        self.invalidate_bounds();

        if let shape_type @ (Type::Path(_) | Type::Bool(_)) = &mut self.shape_type {
            if let Some(path) = shape_type.path_mut() {
                path.transform(transform);
            }
        } else if let Type::Text(text) = &mut self.shape_type {
            text.transform(transform);
        } else if let Type::SVGRaw(_) = &mut self.shape_type {
            self.svg_transform = Some(*transform);
        }
    }

    pub fn apply_structure(&mut self, structure: &Vec<StructureEntry>) {
        let mut result: Vec<Uuid> = Vec::from_iter(self.children.iter().copied());
        let mut to_remove = HashSet::<&Uuid>::new();

        for st in structure {
            match st.entry_type {
                StructureEntryType::AddChild => {
                    result.insert(st.index as usize, st.id);
                }
                StructureEntryType::RemoveChild => {
                    to_remove.insert(&st.id);
                }
                _ => {}
            }
        }

        self.children = result
            .iter()
            .filter(|id| !to_remove.contains(id))
            .copied()
            .collect();
    }

    pub fn transformed(
        &self,
        shapes_pool: ShapesPoolRef,
        transform: Option<&Matrix>,
        structure: Option<&Vec<StructureEntry>>,
    ) -> Self {
        let mut shape: Cow<Shape> = Cow::Borrowed(self);
        if let Some(transform) = transform {
            shape.to_mut().apply_transform(transform);
        }
        if let Some(structure) = structure {
            shape.to_mut().apply_structure(structure);
        }
        if self.is_bool() {
            math_bools::update_bool_to_path(shape.to_mut(), shapes_pool)
        }
        shape.into_owned()
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

    pub fn count_visible_inner_strokes(&self) -> usize {
        self.visible_strokes()
            .filter(|s| s.kind == StrokeKind::Inner)
            .count()
    }

    pub fn drop_shadow_paints(&self) -> Vec<skia_safe::Paint> {
        let drop_shadows: Vec<&Shadow> = self.drop_shadows_visible().collect();

        drop_shadows
            .into_iter()
            .map(|shadow| {
                let mut paint = skia_safe::Paint::default();
                let filter = shadow.get_drop_shadow_filter();
                paint.set_image_filter(filter);
                paint
            })
            .collect()
    }

    pub fn inner_shadow_paints(&self) -> Vec<skia_safe::Paint> {
        let inner_shadows: Vec<&Shadow> = self.inner_shadows_visible().collect();

        inner_shadows
            .into_iter()
            .map(|shadow| {
                let mut paint = skia_safe::Paint::default();
                let filter = shadow.get_inner_shadow_filter();
                paint.set_image_filter(filter);
                paint
            })
            .collect()
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

        shape.add_fill(Fill::Solid(SolidColor(Color::TRANSPARENT)));
        assert_eq!(
            shape.fills.first(),
            Some(&Fill::Solid(SolidColor(Color::TRANSPARENT)))
        )
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
            unreachable!();
        }
    }

    #[test]
    fn test_set_masked() {
        let mut shape = any_shape();
        shape.set_shape_type(Type::Group(Group { masked: false }));
        shape.set_masked(true);

        if let Type::Group(Group { masked, .. }) = shape.shape_type {
            assert!(masked);
        } else {
            unreachable!()
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
