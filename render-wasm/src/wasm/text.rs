use macros::ToJs;

use super::fonts::RawFontStyle;
use crate::math::{Matrix, Point};
use crate::mem;
use crate::shapes::{
    self, GrowType, TextAlign, TextDecoration, TextDirection, TextTransform, Type,
};
use crate::utils::{uuid_from_u32, uuid_from_u32_quartet};
use crate::{with_current_shape, with_current_shape_mut, with_state_mut, STATE};

const RAW_LEAF_DATA_SIZE: usize = std::mem::size_of::<RawTextLeafAttrs>();
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
    leaf_count: u32,
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

// FIXME: Merge this struct with RawTextLeaf once we cap the amount of fills a text shape has
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RawTextLeafAttrs {
    font_style: RawFontStyle,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    text_direction: RawTextDirection,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4], // TODO: maybe add RawUUID type
    text_length: u32,
    fill_count: u32, // FIXME: we should cap the amount of fills a text shape has
}

impl From<[u8; RAW_LEAF_DATA_SIZE]> for RawTextLeafAttrs {
    fn from(bytes: [u8; RAW_LEAF_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTextLeafAttrs {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_LEAF_DATA_SIZE] = bytes
            .get(0..RAW_LEAF_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid text leaf data".to_string())?;
        Ok(RawTextLeafAttrs::from(data))
    }
}

#[allow(dead_code)]
#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawTextLeaf {
    attrs: RawTextLeafAttrs,
    raw_fills: Vec<u8>, // FIXME: remove this once we cap the amount of fills a text shape has
}

impl TryFrom<&[u8]> for RawTextLeaf {
    // TODO: use a proper error type
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let raw_attrs: RawTextLeafAttrs = RawTextLeafAttrs::try_from(bytes)?;
        let total_fills = raw_attrs.fill_count as usize;

        // Use checked_mul to prevent overflow
        let fills_size = total_fills
            .checked_mul(RAW_LEAF_FILLS_SIZE)
            .ok_or("Overflow occurred while calculating fills size")?;

        let fills_start = RAW_LEAF_DATA_SIZE;
        let fills_end = fills_start + fills_size;
        let raw_fills = &bytes[fills_start..fills_end];

        Ok(Self {
            attrs: raw_attrs,
            raw_fills: raw_fills.to_vec(),
        })
    }
}

impl From<RawTextLeaf> for shapes::TextLeaf {
    fn from(value: RawTextLeaf) -> Self {
        let text = String::default();

        let font_family = shapes::FontFamily::new(
            uuid_from_u32(value.attrs.font_id),
            value.attrs.font_weight as u32,
            value.attrs.font_style.into(),
        );
        let fills =
            super::fills::parse_fills_from_bytes(&value.raw_fills, value.attrs.fill_count as usize);

        Self::new(
            text,
            font_family,
            value.attrs.font_size,
            value.attrs.letter_spacing,
            value.attrs.text_decoration.into(),
            value.attrs.text_transform.into(),
            value.attrs.text_direction.into(),
            value.attrs.font_weight,
            uuid_from_u32(value.attrs.font_variant_id),
            fills,
        )
    }
}

#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawParagraph {
    attrs: RawParagraphData,
    leaves: Vec<RawTextLeaf>,
    text_buffer: Vec<u8>,
}

impl TryFrom<&Vec<u8>> for RawParagraph {
    // TODO: use a proper error type
    type Error = String;

    fn try_from(bytes: &Vec<u8>) -> Result<Self, Self::Error> {
        let attrs = RawParagraphData::try_from(&bytes[..RAW_PARAGRAPH_DATA_SIZE])?;
        let mut offset = RAW_PARAGRAPH_DATA_SIZE;
        let mut raw_text_leaves: Vec<RawTextLeaf> = Vec::new();

        for _ in 0..attrs.leaf_count {
            let text_leaf = RawTextLeaf::try_from(&bytes[offset..])?;
            let leaf_size =
                RAW_LEAF_DATA_SIZE + (text_leaf.attrs.fill_count as usize * RAW_LEAF_FILLS_SIZE);

            offset += leaf_size;
            raw_text_leaves.push(text_leaf);
        }

        let text_buffer = &bytes[offset..];

        Ok(Self {
            attrs,
            leaves: raw_text_leaves,
            text_buffer: text_buffer.to_vec(),
        })
    }
}

impl From<RawParagraph> for shapes::Paragraph {
    fn from(value: RawParagraph) -> Self {
        let typography_ref_file = uuid_from_u32(value.attrs.typography_ref_file);
        let typography_ref_id = uuid_from_u32(value.attrs.typography_ref_id);

        let mut leaves = vec![];

        let mut offset = 0;
        for raw_leaf in value.leaves.into_iter() {
            let delta = raw_leaf.attrs.text_length as usize;
            let text_buffer = &value.text_buffer[offset..offset + delta];

            let mut leaf = shapes::TextLeaf::from(raw_leaf);
            if !text_buffer.is_empty() {
                leaf.set_text(String::from_utf8_lossy(text_buffer).to_string());
            }

            leaves.push(leaf);
            offset += delta;
        }

        shapes::Paragraph::new(
            value.attrs.text_align.into(),
            value.attrs.text_direction.into(),
            value.attrs.text_decoration.into(),
            value.attrs.text_transform.into(),
            value.attrs.line_height,
            value.attrs.letter_spacing,
            typography_ref_file,
            typography_ref_id,
            leaves,
        )
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
        let raw_text_data = RawParagraph::try_from(&bytes).unwrap();
        shape
            .add_paragraph(raw_text_data.into())
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

#[no_mangle]
pub extern "C" fn update_shape_text_layout_for(a: u32, b: u32, c: u32, d: u32) {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        if let Some(shape) = state.shapes.get_mut(&shape_id) {
            if let Type::Text(text_content) = &mut shape.shape_type {
                text_content.update_layout(shape.selrect);
            }
        }
    });
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout_for_all() {
    with_state_mut!(state, {
        for shape in state.shapes.iter_mut() {
            if let Type::Text(text_content) = &mut shape.shape_type {
                text_content.update_layout(shape.selrect);
            }
        }
    });
}

#[no_mangle]
pub extern "C" fn get_caret_position_at(x: f32, y: f32) -> i32 {
    with_current_shape!(state, |shape: &Shape| {
        if let Type::Text(text_content) = &shape.shape_type {
            let mut matrix = Matrix::new_identity();
            let shape_matrix = shape.get_concatenated_matrix(&state.shapes);
            let view_matrix = state.render_state.viewbox.get_matrix();
            if let Some(inv_view_matrix) = view_matrix.invert() {
                matrix.post_concat(&inv_view_matrix);
                matrix.post_concat(&shape_matrix);

                let mapped_point = matrix.map_point(Point::new(x, y));

                if let Some(position_with_affinity) =
                    text_content.get_caret_position_at(&mapped_point)
                {
                    return position_with_affinity.position;
                }
            }
        } else {
            panic!("Trying to update grow type in a shape that it's not a text shape");
        }
    });
    -1
}
