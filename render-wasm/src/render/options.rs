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

    /// Fast mode is enabled during interactive pan/zoom operations.
    /// When active, expensive operations like shadows are skipped for better performance.
    pub fn is_fast_mode(&self) -> bool {
        self.flags & options::FAST_MODE == options::FAST_MODE
    }

    pub fn set_fast_mode(&mut self, enabled: bool) {
        if enabled {
            self.flags |= options::FAST_MODE;
        } else {
            self.flags &= !options::FAST_MODE;
        }
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }
}
