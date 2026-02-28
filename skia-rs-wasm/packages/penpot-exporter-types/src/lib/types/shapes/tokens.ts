import type { Uuid } from '../utils/uuid';

export type Tokens = {
  $metadata: Metadata;
  $themes: Theme[];
};

export type TokenSets = Record<string, Set>;

export type Set = {
  [key: string]: Token | Record<string, Token>;
};

export type TokenType =
  | 'color'
  | 'number'
  | 'dimension'
  | 'rotation'
  | 'spacing'
  | 'opacity'
  | 'sizing'
  | 'borderRadius'
  | 'borderWidth'
  | 'fontFamilies'
  | 'fontSizes'
  | 'fontWeights'
  | 'textDecoration'
  | 'letterSpacing'
  | 'textCase';

export type Token = {
  $value: string | string[];
  $type: TokenType;
  $description: string;
};

export type Metadata = {
  tokenSetOrder: string[];
  activeThemes: string[];
  activeSets: string[];
};

export type Theme = {
  id?: Uuid;
  name: string;
  group: string;
  description: string;
  isSource: boolean;
  selectedTokenSets: Record<string, 'enabled'>;
};

export type TokenProperties =
  | 'r1'
  | 'r2'
  | 'r3'
  | 'r4'
  | 'width'
  | 'height'
  | 'layoutItemMinW'
  | 'layoutItemMaxW'
  | 'layoutItemMinH'
  | 'layoutItemMaxH'
  | 'rowGap'
  | 'columnGap'
  | 'p1'
  | 'p2'
  | 'p3'
  | 'p4'
  | 'm1'
  | 'm2'
  | 'm3'
  | 'm4'
  | 'rotation'
  | 'lineHeight'
  | 'fontSize'
  | 'letterSpacing'
  | 'fontFamily'
  | 'fontWeight'
  | 'textCase'
  | 'textDecoration'
  | 'typography'
  | 'strokeWidth'
  | 'fill'
  | 'strokeColor'
  | 'opacity'
  | 'x'
  | 'y';
