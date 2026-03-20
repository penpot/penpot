// Render options flags
const DEBUG_VISIBLE: u32 = 0x01;
const PROFILE_REBUILD_TILES: u32 = 0x02;
const TEXT_EDITOR_V3: u32 = 0x04;
const SHOW_WASM_INFO: u32 = 0x08;

#[derive(Debug, Copy, Clone, PartialEq, Default)]
pub struct RenderOptions {
    pub flags: u32,
    pub dpr: Option<f32>,
    fast_mode: bool,
}

impl RenderOptions {
    pub fn is_debug_visible(&self) -> bool {
        self.flags & DEBUG_VISIBLE == DEBUG_VISIBLE
    }

    pub fn is_profile_rebuild_tiles(&self) -> bool {
        self.flags & PROFILE_REBUILD_TILES == PROFILE_REBUILD_TILES
    }

    /// Use fast mode to enable / disable expensive operations
    pub fn is_fast_mode(&self) -> bool {
        self.fast_mode
    }

    pub fn set_fast_mode(&mut self, enabled: bool) {
        self.fast_mode = enabled;
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }

    pub fn is_text_editor_v3(&self) -> bool {
        self.flags & TEXT_EDITOR_V3 == TEXT_EDITOR_V3
    }

    pub fn show_wasm_info(&self) -> bool {
        self.flags & SHOW_WASM_INFO == SHOW_WASM_INFO
    }
}
