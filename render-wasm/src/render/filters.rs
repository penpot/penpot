use skia_safe::ImageFilter;

/// Composes two image filters, returning a combined filter if both are present,
/// or the individual filter if only one is present, or None if neither is present.
///
/// # Parameters
/// - `filter_a`: The first optional image filter.
/// - `filter_b`: The second optional image filter.
///
/// # Returns
/// - `Some(ImageFilter)`: The composed or single filter.
/// - `None`: If both filters are `None`.
pub fn compose_filters(
    filter_a: Option<&ImageFilter>,
    filter_b: Option<&ImageFilter>,
) -> Option<ImageFilter> {
    match (filter_a, filter_b) {
        (Some(fa), Some(fb)) => ImageFilter::compose(fa, fb),
        (Some(fa), None) => Some(fa.clone()),
        (None, Some(fb)) => Some(fb.clone()),
        (None, None) => None,
    }
}
