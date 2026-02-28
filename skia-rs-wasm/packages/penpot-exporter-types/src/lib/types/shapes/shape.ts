import type { TokenProperties } from './tokens';
import type { BlendMode } from '../utils/blendModes';
import type { Blur } from '../utils/blur';
import type { Export } from '../utils/export';
import type { Fill } from '../utils/fill';
import type { Grid } from '../utils/grid';
import type { Interaction } from '../utils/interaction';
import type { Matrix } from '../utils/matrix';
import type { Point } from '../utils/point';
import type { Selrect } from '../utils/selrect';
import type { Shadow } from '../utils/shadow';
import type { Stroke } from '../utils/stroke';
import type { SyncGroups } from '../utils/syncGroups';
import type { Uuid } from '../utils/uuid';
import type { ComponentPropertyReference } from '../../../component';

export type ShapeBaseAttributes = {
  id: Uuid;
  shapeRef?: Uuid;
  name: string;
  selrect?: Selrect;
  points?: Point[];
  transform?: Matrix;
  transformInverse?: Matrix;
  parentId?: Uuid;
  frameId?: Uuid;
  rotation?: number;
};

export type ShapeAttributes = {
  pageId?: Uuid;
  componentId?: Uuid;
  componentFile?: Uuid;
  componentRoot?: boolean;
  mainInstance?: boolean;
  remoteSynced?: boolean;
  touched?: SyncGroups[];
  blocked?: boolean;
  collapsed?: boolean;
  locked?: boolean;
  hidden?: boolean;
  maskedGroup?: boolean;
  fills?: Fill[];
  proportion?: number;
  proportionLock?: boolean;
  constraintsH?: ConstraintH;
  constraintsV?: ConstraintV;
  fixedScroll?: boolean;
  r1?: number;
  r2?: number;
  r3?: number;
  r4?: number;
  opacity?: number;
  grids?: Grid[];
  exports?: Export[];
  strokes?: Stroke[];
  blendMode?: BlendMode;
  interactions?: Interaction[];
  shadow?: Shadow[];
  blur?: Blur;
  growType?: GrowType;
  appliedTokens?: { [key in TokenProperties]?: string };

  fillStyleId?: string; // @TODO: move to any other place
  componentPropertyReferences?: ComponentPropertyReference; // @TODO: move to any other place

  /** SVG presentation attributes (fill, stroke, etc.); also present as svg-attrs in Penpot JSON */
  svgAttrs?: {
    fill?: string;
    fillOpacity?: number;
    fillRule?: string;
    style?: { fill?: string; fillOpacity?: number };
    [key: string]: unknown;
  };
};

export type ShapeGeomAttributes = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type GrowType = 'auto-width' | 'auto-height' | 'fixed';

export type ConstraintH = 'left' | 'right' | 'leftright' | 'center' | 'scale';
export type ConstraintV = 'top' | 'bottom' | 'topbottom' | 'center' | 'scale';
