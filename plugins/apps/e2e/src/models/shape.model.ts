export interface Shape {
  id: string;
  frameId?: string;
  parentId?: string;
  shapes?: string[];
  layoutGridCells?: Shape[];
}
