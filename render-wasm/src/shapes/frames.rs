use super::Corners;
use super::Layout;

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Frame {
    pub corners: Option<Corners>,
    pub layout: Option<Layout>,
}
