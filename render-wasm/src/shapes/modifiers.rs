use std::collections::{HashMap, HashSet, VecDeque};

mod constraints;
mod flex_layout;

pub mod common;
pub mod grid_layout;

use crate::math::{self as math, bools, identitish, is_close_to, Bounds, Matrix, Point};
use common::GetBounds;

use crate::error::Result;
use crate::shapes;
use crate::shapes::{
    ConstraintH, ConstraintV, Frame, Group, GrowType, Layout, Modifier, PixelPrecision, Shape,
    TransformEntry, TransformEntrySource, Type,
};
use crate::state::{ShapesPoolRef, State};
use crate::uuid::Uuid;

#[allow(clippy::too_many_arguments)]
fn propagate_children(
    shape: &Shape,
    shapes: ShapesPoolRef,
    parent_bounds_before: &Bounds,
    parent_bounds_after: &Bounds,
    transform: Matrix,
    bounds: &HashMap<Uuid, Bounds>,
) -> Result<VecDeque<Modifier>> {
    let mut result = VecDeque::new();

    // We use the identity transform as a mark that a reflow is needed.
    // It's needed to be propagated to its children.
    if identitish(&transform) {
        for child_id in shape.children_ids_iter(true) {
            result.push_back(Modifier::transform_propagate(*child_id, transform));
        }
        return Ok(result);
    }

    for child_id in shape.children_ids_iter(true) {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);

        let constraint_h = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => {
                if child.is_absolute() {
                    child.constraint_h(ConstraintH::Left)
                } else {
                    ConstraintH::Left
                }
            }
            Type::Frame(_) => child.constraint_h(ConstraintH::Left),
            _ => child.constraint_h(ConstraintH::Scale),
        };

        let constraint_v = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => {
                if child.is_absolute() {
                    child.constraint_v(ConstraintV::Top)
                } else {
                    ConstraintV::Top
                }
            }
            Type::Frame(_) => child.constraint_v(ConstraintV::Top),
            _ => child.constraint_v(ConstraintV::Scale),
        };

        let transform = constraints::propagate_shape_constraints(
            parent_bounds_before,
            parent_bounds_after,
            &child_bounds,
            constraint_h,
            constraint_v,
            transform,
            child.ignore_constraints,
        )?;

        result.push_back(Modifier::transform_propagate(*child_id, transform));
    }

    Ok(result)
}

fn calculate_group_bounds(
    shape: &Shape,
    shapes: ShapesPoolRef,
    bounds: &HashMap<Uuid, Bounds>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(shape);
    let mut result = Vec::<Point>::new();

    for child_id in shape.children_ids_iter(true) {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);
        result.append(&mut child_bounds.points());
    }
    shape_bounds.with_points(result)
}

fn calculate_masked_group_bounds(
    shape: &Shape,
    shapes: ShapesPoolRef,
    bounds: &HashMap<Uuid, Bounds>,
) -> Option<Bounds> {
    let mask = shape.mask_id().and_then(|id| shapes.get(id))?;
    Some(bounds.find(mask))
}

fn calculate_bool_bounds(
    shape: &Shape,
    shapes: ShapesPoolRef,
    bounds: &HashMap<Uuid, Bounds>,
    modifiers: &HashMap<Uuid, Matrix>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(shape);
    let children_ids = shape.children_ids(true);

    let Type::Bool(bool_data) = &shape.shape_type else {
        return Some(shape_bounds);
    };

    let mut subtree = shapes.subtree(&shape.id);
    subtree.set_modifiers(modifiers.clone());

    let path = bools::bool_from_shapes(bool_data.bool_type, &children_ids, &subtree);
    let result = path.bounds();

    Some(result)
}

/// Which parts of the geometry a pixel-grid correction rounds: only the ones
/// the transform changes, so a move keeps its dimensions and a resize keeps
/// its anchored corner.
#[derive(PartialEq, Debug, Clone, Copy)]
struct SnapGeometry {
    x: bool,
    y: bool,
    width: bool,
    height: bool,
}

impl SnapGeometry {
    /// Flags the properties that differ between the two bounds. The axis mask
    /// in `precision` applies to the position only.
    fn new(before: &Bounds, after: &Bounds, precision: PixelPrecision) -> Self {
        SnapGeometry {
            x: precision.rounds_x() && !is_close_to(before.min_x(), after.min_x()),
            y: precision.rounds_y() && !is_close_to(before.min_y(), after.min_y()),
            width: !is_close_to(before.width(), after.width()),
            height: !is_close_to(before.height(), after.height()),
        }
    }

    fn resized(&self) -> bool {
        self.width || self.height
    }

    fn any(&self) -> bool {
        self.x || self.y || self.resized()
    }
}

/// Rounds a transform so the parts of the shape the gesture changed land on
/// the pixel grid, leaving everything else exactly where it is.
fn set_pixel_precision(transform: &mut Matrix, bounds: &mut Bounds, snap: SnapGeometry) {
    // Target corner, taken before the size correction: that correction scales
    // about the bounds center, and the translation below undoes the corner
    // displacement it causes. An unsnapped axis targets its own value.
    let x = if snap.x {
        bounds.min_x().round()
    } else {
        bounds.min_x()
    };
    let y = if snap.y {
        bounds.min_y().round()
    } else {
        bounds.min_y()
    };

    if snap.resized() {
        let tr = bounds.transform_matrix().unwrap_or_default();
        let tr_inv = tr.invert().unwrap_or_default();

        let width = bounds.width();
        let height = bounds.height();

        // A rounded dimension is never smaller than one pixel.
        let target_width = f32::max(1.0, width.round());
        let target_height = f32::max(1.0, height.round());

        let scale_width = if snap.width && width > 0.1 {
            f32::max(0.01, target_width / width)
        } else {
            1.0
        };
        let scale_height = if snap.height && height > 0.1 {
            f32::max(0.01, target_height / height)
        } else {
            1.0
        };

        if f32::is_finite(scale_width) && f32::is_finite(scale_height) {
            let mut round_transform = Matrix::scale((scale_width, scale_height));
            round_transform.post_concat(&tr);
            round_transform.pre_concat(&tr_inv);
            transform.post_concat(&round_transform);
            bounds.transform_mut(&round_transform);
        }
    }

    let dx = x - bounds.min_x();
    let dy = y - bounds.min_y();

    if f32::is_finite(dx) && f32::is_finite(dy) {
        let round_transform = Matrix::translate((dx, dy));
        transform.post_concat(&round_transform);
        bounds.transform_mut(&round_transform);
    }
}

#[allow(clippy::too_many_arguments)]
fn propagate_transform(
    entry: TransformEntry,
    pixel_precision: PixelPrecision,
    state: &State,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
    modifiers: &mut HashMap<Uuid, Matrix>,
    reflown: &mut HashSet<Uuid>,
    reflowed_shapes: &mut HashSet<Uuid>,
    pending_reflows: &mut HashSet<Uuid>,
) -> Result<()> {
    let Some(shape) = state.shapes.get(&entry.id) else {
        return Ok(());
    };

    let shapes = &state.shapes;
    let shape_bounds_before = bounds.find(shape);
    let mut shape_bounds_after = shape_bounds_before.transform(&entry.transform);

    let mut transform = entry.transform;

    // Only check the text layout when the width/height changes
    if !is_close_to(shape_bounds_before.width(), shape_bounds_after.width())
        || !is_close_to(shape_bounds_before.height(), shape_bounds_after.height())
    {
        if let Type::Text(text_content) = &shape.shape_type {
            let width_changed =
                !is_close_to(shape_bounds_before.width(), shape_bounds_after.width());
            let height_changed =
                !is_close_to(shape_bounds_before.height(), shape_bounds_after.height());
            let resized_selrect = math::Rect::from_xywh(
                shape.selrect.left(),
                shape.selrect.top(),
                shape_bounds_after.width(),
                shape_bounds_after.height(),
            );
            match text_content.grow_type() {
                GrowType::AutoHeight => {
                    let height_before = text_content.size.height;
                    let new_height = if width_changed {
                        let mut clone = text_content.clone();
                        clone.update_layout(resized_selrect);
                        clone.size.height
                    } else {
                        height_before
                    };
                    if !is_close_to(height_before, new_height) && reflowed_shapes.insert(shape.id) {
                        entries.push_back(Modifier::reflow(shape.id, false));

                        if let Some(parent_id) = shape.parent_id {
                            for pid in
                                shapes::all_with_ancestors(&[parent_id], shapes, false).iter()
                            {
                                reflown.remove(pid);
                            }
                        }
                    }
                    let resize_transform = math::resize_matrix(
                        &shape_bounds_after,
                        &shape_bounds_after,
                        shape_bounds_after.width(),
                        new_height,
                    );
                    shape_bounds_after = shape_bounds_after.transform(&resize_transform);
                    transform.post_concat(&resize_transform);
                }
                GrowType::AutoWidth => {
                    let width_before = text_content.width();
                    let height_before = text_content.size.height;
                    let (new_width, new_height) = if height_changed {
                        let mut clone = text_content.clone();
                        clone.update_layout(resized_selrect);
                        (clone.width(), clone.size.height)
                    } else {
                        (width_before, height_before)
                    };
                    if (!is_close_to(width_before, new_width)
                        || !is_close_to(height_before, new_height))
                        && reflowed_shapes.insert(shape.id)
                    {
                        entries.push_back(Modifier::reflow(shape.id, false));

                        if let Some(parent_id) = shape.parent_id {
                            for pid in
                                shapes::all_with_ancestors(&[parent_id], shapes, false).iter()
                            {
                                reflown.remove(pid);
                            }
                        }
                    }
                    let resize_transform = math::resize_matrix(
                        &shape_bounds_after,
                        &shape_bounds_after,
                        new_width,
                        new_height,
                    );
                    shape_bounds_after = shape_bounds_after.transform(&resize_transform);
                    transform.post_concat(&resize_transform);
                }
                GrowType::Fixed => {}
            }
        }
    }

    if pixel_precision.enabled() {
        let snap = SnapGeometry::new(&shape_bounds_before, &shape_bounds_after, pixel_precision);
        if snap.any() {
            set_pixel_precision(&mut transform, &mut shape_bounds_after, snap);
        }
    }

    if entry.propagate {
        let mut children = propagate_children(
            shape,
            shapes,
            &shape_bounds_before,
            &shape_bounds_after,
            transform,
            bounds,
        )?;
        entries.append(&mut children);
    }

    bounds.insert(shape.id, shape_bounds_after);

    let mut shape_modif = modifiers.get(&shape.id).copied().unwrap_or_default();
    shape_modif.post_concat(&transform);
    modifiers.insert(shape.id, shape_modif);

    let is_resize = !math::is_move_only_matrix(&transform);
    let is_propagate = entry.source == TransformEntrySource::Propagate;

    // If this is a layout and we're only moving don't need to reflow
    if shape.has_layout() && is_resize && pending_reflows.insert(shape.id) {
        entries.push_back(Modifier::reflow(shape.id, false));
    }

    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
        // When the parent is either a group or a layout we only mark for reflow
        // if the current transformation is not a move propagation.
        // If it's a move propagation we don't need to reflow, the parent is already changed.
        if (parent.has_layout() || parent.is_group_like())
            && (is_resize || !is_propagate)
            && pending_reflows.insert(parent.id)
        {
            entries.push_back(Modifier::reflow(parent.id, false));
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn propagate_reflow(
    id: &Uuid,
    state: &State,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
    layout_reflows: &mut HashSet<Uuid>,
    reflown: &mut HashSet<Uuid>,
    modifiers: &HashMap<Uuid, Matrix>,
    pending_reflows: &mut HashSet<Uuid>,
) {
    let Some(shape) = state.shapes.get(id) else {
        return;
    };

    let shapes = &state.shapes;

    if reflown.contains(id) {
        return;
    }

    match &shape.shape_type {
        Type::Frame(Frame {
            layout: Some(_), ..
        }) => {
            layout_reflows.insert(*id);
        }
        Type::Group(Group { masked: true }) => {
            // Masked group bounds are the mask shape bounds (first child in stored
            // order, children_ids returns children in reverse order)
            if let Some(shape_bounds) = calculate_masked_group_bounds(shape, shapes, bounds) {
                bounds.insert(shape.id, shape_bounds);
            }
            reflown.insert(*id);
        }
        Type::Group(_) => {
            if let Some(shape_bounds) = calculate_group_bounds(shape, shapes, bounds) {
                bounds.insert(shape.id, shape_bounds);
            }
            reflown.insert(*id);
        }
        Type::Bool(_) => {
            if let Some(shape_bounds) = calculate_bool_bounds(shape, shapes, bounds, modifiers) {
                bounds.insert(shape.id, shape_bounds);
            }
            reflown.insert(*id);
        }
        _ => {}
    }

    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
        if (parent.has_layout() || parent.is_group_like()) && pending_reflows.insert(parent.id) {
            entries.push_back(Modifier::reflow(parent.id, false));
        }
    }
}

fn reflow_shape(
    id: &Uuid,
    state: &State,
    reflown: &mut HashSet<Uuid>,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
) -> Result<()> {
    let Some(shape) = state.shapes.get(id) else {
        return Ok(());
    };

    let shapes = &state.shapes;

    let Type::Frame(frame_data) = &shape.shape_type else {
        return Ok(());
    };

    if let Some(Layout::FlexLayout(layout_data, flex_data)) = &frame_data.layout {
        let mut children =
            flex_layout::reflow_flex_layout(shape, layout_data, flex_data, shapes, bounds)?;
        entries.append(&mut children);
    } else if let Some(Layout::GridLayout(layout_data, grid_data)) = &frame_data.layout {
        let mut children =
            grid_layout::reflow_grid_layout(shape, layout_data, grid_data, shapes, bounds)?;
        entries.append(&mut children);
    }
    reflown.insert(*id);
    Ok(())
}

/// Propagates a set of transforms through the shape tree, returning one
/// transform per affected shape.
///
/// The transforms are relative to the committed geometry, so callers clear
/// any transform modifier of their own before propagating.
pub fn propagate_modifiers(
    state: &State,
    modifiers: &[TransformEntry],
    pixel_precision: PixelPrecision,
) -> Result<Vec<TransformEntry>> {
    let mut entries: VecDeque<_> = modifiers
        .iter()
        .map(|entry| {
            // If we receive a identity matrix we force a reflow
            if math::identitish(&entry.transform) {
                Modifier::Reflow(entry.id, false)
            } else {
                Modifier::Transform(*entry, pixel_precision)
            }
        })
        .collect();

    let shapes = &state.shapes;
    let mut modifiers = HashMap::<Uuid, Matrix>::new();
    let mut bounds = HashMap::<Uuid, Bounds>::new();
    let mut reflown = HashSet::<Uuid>::new();
    let mut layout_reflows = HashSet::<Uuid>::new();
    // Tracks text shapes that have already triggered a reflow across all outer
    // iterations, preventing oscillation when a parent layout re-emits a
    // transform for the same text shape in a later pass.
    let mut reflowed_shapes = HashSet::<Uuid>::new();
    // Tracks reflow ids already queued to avoid flooding entries with
    // duplicate Reflow entries when many children of the same parent
    // are transformed in the same pass.
    let mut pending_reflows = HashSet::<Uuid>::new();

    // We first propagate the transforms to the children and then after
    // recalculate the layouts. The layout can create further transforms that
    // we need to re-propagate.
    // In order for loop to eventualy finish, we limit the flex reflow to just
    // one (the reflown set).
    while !entries.is_empty() {
        while let Some(modifier) = entries.pop_front() {
            match modifier {
                Modifier::Transform(entry, pixel) => propagate_transform(
                    entry,
                    pixel,
                    state,
                    &mut entries,
                    &mut bounds,
                    &mut modifiers,
                    &mut reflown,
                    &mut reflowed_shapes,
                    &mut pending_reflows,
                )?,
                Modifier::Reflow(id, force_reflow) => {
                    pending_reflows.remove(&id);
                    if force_reflow {
                        reflown.remove(&id);
                    }

                    propagate_reflow(
                        &id,
                        state,
                        &mut entries,
                        &mut bounds,
                        &mut layout_reflows,
                        &mut reflown,
                        &modifiers,
                        &mut pending_reflows,
                    )
                }
            }
        }
        // We sort the reflows so they are processed deepest-first in the
        // tree structure. This way we can be sure that the children layouts
        // are already reflowed before their parents.
        let mut layout_reflows_vec: Vec<Uuid> =
            std::mem::take(&mut layout_reflows).into_iter().collect();
        layout_reflows_vec.sort_unstable_by(|id_a, id_b| {
            let da = shapes.get_depth(id_a);
            let db = shapes.get_depth(id_b);
            db.cmp(&da)
        });

        // This temporary bounds is necesary so the layouts can be calculated
        // correctly but will be discarded before the next iteration for the
        // bounds to be calculated properly with the modifiers.
        let mut bounds_temp = bounds.clone();

        for id in &layout_reflows_vec {
            if reflown.contains(id) {
                continue;
            }
            reflow_shape(id, state, &mut reflown, &mut entries, &mut bounds_temp)?;
        }
    }

    Ok(modifiers
        .iter()
        .map(|(key, val)| TransformEntry::from_input(*key, *val))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::math::{Matrix, Point};
    use crate::shapes::*;
    use crate::state::ShapesPool;

    #[test]
    fn test_propagate_shape() {
        let parent_id = Uuid::new_v4();

        let shapes = {
            let mut shapes = ShapesPool::new();
            shapes.initialize(10);

            let child_id = Uuid::new_v4();
            let child = shapes.add_shape(child_id);
            child.set_selrect(3.0, 3.0, 2.0, 2.0);

            let parent = shapes.add_shape(parent_id);
            parent.set_shape_type(Type::Group(Group::default()));
            parent.add_child(child_id);
            parent.set_selrect(1.0, 1.0, 5.0, 5.0);

            shapes
        };

        let parent = shapes.get(&parent_id).unwrap();
        let mut transform = Matrix::scale((2.0, 1.5));
        let x = parent.selrect.x();
        let y = parent.selrect.y();
        transform.post_translate(Point::new(x, y));
        transform.pre_translate(Point::new(-x, -y));

        let bounds_before = parent.bounds();
        let bounds_after = bounds_before.transform(&transform);

        let result = propagate_children(
            parent,
            &shapes,
            &bounds_before,
            &bounds_after,
            transform,
            &HashMap::new(),
        )
        .unwrap();

        assert_eq!(result.len(), 1);
    }

    #[test]
    fn test_pixel_precision_move_keeps_size() {
        let bounds = Bounds::from_rect(&math::Rect::from_xywh(10.4, 20.6, 100.5, 50.3));
        let mut bounds_after = bounds.transform(&Matrix::translate((5.2, 3.7)));
        let mut transform = Matrix::translate((5.2, 3.7));

        let snap = SnapGeometry::new(&bounds, &bounds_after, PixelPrecision::Both);
        set_pixel_precision(&mut transform, &mut bounds_after, snap);

        assert!(is_close_to(bounds_after.width(), 100.5));
        assert!(is_close_to(bounds_after.height(), 50.3));
        assert!(is_close_to(bounds_after.min_x(), 16.0));
        assert!(is_close_to(bounds_after.min_y(), 24.0));
        assert!(math::is_move_only_matrix(&transform));
    }

    #[test]
    fn test_pixel_precision_resize_rounds_size() {
        let bounds = Bounds::from_rect(&math::Rect::from_xywh(10.4, 20.6, 100.5, 50.3));
        let mut bounds_after = bounds.transform(&Matrix::scale((1.1, 1.1)));
        let mut transform = Matrix::scale((1.1, 1.1));

        let snap = SnapGeometry::new(&bounds, &bounds_after, PixelPrecision::Both);
        set_pixel_precision(&mut transform, &mut bounds_after, snap);

        assert!(is_close_to(
            bounds_after.width(),
            bounds_after.width().round()
        ));
        assert!(is_close_to(
            bounds_after.height(),
            bounds_after.height().round()
        ));
        assert!(is_close_to(
            bounds_after.min_x(),
            bounds_after.min_x().round()
        ));
        assert!(is_close_to(
            bounds_after.min_y(),
            bounds_after.min_y().round()
        ));
    }

    #[test]
    fn test_propagate_pixel_precision_move_only_rounds_position() {
        let shape_id = Uuid::new_v4();
        let mut state = State::new();
        state.shapes.initialize(10);
        {
            let shape = state.shapes.add_shape(shape_id);
            shape.set_selrect(10.4, 20.6, 110.9, 70.9);
        }

        let entry = TransformEntry::from_input(shape_id, Matrix::translate((5.2, 3.7)));
        let result = propagate_modifiers(&state, &[entry], PixelPrecision::Both).unwrap();

        let transform = result
            .iter()
            .find(|entry| entry.id == shape_id)
            .map(|entry| entry.transform)
            .unwrap();

        let shape = state.shapes.get(&shape_id).unwrap();
        let bounds = shape.bounds().transform(&transform);

        assert!(is_close_to(bounds.width(), 100.5));
        assert!(is_close_to(bounds.height(), 50.3));
        assert!(is_close_to(bounds.min_x(), 16.0));
        assert!(is_close_to(bounds.min_y(), 24.0));
    }

    #[test]
    fn test_propagate_pixel_precision_resize_keeps_anchored_corner() {
        let shape_id = Uuid::new_v4();
        let mut state = State::new();
        state.shapes.initialize(10);
        {
            let shape = state.shapes.add_shape(shape_id);
            shape.set_selrect(10.4, 20.6, 110.4, 70.6);
        }

        // Drag the bottom-right corner in small steps: the top-left corner
        // stays put on every step.
        for step in 1..40 {
            let delta = step as f32 * 0.05;
            let mut resize = Matrix::scale(((100.0 + delta) / 100.0, (50.0 + delta) / 50.0));
            resize.post_translate(Point::new(10.4, 20.6));
            resize.pre_translate(Point::new(-10.4, -20.6));

            let entry = TransformEntry::from_input(shape_id, resize);
            let result = propagate_modifiers(&state, &[entry], PixelPrecision::Both).unwrap();

            let transform = result
                .iter()
                .find(|entry| entry.id == shape_id)
                .map(|entry| entry.transform)
                .unwrap();

            let shape = state.shapes.get(&shape_id).unwrap();
            let bounds = shape.bounds().transform(&transform);

            assert!(
                is_close_to(bounds.min_x(), 10.4) && is_close_to(bounds.min_y(), 20.6),
                "corner moved to ({}, {}) at delta {}",
                bounds.min_x(),
                bounds.min_y(),
                delta
            );
            assert!(is_close_to(bounds.width(), bounds.width().round()));
            assert!(is_close_to(bounds.height(), bounds.height().round()));
        }
    }

    #[test]
    fn test_pixel_precision_only_x_leaves_y_untouched() {
        let bounds = Bounds::from_rect(&math::Rect::from_xywh(10.4, 20.6, 100.5, 50.3));
        let mut bounds_after = bounds.transform(&Matrix::translate((5.2, 0.0)));
        let mut transform = Matrix::translate((5.2, 0.0));

        let snap = SnapGeometry::new(&bounds, &bounds_after, PixelPrecision::OnlyX);
        set_pixel_precision(&mut transform, &mut bounds_after, snap);

        assert!(is_close_to(bounds_after.min_x(), 16.0));
        assert!(is_close_to(bounds_after.min_y(), 20.6));
    }

    #[test]
    fn test_pixel_precision_only_y_leaves_x_untouched() {
        let bounds = Bounds::from_rect(&math::Rect::from_xywh(10.4, 20.6, 100.5, 50.3));
        let mut bounds_after = bounds.transform(&Matrix::translate((0.0, 3.7)));
        let mut transform = Matrix::translate((0.0, 3.7));

        let snap = SnapGeometry::new(&bounds, &bounds_after, PixelPrecision::OnlyY);
        set_pixel_precision(&mut transform, &mut bounds_after, snap);

        assert!(is_close_to(bounds_after.min_x(), 10.4));
        assert!(is_close_to(bounds_after.min_y(), 24.0));
    }

    #[test]
    fn test_pixel_precision_resize_never_rounds_below_one_pixel() {
        let bounds = Bounds::from_rect(&math::Rect::from_xywh(10.0, 20.0, 0.4, 0.3));
        let mut bounds_after = bounds.transform(&Matrix::scale((1.5, 1.5)));
        let mut transform = Matrix::scale((1.5, 1.5));

        let snap = SnapGeometry::new(&bounds, &bounds_after, PixelPrecision::Both);
        set_pixel_precision(&mut transform, &mut bounds_after, snap);

        assert!(is_close_to(bounds_after.width(), 1.0));
        assert!(is_close_to(bounds_after.height(), 1.0));
    }

    #[test]
    fn test_propagate_pixel_precision_snaps_every_frame_of_a_gesture() {
        let shape_id = Uuid::new_v4();
        let mut state = State::new();
        state.shapes.initialize(10);
        {
            let shape = state.shapes.add_shape(shape_id);
            shape.set_selrect(10.4, 20.6, 110.9, 70.9);
        }

        // One frame of a drag, as the entry point runs it: clear the
        // modifiers, propagate the delta accumulated since the gesture
        // started, then push the result back as the active modifier, which is
        // what the renderer draws.
        let frame = |state: &mut State, delta: f32| {
            state.shapes.clear_transform_modifiers();

            let entry = TransformEntry::from_input(shape_id, Matrix::translate((delta, delta)));
            let result = propagate_modifiers(state, &[entry], PixelPrecision::Both).unwrap();
            let transform = result
                .iter()
                .find(|entry| entry.id == shape_id)
                .map(|entry| entry.transform)
                .unwrap();

            let bounds = state
                .shapes
                .get_raw(&shape_id)
                .unwrap()
                .bounds()
                .transform(&transform);

            state.set_modifiers(HashMap::from([(shape_id, transform)]));
            bounds
        };

        // Every frame lands on the pixel grid and keeps the size.
        for step in 1..40 {
            let bounds = frame(&mut state, step as f32 * 0.35);

            assert!(
                is_close_to(bounds.min_x(), bounds.min_x().round())
                    && is_close_to(bounds.min_y(), bounds.min_y().round()),
                "shape landed off the pixel grid at ({}, {}) on frame {}",
                bounds.min_x(),
                bounds.min_y(),
                step
            );
            assert!(is_close_to(bounds.width(), 100.5));
            assert!(is_close_to(bounds.height(), 50.3));
        }
    }

    #[test]
    fn test_propagate_pixel_precision_resize_only_rounds_the_changed_dimension() {
        let shape_id = Uuid::new_v4();
        let mut state = State::new();
        state.shapes.initialize(10);
        {
            let shape = state.shapes.add_shape(shape_id);
            shape.set_selrect(10.4, 20.6, 110.9, 70.9);
        }

        // Drag the right edge: the width lands on the grid, the height and
        // the top-left corner stay put.
        let mut resize = Matrix::scale((103.3 / 100.5, 1.0));
        resize.post_translate(Point::new(10.4, 20.6));
        resize.pre_translate(Point::new(-10.4, -20.6));

        let entry = TransformEntry::from_input(shape_id, resize);
        let result = propagate_modifiers(&state, &[entry], PixelPrecision::Both).unwrap();

        let transform = result
            .iter()
            .find(|entry| entry.id == shape_id)
            .map(|entry| entry.transform)
            .unwrap();

        let bounds = state
            .shapes
            .get_raw(&shape_id)
            .unwrap()
            .bounds()
            .transform(&transform);

        assert!(is_close_to(bounds.width(), 103.0));
        assert!(is_close_to(bounds.height(), 50.3));
        assert!(is_close_to(bounds.min_x(), 10.4));
        assert!(is_close_to(bounds.min_y(), 20.6));
    }

    #[test]
    fn test_group_bounds() {
        let parent_id = Uuid::new_v4();
        let shapes = {
            let mut shapes = ShapesPool::new();
            shapes.initialize(10);

            let child1_id = Uuid::new_v4();
            let child1 = shapes.add_shape(child1_id);
            child1.set_selrect(3.0, 3.0, 2.0, 2.0);

            let child2_id = Uuid::new_v4();
            let child2 = shapes.add_shape(child2_id);
            child2.set_selrect(0.0, 0.0, 1.0, 1.0);

            let parent = shapes.add_shape(parent_id);
            parent.set_shape_type(Type::Group(Group::default()));
            parent.add_child(child1_id);
            parent.add_child(child2_id);
            parent.set_selrect(0.0, 0.0, 3.0, 3.0);
            shapes
        };

        let parent = shapes.get(&parent_id).unwrap();

        let bounds = calculate_group_bounds(parent, &shapes, &HashMap::new()).unwrap();

        assert_eq!(bounds.width(), 3.0);
        assert_eq!(bounds.height(), 3.0);
    }

    #[test]
    fn test_masked_group_bounds_are_mask_bounds() {
        let parent_id = Uuid::new_v4();
        let shapes = {
            let mut shapes = ShapesPool::new();
            shapes.initialize(10);

            let mask_id = Uuid::new_v4();
            let mask = shapes.add_shape(mask_id);
            mask.set_selrect(1.0, 1.0, 5.0, 5.0);

            let content_id = Uuid::new_v4();
            let content = shapes.add_shape(content_id);
            content.set_selrect(0.0, 0.0, 10.0, 10.0);

            let parent = shapes.add_shape(parent_id);
            parent.set_shape_type(Type::Group(Group { masked: true }));
            parent.add_child(mask_id);
            parent.add_child(content_id);
            parent.set_selrect(1.0, 1.0, 5.0, 5.0);
            shapes
        };

        let parent = shapes.get(&parent_id).unwrap();

        let bounds = calculate_masked_group_bounds(parent, &shapes, &HashMap::new()).unwrap();

        assert_eq!(bounds.width(), 4.0);
        assert_eq!(bounds.height(), 4.0);
    }
}
