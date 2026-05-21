use macros::wasm_error;

use crate::mem;
use crate::render::{gpu_state::GpuState, RenderState};
use crate::state::{State, TextEditorState};

static mut DESIGN_STATE: *mut State = std::ptr::null_mut();

/// Design State.
pub(crate) fn get_design_state() -> &'static mut State {
    unsafe {
        debug_assert!(!DESIGN_STATE.is_null(), "Design State is null");
        &mut *DESIGN_STATE
    }
}

/// GPU State.
static mut GPU_STATE: *mut GpuState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_gpu_state() -> &'static mut GpuState {
    unsafe {
        debug_assert!(!GPU_STATE.is_null(), "GPU State is null");
        &mut *GPU_STATE
    }
}

/// Render State.
static mut RENDER_STATE: *mut RenderState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_render_state() -> &'static mut RenderState {
    unsafe {
        debug_assert!(!RENDER_STATE.is_null(), "Render State is null");
        &mut *RENDER_STATE
    }
}

/// Text Editor State
static mut TEXT_EDITOR_STATE: *mut TextEditorState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_text_editor_state() -> &'static mut TextEditorState {
    unsafe {
        debug_assert!(!TEXT_EDITOR_STATE.is_null(), "Text Editor state is null");
        &mut *TEXT_EDITOR_STATE
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

/// Initializes DesignState.
fn design_init() {
    unsafe {
        let design_state = State::new();
        DESIGN_STATE = Box::into_raw(Box::new(design_state));
    }
}

fn text_editor_init() {
    unsafe {
        let text_editor_state = TextEditorState::new();
        TEXT_EDITOR_STATE = Box::into_raw(Box::new(text_editor_state));
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn init(width: i32, height: i32) -> Result<()> {
    gpu_init();
    render_init(width, height);
    text_editor_init();
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
