use macros::ToJs;

use super::fonts::RawFontStyle;
use crate::mem;
use crate::shapes::{
    self, Fill, GrowType, TextAlign, TextDecoration, TextDirection, TextTransform, Type,
};
use crate::utils::uuid_from_u32;
use crate::{with_current_shape_mut, STATE};

const RAW_LEAF_DATA_SIZE: usize = std::mem::size_of::<RawTextLeaf>();
pub const RAW_LEAF_FILLS_SIZE: usize = 160;
const RAW_PARAGRAPH_DATA_SIZE: usize = std::mem::size_of::<RawParagraphData>();

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextAlign {
    Left = 0,
    Center = 1,
    Right = 2,
    Justify = 3,
}

impl From<RawTextAlign> for TextAlign {
    fn from(value: RawTextAlign) -> Self {
        match value {
            RawTextAlign::Left => TextAlign::Left,
            RawTextAlign::Center => TextAlign::Center,
            RawTextAlign::Right => TextAlign::Right,
            RawTextAlign::Justify => TextAlign::Justify,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextDirection {
    Ltr = 0,
    Rtl = 1,
}

impl From<RawTextDirection> for TextDirection {
    fn from(value: RawTextDirection) -> Self {
        match value {
            RawTextDirection::Ltr => TextDirection::LTR,
            RawTextDirection::Rtl => TextDirection::RTL,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextDecoration {
    None = 0,
    Underline = 1,
    LineThrough = 2,
    Overline = 3,
}

impl From<RawTextDecoration> for Option<TextDecoration> {
    fn from(value: RawTextDecoration) -> Self {
        match value {
            RawTextDecoration::None => None,
            RawTextDecoration::Underline => Some(TextDecoration::UNDERLINE),
            RawTextDecoration::LineThrough => Some(TextDecoration::LINE_THROUGH),
            RawTextDecoration::Overline => Some(TextDecoration::OVERLINE),
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextTransform {
    None = 0,
    Uppercase = 1,
    Lowercase = 2,
    Capitalize = 3,
}

impl From<RawTextTransform> for Option<TextTransform> {
    fn from(value: RawTextTransform) -> Self {
        match value {
            RawTextTransform::None => None,
            RawTextTransform::Uppercase => Some(TextTransform::Uppercase),
            RawTextTransform::Lowercase => Some(TextTransform::Lowercase),
            RawTextTransform::Capitalize => Some(TextTransform::Capitalize),
        }
    }
}

#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawParagraphData {
    num_leaves: u32,
    text_align: RawTextAlign,
    text_direction: RawTextDirection,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    line_height: f32,
    letter_spacing: f32,
    typography_ref_file: [u32; 4],
    typography_ref_id: [u32; 4],
}

impl From<[u8; RAW_PARAGRAPH_DATA_SIZE]> for RawParagraphData {
    fn from(bytes: [u8; RAW_PARAGRAPH_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawParagraphData {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_PARAGRAPH_DATA_SIZE] = bytes
            .get(0..RAW_PARAGRAPH_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid paragraph data".to_string())?;
        Ok(RawParagraphData::from(data))
    }
}

impl RawTextData {
    fn text_from_bytes(buffer: &[u8], offset: usize, text_length: u32) -> (String, usize) {
        let text_length = text_length as usize;
        let text_end = offset + text_length;

        if text_end > buffer.len() {
            panic!(
                "Invalid text range: offset={}, text_end={}, buffer_len={}",
                offset,
                text_end,
                buffer.len()
            );
        }

        let text_utf8 = buffer[offset..text_end].to_vec();
        if text_utf8.is_empty() {
            return (String::new(), text_end);
        }

        let text = String::from_utf8_lossy(&text_utf8).to_string();
        (text, text_end)
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RawTextLeaf {
    font_style: RawFontStyle,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4],
    text_length: u32,
    total_fills: u32,
}

impl From<[u8; RAW_LEAF_DATA_SIZE]> for RawTextLeaf {
    fn from(bytes: [u8; RAW_LEAF_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTextLeaf {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_LEAF_DATA_SIZE] = bytes
            .get(0..RAW_LEAF_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid text leaf data".to_string())?;
        Ok(RawTextLeaf::from(data))
    }
}

#[allow(dead_code)]
#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawTextLeafData {
    font_style: RawFontStyle,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    text_direction: RawTextDirection,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4],
    text_length: u32,
    total_fills: u32,
    fills: Vec<Fill>,
}

impl From<&[u8]> for RawTextLeafData {
    fn from(bytes: &[u8]) -> Self {
        let text_leaf: RawTextLeaf = RawTextLeaf::try_from(bytes).unwrap();
        let total_fills = text_leaf.total_fills as usize;

        // Use checked_mul to prevent overflow
        let fills_size = total_fills
            .checked_mul(RAW_LEAF_FILLS_SIZE)
            .expect("Overflow occurred while calculating fills size");

        let fills_start = RAW_LEAF_DATA_SIZE;
        let fills_end = fills_start + fills_size;
        let buffer = &bytes[fills_start..fills_end];
        let fills = super::fills::parse_fills_from_bytes(buffer, total_fills);

        Self {
            font_style: text_leaf.font_style,
            text_decoration: text_leaf.text_decoration,
            text_transform: text_leaf.text_transform,
            text_direction: RawTextDirection::Ltr, // TODO: Add this
            font_size: text_leaf.font_size,
            letter_spacing: text_leaf.letter_spacing,
            font_weight: text_leaf.font_weight,
            font_id: text_leaf.font_id,
            font_family: text_leaf.font_family,
            font_variant_id: text_leaf.font_variant_id,
            text_length: text_leaf.text_length,
            total_fills: text_leaf.total_fills,
            fills,
        }
    }
}

// TODO: decouple from model
pub struct RawTextData {
    pub paragraph: shapes::Paragraph,
}

impl From<&Vec<u8>> for RawTextData {
    fn from(bytes: &Vec<u8>) -> Self {
        let paragraph = RawParagraphData::try_from(&bytes[..RAW_PARAGRAPH_DATA_SIZE]).unwrap();
        let mut offset = RAW_PARAGRAPH_DATA_SIZE;
        let mut raw_text_leaves: Vec<RawTextLeafData> = Vec::new();
        let mut text_leaves: Vec<shapes::TextLeaf> = Vec::new();

        for _ in 0..paragraph.num_leaves {
            let text_leaf = RawTextLeafData::from(&bytes[offset..]);
            raw_text_leaves.push(text_leaf.clone());
            offset += RAW_LEAF_DATA_SIZE + (text_leaf.total_fills as usize * RAW_LEAF_FILLS_SIZE);
        }

        for raw_text_leaf in raw_text_leaves.iter() {
            let (text, new_offset) =
                RawTextData::text_from_bytes(bytes, offset, raw_text_leaf.text_length);
            offset = new_offset;

            let font_id = uuid_from_u32(raw_text_leaf.font_id);
            let font_variant_id = uuid_from_u32(raw_text_leaf.font_variant_id);

            let font_family = shapes::FontFamily::new(
                font_id,
                raw_text_leaf.font_weight as u32,
                raw_text_leaf.font_style.into(),
            );

            let text_leaf = shapes::TextLeaf::new(
                text,
                font_family,
                raw_text_leaf.font_size,
                raw_text_leaf.letter_spacing,
                raw_text_leaf.text_decoration.into(),
                raw_text_leaf.text_transform.into(),
                raw_text_leaf.text_direction.into(),
                raw_text_leaf.font_weight,
                font_variant_id,
                raw_text_leaf.fills.clone(),
            );
            text_leaves.push(text_leaf);
        }

        let typography_ref_file = uuid_from_u32(paragraph.typography_ref_file);
        let typography_ref_id = uuid_from_u32(paragraph.typography_ref_id);

        let paragraph = shapes::Paragraph::new(
            paragraph.num_leaves,
            paragraph.text_align.into(),
            paragraph.text_direction.into(),
            paragraph.text_decoration.into(),
            paragraph.text_transform.into(),
            paragraph.line_height,
            paragraph.letter_spacing,
            typography_ref_file,
            typography_ref_id,
            text_leaves.clone(),
        );

        Self { paragraph }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawGrowType {
    Fixed = 0,
    AutoWidth = 1,
    AutoHeight = 2,
}

impl From<u8> for RawGrowType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawGrowType> for GrowType {
    fn from(value: RawGrowType) -> Self {
        match value {
            RawGrowType::Fixed => GrowType::Fixed,
            RawGrowType::AutoWidth => GrowType::AutoWidth,
            RawGrowType::AutoHeight => GrowType::AutoHeight,
        }
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_text();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_text_content() {
    let bytes = mem::bytes();
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let raw_text_data = RawTextData::from(&bytes);
        shape
            .add_paragraph(raw_text_data.paragraph)
            .expect("Failed to add paragraph");
    });
    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_shape_grow_type(grow_type: u8) {
    let grow_type = RawGrowType::from(grow_type);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.set_grow_type(GrowType::from(grow_type));
        } else {
            panic!("Trying to update grow type in a shape that it's not a text shape");
        }
    });
}

#[no_mangle]
pub extern "C" fn get_text_dimensions() -> *mut u8 {
    let mut ptr = std::ptr::null_mut();
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(content) = &mut shape.shape_type {
            let text_content_size = content.update_layout(shape.selrect);

            let mut bytes = vec![0; 12];
            bytes[0..4].clone_from_slice(&text_content_size.width.to_le_bytes());
            bytes[4..8].clone_from_slice(&text_content_size.height.to_le_bytes());
            bytes[8..12].clone_from_slice(&text_content_size.max_width.to_le_bytes());
            ptr = mem::write_bytes(bytes)
        }
    });

    // FIXME: I think it should be better if instead of returning
    // a NULL ptr we failed gracefully.
    ptr
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.update_layout(shape.selrect);
        }
    });
}
