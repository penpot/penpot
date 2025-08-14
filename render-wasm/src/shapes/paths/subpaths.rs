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
        let mut start = None;
        for segment in self.segments.iter() {
            let destination = match segment {
                Segment::MoveTo(xy) => {
                    start = Some(xy);
                    None
                }
                Segment::LineTo(xy) => Some(xy),
                Segment::CurveTo((_, _, xy)) => Some(xy),
                Segment::Close => {
                    return true;
                }
            };

            if let (Some(&start), Some(&destination)) = (start, destination) {
                if are_close_points(start, destination) {
                    return true;
                }
            }
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
                subpaths.push(current_subpath);
                current_subpath = Subpath::default();
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
