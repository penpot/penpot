#![allow(unused_variables, dead_code)]

use crate::math::{Bounds, Matrix, Point, Vector, VectorExt};
use crate::shapes::{FlexData, LayoutData, LayoutItem, Modifier, Shape, Sizing};
use std::collections::{HashMap, VecDeque};
use uuid::Uuid;

use super::common::GetBounds;

const MIN_SIZE: f32 = 0.01;
const MAX_SIZE: f32 = f32::INFINITY;

#[derive(Debug)]
struct TrackData<'a> {
    main_size: f32,
    across_size: f32,
    shapes: Vec<&'a Shape>,
    anchor: Point,
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
}

impl LayoutAxis {
    fn new(layout_bounds: &Bounds, layout_data: &LayoutData, flex_data: &FlexData) -> Self {
        if layout_data.is_row() {
            Self {
                main_size: layout_bounds.width(),
                across_size: layout_bounds.height(),
                main_v: layout_bounds.hv(1.0),
                across_v: layout_bounds.vv(1.0),
                padding_main_start: layout_data.padding_left,
                padding_main_end: layout_data.padding_right,
                padding_across_start: layout_data.padding_top,
                padding_across_end: layout_data.padding_bottom,
                gap_main: flex_data.row_gap,
                gap_across: flex_data.column_gap,
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
                gap_main: flex_data.column_gap,
                gap_across: flex_data.row_gap,
            }
        }
    }
}

#[derive(Debug)]
struct ChildAxis {
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
    is_auto_main: bool,
    is_auto_across: bool,
    is_absolute: bool,
    z_index: i32,
}

impl ChildAxis {
    fn new(
        child_bounds: &Bounds,
        layout_item: &Option<LayoutItem>,
        layout_data: &LayoutData,
    ) -> Self {
        if layout_data.is_row() {
            Self {
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
                is_fill_main: layout_item
                    .map(|i| i.h_sizing == Sizing::Fill)
                    .unwrap_or(false),
                is_fill_across: layout_item
                    .map(|i| i.v_sizing == Sizing::Fill)
                    .unwrap_or(false),
                is_auto_main: layout_item
                    .map(|i| i.h_sizing == Sizing::Auto)
                    .unwrap_or(false),
                is_auto_across: layout_item
                    .map(|i| i.v_sizing == Sizing::Auto)
                    .unwrap_or(false),
                is_absolute: layout_item.map(|i| i.is_absolute).unwrap_or(false),
                z_index: layout_item.map(|i| i.z_index).unwrap_or(0),
            }
        } else {
            Self {
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
                is_fill_across: layout_item
                    .map(|i| i.h_sizing == Sizing::Fill)
                    .unwrap_or(false),
                is_fill_main: layout_item
                    .map(|i| i.v_sizing == Sizing::Fill)
                    .unwrap_or(false),
                is_auto_across: layout_item
                    .map(|i| i.h_sizing == Sizing::Auto)
                    .unwrap_or(false),
                is_auto_main: layout_item
                    .map(|i| i.v_sizing == Sizing::Auto)
                    .unwrap_or(false),
                is_absolute: layout_item.map(|i| i.is_absolute).unwrap_or(false),
                z_index: layout_item.map(|i| i.z_index).unwrap_or(0),
            }
        }
    }
}

fn calculate_track_data<'a>(
    shape: &Shape,
    layout_data: &LayoutData,
    flex_data: &FlexData,
    layout_bounds: &Bounds,
    shapes: &'a HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> Vec<TrackData<'a>> {
    let layout_axis = LayoutAxis::new(layout_bounds, layout_data, flex_data);
    let layout_main_space =
        layout_axis.main_size - layout_axis.padding_main_start - layout_axis.padding_main_end;
    let layout_across_space =
        layout_axis.across_size - layout_axis.padding_across_start - layout_axis.padding_across_end;

    let mut tracks = Vec::<TrackData>::new();

    let mut current_main_size = 0.0;
    let mut current_across_size = 0.0;
    let mut current_track_shapes = Vec::<&Shape>::new();

    let mut children = shape.children.clone();

    if !layout_data.is_reverse() {
        children.reverse();
    }

    for child_id in children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);
        let child_axis = ChildAxis::new(&child_bounds, &shape.layout_item, layout_data);

        let next_main_size = current_main_size + child_axis.main_size + layout_axis.gap_main;

        if flex_data.is_wrap() && (next_main_size > layout_main_space) {
            // Save track, create next track
            tracks.push(TrackData {
                main_size: current_main_size,
                across_size: current_across_size,
                shapes: current_track_shapes,
                anchor: Point::default(),
            });
            current_main_size = child_axis.main_size;
            current_across_size = child_axis.across_size;
            current_track_shapes = Vec::from([child]);
        } else {
            // Update current track
            current_main_size = next_main_size;
            current_across_size = f32::max(child_axis.across_size, current_across_size);
            current_track_shapes.push(&child);
        }
    }

    // Finalize current track
    tracks.push(TrackData {
        main_size: current_main_size,
        across_size: current_across_size,
        shapes: current_track_shapes,
        anchor: Point::default(),
    });

    // Calculate anchors

    let mut next_anchor = layout_bounds.nw
        + layout_axis.main_v * layout_axis.padding_main_start
        + layout_axis.across_v * layout_axis.padding_across_start;

    for track in tracks.iter_mut() {
        track.anchor = next_anchor;
        next_anchor = next_anchor +
            layout_axis.across_v * (track.across_size + layout_axis.gap_across);
    }

    tracks
}

fn first_position(
    layout_data: &LayoutData,
    flex_data: &FlexData,
    layout_bounds: &Bounds,
    track: &TrackData,
) -> Point {
    track.anchor
}

fn next_position(
    layout_data: &LayoutData,
    flex_data: &FlexData,
    layout_item: &Option<LayoutItem>,
    track: &TrackData,
    layout_bounds: &Bounds,
    child_bounds: &Bounds,
    prev_position: Point,
) -> Point {
    let layout_axis = LayoutAxis::new(layout_bounds, layout_data, flex_data);
    let child_axis = ChildAxis::new(child_bounds, layout_item, layout_data);
    prev_position + layout_axis.main_v * (child_axis.main_size + layout_axis.gap_main)
}

pub fn reflow_flex_layout(
    shape: &Shape,
    layout_data: &LayoutData,
    flex_data: &FlexData,
    shapes: &HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    let mut result = VecDeque::new();
    let layout_bounds = &bounds.find(&shape);

    let tracks = calculate_track_data(shape, layout_data, flex_data, layout_bounds, shapes, bounds);

    for track in tracks.iter() {
        let mut position = first_position(layout_data, flex_data, layout_bounds, track);

        for child in track.shapes.iter() {
            let child_bounds = bounds.find(child);
            let delta_v = Vector::new_points(&child_bounds.nw, &position);

            if delta_v.x.abs() > 0.01 || delta_v.y.abs() > 0.01 {
                result.push_back(Modifier::transform(child.id, Matrix::translate(delta_v)));
            }

            position = next_position(
                layout_data,
                flex_data,
                &child.layout_item,
                track,
                layout_bounds,
                &child_bounds,
                position,
            );
        }
    }
    result
}
