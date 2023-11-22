import { isPlainObject } from 'lodash-es';
import { isElement } from 'react-is';
import { ArrayData, ObjectData, PrimitiveData } from './types.js';

export function isString(value: unknown): value is string {
  return typeof value === 'string';
}

export function isNumber(value: unknown): value is number {
  return typeof value === 'number';
}

export function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean';
}

export function isNull(value: unknown): value is null {
  return value === null;
}

export function isUndefined(value: unknown): value is undefined {
  return value === undefined;
}

export function isPrimitiveData(data: unknown): data is PrimitiveData {
  return (
    isString(data) ||
    isNumber(data) ||
    isBoolean(data) ||
    isNull(data) ||
    isUndefined(data)
  );
}

export function isObject(data: unknown): data is ObjectData {
  return isPlainObject(data) && !isElement(data);
}

export function isArray(data: unknown): data is ArrayData {
  return Array.isArray(data);
}
