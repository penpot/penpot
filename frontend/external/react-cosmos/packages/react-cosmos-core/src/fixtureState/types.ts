import { StateUpdater } from '../utils/types.js';

export type FixtureDecoratorId = string;

export type FixtureElementId = {
  decoratorId: string;
  elPath: string;
};

export type FixtureStateUnserializableValue = {
  type: 'unserializable';
  stringifiedData: string;
};

export type PrimitiveData = string | number | boolean | null | undefined;

export type ObjectData = Record<string, unknown>;

export type ArrayData = unknown[];

export type FixtureStateData = PrimitiveData | ObjectData | ArrayData;

export type FixtureStatePrimitiveValue = {
  type: 'primitive';
  data: PrimitiveData;
};

export type FixtureStateObjectValue = {
  type: 'object';
  values: FixtureStateValues;
};

export type FixtureStateArrayValue = {
  type: 'array';
  values: FixtureStateValue[];
};

export type FixtureStateValue =
  | FixtureStateUnserializableValue
  | FixtureStatePrimitiveValue
  | FixtureStateObjectValue
  | FixtureStateArrayValue;

export type FixtureStateValues = Record<string, FixtureStateValue>;

export type FixtureRenderKey = number;

export type FixtureStateProps = {
  elementId: FixtureElementId;
  values: FixtureStateValues;
  renderKey: FixtureRenderKey;
  componentName: string;
};

export type FixtureStateClassState = {
  elementId: FixtureElementId;
  values: FixtureStateValues;
  componentName: string;
};

export type FixtureStateStandardControl = {
  type: 'standard';
  defaultValue: FixtureStateValue;
  currentValue: FixtureStateValue;
};

export type FixtureStateSelectControl = {
  type: 'select';
  options: string[];
  defaultValue: string;
  currentValue: string;
};

export type FixtureStateControl =
  | FixtureStateStandardControl
  | FixtureStateSelectControl;

export type FixtureStateControls = Record<string, FixtureStateControl>;

export type FixtureState = {
  props?: FixtureStateProps[];
  classState?: FixtureStateClassState[];
  controls?: FixtureStateControls;
} & Record<string, unknown>;

export type SetFixtureState = (update: StateUpdater<FixtureState>) => unknown;
