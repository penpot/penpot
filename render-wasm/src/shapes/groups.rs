#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Group {
    pub masked: bool,
}

impl Group {
    pub fn new(masked: bool) -> Self {
        Group { masked }
    }
}
