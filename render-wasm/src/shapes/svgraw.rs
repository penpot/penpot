#[derive(Debug, Clone, PartialEq, Default)]
pub struct SVGRaw {
    pub content: String,
}

impl SVGRaw {
    pub fn from_content(svg: String) -> SVGRaw {
        SVGRaw { content: svg }
    }
}
