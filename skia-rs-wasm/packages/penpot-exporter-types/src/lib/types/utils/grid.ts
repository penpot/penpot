export type Grid = ColumnGrid | RowGrid | SquareGrid;

export type GridAlignment = 'stretch' | 'left' | 'center' | 'right';

export type SavedGrids = {
  square?: SquareParams;
  row?: ColumnParams;
  column?: ColumnParams;
};

export type ColumnGrid = {
  type: 'column';
  display: boolean;
  params: ColumnParams;
};

export type RowGrid = {
  type: 'row';
  display: boolean;
  params: ColumnParams;
};

export type SquareGrid = {
  type: 'square';
  display: boolean;
  params: SquareParams;
};

type ColumnParams = {
  color: GridColor;
  type?: GridAlignment;
  size?: number;
  margin?: number;
  itemLength?: number;
  gutter?: number;
};

type SquareParams = {
  size: number;
  color: GridColor;
};

type GridColor = {
  color: string;
  opacity: number;
};
