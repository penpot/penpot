use crate::get_render_state;
use crate::render::video::{shape_overlay_blockers, VideoIneligible};
use crate::utils::uuid_from_u32_quartet;
use crate::uuid::Uuid;
use crate::with_state;

/// Redraws the shapes painting `image_id` once, so the tiles pick up the switch
/// between the poster frame and the composited overlay.
fn resync_image(image_id: Uuid) {
    with_state!(state, {
        get_render_state().refresh_composited_videos(&state.shapes);
        let ids = state.shapes.shapes_with_image(image_id).to_vec();
        for id in ids {
            state.touch_shape(id);
        }
    });
}

/// Marks an image as backed by a playing video, so its frames are stamped
/// during composition instead of rastered into the tiles. Called by
/// `app.render-wasm.api.video` when a video starts.
#[no_mangle]
pub extern "C" fn register_video_image(a: u32, b: u32, c: u32, d: u32) {
    let image_id = uuid_from_u32_quartet(a, b, c, d);
    get_render_state().videos.register(image_id);
    resync_image(image_id);
}

/// Stops treating an image as video. The shapes painting it go back to showing
/// the still image.
#[no_mangle]
pub extern "C" fn unregister_video_image(a: u32, b: u32, c: u32, d: u32) {
    let image_id = uuid_from_u32_quartet(a, b, c, d);
    get_render_state().videos.unregister(image_id);
    resync_image(image_id);
}

/// Whether a shape could have a video stamped during composition, as a code the
/// bridge turns into the reason shown in the sidebar. `0` means it could; any
/// other value is a `VideoIneligible` discriminant. Answered from the shape's
/// own properties, so it can be asked before a video is attached.
#[no_mangle]
pub extern "C" fn get_video_eligibility(a: u32, b: u32, c: u32, d: u32) -> u32 {
    with_state!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);

        let Some(shape) = state.shapes.get_raw(&shape_id) else {
            return VideoIneligible::NoVideoFill as u32;
        };

        match shape_overlay_blockers(&state.shapes, shape) {
            Ok(()) => 0,
            Err(reason) => reason as u32,
        }
    })
}

/// Turns the `video-overlay-wasm/v1` path on or off. With it off the renderer
/// keeps painting video frames through the tiles.
#[no_mangle]
pub extern "C" fn set_video_overlay_enabled(enabled: bool) {
    get_render_state().video_overlay_enabled = enabled;
    let image_ids: Vec<Uuid> = get_render_state().videos.iter().copied().collect();
    for image_id in image_ids {
        resync_image(image_id);
    }
}
