use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;

#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
pub enum Layout {
    FlexLayout(LayoutData, FlexData),
    GridLayout(LayoutData, GridData),
}

#[derive(Debug, Clone, PartialEq)]
pub enum FlexDirection {
    Row,
    RowReverse,
    Column,
    ColumnReverse,
}

impl FlexDirection {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Row,
            1 => Self::RowReverse,
            2 => Self::Column,
            3 => Self::ColumnReverse,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum GridDirection {
    Row,
    Column,
}

impl GridDirection {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Row,
            1 => Self::Column,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum AlignItems {
    Start,
    End,
    Center,
    Stretch,
}

impl AlignItems {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Start,
            1 => Self::End,
            2 => Self::Center,
            3 => Self::Stretch,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum AlignContent {
    Start,
    End,
    Center,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
    Stretch,
}

impl AlignContent {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Start,
            1 => Self::End,
            2 => Self::Center,
            3 => Self::SpaceBetween,
            4 => Self::SpaceAround,
            5 => Self::SpaceEvenly,
            6 => Self::Stretch,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum JustifyItems {
    Start,
    End,
    Center,
    Stretch,
}

impl JustifyItems {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Start,
            1 => Self::End,
            2 => Self::Center,
            3 => Self::Stretch,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum JustifyContent {
    Start,
    End,
    Center,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
    Stretch,
}

impl JustifyContent {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Start,
            1 => Self::End,
            2 => Self::Center,
            3 => Self::SpaceBetween,
            4 => Self::SpaceAround,
            5 => Self::SpaceEvenly,
            6 => Self::Stretch,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum WrapType {
    Wrap,
    NoWrap,
}

impl WrapType {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Wrap,
            1 => Self::NoWrap,
            _ => unreachable!(),
        }
    }
}
#[derive(Debug, Copy, Clone, PartialEq)]
pub enum GridTrackType {
    Percent,
    Flex,
    Auto,
    Fixed,
}

impl GridTrackType {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Percent,
            1 => Self::Flex,
            2 => Self::Auto,
            3 => Self::Fixed,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridTrack {
    pub track_type: GridTrackType,
    pub value: f32,
}

impl GridTrack {
    pub fn from_raw(raw: &RawGridTrack) -> Self {
        Self {
            track_type: GridTrackType::from_u8(raw.track_type),
            value: f32::from_le_bytes(raw.value),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridCell {
    pub row: i32,
    pub row_span: i32,
    pub column: i32,
    pub column_span: i32,
    pub align_self: Option<AlignSelf>,
    pub justify_self: Option<JustifySelf>,
    pub shape: Option<Uuid>,
}

impl GridCell {
    pub fn from_raw(raw: &RawGridCell) -> Self {
        Self {
            row: i32::from_le_bytes(raw.row),
            row_span: i32::from_le_bytes(raw.row_span),
            column: i32::from_le_bytes(raw.column),
            column_span: i32::from_le_bytes(raw.column_span),
            align_self: if raw.has_align_self == 1 {
                AlignSelf::from_u8(raw.align_self)
            } else {
                None
            },
            justify_self: if raw.has_justify_self == 1 {
                JustifySelf::from_u8(raw.justify_self)
            } else {
                None
            },
            shape: if raw.has_shape_id == 1 {
                Some(uuid_from_u32_quartet(
                    u32::from_le_bytes(raw.shape_id_a),
                    u32::from_le_bytes(raw.shape_id_b),
                    u32::from_le_bytes(raw.shape_id_c),
                    u32::from_le_bytes(raw.shape_id_d),
                ))
            } else {
                None
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum Sizing {
    Fill,
    Fix,
    Auto,
}

impl Sizing {
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Fill,
            1 => Self::Fix,
            2 => Self::Auto,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct LayoutData {
    pub align_items: AlignItems,
    pub align_content: AlignContent,
    pub justify_items: JustifyItems,
    pub justify_content: JustifyContent,
    pub padding_top: f32,
    pub padding_right: f32,
    pub padding_bottom: f32,
    pub padding_left: f32,
    pub row_gap: f32,
    pub column_gap: f32,
}

#[derive(Debug, Copy, Clone, PartialEq)]
pub enum AlignSelf {
    Auto,
    Start,
    End,
    Center,
    Stretch,
}

impl AlignSelf {
    pub fn from_u8(value: u8) -> Option<AlignSelf> {
        match value {
            0 => Some(Self::Auto),
            1 => Some(Self::Start),
            2 => Some(Self::End),
            3 => Some(Self::Center),
            4 => Some(Self::Stretch),
            _ => None,
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq)]
pub enum JustifySelf {
    Auto,
    Start,
    End,
    Center,
    Stretch,
}

impl JustifySelf {
    pub fn from_u8(value: u8) -> Option<JustifySelf> {
        match value {
            0 => Some(Self::Auto),
            1 => Some(Self::Start),
            2 => Some(Self::End),
            3 => Some(Self::Center),
            4 => Some(Self::Stretch),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlexData {
    pub direction: FlexDirection,
    pub wrap_type: WrapType,
}

impl FlexData {
    pub fn is_reverse(&self) -> bool {
        match &self.direction {
            FlexDirection::RowReverse | FlexDirection::ColumnReverse => true,
            _ => false,
        }
    }

    pub fn is_row(&self) -> bool {
        match &self.direction {
            FlexDirection::RowReverse | FlexDirection::Row => true,
            _ => false,
        }
    }
}

impl FlexData {
    pub fn is_wrap(&self) -> bool {
        match self.wrap_type {
            WrapType::Wrap => true,
            _ => false,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridData {
    pub direction: GridDirection,
    pub rows: Vec<GridTrack>,
    pub columns: Vec<GridTrack>,
    pub cells: Vec<GridCell>,
}

impl GridData {
    pub fn default() -> Self {
        Self {
            direction: GridDirection::Row,
            rows: vec![],
            columns: vec![],
            cells: vec![],
        }
    }
}

#[derive(Debug)]
#[repr(C)]
pub struct RawGridTrack {
    track_type: u8,
    value: [u8; 4],
}

impl RawGridTrack {
    pub fn from_bytes(bytes: [u8; 5]) -> Self {
        Self {
            track_type: bytes[0],
            value: [bytes[1], bytes[2], bytes[3], bytes[4]],
        }
    }
}

#[derive(Debug)]
#[repr(C)]
pub struct RawGridCell {
    row: [u8; 4],
    row_span: [u8; 4],
    column: [u8; 4],
    column_span: [u8; 4],
    has_align_self: u8,
    align_self: u8,
    has_justify_self: u8,
    justify_self: u8,
    has_shape_id: u8,
    shape_id_a: [u8; 4],
    shape_id_b: [u8; 4],
    shape_id_c: [u8; 4],
    shape_id_d: [u8; 4],
}

impl RawGridCell {
    pub fn from_bytes(bytes: [u8; 37]) -> Self {
        Self {
            row: [bytes[0], bytes[1], bytes[2], bytes[3]],
            row_span: [bytes[4], bytes[5], bytes[6], bytes[7]],
            column: [bytes[8], bytes[9], bytes[10], bytes[11]],
            column_span: [bytes[12], bytes[13], bytes[14], bytes[15]],

            has_align_self: bytes[16],
            align_self: bytes[17],

            has_justify_self: bytes[18],
            justify_self: bytes[19],

            has_shape_id: bytes[20],
            shape_id_a: [bytes[21], bytes[22], bytes[23], bytes[24]],
            shape_id_b: [bytes[25], bytes[26], bytes[27], bytes[28]],
            shape_id_c: [bytes[29], bytes[30], bytes[31], bytes[32]],
            shape_id_d: [bytes[33], bytes[34], bytes[35], bytes[36]],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct LayoutItem {
    pub margin_top: f32,
    pub margin_right: f32,
    pub margin_bottom: f32,
    pub margin_left: f32,
    pub h_sizing: Sizing,
    pub v_sizing: Sizing,
    pub max_h: Option<f32>,
    pub min_h: Option<f32>,
    pub max_w: Option<f32>,
    pub min_w: Option<f32>,
    pub is_absolute: bool,
    pub z_index: i32,
    pub align_self: Option<AlignSelf>,
}
