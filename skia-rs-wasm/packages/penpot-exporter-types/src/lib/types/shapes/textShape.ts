import type { LayoutChildAttributes } from './layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';
import type { Fill } from '../utils/fill';
import type { Typography } from '../utils/typography';

export type TextShape = ShapeBaseAttributes &
  ShapeGeomAttributes &
  ShapeAttributes &
  TextAttributes &
  LayoutChildAttributes;

/** Per-fragment layout info for text shapes (computed by WASM layout engine) */
export type PositionDataEntry = {
  paragraph: number;
  span: number;
  startPos: number;
  endPos: number;
  x: number;
  y: number;
  width: number;
  height: number;
  direction: number;
};

export type TextAttributes = {
  type: 'text';
  content?: TextContent;
  characters?: string; // @ TODO: move to any other place
  positionData?: PositionDataEntry[];
};

export type TextContent = {
  type: 'root';
  key?: string;
  verticalAlign?: TextVerticalAlign;
  children?: ParagraphSet[];
};

export type TextVerticalAlign = 'top' | 'bottom' | 'center';
export type TextHorizontalAlign = 'left' | 'right' | 'center' | 'justify';
export type TextFontStyle = 'normal' | 'italic';

export type ParagraphSet = {
  type: 'paragraph-set';
  key?: string;
  children: Paragraph[];
};

export type Paragraph = {
  type: 'paragraph';
  key?: string;
  children: TextNode[];
} & TextStyle;

export type TextNode = {
  text: string;
  key?: string;
} & TextStyle;

export type TextStyle = TextTypography & {
  textDecoration?: string;
  direction?: string;
  typographyRefId?: string;
  typographyRefFile?: string;
  textAlign?: TextHorizontalAlign;
  textDirection?: 'ltr' | 'rtl' | 'auto';
  fills?: Fill[];

  fillStyleId?: string; // @TODO: move to any other place
  textStyleId?: string; // @TODO: move to any other place
};

export type TextTypography = FontId & {
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string;
  fontStyle?: TextFontStyle;
  lineHeight?: string;
  letterSpacing?: string;
  textTransform?: string;
};

export type FontId = {
  fontId?: string;
  fontVariantId?: string;
};

export type TypographyStyle = {
  name: string;
  textStyle: TextStyle;
  typography: Typography;
};
