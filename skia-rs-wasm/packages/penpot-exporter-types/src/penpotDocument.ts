import type { PenpotPage } from './lib/types/penpotPage';
import type { TypographyStyle } from './lib/types/shapes/textShape';
import type { Tokens } from './lib/types/shapes/tokens';
import type { FillStyle } from './lib/types/utils/fill';
import type { ComponentProperty, ComponentRoot } from './component';

export type PenpotDocument = {
  name: string;
  children?: PenpotPage[];
  components: Record<string, ComponentRoot>;
  images: Record<string, Uint8Array<ArrayBuffer>>;
  paintStyles: Record<string, FillStyle>;
  textStyles: Record<string, TypographyStyle>;
  tokens?: Tokens;
  componentProperties: Record<string, ComponentProperty>;
  externalLibraries: Record<string, string>;
  missingFonts: string[];
  isShared: boolean;
};
