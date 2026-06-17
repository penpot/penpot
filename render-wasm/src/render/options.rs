// Render options flags
const DEBUG_VISIBLE: u32 = 0x01;
const PROFILE_REBUILD_TILES: u32 = 0x02;
const TEXT_EDITOR_V3: u32 = 0x04;
const SHOW_WASM_INFO: u32 = 0x08;

// Render performance options
// This is the extra area used for tile rendering (tiles beyond viewport).
// Higher values pre-render more tiles, reducing empty squares during pan but using more memory.
const VIEWPORT_INTEREST_AREA_THRESHOLD: i32 = 1;
const MIN_DPR_VIEWPORT_INTEREST_AREA_THRESHOLD: i32 = 2;
const MAX_BLOCKING_TIME_MS: i32 = 8;
const NODE_BATCH_THRESHOLD: i32 = 3;
const BLUR_DOWNSCALE_THRESHOLD: f32 = 8.0;
const ANTIALIAS_THRESHOLD: f32 = 7.0;
#[derive(Debug, Copy, Clone, PartialEq)]
pub struct RenderOptions {
    pub flags: u32,
    pub dpr: f32,
    fast_mode: bool,
    /// Active while the user is interacting with a shape (drag, resize,
    /// rotate). Implies `fast_mode` semantics for expensive effects but
    /// keeps per-frame flushing enabled (unlike pan/zoom, where
    /// `render_from_cache` drives target presentation).
    interactive_transform: bool,
    /// Minimum on-screen size (CSS px at 1:1 zoom) above which vector antialiasing is enabled.
    pub antialias_threshold: f32,
    pub viewport_interest_area_threshold: i32,
    pub dpr_viewport_interest_area_threshold: i32,
    pub max_blocking_time_ms: i32,
    pub node_batch_threshold: i32,
    pub blur_downscale_threshold: f32,
    pub capture_frames: i32,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            flags: 0,
            dpr: 1.0,
            fast_mode: false,
            interactive_transform: false,
            antialias_threshold: ANTIALIAS_THRESHOLD,
            viewport_interest_area_threshold: VIEWPORT_INTEREST_AREA_THRESHOLD,
            dpr_viewport_interest_area_threshold: VIEWPORT_INTEREST_AREA_THRESHOLD,
            max_blocking_time_ms: MAX_BLOCKING_TIME_MS,
            node_batch_threshold: NODE_BATCH_THRESHOLD,
            blur_downscale_threshold: BLUR_DOWNSCALE_THRESHOLD,
            capture_frames: 0,
        }
    }
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

    pub fn set_capture_frames(&mut self, capture_frames: i32) {
        self.capture_frames = capture_frames;
    }

    /// Updates the dpr viewport interest area threshold.
    /// This function is updated when the dpr or the
    /// viewport_interest_area_threshold is changed
    fn update_dpr_viewport_interest_area_threshold(&mut self) {
        // TODO: this will likely need to change once we have the tile atlas in place
        self.dpr_viewport_interest_area_threshold =
            ((self.dpr * self.viewport_interest_area_threshold as f32).ceil() as i32)
                .min(MIN_DPR_VIEWPORT_INTEREST_AREA_THRESHOLD);
    }

    /// Sets the devicePixelRatio.
    pub fn set_dpr(&mut self, value: f32) -> bool {
        if value > 0.0 && self.dpr != value {
            self.dpr = value;
            self.update_dpr_viewport_interest_area_threshold();
            return true;
        }
        false
    }

    /// Interactive transform is ON while the user is dragging, resizing
    /// or rotating a shape. Callers use it to keep per-frame flushing
    /// enabled and to render visible tiles in a single frame so tiles
    /// never appear sequentially or flicker during the gesture.
    pub fn is_interactive_transform(&self) -> bool {
        self.interactive_transform
    }

    pub fn set_interactive_transform(&mut self, enabled: bool) {
        self.interactive_transform = enabled;
    }

    pub fn is_text_editor_v3(&self) -> bool {
        self.flags & TEXT_EDITOR_V3 == TEXT_EDITOR_V3
    }

    pub fn show_wasm_info(&self) -> bool {
        self.flags & SHOW_WASM_INFO == SHOW_WASM_INFO
    }

    pub fn set_antialias_threshold(&mut self, value: f32) -> bool {
        if value.is_finite() && value > 0.0 {
            self.antialias_threshold = value;
            return true;
        }
        false
    }

    pub fn set_blur_downscale_threshold(&mut self, value: f32) -> bool {
        if value.is_finite() && value > 0.0 {
            self.blur_downscale_threshold = value;
            return true;
        }
        false
    }

    pub fn set_viewport_interest_area_threshold(&mut self, value: i32) -> bool {
        if value >= 0 && self.viewport_interest_area_threshold != value {
            self.viewport_interest_area_threshold = value;
            self.update_dpr_viewport_interest_area_threshold();
            return true;
        }
        false
    }

    pub fn set_node_batch_threshold(&mut self, value: i32) -> bool {
        if value > 0 {
            self.node_batch_threshold = value;
            return true;
        }
        false
    }

    pub fn set_max_blocking_time_ms(&mut self, value: i32) -> bool {
        if value > 0 {
            self.max_blocking_time_ms = value;
            return true;
        }
        false
    }
}
