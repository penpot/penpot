import { z } from 'zod';

export const openUISchema = z.object({
  width: z.number().positive(),
  height: z.number().positive(),
  hidden: z.boolean().optional(),
});
