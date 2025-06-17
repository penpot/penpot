use crate::options;

#[derive(Debug, Copy, Clone, PartialEq, Default)]
pub struct RenderOptions {
    pub flags: u32,
    pub dpr: Option<f32>,
}

impl RenderOptions {
    pub fn is_debug_visible(&self) -> bool {
        self.flags & options::DEBUG_VISIBLE == options::DEBUG_VISIBLE
    }

    pub fn is_profile_rebuild_tiles(&self) -> bool {
        self.flags & options::PROFILE_REBUILD_TILES == options::PROFILE_REBUILD_TILES
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }
}
