import { z } from 'zod';

export const manifestSchema = z.object({
  pluginId: z.string(),
  name: z.string(),
  host: z.string().url(),
  code: z.string(),
  icon: z.string().optional(),
  description: z.string().max(200).optional(),
  permissions: z.array(
    z.enum([
      'content:read',
      'content:write',
      'library:read',
      'library:write',
      'user:read',
      'comment:read',
      'comment:write',
      'allow:downloads',
      'allow:localstorage',
    ]),
  ),
});
