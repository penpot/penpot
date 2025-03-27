#![allow(dead_code, unused_variables)]
use crate::math::{intersect_rays, Bounds, Matrix, Point, Ray, Vector, VectorExt};
use crate::shapes::{
    AlignContent, AlignItems, AlignSelf, GridCell, GridData, GridTrack, GridTrackType,
    JustifyContent, JustifyItems, JustifySelf, LayoutData, LayoutItem, Modifier, Shape,
};
use crate::uuid::Uuid;
use std::collections::{HashMap, VecDeque};

use super::common::GetBounds;

const MIN_SIZE: f32 = 0.01;
const MAX_SIZE: f32 = f32::INFINITY;

#[derive(Debug)]
struct CellData<'a> {
    shape: &'a Shape,
    anchor: Point,
    width: f32,
    height: f32,
    align_self: Option<AlignSelf>,
    justify_self: Option<JustifySelf>,
}

#[derive(Debug)]
struct TrackData {
    track_type: GridTrackType,
    value: f32,
    size: f32,
    max_size: f32,
    anchor_start: Point,
    anchor_end: Point,
}

fn calculate_tracks(
    is_column: bool,
    layout_data: &LayoutData,
    grid_data: &GridData,
    layout_bounds: &Bounds,
    cells: &Vec<GridCell>,
    shapes: &HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> Vec<TrackData> {
    let layout_size = if is_column {
        layout_bounds.width() - layout_data.padding_left - layout_data.padding_right
    } else {
        layout_bounds.height() - layout_data.padding_top - layout_data.padding_bottom
    };

    let grid_tracks = if is_column {
        &grid_data.columns
    } else {
        &grid_data.rows
    };

    let mut tracks = init_tracks(grid_tracks, layout_size);
    set_auto_base_size(is_column, &mut tracks, cells, shapes, bounds);

    set_auto_multi_span(is_column, layout_data, &layout_bounds, &mut tracks);
    set_flex_multi_span(is_column, layout_data, &layout_bounds, &mut tracks);

    set_fr_value(is_column, layout_data, &mut tracks, layout_size, 0.0);
    stretch_tracks(is_column, layout_data, &mut tracks, layout_size);
    assign_anchors(is_column, layout_data, &layout_bounds, &mut tracks);

    return tracks;
}

fn init_tracks(track: &Vec<GridTrack>, size: f32) -> Vec<TrackData> {
    track
        .iter()
        .map(|t| {
            let (size, max_size) = match t.track_type {
                GridTrackType::Fixed => (t.value, t.value),
                GridTrackType::Percent => (size * t.value / 100.0, size * t.value / 100.0),
                _ => (MIN_SIZE, MAX_SIZE),
            };
            TrackData {
                track_type: t.track_type,
                value: t.value,
                size,
                max_size,
                anchor_start: Point::default(),
                anchor_end: Point::default(),
            }
        })
        .collect()
}

// Go through cells to adjust auto sizes for span=1. Base is the max of its children
fn set_auto_base_size(
    column: bool,
    tracks: &mut Vec<TrackData>,
    cells: &Vec<GridCell>,
    shapes: &HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) {
    for cell in cells {
        let (prop, prop_span) = if column {
            (cell.column, cell.column_span)
        } else {
            (cell.row, cell.row_span)
        };

        if prop_span != 1 {
            continue;
        }

        let track = &mut tracks[(prop - 1) as usize];

        if track.track_type != GridTrackType::Auto && track.track_type != GridTrackType::Flex {
            continue;
        }

        let Some(shape) = cell.shape.and_then(|id| shapes.get(&id)) else {
            continue;
        };

        let bounds = bounds.find(shape);

        let shape_size = if column {
            bounds.width()
        } else {
            bounds.height()
        };

        let min_size = if column && shape.is_layout_horizontal_fill() {
            shape.layout_item.and_then(|i| i.min_w).unwrap_or(MIN_SIZE)
        } else if !column && shape.is_layout_vertical_fill() {
            shape.layout_item.and_then(|i| i.min_h).unwrap_or(MIN_SIZE)
        } else {
            shape_size
        };

        track.size = f32::max(track.size, min_size);
    }
}

// Adjust multi-spaned cells with no flex columns
fn set_auto_multi_span(
    _column: bool,
    _layout_data: &LayoutData,
    _layout_bounds: &Bounds,
    _tracks: &mut Vec<TrackData>,
) {
    // Sort descendant order of prop-span
    // Remove groups with flex (will be set in flex_multi_span)
    // Retrieve teh value we need to distribute (fixed cell size minus gaps)
    // Distribute the size between the tracks that already have a set value
    // Distribute the space between auto tracks
    // If we still have more space we distribute equally between all tracks
}

// Adjust multi-spaned cells with flex columns
fn set_flex_multi_span(
    _column: bool,
    _layout_data: &LayoutData,
    _layout_bounds: &Bounds,
    _tracks: &mut Vec<TrackData>,
) {
    // Sort descendant order of prop-span
    // Remove groups without flex (will be set in flex_auto_span)
    // Retrieve the value that we need to distribute (fixed size of cell minus gaps)
    // Distribute the size first between the tracks that have the fixed size
    // When finished we distribute equally between the the rest of the tracks
}

// Calculate the `fr` unit and adjust the size
fn set_fr_value(
    column: bool,
    layout_data: &LayoutData,
    tracks: &mut Vec<TrackData>,
    layout_size: f32,
    min_fr_size: f32,
) {
    let tot_gap: f32 = if column {
        layout_data.column_gap * (tracks.len() - 1) as f32
    } else {
        layout_data.row_gap * (tracks.len() - 1) as f32
    };

    // Total size already used
    let tot_size: f32 = tracks
        .iter()
        .filter(|t| t.track_type != GridTrackType::Flex)
        .map(|t| t.size)
        .sum::<f32>()
        + tot_gap;

    // Get the total of frs to divide the space into
    let tot_frs: f32 = tracks
        .iter()
        .filter(|t| t.track_type == GridTrackType::Flex)
        .map(|t| t.value)
        .sum();

    // Divide the space between FRS
    let fr = f32::max(min_fr_size, (layout_size - tot_size) / tot_frs);

    // Assign the space to the FRS
    tracks
        .iter_mut()
        .filter(|t| t.track_type == GridTrackType::Flex)
        .for_each(|t| t.size = f32::max(t.size, fr * t.value));
}

fn stretch_tracks(
    column: bool,
    layout_data: &LayoutData,
    tracks: &mut Vec<TrackData>,
    layout_size: f32,
) {
    if (column && layout_data.justify_content != JustifyContent::Stretch)
        || (!column && layout_data.align_content != AlignContent::Stretch)
    {
        return;
    }

    let tot_gap: f32 = if column {
        layout_data.column_gap * (tracks.len() - 1) as f32
    } else {
        layout_data.row_gap * (tracks.len() - 1) as f32
    };

    // Total size already used
    let tot_size: f32 = tracks.iter().map(|t| t.size).sum::<f32>() + tot_gap;

    let auto_tracks = tracks
        .iter_mut()
        .filter(|t| t.track_type == GridTrackType::Auto)
        .count() as f32;

    let free_space = layout_size - tot_size;
    let add_size = free_space / auto_tracks;

    // Assign the space to the FRS
    tracks
        .iter_mut()
        .filter(|t| t.track_type == GridTrackType::Auto)
        .for_each(|t| t.size = f32::min(t.max_size, t.size + add_size));
}

fn justify_to_align(justify: JustifyContent) -> AlignContent {
    match justify {
        JustifyContent::Start => AlignContent::Start,
        JustifyContent::End => AlignContent::End,
        JustifyContent::Center => AlignContent::Center,
        JustifyContent::SpaceBetween => AlignContent::SpaceBetween,
        JustifyContent::SpaceAround => AlignContent::SpaceAround,
        JustifyContent::SpaceEvenly => AlignContent::SpaceEvenly,
        JustifyContent::Stretch => AlignContent::Stretch,
    }
}

fn assign_anchors(
    column: bool,
    layout_data: &LayoutData,
    layout_bounds: &Bounds,
    tracks: &mut Vec<TrackData>,
) {
    let tot_track_length = tracks.iter().map(|t| t.size).sum::<f32>();

    let mut cursor = layout_bounds.nw;

    let (v, gap, size, padding_start, padding_end, align) = if column {
        (
            layout_bounds.hv(1.0),
            layout_data.column_gap,
            layout_bounds.width(),
            layout_data.padding_left,
            layout_data.padding_right,
            justify_to_align(layout_data.justify_content),
        )
    } else {
        (
            layout_bounds.vv(1.0),
            layout_data.row_gap,
            layout_bounds.height(),
            layout_data.padding_top,
            layout_data.padding_bottom,
            layout_data.align_content,
        )
    };

    let tot_gap = gap * (tracks.len() - 1) as f32;
    let tot_size = tot_track_length + tot_gap;
    let padding = padding_start + padding_end;
    let pad_size = size - padding;

    let (real_margin, real_gap) = match align {
        AlignContent::End => (size - padding_end - tot_size, gap),
        AlignContent::Center => ((size - tot_size) / 2.0, gap),
        AlignContent::SpaceAround => {
            let effective_gap = (pad_size - tot_track_length) / tracks.len() as f32;
            (padding_start + effective_gap / 2.0, effective_gap)
        }
        AlignContent::SpaceBetween => (
            padding_start,
            f32::max(
                gap,
                (pad_size - tot_track_length) / (tracks.len() - 1) as f32,
            ),
        ),
        _ => (padding_start + 0.0, gap),
    };

    cursor = cursor + (v * real_margin);

    for track in tracks {
        track.anchor_start = cursor;
        track.anchor_end = cursor + (v * track.size);
        cursor = track.anchor_end + (v * real_gap);
    }
}

fn cell_bounds(
    layout_bounds: &Bounds,
    column_start: Point,
    column_end: Point,
    row_start: Point,
    row_end: Point,
) -> Option<Bounds> {
    let hv = layout_bounds.hv(1.0);
    let vv = layout_bounds.vv(1.0);
    let nw = intersect_rays(&Ray::new(column_start, vv), &Ray::new(row_start, hv))?;
    let ne = intersect_rays(&Ray::new(column_end, vv), &Ray::new(row_start, hv))?;
    let sw = intersect_rays(&Ray::new(column_start, vv), &Ray::new(row_end, hv))?;
    let se = intersect_rays(&Ray::new(column_end, vv), &Ray::new(row_end, hv))?;
    Some(Bounds::new(nw, ne, se, sw))
}

fn create_cell_data<'a>(
    layout_bounds: &Bounds,
    shapes: &'a HashMap<Uuid, Shape>,
    cells: &Vec<GridCell>,
    column_tracks: &Vec<TrackData>,
    row_tracks: &Vec<TrackData>,
) -> Vec<CellData<'a>> {
    let mut result = Vec::<CellData<'a>>::new();

    for cell in cells {
        let Some(shape_id) = cell.shape else {
            continue;
        };
        let Some(shape) = shapes.get(&shape_id) else {
            continue;
        };

        let column_start = (cell.column - 1) as usize;
        let column_end = (cell.column + cell.column_span - 2) as usize;
        let row_start = (cell.row - 1) as usize;
        let row_end = (cell.row + cell.row_span - 2) as usize;
        let Some(cell_bounds) = cell_bounds(
            layout_bounds,
            column_tracks[column_start].anchor_start,
            column_tracks[column_end].anchor_end,
            row_tracks[row_start].anchor_start,
            row_tracks[row_end].anchor_end,
        ) else {
            continue;
        };

        result.push(CellData {
            shape,
            anchor: cell_bounds.nw,
            width: cell_bounds.width(),
            height: cell_bounds.height(),
            align_self: cell.align_self,
            justify_self: cell.justify_self,
        });
    }

    result
}

fn calculate_cell_data<'a>(
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    shapes: &'a HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> Vec<CellData<'a>> {
    let result: Vec<CellData<'a>> = vec![];

    let layout_bounds = bounds.find(shape);

    let column_tracks = calculate_tracks(
        true,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );
    let row_tracks = calculate_tracks(
        false,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    create_cell_data(
        &layout_bounds,
        shapes,
        &grid_data.cells,
        &column_tracks,
        &row_tracks,
    )
}

fn child_position(
    layout_bounds: &Bounds,
    layout_data: &LayoutData,
    child_bounds: &Bounds,
    layout_item: Option<LayoutItem>,
    cell: &CellData,
) -> Point {
    let hv = layout_bounds.hv(1.0);
    let vv = layout_bounds.vv(1.0);

    let margin_left = layout_item.map(|i| i.margin_left).unwrap_or(0.0);
    let margin_top = layout_item.map(|i| i.margin_top).unwrap_or(0.0);
    let margin_right = layout_item.map(|i| i.margin_right).unwrap_or(0.0);
    let margin_bottom = layout_item.map(|i| i.margin_bottom).unwrap_or(0.0);

    cell.anchor
        + vv * match (cell.align_self, layout_data.align_items) {
            (Some(AlignSelf::Start), _) => margin_left,
            (Some(AlignSelf::Center), _) => (cell.height - child_bounds.height()) / 2.0,
            (Some(AlignSelf::End), _) => margin_right + cell.height - child_bounds.height(),
            (_, AlignItems::Center) => (cell.height - child_bounds.height()) / 2.0,
            (_, AlignItems::End) => margin_right + cell.height - child_bounds.height(),
            _ => margin_left,
        }
        + hv * match (cell.justify_self, layout_data.justify_items) {
            (Some(JustifySelf::Start), _) => margin_top,
            (Some(JustifySelf::Center), _) => (cell.width - child_bounds.width()) / 2.0,
            (Some(JustifySelf::End), _) => margin_bottom + cell.width - child_bounds.width(),
            (_, JustifyItems::Center) => (cell.width - child_bounds.width()) / 2.0,
            (_, JustifyItems::End) => margin_bottom + cell.width - child_bounds.width(),
            _ => margin_top,
        }
}

pub fn reflow_grid_layout<'a>(
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    shapes: &'a HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    let mut result = VecDeque::new();

    let cells = calculate_cell_data(shape, layout_data, grid_data, shapes, bounds);
    let layout_bounds = bounds.find(shape);

    for cell in cells.iter() {
        let child = cell.shape;
        let child_bounds = bounds.find(child);
        let position = child_position(
            &layout_bounds,
            &layout_data,
            &child_bounds,
            child.layout_item,
            cell,
        );

        let mut transform = Matrix::default();
        let delta_v = Vector::new_points(&child_bounds.nw, &position);

        if delta_v.x.abs() > MIN_SIZE || delta_v.y.abs() > MIN_SIZE {
            transform.post_concat(&Matrix::translate(delta_v));
        }

        result.push_back(Modifier::transform(child.id, transform));
    }

    result
}
