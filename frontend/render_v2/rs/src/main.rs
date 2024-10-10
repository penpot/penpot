use std::boxed::Box;
use skia_safe::{
    gpu::{self, gl::FramebufferInfo, DirectContext},
    textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle, TextStyle, TypefaceFontProvider},
    FontMgr, Paint, Path, PaintStyle
};

use skia_safe as skia;

static ROBOTO_REGULAR: &[u8] = include_bytes!("RobotoMono-Regular.ttf");
static TYPEFACE_ALIAS: &str = "roboto-regular";

extern "C" {
    pub fn emscripten_GetProcAddress(
        name: *const ::std::os::raw::c_char,
    ) -> *const ::std::os::raw::c_void;
}

struct GpuState {
    context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions. Note that rust-skia data
/// structures are not thread safe, so a state must not be shared between different Web Workers.
pub struct State {
    gpu_state: GpuState,
    surface: skia::Surface,
    typeface_font_provider: TypefaceFontProvider,
    default_font: skia_safe::Font,
}

impl State {
    fn new(gpu_state: GpuState, surface: skia::Surface, typeface_font_provider: TypefaceFontProvider, default_font: skia_safe::Font) -> Self {
        State { gpu_state, surface, typeface_font_provider, default_font }
    }

    fn set_surface(&mut self, surface: skia::Surface) {
        self.surface = surface;
    }
}

fn init_gl() {
    unsafe {
        gl::load_with(|addr| {
            let addr = std::ffi::CString::new(addr).unwrap();
            emscripten_GetProcAddress(addr.into_raw() as *const _) as *const _
        });
    }
}

/// This needs to be done once per WebGL context.
fn create_gpu_state() -> GpuState {
    let interface = skia_safe::gpu::gl::Interface::new_native().unwrap();
    let context = skia_safe::gpu::direct_contexts::make_gl(interface, None).unwrap();
    let framebuffer_info = {
        let mut fboid: gl::types::GLint = 0;
        unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

        FramebufferInfo {
            fboid: fboid.try_into().unwrap(),
            format: skia_safe::gpu::gl::Format::RGBA8.into(),
            protected: skia_safe::gpu::Protected::No,
        }
    };

    GpuState {
        context,
        framebuffer_info,
    }
}

/// Create the Skia surface that will be used for rendering.
fn create_surface(gpu_state: &mut GpuState, width: i32, height: i32) -> skia::Surface {
    let backend_render_target =
        gpu::backend_render_targets::make_gl((width, height), 1, 8, gpu_state.framebuffer_info);

    gpu::surfaces::wrap_backend_render_target(
        &mut gpu_state.context,
        &backend_render_target,
        skia_safe::gpu::SurfaceOrigin::BottomLeft,
        skia_safe::ColorType::RGBA8888,
        None,
        None,
    )
    .unwrap()
}

fn render_rect(surface: &mut skia::Surface, rect: skia::Rect, color: skia::Color) {
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Fill);
    paint.set_color(color);
    paint.set_anti_alias(true);
    surface.canvas().draw_rect(rect, &paint);
}

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) -> Box<State> {
    let mut gpu_state = create_gpu_state();
    let surface = create_surface(&mut gpu_state, width, height);

    // skia_safe::Font::default() is empty, let's use something better
    let font_mgr = skia_safe::FontMgr::new();
    let typeface = font_mgr
             .new_from_data(ROBOTO_REGULAR, None)
             .expect("Failed to load ROBOTO font");        
    let default_font = skia_safe::Font::new(typeface.clone(), 10.0);

    let typeface_font_provider = {
      let mut typeface_font_provider = TypefaceFontProvider::new();
      // We need a system font manager to be able to load typefaces.
      typeface_font_provider.register_typeface(typeface, TYPEFACE_ALIAS);
      typeface_font_provider
    };

    let state = State::new(gpu_state, surface, typeface_font_provider, default_font);

    Box::new(state)
}

/// This is called from JS when the window is resized.
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn resize_surface(state: *mut State, width: i32, height: i32) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    let surface = create_surface(&mut state.gpu_state, width, height);
    state.set_surface(surface);
}

#[repr(C)]
pub struct Color {
    r: u8,
    g: u8,
    b: u8,
    a: f32,
}

#[repr(C)]
pub struct Rect {
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    r: f32,
    g: f32,
    b: f32,
    a: f32,
}

#[no_mangle]
pub extern "C" fn alloc_rects(len: usize) -> *mut Rect {
    // create a new mutable buffer with capacity `len`
    let mut buf : Vec<Rect> = Vec::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    // take ownership of the memory block and ensure the its destructor is not
    // called when the object goes out of scope at the end of the function
    std::mem::forget(buf);
    return ptr;
}

#[no_mangle]
pub unsafe fn free_rects(ptr: *mut Rect, len: usize) {
    let buf = Vec::<Rect>::from_raw_parts(ptr, len, len);
    std::mem::forget(buf);
}


/// Draws a rect at the specified coordinates with the give ncolor
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn draw_rect(state: *mut State, rect: &Rect, color: &Color) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    let r = skia::Rect::new(rect.left, rect.top, rect.right, rect.bottom);
    let color = skia::Color::from_argb((color.a * 255.0) as u8, color.r, color.g, color.b);
    render_rect(&mut state.surface, r, color);
}

#[no_mangle]
pub unsafe extern "C" fn flush(state: *mut State) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    state
        .gpu_state
        .context
        .flush_and_submit_surface(&mut state.surface, None);
}

#[no_mangle]
pub unsafe extern "C" fn translate(state: *mut State, dx: f32, dy: f32) {
    (*state).surface.canvas().translate((dx, dy));
}

#[no_mangle]
pub unsafe extern "C" fn scale(state: *mut State, sx: f32, sy: f32) {
    (*state).surface.canvas().scale((sx, sy));
}

#[no_mangle]
pub unsafe extern "C" fn reset_canvas(state: *mut State) {
    (*state).surface.canvas().clear(skia_safe::Color::TRANSPARENT);
    (*state).surface.canvas().reset_matrix();
}

#[no_mangle]
pub unsafe extern "C" fn draw_shapes(state: *mut State, ptr: *mut Rect, len: usize, zoom: f32, dx: f32, dy: f32) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    reset_canvas(state);
    scale(state, zoom, zoom);
    translate(state, dx, dy);
    // create a `Vec<Rect>` from the pointer to the linear memory and length
    let buf = Vec::<Rect>::from_raw_parts(ptr, len, len);

    let mut text_paint = skia::Paint::default();
    text_paint.set_anti_alias(true);
    text_paint.set_style(skia_safe::paint::Style::StrokeAndFill);
    text_paint.set_stroke_width(1.0);

    let mut path_paint = skia::Paint::default();
    path_paint.set_color(skia_safe::Color::BLACK);
    path_paint.set_anti_alias(true);
    path_paint.set_stroke_width(1.0);
    path_paint.set_style(PaintStyle::Stroke);

    for rect in buf.iter() {
        let r = skia::Rect::new(rect.left, rect.top, rect.right, rect.bottom);
        let color = skia::Color::from_argb((rect.a * 255.0) as u8, rect.r as u8, rect.g as u8, rect.b as u8);
        render_rect(&mut state.surface, r, color);

        text_paint.set_color(color);
        state.surface.canvas().draw_str("SKIA TEXT", (rect.left, rect.top), &state.default_font, &text_paint);

        let mut path = Path::new();
        path.move_to((rect.left, rect.top));
        path.line_to((rect.right, rect.bottom));
        state.surface.canvas().draw_path(&path, &path_paint);

        // https://github.com/rust-skia/rust-skia/blob/02c89a87649af8d2870fb631aae4a5e171887367/skia-org/src/skparagraph_example.rs#L18    
        let mut font_collection = FontCollection::new();
        font_collection.set_default_font_manager(Some(state.typeface_font_provider.clone().into()), None);
        let paragraph_style = ParagraphStyle::new();
        let mut paragraph_builder = ParagraphBuilder::new(&paragraph_style, font_collection);
        let mut ts = TextStyle::new();
        ts.set_foreground_paint(&Paint::default())
            .set_font_families(&[TYPEFACE_ALIAS]);
        paragraph_builder.push_style(&ts);
        paragraph_builder.add_text("Other skia text");
        let mut paragraph = paragraph_builder.build();
        paragraph.layout(256.0);
        paragraph.paint(state.surface.canvas(), (rect.left, rect.top));
    }
    flush(state);
    std::mem::forget(buf);
}

fn main() {
    init_gl();
}

#[cfg(test)]
mod tests {
    use super::*;
}
