#![allow(dead_code)]
use crate::math::{self as math, Bounds, Matrix, Point, Vector, VectorExt};
use crate::shapes::{
    modified_children_ids, AlignContent, AlignItems, AlignSelf, FlexData, JustifyContent,
    LayoutData, LayoutItem, Modifier, Shape, StructureEntry,
};
use crate::uuid::Uuid;

use std::collections::{HashMap, VecDeque};

use super::common::GetBounds;

const MIN_SIZE: f32 = 0.01;
const MAX_SIZE: f32 = f32::INFINITY;

#[derive(Debug)]
struct TrackData {
    main_size: f32,
    across_size: f32,
    max_across_size: f32,
    is_fill_across: bool,
    shapes: Vec<ChildAxis>,
    anchor: Point,
}

impl TrackData {
    fn default() -> Self {
        Self {
            main_size: MIN_SIZE,
            across_size: MIN_SIZE,
            max_across_size: MAX_SIZE,
            is_fill_across: false,
            shapes: Vec::new(),
            anchor: Point::default(),
        }
    }
}

#[derive(Debug)]
struct LayoutAxis {
    main_size: f32,
    across_size: f32,
    main_v: Vector,
    across_v: Vector,
    padding_main_start: f32,
    padding_main_end: f32,
    padding_across_start: f32,
    padding_across_end: f32,
    gap_main: f32,
    gap_across: f32,
    is_auto_main: bool,
    is_auto_across: bool,
}

impl LayoutAxis {
    fn main_space(&self) -> f32 {
        self.main_size - self.padding_main_start - self.padding_main_end
    }
    fn across_space(&self) -> f32 {
        self.across_size - self.padding_across_start - self.padding_across_end
    }
}

impl LayoutAxis {
    fn new(
        shape: &Shape,
        layout_bounds: &Bounds,
        layout_data: &LayoutData,
        flex_data: &FlexData,
    ) -> Self {
        if flex_data.is_row() {
            Self {
                main_size: layout_bounds.width(),
                across_size: layout_bounds.height(),
                main_v: layout_bounds.hv(1.0),
                across_v: layout_bounds.vv(1.0),
                padding_main_start: layout_data.padding_left,
                padding_main_end: layout_data.padding_right,
                padding_across_start: layout_data.padding_top,
                padding_across_end: layout_data.padding_bottom,
                gap_main: layout_data.column_gap,
                gap_across: layout_data.row_gap,
                is_auto_main: shape.is_layout_horizontal_auto(),
                is_auto_across: shape.is_layout_vertical_auto(),
            }
        } else {
            Self {
                main_size: layout_bounds.height(),
                across_size: layout_bounds.width(),
                main_v: layout_bounds.vv(1.0),
                across_v: layout_bounds.hv(1.0),
                padding_main_start: layout_data.padding_top,
                padding_main_end: layout_data.padding_bottom,
                padding_across_start: layout_data.padding_left,
                padding_across_end: layout_data.padding_right,
                gap_main: layout_data.row_gap,
                gap_across: layout_data.column_gap,
                is_auto_main: shape.is_layout_vertical_auto(),
                is_auto_across: shape.is_layout_horizontal_auto(),
            }
        }
    }
}

#[derive(Debug, Copy, Clone)]
struct ChildAxis {
    id: Uuid,
    main_size: f32,
    across_size: f32,
    margin_main_start: f32,
    margin_main_end: f32,
    margin_across_start: f32,
    margin_across_end: f32,
    min_main_size: f32,
    max_main_size: f32,
    min_across_size: f32,
    max_across_size: f32,
    is_fill_main: bool,
    is_fill_across: bool,
    z_index: i32,
    bounds: Bounds,
}

impl ChildAxis {
    fn new(child: &Shape, child_bounds: &Bounds, flex_data: &FlexData) -> Self {
        let id = child.id;
        let layout_item = child.layout_item;
        let mut result = if flex_data.is_row() {
            Self {
                id,
                main_size: child_bounds.width(),
                across_size: child_bounds.height(),
                margin_main_start: layout_item.map(|i| i.margin_left).unwrap_or(0.0),
                margin_main_end: layout_item.map(|i| i.margin_right).unwrap_or(0.0),
                margin_across_start: layout_item.map(|i| i.margin_top).unwrap_or(0.0),
                margin_across_end: layout_item.map(|i| i.margin_bottom).unwrap_or(0.0),
                min_main_size: layout_item.and_then(|i| i.min_w).unwrap_or(MIN_SIZE),
                max_main_size: layout_item.and_then(|i| i.max_w).unwrap_or(MAX_SIZE),
                min_across_size: layout_item.and_then(|i| i.min_h).unwrap_or(MIN_SIZE),
                max_across_size: layout_item.and_then(|i| i.max_h).unwrap_or(MAX_SIZE),
                is_fill_main: child.is_layout_horizontal_fill(),
                is_fill_across: child.is_layout_vertical_fill(),
                z_index: layout_item.map(|i| i.z_index).unwrap_or(0),
                bounds: *child_bounds,
            }
        } else {
            Self {
                id,
                across_size: child_bounds.width(),
                main_size: child_bounds.height(),
                margin_across_start: layout_item.map(|i| i.margin_left).unwrap_or(0.0),
                margin_across_end: layout_item.map(|i| i.margin_right).unwrap_or(0.0),
                margin_main_start: layout_item.map(|i| i.margin_top).unwrap_or(0.0),
                margin_main_end: layout_item.map(|i| i.margin_bottom).unwrap_or(0.0),
                min_across_size: layout_item.and_then(|i| i.min_w).unwrap_or(MIN_SIZE),
                max_across_size: layout_item.and_then(|i| i.max_w).unwrap_or(MAX_SIZE),
                min_main_size: layout_item.and_then(|i| i.min_h).unwrap_or(MIN_SIZE),
                max_main_size: layout_item.and_then(|i| i.max_h).unwrap_or(MAX_SIZE),
                is_fill_main: child.is_layout_vertical_fill(),
                is_fill_across: child.is_layout_horizontal_fill(),
                z_index: layout_item.map(|i| i.z_index).unwrap_or(0),
                bounds: *child_bounds,
            }
        };

        if result.is_fill_main {
            result.main_size = result.min_main_size;
        }
        if result.is_fill_across {
            result.across_size = result.min_across_size;
        }
        result
    }
}

fn initialize_tracks(
    shape: &Shape,
    layout_bounds: &Bounds,
    layout_axis: &LayoutAxis,
    flex_data: &FlexData,
    shapes: &HashMap<Uuid, &mut Shape>,
    bounds: &HashMap<Uuid, Bounds>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> Vec<TrackData> {
    let mut tracks = Vec::<TrackData>::new();
    let mut current_track = TrackData::default();
    let mut children = modified_children_ids(shape, structure.get(&shape.id));
    let mut first = true;

    if flex_data.is_reverse() {
        children.reverse();
    }

    for child_id in children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        if child.is_absolute() || child.hidden() {
            continue;
        }

        let default_bounds = bounds.find(child);
        let child_bounds = layout_bounds
            .box_bounds(&default_bounds)
            .unwrap_or(default_bounds);

        let child_axis = ChildAxis::new(child, &child_bounds, flex_data);

        let child_main_size = child_axis.margin_main_start
            + child_axis.margin_main_end
            + if child_axis.is_fill_main {
                child_axis.min_main_size
            } else {
                child_axis.main_size
            };

        let child_across_size = child_axis.margin_across_start
            + child_axis.margin_across_end
            + if child_axis.is_fill_across {
                child_axis.min_across_size
            } else {
                child_axis.across_size
            };

        let child_max_across_size = if child_axis.is_fill_across {
            child_axis.max_across_size
        } else {
            child_axis.across_size
        };

        let gap_main = if first { 0.0 } else { layout_axis.gap_main };
        let next_main_size = current_track.main_size + child_main_size + gap_main;

        if !layout_axis.is_auto_main
            && flex_data.is_wrap()
            && (next_main_size > layout_axis.main_space())
        {
            tracks.push(current_track);

            current_track = TrackData {
                main_size: child_main_size,
                across_size: child_across_size,
                shapes: Vec::from([child_axis]),
                is_fill_across: child_axis.is_fill_across,
                anchor: Point::default(),
                max_across_size: child_max_across_size,
            };
        } else {
            // Update current track
            current_track.main_size = next_main_size;
            current_track.across_size = f32::max(child_across_size, current_track.across_size);
            current_track.shapes.push(child_axis);
            current_track.is_fill_across =
                current_track.is_fill_across || child_axis.is_fill_across;
            current_track.max_across_size =
                f32::max(current_track.max_across_size, child_max_across_size);
        }

        first = false;
    }

    // Finalize current track
    tracks.push(current_track);

    tracks
}

// Resize main axis fill
fn distribute_fill_main_space(layout_axis: &LayoutAxis, tracks: &mut [TrackData]) {
    for track in tracks.iter_mut() {
        let mut left_space = layout_axis.main_space() - track.main_size;
        let mut to_resize_children: Vec<&mut ChildAxis> = Vec::new();

        for child in track.shapes.iter_mut() {
            if child.is_fill_main && child.main_size < child.max_main_size {
                to_resize_children.push(child);
            }
        }

        while left_space > MIN_SIZE && !to_resize_children.is_empty() {
            let current = left_space / to_resize_children.len() as f32;
            for i in (0..to_resize_children.len()).rev() {
                let child = &mut to_resize_children[i];
                let delta =
                    f32::min(child.max_main_size, child.main_size + current) - child.main_size;
                child.main_size += delta;
                left_space -= delta;
                track.main_size += delta;

                if (child.main_size - child.max_main_size).abs() < MIN_SIZE {
                    to_resize_children.remove(i);
                }
            }
        }
    }
}

fn distribute_fill_across_space(layout_axis: &LayoutAxis, tracks: &mut [TrackData]) {
    let total_across_size = tracks.iter().map(|t| t.across_size).sum::<f32>()
        + (tracks.len() - 1) as f32 * layout_axis.gap_across;
    let mut left_space = layout_axis.across_space() - total_across_size;
    let mut to_resize_tracks: Vec<&mut TrackData> = Vec::new();

    for track in tracks.iter_mut() {
        if track.is_fill_across && track.across_size < track.max_across_size {
            to_resize_tracks.push(track);
        }
    }

    while left_space > MIN_SIZE && !to_resize_tracks.is_empty() {
        let current = left_space / to_resize_tracks.len() as f32;
        for i in (0..to_resize_tracks.len()).rev() {
            let track = &mut to_resize_tracks[i];
            let delta =
                f32::min(track.max_across_size, track.across_size + current) - track.across_size;
            track.across_size += delta;
            left_space -= delta;

            if (track.across_size - track.max_across_size).abs() < MIN_SIZE {
                to_resize_tracks.remove(i);
            }
        }
    }

    // After assigning the across size to the tracks we can assing the size to the shapes
    for track in tracks.iter_mut() {
        if !track.is_fill_across {
            continue;
        }

        for child in track.shapes.iter_mut() {
            if child.is_fill_across {
                let mut size =
                    track.across_size - child.margin_across_start - child.margin_across_end;
                size = size.clamp(child.min_across_size, child.max_across_size);
                child.across_size = size;
            }
        }
    }
}

fn stretch_tracks_sizes(
    layout_axis: &LayoutAxis,
    tracks: &mut [TrackData],
    total_across_size: f32,
) {
    let total_across_size = total_across_size + (tracks.len() - 1) as f32 * layout_axis.gap_across;
    let left_space = layout_axis.across_space() - total_across_size;
    let delta = left_space / tracks.len() as f32;

    for track in tracks.iter_mut() {
        track.across_size += delta;
    }
}

fn calculate_track_positions(
    layout_data: &LayoutData,
    layout_axis: &LayoutAxis,
    layout_bounds: &Bounds,
    tracks: &mut [TrackData],
    total_across_size: f32,
) {
    let mut align_content = &layout_data.align_content;

    if layout_axis.is_auto_across {
        align_content = &AlignContent::Start;
    }

    let total_across_size_gap: f32 =
        total_across_size + (tracks.len() - 1) as f32 * layout_axis.gap_across;

    let (real_margin, real_gap) = match align_content {
        AlignContent::End => (
            layout_axis.across_size - total_across_size_gap - layout_axis.padding_across_end,
            layout_axis.gap_across,
        ),

        AlignContent::Center => (
            (layout_axis.across_size - total_across_size_gap) / 2.0,
            layout_axis.gap_across,
        ),

        AlignContent::SpaceBetween => (
            layout_axis.padding_across_start,
            f32::max(
                layout_axis.gap_across,
                (layout_axis.across_space() - total_across_size) / (tracks.len() - 1) as f32,
            ),
        ),

        AlignContent::SpaceAround => {
            let effective_gap =
                (layout_axis.across_space() - total_across_size) / tracks.len() as f32;
            (effective_gap / 2.0, effective_gap)
        }

        AlignContent::SpaceEvenly => {
            let effective_gap =
                (layout_axis.across_space() - total_across_size) / (tracks.len() + 1) as f32;
            (
                layout_axis.padding_across_start + effective_gap,
                effective_gap,
            )
        }

        _ => (layout_axis.padding_across_start, layout_axis.gap_across),
    };

    let mut next_anchor = layout_bounds.nw + layout_axis.across_v * real_margin;

    for track in tracks.iter_mut() {
        track.anchor = next_anchor;
        next_anchor += layout_axis.across_v * real_gap;
    }
}

fn calculate_track_data(
    shape: &Shape,
    layout_data: &LayoutData,
    flex_data: &FlexData,
    layout_bounds: &Bounds,
    shapes: &HashMap<Uuid, &mut Shape>,
    bounds: &HashMap<Uuid, Bounds>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> Vec<TrackData> {
    let layout_axis = LayoutAxis::new(shape, layout_bounds, layout_data, flex_data);
    let mut tracks = initialize_tracks(
        shape,
        layout_bounds,
        &layout_axis,
        flex_data,
        shapes,
        bounds,
        structure,
    );

    if !layout_axis.is_auto_main {
        distribute_fill_main_space(&layout_axis, &mut tracks);
    }

    if !layout_axis.is_auto_across {
        distribute_fill_across_space(&layout_axis, &mut tracks);
    }

    let total_across_size = tracks.iter().map(|t| t.across_size).sum::<f32>();

    if !layout_axis.is_auto_across && layout_data.align_content == AlignContent::Stretch {
        stretch_tracks_sizes(&layout_axis, &mut tracks, total_across_size);
    }

    calculate_track_positions(
        layout_data,
        &layout_axis,
        layout_bounds,
        &mut tracks,
        total_across_size,
    );
    tracks
}

fn first_anchor(
    layout_data: &LayoutData,
    layout_axis: &LayoutAxis,
    track: &TrackData,
    total_shapes_size: f32,
) -> Point {
    if layout_axis.is_auto_main {
        return track.anchor + layout_axis.main_v * layout_axis.padding_main_start;
    }

    let delta = match layout_data.justify_content {
        JustifyContent::Center => (layout_axis.main_size - track.main_size) / 2.0,
        JustifyContent::End => {
            layout_axis.main_size - layout_axis.padding_main_end - track.main_size
        }
        JustifyContent::SpaceAround => {
            let effective_gap =
                (layout_axis.main_space() - total_shapes_size) / (track.shapes.len()) as f32;
            layout_axis.padding_main_end + f32::max(layout_axis.gap_main, effective_gap / 2.0)
        }
        JustifyContent::SpaceEvenly => {
            let effective_gap =
                (layout_axis.main_space() - total_shapes_size) / (track.shapes.len() + 1) as f32;
            layout_axis.padding_main_end + f32::max(layout_axis.gap_main, effective_gap)
        }
        _ => layout_axis.padding_main_start,
    };
    track.anchor + layout_axis.main_v * delta
}

fn next_anchor(
    layout_data: &LayoutData,
    layout_axis: &LayoutAxis,
    child_axis: &ChildAxis,
    track: &TrackData,
    prev_anchor: Point,
    total_shapes_size: f32,
) -> Point {
    if layout_axis.is_auto_main {
        let delta = child_axis.margin_main_start
            + child_axis.margin_main_end
            + child_axis.main_size
            + layout_axis.gap_main;
        return prev_anchor + layout_axis.main_v * delta;
    }

    let delta = child_axis.margin_main_start
        + child_axis.margin_main_end
        + match layout_data.justify_content {
            JustifyContent::SpaceBetween => {
                let effective_gap = (layout_axis.main_space() - total_shapes_size)
                    / (track.shapes.len() - 1) as f32;
                child_axis.main_size + f32::max(layout_axis.gap_main, effective_gap)
            }
            JustifyContent::SpaceAround => {
                let effective_gap =
                    (layout_axis.main_space() - total_shapes_size) / (track.shapes.len()) as f32;
                child_axis.main_size + f32::max(layout_axis.gap_main, effective_gap)
            }
            JustifyContent::SpaceEvenly => {
                let effective_gap = (layout_axis.main_space() - total_shapes_size)
                    / (track.shapes.len() + 1) as f32;
                child_axis.main_size + f32::max(layout_axis.gap_main, effective_gap)
            }
            _ => child_axis.main_size + layout_axis.gap_main,
        };
    prev_anchor + layout_axis.main_v * delta
}

fn child_position(
    child: &Shape,
    shape_anchor: Point,
    layout_data: &LayoutData,
    layout_axis: &LayoutAxis,
    child_axis: &ChildAxis,
    track: &TrackData,
) -> Point {
    let delta = child_axis.margin_across_start
        + match child.layout_item {
            Some(LayoutItem {
                align_self: Some(align_self),
                ..
            }) => match align_self {
                AlignSelf::Center => (track.across_size - child_axis.across_size) / 2.0,
                AlignSelf::End => track.across_size - child_axis.across_size,
                _ => 0.0,
            },
            _ => match layout_data.align_items {
                AlignItems::Center => (track.across_size - child_axis.across_size) / 2.0,
                AlignItems::End => track.across_size - child_axis.across_size,
                _ => 0.0,
            },
        };
    shape_anchor + layout_axis.main_v * child_axis.margin_main_start + layout_axis.across_v * delta
}

pub fn reflow_flex_layout(
    shape: &Shape,
    layout_data: &LayoutData,
    flex_data: &FlexData,
    shapes: &HashMap<Uuid, &mut Shape>,
    bounds: &mut HashMap<Uuid, Bounds>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> VecDeque<Modifier> {
    let mut result = VecDeque::new();
    let layout_bounds = &bounds.find(shape);
    let layout_axis = LayoutAxis::new(shape, layout_bounds, layout_data, flex_data);
    let tracks = calculate_track_data(
        shape,
        layout_data,
        flex_data,
        layout_bounds,
        shapes,
        bounds,
        structure,
    );

    for track in tracks.iter() {
        let total_shapes_size = track.shapes.iter().map(|s| s.main_size).sum::<f32>();
        let mut shape_anchor = first_anchor(layout_data, &layout_axis, track, total_shapes_size);

        for child_axis in track.shapes.iter() {
            let child_id = child_axis.id;
            let Some(child) = shapes.get(&child_id) else {
                continue;
            };

            let position = child_position(
                child,
                shape_anchor,
                layout_data,
                &layout_axis,
                child_axis,
                track,
            );
            let child_bounds = &child_axis.bounds;
            let delta_v = Vector::new_points(&child_bounds.nw, &position);

            let (new_width, new_height) = if flex_data.is_row() {
                (child_axis.main_size, child_axis.across_size)
            } else {
                (child_axis.across_size, child_axis.main_size)
            };

            let mut transform = Matrix::default();

            if (new_width - child_bounds.width()).abs() > MIN_SIZE
                || (new_height - child_bounds.height()).abs() > MIN_SIZE
            {
                transform.post_concat(&math::resize_matrix(
                    layout_bounds,
                    child_bounds,
                    new_width,
                    new_height,
                ));
            }

            if delta_v.x.abs() > MIN_SIZE || delta_v.y.abs() > MIN_SIZE {
                transform.post_concat(&Matrix::translate(delta_v));
            }

            result.push_back(Modifier::transform(child.id, transform));

            shape_anchor = next_anchor(
                layout_data,
                &layout_axis,
                child_axis,
                track,
                shape_anchor,
                total_shapes_size,
            );
        }
    }

    if layout_axis.is_auto_across || layout_axis.is_auto_main {
        let width = layout_bounds.width();
        let height = layout_bounds.height();

        let auto_across_size = if layout_axis.is_auto_across {
            tracks.iter().map(|track| track.across_size).sum::<f32>()
                + (tracks.len() - 1) as f32 * layout_axis.gap_across
                + layout_axis.padding_across_start
                + layout_axis.padding_across_end
        } else {
            0.0
        };

        let auto_main_size = if layout_axis.is_auto_main {
            tracks
                .iter()
                .map(|track| {
                    track.shapes.iter().map(|s| s.main_size).sum::<f32>()
                        + (track.shapes.len() - 1) as f32 * layout_axis.gap_main
                })
                .reduce(f32::max)
                .unwrap_or(0.01)
                + layout_axis.padding_main_start
                + layout_axis.padding_main_end
        } else {
            0.0
        };

        let (scale_width, scale_height) = if flex_data.is_row() {
            (
                if layout_axis.is_auto_main {
                    auto_main_size / width
                } else {
                    1.0
                },
                if layout_axis.is_auto_across {
                    auto_across_size / height
                } else {
                    1.0
                },
            )
        } else {
            (
                if layout_axis.is_auto_across {
                    auto_across_size / width
                } else {
                    1.0
                },
                if layout_axis.is_auto_main {
                    auto_main_size / height
                } else {
                    1.0
                },
            )
        };

        let parent_transform = layout_bounds.transform_matrix().unwrap_or_default();

        let parent_transform_inv = &parent_transform.invert().unwrap();
        let origin = parent_transform_inv.map_point(layout_bounds.nw);

        let mut scale = Matrix::scale((scale_width, scale_height));
        scale.post_translate(origin);
        scale.post_concat(&parent_transform);
        scale.pre_translate(-origin);
        scale.pre_concat(parent_transform_inv);

        let layout_bounds_after = layout_bounds.transform(&scale);
        result.push_back(Modifier::parent(shape.id, scale));
        bounds.insert(shape.id, layout_bounds_after);
    }
    result
}
