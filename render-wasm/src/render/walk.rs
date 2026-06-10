use crate::shapes::{Corners, Shadow, Shape, Type};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;
use skia_safe::{Matrix, Rect};
use std::collections::HashMap;

pub type ClipStack = Vec<(Rect, Option<Corners>, Matrix, Matrix)>;

#[derive(Debug)]
pub struct NodeRenderState {
    pub id: Uuid,
    pub(crate) visited_children: bool,
    pub(crate) clip_bounds: Option<ClipStack>,
    pub(crate) visited_mask: bool,
    pub(crate) mask: bool,
    pub(crate) flattened: bool,
}

/// Get simplified children of a container, flattening nested flattened containers
pub fn get_simplified_children<'a>(
    tree: ShapesPoolRef<'a>,
    shape: &'a Shape,
    result: &mut Vec<Uuid>,
) {
    for child_id in shape.children_ids_iter(false) {
        if let Some(child) = tree.get(child_id) {
            if child.can_flatten() {
                get_simplified_children(tree, child, result);
            } else {
                result.push(*child_id);
            }
        }
    }
}

impl NodeRenderState {
    pub fn is_root(&self) -> bool {
        self.id.is_nil()
    }

    /// Calculates the clip bounds for child elements of a given shape.
    ///
    /// This function determines the clipping region that should be applied to child elements
    /// when rendering. It takes into account the element's selection rectangle, transform.
    ///
    /// # Parameters
    ///
    /// * `element` - The shape element for which to calculate clip bounds
    /// * `offset` - Optional offset (x, y) to adjust the bounds position. When provided,
    ///   the bounds are translated by the negative of this offset, effectively moving
    ///   the clipping region to compensate for coordinate system transformations.
    ///   This is useful for nested coordinate systems or when elements are grouped
    ///   and need relative positioning adjustments.
    fn append_clip(
        clip_stack: Option<ClipStack>,
        clip: (Rect, Option<Corners>, Matrix, Matrix),
    ) -> Option<ClipStack> {
        match clip_stack {
            Some(mut stack) => {
                stack.push(clip);
                Some(stack)
            }
            None => Some(vec![clip]),
        }
    }

    pub fn get_children_clip_bounds(
        &self,
        element: &Shape,
        offset: Option<(f32, f32)>,
        clip_inset: Option<f32>,
    ) -> Option<ClipStack> {
        if self.id.is_nil() || !element.clip() {
            return self.clip_bounds.clone();
        }

        let mut bounds = element.selrect();
        if let Some(offset) = offset {
            let x = bounds.x() - offset.0;
            let y = bounds.y() - offset.1;
            let width = bounds.width();
            let height = bounds.height();
            bounds.set_xywh(x, y, width, height);
        }
        let mut transform = element.transform;
        transform.post_translate(bounds.center());
        transform.pre_translate(-bounds.center());

        let corners = match &element.shape_type {
            Type::Rect(data) => data.corners,
            Type::Frame(data) => data.corners,
            _ => None,
        };

        if let Some(clip_inset) = clip_inset.filter(|&e| e > 0.0) {
            bounds.inset((clip_inset, clip_inset));
        }

        Self::append_clip(
            self.clip_bounds.clone(),
            (
                bounds,
                corners,
                transform,
                transform.invert().unwrap_or_default(),
            ),
        )
    }

    /// Calculates the clip bounds for shadow rendering of a given shape.
    ///
    /// This function determines the clipping region that should be applied when rendering a
    /// shadow for a shape element. For frames, it uses the shadow bounds to clip nested
    /// shadows. For groups, it returns the existing clip bounds since groups should not
    /// constrain nested shadows based on their selection rectangle bounds.
    ///
    /// # Parameters
    ///
    /// * `element` - The shape element for which to calculate shadow clip bounds
    /// * `shadow` - The shadow configuration containing blur, offset, and other properties
    pub fn get_nested_shadow_clip_bounds(
        &self,
        element: &Shape,
        shadow: &Shadow,
    ) -> Option<ClipStack> {
        if self.id.is_nil() {
            return self.clip_bounds.clone();
        }

        // Assert that the shape is either a Frame or Group
        assert!(
            matches!(element.shape_type, Type::Frame(_) | Type::Group(_)),
            "Shape must be a Frame or Group for nested shadow clip bounds calculation"
        );

        match &element.shape_type {
            Type::Frame(_) => {
                let mut bounds = element.get_selrect_shadow_bounds(shadow);
                let blur_inset = (shadow.blur * 2.).max(0.0);
                if blur_inset > 0.0 {
                    let max_inset_x = (bounds.width() * 0.5).max(0.0);
                    let max_inset_y = (bounds.height() * 0.5).max(0.0);
                    // Clamp the inset so we never shrink more than half of the width/height;
                    // otherwise the rect could end up inverted on small frames.
                    let inset_x = blur_inset.min(max_inset_x);
                    let inset_y = blur_inset.min(max_inset_y);
                    if inset_x > 0.0 || inset_y > 0.0 {
                        bounds.inset((inset_x, inset_y));
                    }
                }

                let mut transform = element.transform;
                transform.post_translate(element.center());
                transform.pre_translate(-element.center());

                let corners = match &element.shape_type {
                    Type::Frame(data) => data.corners,
                    _ => None,
                };

                Self::append_clip(
                    self.clip_bounds.clone(),
                    (
                        bounds,
                        corners,
                        transform,
                        transform.invert().unwrap_or_default(),
                    ),
                )
            }
            _ => self.clip_bounds.clone(),
        }
    }
}

/*
 * Sort by z_index descending (higher z renders on top).
 * The sort is stable so if the values are equal the index for the children
 * has preference.
 * When changing this method check the benchmark
 */
pub fn sort_z_index(tree: ShapesPoolRef, element: &Shape, children_ids: Vec<Uuid>) -> Vec<Uuid> {
    if element.has_layout() {
        let mut ids = children_ids;
        ids.sort_by_cached_key(|id| {
            std::cmp::Reverse(tree.get(id).map(|s| s.z_index()).unwrap_or(0))
        });
        if element.is_flex() && !element.is_flex_reverse() {
            ids.reverse();
        }
        ids
    } else {
        children_ids
    }
}

pub struct RenderStats {
    pub counts: HashMap<Uuid, i32>,
}

#[allow(dead_code)]
impl RenderStats {
    pub fn new() -> Self {
        Self {
            counts: HashMap::new(),
        }
    }

    fn count(&mut self, id: Uuid) -> i32 {
        let counter = self.counts.entry(id).or_insert(0);
        *counter += 1;
        *counter
    }

    fn clear(&mut self) {
        self.counts.clear();
    }

    #[allow(dead_code)]
    fn get(&self, id: &Uuid) -> Option<&i32> {
        self.counts.get(id)
    }

    pub(crate) fn print(&self) {
        let mut sum: i32 = 0;
        for (&id, &count) in self.counts.iter() {
            println!("{}: {}", id, count);
            sum += count;
        }
        println!("{}: {}", self.counts.len(), sum);
    }
}
