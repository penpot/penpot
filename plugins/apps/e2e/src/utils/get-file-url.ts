import { FileRpc } from '../models/file-rpc.model';
import { cleanId } from './clean-id';

export function getFileUrl(file: FileRpc) {
  const projectId = cleanId(file['~:project-id']);
  const fileId = cleanId(file['~:id']);
  const pageId = cleanId(file['~:data']['~:pages'][0]);

  return `http://localhost:3449/#/workspace/${projectId}/${fileId}?page-id=${pageId}`;
}
