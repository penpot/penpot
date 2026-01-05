import { z } from 'zod';
import { manifestSchema } from './manifest.schema.js';

export type Manifest = z.infer<typeof manifestSchema>;
export type Permissions = Manifest['permissions'][number];
