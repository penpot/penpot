import { Shape } from '../models/shape.model';

export function cleanId(id: string) {
  return id.replace('~u', '');
}

export function idObjectToArray(obj: Shape[], newId: string) {
  return Object.values(obj).map((item) => {
    return {
      ...item,
      id: newId,
    };
  });
}
