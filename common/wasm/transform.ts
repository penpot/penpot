import Matrix from './Matrix';
import Point from './Point';
import { degreesToRadians } from './Angle';
import TransformInput from './TransformInput';
import TransformOutput from './TransformOutput';
import Modifier from './Modifier';
import ModifierType from './ModifierType';

export const transformInput: TransformInput = new TransformInput();
export const transformOutput: TransformOutput = new TransformOutput();

const MAX_MODIFIERS = 64;

let modifierIndex: i32 = 0;
const modifiers: StaticArray<Modifier> = new StaticArray<Modifier>(MAX_MODIFIERS);
for (let i = 0; i < MAX_MODIFIERS; i++) {
  modifiers[i] = new Modifier();
}

const matrix: Matrix = new Matrix();

export function reset(): void
{
  modifierIndex = 0;
}

export function addMove(order: i32): void
{
  modifiers[modifierIndex].type = ModifierType.MOVE;
  modifiers[modifierIndex].vector.copy(transformInput.vector);
  modifiers[modifierIndex].order = order;
  modifierIndex++;
}

export function addResize(order: i32): void
{
  modifiers[modifierIndex].type = ModifierType.RESIZE;
  modifiers[modifierIndex].vector.copy(transformInput.vector);
  modifiers[modifierIndex].origin.copy(transformInput.origin);
  modifiers[modifierIndex].transform.copy(transformInput.transform);
  modifiers[modifierIndex].transformInverse.copy(transformInput.transformInverse);
  modifiers[modifierIndex].shouldTransform = transformInput.shouldTransform;
  modifiers[modifierIndex].order = order;
  modifierIndex++;
}

export function addRotation(order: i32): void
{
  modifiers[modifierIndex].type = ModifierType.ROTATION;
  modifiers[modifierIndex].center.copy(transformInput.center);
  modifiers[modifierIndex].rotation = degreesToRadians(transformInput.rotation);
  modifiers[modifierIndex].order = order;
  modifierIndex++;
}

export function compute(): void
{
  // modifiers.sort((a, b) => a.order - b.order);
  matrix.identity()
  for (let index = 0; index < modifierIndex; index++) {
    if (modifiers[index].type === ModifierType.MOVE || modifiers[index].type === ModifierType.RESIZE) {
      Matrix.multiply(matrix, matrix, modifiers[index].compute().matrix)
    } else if (modifiers[index].type === ModifierType.ROTATION) { // WTF?
      Matrix.multiply(matrix, modifiers[index].compute().matrix, matrix)
    }
  }
  transformOutput.matrix.copy(matrix)
}
