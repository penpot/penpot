#[derive(Debug, Clone)]
pub struct GlassEffect {
    pub surface_type: i32,          // 0=circle, 1=squircle, 2=concave, 3=lip
    pub bezel_width: f32,           // pixels (5–100)
    pub glass_thickness: f32,       // multiplier (0.2–3.0)
    pub refractive_index: f32,      // physical index (1.0–2.5)
    pub specular_angle: f32,        // radians
    pub specular_opacity: f32,      // 0–1
    pub specular_saturation: f32,   // 0=white, 9=vivid prismatic (0–12)
    pub chromatic_aberration: f32,  // pixels (0–10)
    pub splay: f32,                 // 0=flat pane, 1=dome
    pub tilt_angle: f32,            // radians
    pub edge_boost: f32,            // 0–15
    pub zoom: f32,                  // 0.5–3.0
    pub blur: f32,                  // sigma 0–20
    pub frost: f32,                 // 0–1
    pub hidden: bool,
}

pub const GLASS_SKSL: &str = include_str!("../shaders/glass_composite.sksl");
pub const GLASS_REFRACTION_SKSL: &str = include_str!("../shaders/glass_refraction.sksl");
pub const GLASS_DISPLACEMENT_SKSL: &str = include_str!("../shaders/glass_displacement.sksl");

impl GlassEffect {
    /// Combined blur sigma from blur + frost contribution.
    pub fn total_blur_sigma(&self) -> f32 {
        self.blur + self.frost * 8.0
    }
}
