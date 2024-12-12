use crate::debug;

#[derive(Debug, Copy, Clone, PartialEq)]
pub struct RenderOptions {
    pub debug_flags: u32,
    pub dpr: Option<f32>,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            debug_flags: 0x00,
            dpr: None,
        }
    }
}

impl RenderOptions {
    pub fn is_debug_visible(&self) -> bool {
        self.debug_flags & debug::DEBUG_VISIBLE == debug::DEBUG_VISIBLE
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }
}
