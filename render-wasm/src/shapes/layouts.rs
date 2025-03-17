#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
pub enum Layout {
    FlexLayout(LayoutData, FlexData),
    GridLayout(LayoutData, GridData),
}

#[derive(Debug, Clone, PartialEq)]
pub enum Direction {
    Row,
    RowReverse,
    Column,
    ColumnReverse,
}

impl Direction {
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

#[derive(Debug, Clone, PartialEq)]
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

#[derive(Debug, Clone, PartialEq)]
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

#[derive(Debug, Clone, PartialEq)]
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

#[derive(Debug, Clone, PartialEq)]
pub struct GridTrack {}

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
    pub direction: Direction,
    pub align_items: AlignItems,
    pub align_content: AlignContent,
    pub justify_items: JustifyItems,
    pub justify_content: JustifyContent,
    pub padding_top: f32,
    pub padding_right: f32,
    pub padding_bottom: f32,
    pub padding_left: f32,
}

impl LayoutData {
    pub fn is_reverse(&self) -> bool {
        match &self.direction {
            Direction::RowReverse | Direction::ColumnReverse => true,
            _ => false,
        }
    }
    pub fn is_row(&self) -> bool {
        match &self.direction {
            Direction::RowReverse | Direction::Row => true,
            _ => false,
        }
    }

    #[allow(dead_code)]
    pub fn is_column(&self) -> bool {
        match &self.direction {
            Direction::ColumnReverse | Direction::Column => true,
            _ => false,
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq)]
pub enum AlignSelf {
    Start,
    End,
    Center,
    Stretch,
}

impl AlignSelf {
    pub fn from_u8(value: u8) -> Option<AlignSelf> {
        match value {
            0 => Some(Self::Start),
            1 => Some(Self::End),
            2 => Some(Self::Center),
            3 => Some(Self::Stretch),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlexData {
    pub row_gap: f32,
    pub column_gap: f32,
    pub wrap_type: WrapType,
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
    pub rows: Vec<GridTrack>,
    pub columns: Vec<GridTrack>,
    // layout-grid-cells       ;; map of id->grid-cell
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
