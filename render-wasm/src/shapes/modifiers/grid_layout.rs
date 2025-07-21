use crate::math::{self as math, intersect_rays, Bounds, Matrix, Point, Ray, Vector, VectorExt};
use crate::shapes::{
    AlignContent, AlignItems, AlignSelf, Frame, GridCell, GridData, GridTrack, GridTrackType,
    JustifyContent, JustifyItems, JustifySelf, Layout, LayoutData, LayoutItem, Modifier, Shape,
    StructureEntry, Type,
};
use crate::state::ShapesPool;
use crate::uuid::Uuid;
use indexmap::IndexSet;
use std::collections::{HashMap, VecDeque};

use super::common::GetBounds;

const MIN_SIZE: f32 = 0.01;
const MAX_SIZE: f32 = f32::INFINITY;

#[derive(Debug)]
pub struct CellData<'a> {
    pub shape: Option<&'a Shape>,
    pub anchor: Point,
    pub width: f32,
    pub height: f32,
    pub align_self: Option<AlignSelf>,
    pub justify_self: Option<JustifySelf>,
    pub row: usize,
    pub column: usize,
}

#[derive(Debug)]
pub struct TrackData {
    pub track_type: GridTrackType,
    pub value: f32,
    pub size: f32,
    pub max_size: f32,
    pub anchor_start: Point,
    pub anchor_end: Point,
}

// FIXME: We might be able to simplify these arguments
#[allow(clippy::too_many_arguments)]
pub fn calculate_tracks(
    is_column: bool,
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    layout_bounds: &Bounds,
    cells: &Vec<GridCell>,
    shapes: &ShapesPool,
    bounds: &HashMap<Uuid, Bounds>,
) -> Vec<TrackData> {
    let layout_size = if is_column {
        layout_bounds.width() - layout_data.padding_left - layout_data.padding_right
    } else {
        layout_bounds.height() - layout_data.padding_top - layout_data.padding_bottom
    };

    let auto_layout = if is_column {
        shape.is_layout_horizontal_auto()
    } else {
        shape.is_layout_vertical_auto()
    };

    let grid_tracks = if is_column {
        &grid_data.columns
    } else {
        &grid_data.rows
    };

    let mut tracks = init_tracks(grid_tracks, layout_size);
    set_auto_base_size(is_column, &mut tracks, cells, shapes, bounds);
    set_auto_multi_span(is_column, &mut tracks, cells, shapes, bounds);
    set_flex_multi_span(is_column, layout_data, &mut tracks, cells, shapes, bounds);
    set_fr_value(
        is_column,
        layout_data,
        &mut tracks,
        layout_size,
        auto_layout,
    );
    stretch_tracks(is_column, shape, layout_data, &mut tracks, layout_size);
    assign_anchors(is_column, layout_data, layout_bounds, &mut tracks);
    tracks
}

fn init_tracks(track: &[GridTrack], size: f32) -> Vec<TrackData> {
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

fn min_size(column: bool, shape: &Shape, bounds: &HashMap<Uuid, Bounds>) -> f32 {
    if column && shape.is_layout_horizontal_fill() {
        shape.layout_item.and_then(|i| i.min_w).unwrap_or(MIN_SIZE)
    } else if !column && shape.is_layout_vertical_fill() {
        shape.layout_item.and_then(|i| i.min_h).unwrap_or(MIN_SIZE)
    } else if column {
        let bounds = bounds.find(shape);
        bounds.width()
    } else {
        let bounds = bounds.find(shape);
        bounds.height()
    }
}

// Go through cells to adjust auto sizes for span=1. Base is the max of its children
fn set_auto_base_size(
    column: bool,
    tracks: &mut [TrackData],
    cells: &Vec<GridCell>,
    shapes: &ShapesPool,
    bounds: &HashMap<Uuid, Bounds>,
) {
    for cell in cells {
        let (prop, prop_span) = if column {
            (cell.column, cell.column_span)
        } else {
            (cell.row, cell.row_span)
        };

        if prop_span != 1 || (prop as usize) > tracks.len() {
            continue;
        }

        let track = &mut tracks[(prop - 1) as usize];

        // We change the size for auto+flex tracks
        if track.track_type != GridTrackType::Auto && track.track_type != GridTrackType::Flex {
            continue;
        }

        let Some(shape) = cell.shape.and_then(|id| shapes.get(&id)) else {
            continue;
        };

        let min_size = min_size(column, shape, bounds);
        track.size = f32::max(track.size, min_size);
    }
}

fn track_index(is_column: bool, c: &GridCell) -> (usize, usize) {
    if is_column {
        (
            (c.column - 1) as usize,
            (c.column + c.column_span - 1) as usize,
        )
    } else {
        ((c.row - 1) as usize, (c.row + c.row_span - 1) as usize)
    }
}

fn has_flex(is_column: bool, cell: &GridCell, tracks: &mut [TrackData]) -> bool {
    let (start, end) = track_index(is_column, cell);
    (start..end).any(|i| tracks[i].track_type == GridTrackType::Flex)
}

// Adjust multi-spaned cells with no flex columns
fn set_auto_multi_span(
    column: bool,
    tracks: &mut [TrackData],
    cells: &[GridCell],
    shapes: &ShapesPool,
    bounds: &HashMap<Uuid, Bounds>,
) {
    // Remove groups with flex (will be set in flex_multi_span)
    let mut selected_cells: Vec<&GridCell> = cells
        .iter()
        .filter(|c| {
            if column {
                c.column_span > 1
            } else {
                c.row_span > 1
            }
        })
        .filter(|c| !has_flex(column, c, tracks))
        .collect();

    // Sort descendant order of prop-span
    selected_cells.sort_by(|a, b| {
        if column {
            b.column_span.cmp(&a.row_span)
        } else {
            b.row_span.cmp(&a.row_span)
        }
    });

    for cell in selected_cells {
        let Some(child) = cell.shape.and_then(|id| shapes.get(&id)) else {
            continue;
        };

        // Retrieve the value we need to distribute (fixed cell size minus gaps)
        let mut dist = min_size(column, child, bounds);
        let mut num_auto = 0;

        let (start, end) = track_index(column, cell);

        // Distribute the size between the tracks that already have a set value
        for track in tracks[start..end].iter() {
            dist -= track.size;

            if track.track_type == GridTrackType::Auto {
                num_auto += 1;
            }
        }

        // If we still have more space we distribute equally between all auto tracks
        while dist > MIN_SIZE && num_auto > 0 {
            let rest = dist / num_auto as f32;

            // Distribute the space between auto tracks
            for track in tracks[start..end].iter_mut() {
                if track.track_type == GridTrackType::Auto {
                    // dist = dist - track[i].size;
                    let new_size = if track.size + rest < track.max_size {
                        track.size + rest
                    } else {
                        num_auto -= 1;
                        track.max_size
                    };

                    let aloc = new_size - track.size;
                    dist -= aloc;
                    track.size += aloc;
                }
            }
        }
    }
}

// Adjust multi-spaned cells with flex columns
fn set_flex_multi_span(
    column: bool,
    layout_data: &LayoutData,
    tracks: &mut [TrackData],
    cells: &[GridCell],
    shapes: &ShapesPool,
    bounds: &HashMap<Uuid, Bounds>,
) {
    // Remove groups without flex
    let mut selected_cells: Vec<&GridCell> = cells
        .iter()
        .filter(|c| {
            if column {
                c.column_span > 1
            } else {
                c.row_span > 1
            }
        })
        .filter(|c| has_flex(column, c, tracks))
        .collect();

    // Sort descendant order of prop-span
    selected_cells.sort_by(|a, b| {
        if column {
            b.column_span.cmp(&a.row_span)
        } else {
            b.row_span.cmp(&a.row_span)
        }
    });

    let gap_value = if column {
        layout_data.column_gap
    } else {
        layout_data.row_gap
    };

    // Retrieve the value that we need to distribute and the number of frs
    for cell in selected_cells {
        let Some(child) = cell.shape.and_then(|id| shapes.get(&id)) else {
            continue;
        };

        // Retrieve the value we need to distribute (fixed cell size minus gaps)
        let (start, end) = track_index(column, cell);
        let gaps = (end - start - 1) as f32 * gap_value;
        let mut dist = min_size(column, child, bounds) - gaps;
        let mut num_flex = 0.0;

        // Distribute the size between the tracks that already have a set value
        for track in tracks[start..end].iter() {
            match track.track_type {
                GridTrackType::Flex => {
                    num_flex += track.value;
                }
                _ => {
                    dist -= track.size;
                }
            }
        }

        if dist <= MIN_SIZE {
            // No space available to distribute
            continue;
        }

        let alloc = dist / num_flex;

        // Distribute the space between flex tracks in proportion to the division
        for track in tracks[start..end].iter_mut() {
            if track.track_type == GridTrackType::Flex {
                let new_size = alloc.clamp(track.size, track.max_size);
                let aloc = new_size - track.size;
                dist -= aloc;
                track.size = new_size;
            }
        }
    }
}

// Calculate the `fr` unit and adjust the size
fn set_fr_value(
    column: bool,
    layout_data: &LayoutData,
    tracks: &mut [TrackData],
    layout_size: f32,
    auto_layout: bool,
) {
    let tot_gap: f32 = if column {
        layout_data.column_gap * (tracks.len() as f32 - 1.0)
    } else {
        layout_data.row_gap * (tracks.len() as f32 - 1.0)
    };

    let mut tot_size_nofr = tot_gap;
    let mut tot_frs = 0.0;
    let mut cur_fr_value = 0.0;

    for t in tracks.iter() {
        if t.track_type == GridTrackType::Flex {
            tot_frs += t.value;
            cur_fr_value = f32::max(t.size / t.value, cur_fr_value);
        } else {
            tot_size_nofr += t.size;
        }
    }

    let fr_space = f32::max(0.0, layout_size - tot_size_nofr);

    let mut fr_value = if auto_layout {
        // If auto_layout we assign the max fr
        cur_fr_value
    } else {
        fr_space / tot_frs
    };

    if !auto_layout {
        loop {
            // While there is still tracks that can take more size
            let mut pending = fr_space;
            let mut free_frs = 0;

            for t in tracks.iter() {
                if t.track_type == GridTrackType::Flex {
                    // Use the current fr size
                    let current = t.value * fr_value;

                    if t.size > current {
                        // If it's not enough psace to allocate, this is full. Cannot grow anymore.
                        pending -= t.size;
                    } else {
                        // Otherwise, this track can still grow
                        free_frs += 1;
                        pending -= current;
                    }
                }
            }
            // We finish when we cannot reduce the tracks more or we've allocated all
            // the space
            if free_frs == 0 || pending >= -0.01 {
                break;
            }

            fr_value += pending / free_frs as f32;
        }
    }

    // Assign the fr_value to the flex tracks
    tracks
        .iter_mut()
        .filter(|t| t.track_type == GridTrackType::Flex)
        .for_each(|t| t.size = (fr_value * t.value).clamp(t.size, t.max_size));
}

fn stretch_tracks(
    column: bool,
    shape: &Shape,
    layout_data: &LayoutData,
    tracks: &mut [TrackData],
    layout_size: f32,
) {
    if (tracks.is_empty()
        || column
            && (layout_data.justify_content != JustifyContent::Stretch
                || shape.is_layout_horizontal_auto()))
        || (!column
            && (layout_data.align_content != AlignContent::Stretch
                || shape.is_layout_vertical_auto()))
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

    if free_space <= 0.0 {
        return;
    }

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

    let tot_gap = if tracks.is_empty() {
        0.0
    } else {
        gap * (tracks.len() - 1) as f32
    };
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

    cursor += v * real_margin;

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

pub fn create_cell_data<'a>(
    layout_bounds: &Bounds,
    children: &IndexSet<Uuid>,
    shapes: &'a ShapesPool,
    cells: &Vec<GridCell>,
    column_tracks: &[TrackData],
    row_tracks: &[TrackData],
    allow_empty: bool,
) -> Vec<CellData<'a>> {
    let mut result = Vec::<CellData<'a>>::new();

    for cell in cells {
        let shape: Option<&Shape> = if let Some(shape_id) = cell.shape {
            if !children.contains(&shape_id) {
                None
            } else {
                shapes.get(&shape_id)
            }
        } else {
            None
        };

        if !allow_empty && shape.is_none() {
            continue;
        }

        let column_start = (cell.column - 1) as usize;
        let column_end = (cell.column + cell.column_span - 2) as usize;
        let row_start = (cell.row - 1) as usize;
        let row_end = (cell.row + cell.row_span - 2) as usize;

        if column_start >= column_tracks.len()
            || column_end >= column_tracks.len()
            || row_start >= row_tracks.len()
            || row_end >= row_tracks.len()
        {
            continue;
        }

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
            row: row_start,
            column: column_start,
        });
    }

    result
}

pub fn grid_cell_data<'a>(
    shape: &Shape,
    shapes: &'a ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
    allow_empty: bool,
) -> Vec<CellData<'a>> {
    let Type::Frame(Frame {
        layout: Some(Layout::GridLayout(layout_data, grid_data)),
        ..
    }) = &shape.shape_type
    else {
        return vec![];
    };

    let bounds = &mut HashMap::<Uuid, Bounds>::new();

    let shape = &mut shape.clone();
    if let Some(modifiers) = modifiers.get(&shape.id) {
        shape.apply_transform(modifiers);
    }

    let layout_bounds = shape.bounds();
    let children = shape.modified_children_ids(structure.get(&shape.id), false);

    for child_id in children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        if let Some(modifier) = modifiers.get(child_id) {
            let mut b = bounds.find(child);
            b.transform_mut(modifier);
            bounds.insert(*child_id, b);
        }
    }

    let column_tracks = calculate_tracks(
        true,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let row_tracks = calculate_tracks(
        false,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    create_cell_data(
        &layout_bounds,
        &children,
        shapes,
        &grid_data.cells,
        &column_tracks,
        &row_tracks,
        allow_empty,
    )
}

fn child_position(
    child: &Shape,
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

    let vpos = match (cell.align_self, layout_data.align_items) {
        (Some(AlignSelf::Start), _) => margin_top,
        (Some(AlignSelf::Center), _) => (cell.height - child_bounds.height()) / 2.0,
        (Some(AlignSelf::End), _) => margin_bottom + cell.height - child_bounds.height(),
        (_, AlignItems::Center) => (cell.height - child_bounds.height()) / 2.0,
        (_, AlignItems::End) => margin_bottom + cell.height - child_bounds.height(),
        _ => margin_top,
    };

    let vpos = if child.is_layout_vertical_fill() {
        margin_top
    } else {
        vpos
    };

    let hpos = match (cell.justify_self, layout_data.justify_items) {
        (Some(JustifySelf::Start), _) => margin_left,
        (Some(JustifySelf::Center), _) => (cell.width - child_bounds.width()) / 2.0,
        (Some(JustifySelf::End), _) => margin_right + cell.width - child_bounds.width(),
        (_, JustifyItems::Center) => (cell.width - child_bounds.width()) / 2.0,
        (_, JustifyItems::End) => margin_right + cell.width - child_bounds.width(),
        _ => margin_left,
    };

    let hpos = if child.is_layout_horizontal_fill() {
        margin_left
    } else {
        hpos
    };

    cell.anchor + vv * vpos + hv * hpos
}

pub fn reflow_grid_layout(
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    shapes: &ShapesPool,
    bounds: &mut HashMap<Uuid, Bounds>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> VecDeque<Modifier> {
    let mut result = VecDeque::new();
    let layout_bounds = bounds.find(shape);
    let children = shape.modified_children_ids(structure.get(&shape.id), true);

    let column_tracks = calculate_tracks(
        true,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let row_tracks = calculate_tracks(
        false,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let cells = create_cell_data(
        &layout_bounds,
        &children,
        shapes,
        &grid_data.cells,
        &column_tracks,
        &row_tracks,
        false,
    );

    for cell in cells.iter() {
        let Some(child) = cell.shape else { continue };
        let child_bounds = bounds.find(child);

        let mut new_width = child_bounds.width();
        if child.is_layout_horizontal_fill() {
            let margin_left = child.layout_item.map(|i| i.margin_left).unwrap_or(0.0);
            new_width = cell.width - margin_left;
            let min_width = child.layout_item.and_then(|i| i.min_w).unwrap_or(MIN_SIZE);
            let max_width = child.layout_item.and_then(|i| i.max_w).unwrap_or(MAX_SIZE);
            new_width = new_width.clamp(min_width, max_width);
        }

        let mut new_height = child_bounds.height();
        if child.is_layout_vertical_fill() {
            let margin_top = child.layout_item.map(|i| i.margin_top).unwrap_or(0.0);
            new_height = cell.height - margin_top;
            let min_height = child.layout_item.and_then(|i| i.min_h).unwrap_or(MIN_SIZE);
            let max_height = child.layout_item.and_then(|i| i.max_h).unwrap_or(MAX_SIZE);
            new_height = new_height.clamp(min_height, max_height);
        }

        let mut transform = Matrix::default();

        if (new_width - child_bounds.width()).abs() > MIN_SIZE
            || (new_height - child_bounds.height()).abs() > MIN_SIZE
        {
            transform.post_concat(&math::resize_matrix(
                &layout_bounds,
                &child_bounds,
                new_width,
                new_height,
            ));
        }

        let position = child_position(
            child,
            &layout_bounds,
            layout_data,
            &child_bounds,
            child.layout_item,
            cell,
        );

        let delta_v = Vector::new_points(&child_bounds.nw, &position);

        if delta_v.x.abs() > MIN_SIZE || delta_v.y.abs() > MIN_SIZE {
            transform.post_concat(&Matrix::translate(delta_v));
        }

        result.push_back(Modifier::transform(child.id, transform));
    }

    if shape.is_layout_horizontal_auto() || shape.is_layout_vertical_auto() {
        let width = layout_bounds.width();
        let height = layout_bounds.height();

        let mut scale_width = 1.0;
        let mut scale_height = 1.0;

        if shape.is_layout_horizontal_auto() {
            let auto_width = column_tracks.iter().map(|t| t.size).sum::<f32>()
                + layout_data.padding_left
                + layout_data.padding_right
                + (column_tracks.len() - 1) as f32 * layout_data.column_gap;
            scale_width = auto_width / width;
        }

        if shape.is_layout_vertical_auto() {
            let auto_height = row_tracks.iter().map(|t| t.size).sum::<f32>()
                + layout_data.padding_top
                + layout_data.padding_bottom
                + (row_tracks.len() - 1) as f32 * layout_data.row_gap;
            scale_height = auto_height / height;
        }

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
