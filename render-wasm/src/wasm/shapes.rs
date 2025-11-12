use macros::ToJs;

use crate::shapes::{Bool, Frame, Group, Path, Rect, SVGRaw, TextContent, Type};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawShapeType {
    Frame = 0,
    Group = 1,
    Bool = 2,
    Rect = 3,
    Path = 4,
    Text = 5,
    Circle = 6,
    SVGRaw = 7,
}

impl From<u8> for RawShapeType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawShapeType> for Type {
    fn from(value: RawShapeType) -> Self {
        match value {
            RawShapeType::Frame => Type::Frame(Frame::default()),
            RawShapeType::Group => Type::Group(Group::default()),
            RawShapeType::Bool => Type::Bool(Bool::default()),
            RawShapeType::Rect => Type::Rect(Rect::default()),
            RawShapeType::Path => Type::Path(Path::default()),
            RawShapeType::Text => Type::Text(TextContent::default()),
            RawShapeType::Circle => Type::Circle,
            RawShapeType::SVGRaw => Type::SVGRaw(SVGRaw::default()),
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_type(shape_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let shape_type = RawShapeType::from(shape_type);
        shape.set_shape_type(shape_type.into());
    });
}
