use macros::ToJs;

use crate::shapes::{
    AlignContent, AlignItems, AlignSelf, JustifyContent, JustifyItems, JustifySelf, VerticalAlign,
};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAlignItems {
    Start = 0,
    End = 1,
    Center = 2,
    Stretch = 3,
}

impl From<u8> for RawAlignItems {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawAlignItems> for AlignItems {
    fn from(value: RawAlignItems) -> Self {
        match value {
            RawAlignItems::Start => AlignItems::Start,
            RawAlignItems::End => AlignItems::End,
            RawAlignItems::Center => AlignItems::Center,
            RawAlignItems::Stretch => AlignItems::Stretch,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAlignContent {
    Start = 0,
    End = 1,
    Center = 2,
    SpaceBetween = 3,
    SpaceAround = 4,
    SpaceEvenly = 5,
    Stretch = 6,
}

impl From<u8> for RawAlignContent {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawAlignContent> for AlignContent {
    fn from(value: RawAlignContent) -> Self {
        match value {
            RawAlignContent::Start => AlignContent::Start,
            RawAlignContent::End => AlignContent::End,
            RawAlignContent::Center => AlignContent::Center,
            RawAlignContent::SpaceBetween => AlignContent::SpaceBetween,
            RawAlignContent::SpaceAround => AlignContent::SpaceAround,
            RawAlignContent::SpaceEvenly => AlignContent::SpaceEvenly,
            RawAlignContent::Stretch => AlignContent::Stretch,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawJustifyItems {
    Start = 0,
    End = 1,
    Center = 2,
    Stretch = 3,
}

impl From<u8> for RawJustifyItems {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawJustifyItems> for JustifyItems {
    fn from(value: RawJustifyItems) -> Self {
        match value {
            RawJustifyItems::Start => JustifyItems::Start,
            RawJustifyItems::End => JustifyItems::End,
            RawJustifyItems::Center => JustifyItems::Center,
            RawJustifyItems::Stretch => JustifyItems::Stretch,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawJustifyContent {
    Start = 0,
    End = 1,
    Center = 2,
    SpaceBetween = 3,
    SpaceAround = 4,
    SpaceEvenly = 5,
    Stretch = 6,
}

impl From<u8> for RawJustifyContent {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawJustifyContent> for JustifyContent {
    fn from(value: RawJustifyContent) -> Self {
        match value {
            RawJustifyContent::Start => JustifyContent::Start,
            RawJustifyContent::End => JustifyContent::End,
            RawJustifyContent::Center => JustifyContent::Center,
            RawJustifyContent::SpaceBetween => JustifyContent::SpaceBetween,
            RawJustifyContent::SpaceAround => JustifyContent::SpaceAround,
            RawJustifyContent::SpaceEvenly => JustifyContent::SpaceEvenly,
            RawJustifyContent::Stretch => JustifyContent::Stretch,
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawJustifySelf {
    None = 0,
    Auto = 1,
    Start = 2,
    End = 3,
    Center = 4,
    Stretch = 5,
}

impl From<u8> for RawJustifySelf {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl TryFrom<RawJustifySelf> for JustifySelf {
    type Error = ();
    fn try_from(value: RawJustifySelf) -> Result<JustifySelf, Self::Error> {
        match value {
            RawJustifySelf::None => Err(()),
            RawJustifySelf::Auto => Ok(JustifySelf::Auto),
            RawJustifySelf::Start => Ok(JustifySelf::Start),
            RawJustifySelf::End => Ok(JustifySelf::End),
            RawJustifySelf::Center => Ok(JustifySelf::Center),
            RawJustifySelf::Stretch => Ok(JustifySelf::Stretch),
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAlignSelf {
    None = 0,
    Auto = 1,
    Start = 2,
    End = 3,
    Center = 4,
    Stretch = 5,
}

impl From<u8> for RawAlignSelf {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl TryFrom<RawAlignSelf> for AlignSelf {
    type Error = ();
    fn try_from(value: RawAlignSelf) -> Result<AlignSelf, Self::Error> {
        match value {
            RawAlignSelf::None => Err(()),
            RawAlignSelf::Auto => Ok(AlignSelf::Auto),
            RawAlignSelf::Start => Ok(AlignSelf::Start),
            RawAlignSelf::End => Ok(AlignSelf::End),
            RawAlignSelf::Center => Ok(AlignSelf::Center),
            RawAlignSelf::Stretch => Ok(AlignSelf::Stretch),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawVerticalAlign {
    Top = 0,
    Center = 1,
    Bottom = 2,
}

impl From<u8> for RawVerticalAlign {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawVerticalAlign> for VerticalAlign {
    fn from(value: RawVerticalAlign) -> Self {
        match value {
            RawVerticalAlign::Top => VerticalAlign::Top,
            RawVerticalAlign::Center => VerticalAlign::Center,
            RawVerticalAlign::Bottom => VerticalAlign::Bottom,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_vertical_align(align: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let align = RawVerticalAlign::from(align);
        shape.set_vertical_align(align.into());
    });
}
