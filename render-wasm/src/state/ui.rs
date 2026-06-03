use crate::ui::Guide;
use crate::uuid::Uuid;

pub struct UIState {
    pub guides: Vec<Guide>,
    pub _show_grid: Option<Uuid>,
}

impl UIState {
    pub fn new() -> Self {
        Self {
            guides: Vec::new(),
            _show_grid: None,
        }
    }
}
