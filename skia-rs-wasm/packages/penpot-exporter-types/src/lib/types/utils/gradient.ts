export type Gradient = {
  type: 'linear' | 'radial';
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  width: number;
  stops: GradientStop[];
};

type GradientStop = {
  color: string;
  opacity?: number;
  offset: number;
};
