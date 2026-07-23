use macros::wasm_error;

#[cfg(target_arch = "wasm32")]
use crate::emscripten::init_gl;

use crate::mem;
use crate::render::{gpu_state::GpuState, RenderResources, RenderState};
use crate::state::{State, TextEditorState, UIState};

static mut DESIGN_STATE: *mut State = std::ptr::null_mut();
static mut GPU_STATE: *mut GpuState = std::ptr::null_mut();
static mut RENDER_STATE: *mut RenderState = std::ptr::null_mut();
static mut UI_STATE: *mut UIState = std::ptr::null_mut();
static mut TEXT_EDITOR_STATE: *mut TextEditorState = std::ptr::null_mut();
static mut RENDER_RESOURCES: *mut RenderResources = std::ptr::null_mut();

pub(crate) fn get_design_state() -> &'static mut State {
    unsafe {
        debug_assert!(!DESIGN_STATE.is_null(), "Design State is null");
        &mut *DESIGN_STATE
    }
}

#[inline(always)]
pub(crate) fn get_gpu_state() -> &'static mut GpuState {
    unsafe {
        assert!(
            !GPU_STATE.is_null(),
            "GPU State is null (headless instance?)"
        );
        &mut *GPU_STATE
    }
}

#[inline(always)]
pub(crate) fn get_render_state() -> &'static mut RenderState {
    unsafe {
        debug_assert!(!RENDER_STATE.is_null(), "Render State is null");
        &mut *RENDER_STATE
    }
}

#[inline(always)]
pub(crate) fn has_render_state() -> bool {
    unsafe { !RENDER_STATE.is_null() }
}

#[inline(always)]
pub(crate) fn get_resources() -> &'static mut RenderResources {
    unsafe {
        debug_assert!(!RENDER_RESOURCES.is_null(), "Render Resources is null");
        &mut *RENDER_RESOURCES
    }
}

#[inline(always)]
pub(crate) fn get_text_editor_state() -> &'static mut TextEditorState {
    unsafe {
        debug_assert!(!TEXT_EDITOR_STATE.is_null(), "Text Editor state is null");
        &mut *TEXT_EDITOR_STATE
    }
}

#[inline(always)]
pub(crate) fn get_ui_state() -> &'static mut UIState {
    unsafe {
        debug_assert!(!UI_STATE.is_null(), "UI State is null");
        &mut *UI_STATE
    }
}

// FIXME: These with_state* macros should be using our CriticalError instead of expect.
// But to do that, we need to not use them at domain-level (i.e. in business logic), just
// in the context of the wasm call.
#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        $block
    }};
}

#[macro_export]
macro_rules! with_current_shape_mut {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        $state.touch_current();
        if let Some($shape) = $state.current_shape_mut() {
            $block
        }
    };
}

#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

/// Initializes GPUState.
fn gpu_init() {
    unsafe {
        let gpu_state = GpuState::try_new().expect("Cannot initialize GPU State");
        GPU_STATE = Box::into_raw(Box::new(gpu_state));
    }
}

/// Initializes RenderState.
fn render_init(width: i32, height: i32) {
    unsafe {
        let render_state =
            RenderState::try_new(width, height).expect("Cannot initialize RenderState");
        RENDER_STATE = Box::into_raw(Box::new(render_state));
    }
}

/// Initializes the interactive RenderResources (GPU image store).
fn resources_init() {
    unsafe {
        let resources = RenderResources::try_new().expect("Cannot initialize RenderResources");
        RENDER_RESOURCES = Box::into_raw(Box::new(resources));
    }
}

/// Initializes GPU-free RenderResources for the headless export path.
fn resources_init_headless() {
    unsafe {
        let resources = RenderResources::try_new_headless()
            .expect("Cannot initialize headless RenderResources");
        RENDER_RESOURCES = Box::into_raw(Box::new(resources));
    }
}

/// Initializes DesignState.
fn design_init() {
    unsafe {
        let design_state = State::new();
        DESIGN_STATE = Box::into_raw(Box::new(design_state));
    }
}

/// Initializes TextEditorState.
fn text_editor_init() {
    unsafe {
        let text_editor_state = TextEditorState::new();
        TEXT_EDITOR_STATE = Box::into_raw(Box::new(text_editor_state));
    }
}

/// Initializes UIState.
fn ui_init() {
    unsafe {
        let ui_state = UIState::new();
        UI_STATE = Box::into_raw(Box::new(ui_state));
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn init(width: i32, height: i32) -> Result<()> {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
    gpu_init();
    resources_init();
    render_init(width, height);
    text_editor_init();
    design_init();
    ui_init();
    Ok(())
}

/// Boots the engine for headless export with no GPU/WebGL context: only
/// `RenderResources` (GPU-free) + the design state. The export (raster/PDF)
/// paths render onto their own surface; the interactive render loop is not
/// available on an instance initialized this way. `width`/`height` are kept for
/// API symmetry with `init` but unused — the export sizes from shape bounds.
#[no_mangle]
#[wasm_error]
pub extern "C" fn init_headless(_width: i32, _height: i32) -> Result<()> {
    resources_init_headless();
    design_init();
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_up() -> Result<()> {
    // Cancel the current animation frame if it exists so
    // it won't try to render without context
    let render_state = get_render_state();
    render_state.prepare_context_loss_cleanup();
    unsafe { DESIGN_STATE = std::ptr::null_mut() }
    mem::free_bytes()?;
    Ok(())
}
