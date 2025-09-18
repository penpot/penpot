use macros::ToJs;

use crate::shapes::{
    AlignContent, AlignItems, AlignSelf, JustifyContent, JustifyItems, JustifySelf, VerticalAlign,
};
use crate::{with_current_shape_mut, STATE};

// TODO: maybe move this to the wasm module?
#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAlignItems {
    Start = 0,
    End = 1,
    Center = 2,
    Stretch = 3,
}

// TODO: maybe move this to the wasm module?
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

impl From<RawJustifySelf> for JustifySelf {
    fn from(value: RawJustifySelf) -> Self {
        match value {
            RawJustifySelf::None => unreachable!(), // FIXME: implement try_from instead
            RawJustifySelf::Auto => JustifySelf::Auto,
            RawJustifySelf::Start => JustifySelf::Start,
            RawJustifySelf::End => JustifySelf::End,
            RawJustifySelf::Center => JustifySelf::Center,
            RawJustifySelf::Stretch => JustifySelf::Stretch,
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

impl From<RawAlignSelf> for AlignSelf {
    fn from(value: RawAlignSelf) -> Self {
        match value {
            RawAlignSelf::None => unreachable!(), // FIXME: implement try_from instead
            RawAlignSelf::Auto => AlignSelf::Auto,
            RawAlignSelf::Start => AlignSelf::Start,
            RawAlignSelf::End => AlignSelf::End,
            RawAlignSelf::Center => AlignSelf::Center,
            RawAlignSelf::Stretch => AlignSelf::Stretch,
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
