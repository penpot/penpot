#![allow(dead_code, unused_variables)]
use crate::math::{intersect_rays, Bounds, Matrix, Point, Ray, Vector, VectorExt};
use crate::shapes::{GridCell, GridData, GridTrack, GridTrackType, LayoutData, Modifier, Shape};
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

fn assign_anchors(
    column: bool,
    layout_data: &LayoutData,
    layout_bounds: &Bounds,
    tracks: &mut Vec<TrackData>,
) {
    let mut cursor = layout_bounds.nw;

    let (v, gap, padding_start) = if column {
        (
            layout_bounds.hv(1.0),
            layout_data.row_gap,
            layout_data.padding_left,
        )
    } else {
        (
            layout_bounds.vv(1.0),
            layout_data.column_gap,
            layout_data.padding_top,
        )
    };

    cursor = cursor + (v * padding_start);

    for track in tracks {
        track.anchor_start = cursor;
        track.anchor_end = cursor + (v * track.size);
        cursor = track.anchor_end + (v * gap);
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

    let layout_width = layout_bounds.width() - layout_data.padding_left - layout_data.padding_right;
    let layout_height =
        layout_bounds.height() - layout_data.padding_top - layout_data.padding_bottom;

    let mut column_tracks = init_tracks(&grid_data.columns, layout_width);
    let mut row_tracks = init_tracks(&grid_data.rows, layout_height);

    assign_anchors(true, layout_data, &layout_bounds, &mut column_tracks);
    assign_anchors(false, layout_data, &layout_bounds, &mut row_tracks);

    create_cell_data(
        &layout_bounds,
        shapes,
        &grid_data.cells,
        &column_tracks,
        &row_tracks,
    )
}

fn child_position(child_bounds: &Bounds, cell: &CellData) -> Point {
    cell.anchor
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

    for cell in cells.iter() {
        let child = cell.shape;
        let child_bounds = bounds.find(child);
        let position = child_position(&child_bounds, cell);

        let mut transform = Matrix::default();
        let delta_v = Vector::new_points(&child_bounds.nw, &position);

        if delta_v.x.abs() > MIN_SIZE || delta_v.y.abs() > MIN_SIZE {
            transform.post_concat(&Matrix::translate(delta_v));
        }

        result.push_back(Modifier::transform(child.id, transform));
    }

    result
}
