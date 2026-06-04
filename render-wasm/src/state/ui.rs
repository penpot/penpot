use crate::ui::{Guide, GuideKind};
use crate::uuid::Uuid;

pub struct GuidePool {
    horizontal: Vec<Guide>,
    vertical: Vec<Guide>,
}

impl GuidePool {
    pub fn new() -> Self {
        Self {
            horizontal: Vec::new(),
            vertical: Vec::new(),
        }
    }

    pub fn set(&mut self, guides: Vec<Guide>) {
        for guide in guides {
            match guide.kind {
                GuideKind::Vertical(_) => self.vertical.push(guide),
                GuideKind::Horizontal(_) => self.horizontal.push(guide),
            }
        }

        // TODO: handle the unwraps here
        self.horizontal
            .sort_by(|a, b| a.position().total_cmp(&b.position()));
        self.vertical
            .sort_by(|a, b| a.position().total_cmp(&b.position()));
    }
}

pub struct UIState {
    guides: GuidePool,
    pub _show_grid: Option<Uuid>,
}

impl UIState {
    pub fn new() -> Self {
        Self {
            guides: GuidePool::new(),
            _show_grid: None,
        }
    }

    pub fn guides(&self) -> (&Vec<Guide>, &Vec<Guide>) {
        (&self.guides.horizontal, &self.guides.vertical)
    }

    pub fn set_guides(&mut self, guides: Vec<Guide>) {
        self.guides.set(guides);
    }

    #[allow(dead_code)]
    fn find_guide_at(&self, _x: f32, _y: f32, _zoom: f32) -> Option<&Guide> {
        unimplemented!("TODO: Implement guide finding");
    }
}
