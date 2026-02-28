import type { BoolShape } from './lib/types/shapes/boolShape';
import type { CircleShape } from './lib/types/shapes/circleShape';
import type { ComponentShape } from './lib/types/shapes/componentShape';
import type { FrameShape } from './lib/types/shapes/frameShape';
import type { GroupShape } from './lib/types/shapes/groupShape';
import type { PathShape } from './lib/types/shapes/pathShape';
import type { RectShape } from './lib/types/shapes/rectShape';
import type { TextShape } from './lib/types/shapes/textShape';
import type { ComponentInstance } from './component';

export type PenpotNode =
  | FrameShape
  | GroupShape
  | PathShape
  | RectShape
  | CircleShape
  | TextShape
  | BoolShape
  | ComponentInstance
  | ComponentShape;
