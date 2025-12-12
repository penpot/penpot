use macros::ToJs;

use super::{fills::RawFillData, fonts::RawFontStyle};
use crate::math::{Matrix, Point};
use crate::mem;
use crate::shapes::{
    self, GrowType, Shape, TextAlign, TextDecoration, TextDirection, TextTransform, Type,
};
use crate::utils::{uuid_from_u32, uuid_from_u32_quartet};
use crate::{
    with_current_shape_mut, with_state, with_state_mut, with_state_mut_current_shape, STATE,
};

const RAW_SPAN_DATA_SIZE: usize = std::mem::size_of::<RawTextSpan>();
const RAW_PARAGRAPH_DATA_SIZE: usize = std::mem::size_of::<RawParagraphData>();

const MAX_TEXT_FILLS: usize = 8;

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
    span_count: u32,
    text_align: RawTextAlign,
    text_direction: RawTextDirection,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    line_height: f32,
    letter_spacing: f32,
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

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RawTextSpan {
    font_style: RawFontStyle,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    text_direction: RawTextDirection,
    font_size: f32,
    line_height: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4], // TODO: maybe add RawUUID type
    text_length: u32,
    fill_count: u32,
    fills: [RawFillData; MAX_TEXT_FILLS],
}

impl From<[u8; RAW_SPAN_DATA_SIZE]> for RawTextSpan {
    fn from(bytes: [u8; RAW_SPAN_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTextSpan {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_SPAN_DATA_SIZE] = bytes
            .get(0..RAW_SPAN_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid text span data".to_string())?;
        Ok(RawTextSpan::from(data))
    }
}

impl From<RawTextSpan> for shapes::TextSpan {
    fn from(value: RawTextSpan) -> Self {
        let text = String::default();

        let font_family = shapes::FontFamily::new(
            uuid_from_u32(value.font_id),
            value.font_weight as u32,
            value.font_style.into(),
        );

        let fills = value
            .fills
            .into_iter()
            .take(value.fill_count as usize)
            .map(|fill| fill.into())
            .collect();

        Self::new(
            text,
            font_family,
            value.font_size,
            value.line_height,
            value.letter_spacing,
            value.text_decoration.into(),
            value.text_transform.into(),
            value.text_direction.into(),
            value.font_weight,
            uuid_from_u32(value.font_variant_id),
            fills,
        )
    }
}

#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawParagraph {
    attrs: RawParagraphData,
    spans: Vec<RawTextSpan>,
    text_buffer: Vec<u8>,
}

impl TryFrom<&Vec<u8>> for RawParagraph {
    // TODO: use a proper error type
    type Error = String;

    fn try_from(bytes: &Vec<u8>) -> Result<Self, Self::Error> {
        let attrs = RawParagraphData::try_from(&bytes[..RAW_PARAGRAPH_DATA_SIZE])?;
        let mut offset = RAW_PARAGRAPH_DATA_SIZE;
        let mut raw_text_spans: Vec<RawTextSpan> = Vec::new();

        for _ in 0..attrs.span_count {
            let text_span = RawTextSpan::try_from(&bytes[offset..(offset + RAW_SPAN_DATA_SIZE)])?;
            offset += RAW_SPAN_DATA_SIZE;
            raw_text_spans.push(text_span);
        }

        let text_buffer = &bytes[offset..];

        Ok(Self {
            attrs,
            spans: raw_text_spans,
            text_buffer: text_buffer.to_vec(),
        })
    }
}

impl From<RawParagraph> for shapes::Paragraph {
    fn from(value: RawParagraph) -> Self {
        let mut spans = vec![];

        let mut offset = 0;
        for raw_span in value.spans.into_iter() {
            let delta = raw_span.text_length as usize;
            let text_buffer = &value.text_buffer[offset..offset + delta];

            let mut span = shapes::TextSpan::from(raw_span);
            if !text_buffer.is_empty() {
                span.set_text(String::from_utf8_lossy(text_buffer).to_string());
            }

            spans.push(span);
            offset += delta;
        }

        shapes::Paragraph::new(
            value.attrs.text_align.into(),
            value.attrs.text_direction.into(),
            value.attrs.text_decoration.into(),
            value.attrs.text_transform.into(),
            value.attrs.line_height,
            value.attrs.letter_spacing,
            spans,
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

        if shape.add_paragraph(raw_text_data.into()).is_err() {
            println!("Error with set_shape_text_content on {:?}", shape.id);
        }
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

            // Sacar de aqui x, y, width, height
            let rect = content.content_rect(&shape.selrect, shape.vertical_align);

            let mut bytes = vec![0; 20];
            bytes[0..4].clone_from_slice(&text_content_size.width.to_le_bytes());
            bytes[4..8].clone_from_slice(&text_content_size.height.to_le_bytes());
            bytes[8..12].clone_from_slice(&text_content_size.max_width.to_le_bytes());

            // veamos
            bytes[12..16].clone_from_slice(&rect.x().to_le_bytes());
            bytes[16..20].clone_from_slice(&rect.y().to_le_bytes());

            ptr = mem::write_bytes(bytes)
        }
    });

    // FIXME: I think it should be better if instead of returning
    // a NULL ptr we failed gracefully.
    ptr
}

#[no_mangle]
pub extern "C" fn intersect_position_in_shape(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    x_pos: f32,
    y_pos: f32,
) -> bool {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let Some(shape) = state.shapes.get(&id) else {
            return false;
        };
        if let Type::Text(content) = &shape.shape_type {
            return content.intersect_position_in_text(shape, x_pos, y_pos);
        }
    });
    false
}

fn update_text_layout(shape: &mut Shape) {
    if let Type::Text(text_content) = &mut shape.shape_type {
        text_content.update_layout(shape.selrect);
        shape.invalidate_extrect();
    }
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        update_text_layout(shape);
    });
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout_for(a: u32, b: u32, c: u32, d: u32) {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        if let Some(shape) = state.shapes.get_mut(&shape_id) {
            update_text_layout(shape);
        }
    });
}

#[no_mangle]
pub extern "C" fn get_caret_position_at(x: f32, y: f32) -> i32 {
    with_state_mut_current_shape!(state, |shape: &Shape| {
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
                    return position_with_affinity.position_with_affinity.position;
                }
            }
        } else {
            panic!("Trying to get caret position of a shape that it's not a text shape");
        }
    });
    -1
}
