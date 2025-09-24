use crate::shapes::paths::Point;
use crate::shapes::paths::Segment;

#[derive(Debug, Clone)]
pub struct Subpath {
    pub segments: Vec<Segment>,
    closed: Option<bool>,
}

impl Subpath {
    pub fn new(segments: Vec<Segment>) -> Self {
        Self {
            segments,
            closed: None,
        }
    }

    pub fn start(&self) -> Option<Point> {
        self.segments.first().and_then(|s| match s {
            Segment::MoveTo(p) | Segment::LineTo(p) => Some(*p),
            _ => None,
        })
    }

    pub fn end(&self) -> Option<Point> {
        self.segments.iter().rev().find_map(|s| match s {
            Segment::MoveTo(p) | Segment::LineTo(p) => Some(*p),
            Segment::CurveTo((_, _, p)) => Some(*p),
            _ => None,
        })
    }

    pub fn is_closed(&self) -> bool {
        self.closed.unwrap_or_else(|| self.calculate_closed())
    }

    pub fn reversed(&self) -> Self {
        let mut rev = self.clone();
        rev.segments.reverse();
        rev.closed = None;
        rev
    }

    fn calculate_closed(&self) -> bool {
        if self.segments.is_empty() {
            return true;
        }
        if let Some(Segment::Close) = self.segments.last() {
            return true;
        }
        if let (Some(first), Some(last)) = (self.start(), self.end()) {
            return are_close_points(first, last);
        }
        false
    }
}

fn are_close_points(a: Point, b: Point) -> bool {
    let tol = 1e-1;
    (a.0 - b.0).abs() < tol && (a.1 - b.1).abs() < tol
}

#[derive(Debug, Clone)]
enum MergeMode {
    EndStart,
    StartEnd,
    EndEnd,
    StartStart,
}

impl TryFrom<(&Subpath, &Subpath)> for Subpath {
    type Error = &'static str;
    fn try_from((a, b): (&Subpath, &Subpath)) -> Result<Self, Self::Error> {
        let mut segs = a.segments.clone();
        segs.extend_from_slice(&b.segments);
        Ok(Subpath::new(segs))
    }
}

pub fn closed_subpaths(subpaths: Vec<Subpath>) -> Vec<Subpath> {
    let n = subpaths.len();
    if n == 0 {
        return vec![];
    }

    let mut used = vec![false; n];
    let mut result = Vec::with_capacity(n);

    for i in 0..n {
        if used[i] {
            continue;
        }

        let mut current = subpaths[i].clone();
        used[i] = true;
        let mut merged_any = false;

        loop {
            if current.is_closed() {
                break;
            }

            let mut did_merge = false;

            for j in 0..n {
                if used[j] || subpaths[j].is_closed() {
                    continue;
                }

                let candidate = &subpaths[j];
                let maybe_merge = [
                    (current.end(), candidate.start(), MergeMode::EndStart),
                    (current.start(), candidate.end(), MergeMode::StartEnd),
                    (current.end(), candidate.end(), MergeMode::EndEnd),
                    (current.start(), candidate.start(), MergeMode::StartStart),
                ]
                .iter()
                .find_map(|(p1, p2, mode)| {
                    if let (Some(a), Some(b)) = (p1, p2) {
                        if are_close_points(*a, *b) {
                            Some(mode.clone())
                        } else {
                            None
                        }
                    } else {
                        None
                    }
                });

                if let Some(mode) = maybe_merge {
                    if let Some(new_current) = try_merge(&current, candidate, mode) {
                        used[j] = true;
                        current = new_current;
                        merged_any = true;
                        did_merge = true;
                        break;
                    }
                }
            }

            if !did_merge {
                break;
            }
        }

        if !current.is_closed() && merged_any {
            if let Some(start) = current.start() {
                let mut segs = current.segments.clone();
                segs.push(Segment::LineTo(start));
                segs.push(Segment::Close);
                current = Subpath::new(segs);
            }
        }

        result.push(current);
    }

    result
}

fn try_merge(current: &Subpath, candidate: &Subpath, mode: MergeMode) -> Option<Subpath> {
    match mode {
        MergeMode::EndStart => Subpath::try_from((current, candidate)).ok(),
        MergeMode::StartEnd => Subpath::try_from((candidate, current)).ok(),
        MergeMode::EndEnd => Subpath::try_from((current, &candidate.reversed())).ok(),
        MergeMode::StartStart => Subpath::try_from((&candidate.reversed(), current)).ok(),
    }
}

pub fn split_into_subpaths(segments: &[Segment]) -> Vec<Subpath> {
    let mut subpaths = Vec::new();
    let mut current_segments = Vec::new();

    for segment in segments {
        match segment {
            Segment::MoveTo(_) => {
                // Start new subpath unless current is empty
                if !current_segments.is_empty() {
                    subpaths.push(Subpath::new(current_segments.clone()));
                    current_segments.clear();
                }
                current_segments.push(*segment);
            }
            _ => current_segments.push(*segment),
        }
    }

    // Push last subpath if any
    if !current_segments.is_empty() {
        subpaths.push(Subpath::new(current_segments));
    }

    subpaths
}

pub fn is_open_path(segments: &[Segment]) -> bool {
    let subpaths = split_into_subpaths(segments);
    let closed_subpaths = closed_subpaths(subpaths);
    closed_subpaths.iter().any(|sp| !sp.is_closed())
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

        let result = subpaths::is_open_path(&segments);
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

        let result = subpaths::is_open_path(&segments);
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

        let result = subpaths::is_open_path(&segments);
        assert!(result, "Path should be open");
    }

    #[test]
    fn test_is_open_path_4() {
        let segments = vec![];
        let result = subpaths::is_open_path(&segments);
        assert!(!result, "Path should be closed");
    }
}
