import { z } from 'zod';
import { openUISchema } from './open-ui-options.schema.js';

export type OpenUIOptions = z.infer<typeof openUISchema>;
