#[derive(Debug, Clone, PartialEq)]
pub struct SVGRaw {
    pub content: String,
}

impl SVGRaw {
    pub fn from_content(svg: String) -> SVGRaw {
        SVGRaw { content: svg }
    }
}

impl Default for SVGRaw {
    fn default() -> Self {
        Self {
            content: String::from(""),
        }
    }
}
