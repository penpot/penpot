import { z } from 'zod';
import { openUISchema } from '../models/open-ui-options.schema.js';
import { createModal } from '../create-modal.js';

export const openUIApi = z
  .function()
  .args(
    z.string(),
    z.string(),
    z.enum(['dark', 'light']),
    openUISchema.optional(),
    z.boolean().optional(),
  )
  .implement((title, url, theme, options, allowDownloads) => {
    return createModal(title, url, theme, options, allowDownloads);
  });
