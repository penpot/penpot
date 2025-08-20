use super::Segment;
use crate::math::are_close_points;

type Result<T> = std::result::Result<T, String>;

#[derive(Debug, Clone, PartialEq)]
pub struct Subpath {
    segments: Vec<Segment>,
    closed: Option<bool>,
}

impl Subpath {
    pub fn new(segments: Vec<Segment>) -> Self {
        Self {
            segments,
            closed: None,
        }
    }

    pub fn starts_in(&self, other_segment: Option<&Segment>) -> bool {
        if let (Some(start), Some(end)) = (self.start(), other_segment) {
            start.is_close_to(end)
        } else {
            false
        }
    }

    pub fn ends_in(&self, other_segment: Option<&Segment>) -> bool {
        if let (Some(end), Some(start)) = (self.end(), other_segment) {
            end.is_close_to(start)
        } else {
            false
        }
    }

    pub fn start(&self) -> Option<&Segment> {
        self.segments.first()
    }

    pub fn end(&self) -> Option<&Segment> {
        self.segments.last()
    }

    pub fn is_empty(&self) -> bool {
        self.segments.is_empty()
    }

    pub fn is_closed(&self) -> bool {
        self.closed.unwrap_or_else(|| self.calculate_closed())
    }

    pub fn add_segment(&mut self, segment: Segment) {
        self.segments.push(segment);
        self.closed = None;
    }

    pub fn reversed(&self) -> Self {
        let mut reversed = self.clone();
        reversed.segments.reverse();
        reversed
    }

    fn calculate_closed(&self) -> bool {
        if self.segments.is_empty() {
            return false;
        }

        // Check if the path ends with a Close segment
        if let Some(Segment::Close) = self.segments.last() {
            return true;
        }

        // Check if the first and last points are close to each other
        if let (Some(first), Some(last)) = (self.segments.first(), self.segments.last()) {
            let first_point = match first {
                Segment::MoveTo(xy) => xy,
                _ => return false,
            };

            let last_point = match last {
                Segment::LineTo(xy) => xy,
                Segment::CurveTo((_, _, xy)) => xy,
                _ => return false,
            };

            return are_close_points(*first_point, *last_point);
        }

        false
    }
}

impl Default for Subpath {
    fn default() -> Self {
        Self::new(vec![])
    }
}

/// Joins two subpaths into a single subpath
impl TryFrom<(&Subpath, &Subpath)> for Subpath {
    type Error = String;

    fn try_from((subpath, other): (&Subpath, &Subpath)) -> Result<Self> {
        if subpath.is_empty() || other.is_empty() || subpath.end() != other.start() {
            return Err("Subpaths cannot be joined".to_string());
        }

        let mut segments = subpath.segments.clone();
        segments.extend_from_slice(&other.segments);
        Ok(Subpath::new(segments))
    }
}

/// Groups segments into subpaths based on MoveTo segments
fn get_subpaths(segments: &[Segment]) -> Vec<Subpath> {
    let mut subpaths: Vec<Subpath> = vec![];
    let mut current_subpath = Subpath::default();

    for segment in segments {
        match segment {
            Segment::MoveTo(_) => {
                if !current_subpath.is_empty() {
                    subpaths.push(current_subpath);
                }
                current_subpath = Subpath::default();
                // Add the MoveTo segment to the new subpath
                current_subpath.add_segment(*segment);
            }
            _ => {
                current_subpath.add_segment(*segment);
            }
        }
    }

    if !current_subpath.is_empty() {
        subpaths.push(current_subpath);
    }

    subpaths
}

/// Computes the merged candidate and the remaining, unmerged subpaths
fn merge_paths(candidate: Subpath, others: Vec<Subpath>) -> Result<(Subpath, Vec<Subpath>)> {
    if candidate.is_closed() {
        return Ok((candidate, others));
    }

    let mut merged = candidate.clone();
    let mut other_without_merged = vec![];

    for subpath in others {
        // Only merge if the candidate is not already closed and the subpath can be meaningfully connected
        if !merged.is_closed() && !subpath.is_closed() {
            if merged.ends_in(subpath.start()) {
                merged = Subpath::try_from((&merged, &subpath))?;
            } else if merged.starts_in(subpath.end()) {
                merged = Subpath::try_from((&subpath, &merged))?;
            } else if merged.ends_in(subpath.end()) {
                merged = Subpath::try_from((&merged, &subpath.reversed()))?;
            } else if merged.starts_in(subpath.start()) {
                merged = Subpath::try_from((&subpath.reversed(), &merged))?;
            } else {
                other_without_merged.push(subpath);
            }
        } else {
            // If either subpath is closed, don't merge
            other_without_merged.push(subpath);
        }
    }

    Ok((merged, other_without_merged))
}

/// Searches a path for potential subpaths that can be closed and merges them
fn closed_subpaths(
    current: &Subpath,
    others: &[Subpath],
    partial: &[Subpath],
) -> Result<Vec<Subpath>> {
    let mut result = partial.to_vec();

    let (new_current, new_others) = if current.is_closed() {
        (current.clone(), others.to_vec())
    } else {
        merge_paths(current.clone(), others.to_vec())?
    };

    // we haven't found any matching subpaths -> advance
    if new_current == *current {
        result.push(current.clone());
        if new_others.is_empty() {
            return Ok(result);
        }

        closed_subpaths(&new_others[0], &new_others[1..], &result)
    }
    // if diffrent, we have to search again with the merged subpaths
    else {
        closed_subpaths(&new_current, &new_others, &result)
    }
}

pub fn is_open_path(segments: &[Segment]) -> Result<bool> {
    let subpaths = get_subpaths(segments);
    let closed_subpaths = if subpaths.len() > 1 {
        closed_subpaths(&subpaths[0], &subpaths[1..], &[])?
    } else {
        subpaths
    };

    // return true if any subpath is open
    Ok(closed_subpaths.iter().any(|subpath| !subpath.is_closed()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::paths::subpaths;

    #[test]
    fn test_is_open_path_1() {
        let segments = vec![
            Segment::MoveTo((807.0, 348.0)),
            Segment::LineTo((807.0, 343.0)),
            Segment::MoveTo((807.0, 343.0)),
            Segment::LineTo((807.00037, 338.0)),
            Segment::LineTo((810.66144, 338.0)),
            Segment::CurveTo((
                (811.95294, 338.0),
                (812.99994, 339.11926),
                (812.99994, 340.5),
            )),
            Segment::CurveTo(((812.99994, 341.88074), (811.95264, 343.0), (810.661, 343.0))),
            Segment::LineTo((807.0, 343.0)),
            Segment::Close,
        ];

        let result =
            subpaths::is_open_path(&segments).expect("Failed to determine if path is open");
        assert!(result, "Path should be open");
    }

    #[test]
    fn test_is_open_path_2() {
        let segments = vec![
            Segment::MoveTo((223.0, 582.0)),
            Segment::LineTo((505.0, 356.0)),
            Segment::LineTo((489.0, 874.0)),
            Segment::LineTo((223.0, 582.0)),
        ];

        let result =
            subpaths::is_open_path(&segments).expect("Failed to determine if path is open");
        assert!(!result, "Path should be closed");
    }

    #[test]
    fn test_is_open_path_3() {
        let segments = vec![
            Segment::MoveTo((389.02805, 617.99994)),
            Segment::LineTo((391.29263, 610.7184)),
            Segment::CurveTo((
                (391.42545, 610.2914),
                (391.82388, 610.0),
                (392.27505, 610.0),
            )),
            Segment::LineTo((401.97116, 610.0)),
            Segment::CurveTo((
                (402.67935, 610.0),
                (403.17532, 610.69257),
                (402.9415, 611.35455),
            )),
            Segment::LineTo((400.834, 617.3182)),
            Segment::CurveTo((
                (400.68973, 617.7266),
                (400.30063, 617.99994),
                (399.86374, 617.99994),
            )),
            Segment::LineTo((389.02805, 617.99994)),
            Segment::CurveTo((
                (388.46024, 617.99994),
                (388.00003, 617.5442),
                (388.00003, 616.98175),
            )),
            Segment::LineTo((388.00003, 607.52344)),
            Segment::CurveTo((
                (388.00003, 607.12),
                (388.15427, 606.73254),
                (388.4307, 606.44684),
            )),
            Segment::CurveTo(((388.70602, 606.16), (389.07944, 606.0), (389.46823, 606.0))),
            Segment::LineTo((392.40573, 606.0)),
            Segment::LineTo((393.50717, 607.71436)),
            Segment::LineTo((399.0878, 607.71436)),
            Segment::CurveTo((
                (399.65555, 607.71436),
                (400.1158, 608.1701),
                (400.1158, 608.7325),
            )),
            Segment::LineTo((400.1158, 610.0)),
        ];

        let result =
            subpaths::is_open_path(&segments).expect("Failed to determine if path is open");
        assert!(result, "Path should be open");
    }

    #[test]
    fn test_is_open_path_4() {
        let segments = vec![];
        let result =
            subpaths::is_open_path(&segments).expect("Failed to determine if path is open");
        assert!(!result, "Path should be closed");
    }
}
