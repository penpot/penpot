use crate::skia::textlayout::FontCollection;
use crate::skia::Image;
use crate::uuid::Uuid;
use crate::with_state_mut;
use crate::STATE;
use std::collections::HashSet;

pub fn uuid_from_u32_quartet(a: u32, b: u32, c: u32, d: u32) -> Uuid {
    let hi: u64 = ((a as u64) << 32) | b as u64;
    let lo: u64 = ((c as u64) << 32) | d as u64;
    Uuid::from_u64_pair(hi, lo)
}

pub fn uuid_to_u32_quartet(id: &Uuid) -> (u32, u32, u32, u32) {
    let (hi, lo) = id.as_u64_pair();
    let hihi32 = (hi >> 32) as u32;
    let hilo32 = hi as u32;
    let lohi32 = (lo >> 32) as u32;
    let lolo32 = lo as u32;
    (hihi32, hilo32, lohi32, lolo32)
}

pub fn uuid_from_u32(id: [u32; 4]) -> Uuid {
    uuid_from_u32_quartet(id[0], id[1], id[2], id[3])
}

pub fn get_image(image_id: &Uuid) -> Option<&Image> {
    with_state_mut!(state, { state.render_state_mut().images.get(image_id) })
}

// FIXME: move to a different place ?
pub fn get_fallback_fonts() -> &'static HashSet<String> {
    with_state_mut!(state, { state.render_state().fonts().get_fallback() })
}

pub fn get_font_collection() -> &'static FontCollection {
    with_state_mut!(state, { state.font_collection() })
}
