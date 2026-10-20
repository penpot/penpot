import { FileRpc } from '../models/file-rpc.model';
import { cleanId } from './clean-id';

export function getFileUrl(file: FileRpc, teamId: string) {
  const fileId = cleanId(file['~:id']);
  const pageId = cleanId(file['~:data']['~:pages'][0]);

  return `https://localhost:3449/?screen=workspace&team-id=${teamId}&file-id=${fileId}&page-id=${pageId}`;
}
