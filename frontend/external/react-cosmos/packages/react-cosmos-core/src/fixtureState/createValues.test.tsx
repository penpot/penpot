import React from 'react';
import { createValues } from './createValues.js';

it('creates string value', () => {
  const values = createValues({ myProp: 'foo' });
  expect(values).toEqual({
    myProp: {
      type: 'primitive',
      data: 'foo',
    },
  });
});

it('creates number value', () => {
  const values = createValues({ myProp: 56 });
  expect(values).toEqual({
    myProp: {
      type: 'primitive',
      data: 56,
    },
  });
});

it('creates boolean value', () => {
  const values = createValues({ myProp: false });
  expect(values).toEqual({
    myProp: {
      type: 'primitive',
      data: false,
    },
  });
});

it('creates undefined value', () => {
  const values = createValues({ myProp: undefined });
  expect(values).toEqual({
    myProp: {
      type: 'primitive',
      data: undefined,
    },
  });
});

it('creates null value', () => {
  const values = createValues({ myProp: null });
  expect(values).toEqual({
    myProp: {
      type: 'primitive',
      data: null,
    },
  });
});

it('creates unserializable function value', () => {
  const values = createValues({ myProp: () => {} });
  expect(values).toEqual({
    myProp: {
      type: 'unserializable',
      stringifiedData: '() => { }',
    },
  });
});

it('creates unserializable regexp value', () => {
  const values = createValues({ myProp: /impossible/g });
  expect(values).toEqual({
    myProp: {
      type: 'unserializable',
      stringifiedData: '/impossible/g',
    },
  });
});

it('creates unserializable React element value', () => {
  const values = createValues({ myProp: <div /> });
  expect(values).toEqual({
    myProp: {
      type: 'unserializable',
      stringifiedData: '<React.Element />',
    },
  });
});

it('creates empty object value', () => {
  const values = createValues({ myProp: {} });
  expect(values).toEqual({
    myProp: {
      type: 'object',
      values: {},
    },
  });
});

it('creates serializable object value', () => {
  const values = createValues({
    myProp: { strProp: 'foo', numProp: 56, boolProp: false },
  });
  expect(values).toEqual({
    myProp: {
      type: 'object',
      values: {
        strProp: {
          type: 'primitive',
          data: 'foo',
        },
        numProp: {
          type: 'primitive',
          data: 56,
        },
        boolProp: {
          type: 'primitive',
          data: false,
        },
      },
    },
  });
});

it('creates partially serializable object value', () => {
  const values = createValues({
    myProp: { strProp: 'foo', fnProp: () => {} },
  });
  expect(values).toEqual({
    myProp: {
      type: 'object',
      values: {
        strProp: {
          type: 'primitive',
          data: 'foo',
        },
        fnProp: {
          type: 'unserializable',
          stringifiedData: '() => { }',
        },
      },
    },
  });
});

it('creates array value', () => {
  const values = createValues({
    myProp: ['foo', () => {}],
  });
  expect(values).toEqual({
    myProp: {
      type: 'array',
      values: [
        {
          type: 'primitive',
          data: 'foo',
        },
        {
          type: 'unserializable',
          stringifiedData: '() => { }',
        },
      ],
    },
  });
});
