import type { Uuid } from '../utils/uuid';

export type LayoutSizing = 'fill' | 'fix' | 'auto';

export type LayoutAlignSelf = 'start' | 'end' | 'center' | 'stretch';

export type LayoutChildAttributes = {
  'layoutItemMarginType'?: 'simple' | 'multiple';
  'layoutItemMargin'?: {
    m1?: number;
    m2?: number;
    m3?: number;
    m4?: number;
  };
  'layoutItemMaxH'?: number;
  'layoutItemMinH'?: number;
  'layoutItemMaxW'?: number;
  'layoutItemMinW'?: number;
  'layoutItemH-Sizing'?: LayoutSizing;
  'layoutItemV-Sizing'?: LayoutSizing;
  'layoutItemAlignSelf'?: LayoutAlignSelf;
  'layoutItemAbsolute'?: boolean;
  'layoutItemZIndex'?: number;
};

export type JustifyAlignContent =
  | 'start'
  | 'center'
  | 'end'
  | 'space-between'
  | 'space-around'
  | 'space-evenly'
  | 'stretch';

export type JustifyAlignItems = 'start' | 'end' | 'center' | 'stretch';

export type LayoutFlexDir =
  | 'row'
  | 'reverse-row'
  | 'row-reverse'
  | 'column'
  | 'reverse-column'
  | 'column-reverse';

export type LayoutGridDir = 'row' | 'column';

export type LayoutGap = {
  rowGap?: number;
  columnGap?: number;
};

export type LayoutWrapType = 'wrap' | 'nowrap' | 'no-wrap';

export type LayoutPadding = {
  p1?: number;
  p2?: number;
  p3?: number;
  p4?: number;
};

export type GridTrack = {
  type: 'percent' | 'flex' | 'auto' | 'fixed';
  value?: number;
};

export type GridCellPosition = 'auto' | 'manual' | 'area';

export type GridCellAlignSelf = 'auto' | 'start' | 'end' | 'center' | 'stretch';

export type GridCellJustifySelf = 'auto' | 'start' | 'end' | 'center' | 'stretch';

export type GridCell = {
  id?: Uuid;
  areaName?: string;
  row: number;
  rowSpan: number;
  column: number;
  columnSpan: number;
  position?: GridCellPosition;
  alignSelf?: GridCellAlignSelf;
  justifySelf?: GridCellJustifySelf;
  shapes?: Uuid[];
};

export type LayoutMode = 'flex' | 'grid';

export type LayoutAttributes = {
  layout?: LayoutMode;
  layoutFlexDir?: LayoutFlexDir;
  layoutGap?: LayoutGap;
  layoutGapType?: 'simple' | 'multiple';
  layoutWrapType?: LayoutWrapType;
  layoutPaddingType?: 'simple' | 'multiple';
  layoutPadding?: LayoutPadding;
  layoutJustifyContent?: JustifyAlignContent;
  layoutJustifyItems?: JustifyAlignItems;
  layoutAlignContent?: JustifyAlignContent;
  layoutAlignItems?: JustifyAlignItems;
  layoutGridDir?: LayoutGridDir;
  layoutGridRows?: GridTrack[];
  layoutGridColumns?: GridTrack[];
  layoutGridCells?: { [uuid: Uuid]: GridCell };
};
