/**
 * Update IndexedPage metadata (name, background) and sync WASM canvas background.
 */

import type { IndexedPage } from '../../worker/types'
import { commitPageUpdate } from '../store/commit'
import { useWorkspaceStore } from '../store/workspace-store'

export async function commitPageMetadataUpdate(
  pageId: string,
  pageBefore: IndexedPage,
  partial: Partial<Pick<IndexedPage, 'name' | 'background'>>
): Promise<void> {
  const updatedPage: IndexedPage = { ...pageBefore, ...partial }
  await commitPageUpdate({ pageId, updatedPage })
  const bg = updatedPage.background ?? '#FFFFFF'
  useWorkspaceStore.getState().renderer?.setBackground(bg)
}
