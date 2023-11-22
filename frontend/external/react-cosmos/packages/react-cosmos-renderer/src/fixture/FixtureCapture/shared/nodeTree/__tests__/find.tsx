import { get } from 'lodash-es';
import React, { ReactElement, ReactNode } from 'react';
import { isElement } from 'react-is';
import { findElementPaths } from '../index.js';

it('finds no paths on empty node', () => {
  const noChildrenNodes: ReactNode[] = [null, true, false, 'Hello', 7];

  noChildrenNodes.forEach(node => {
    expect(findElementPaths(node)).toEqual([]);
  });
});

it('finds single child path', () => {
  expect(findElementPaths(<div />)).toEqual(['']);
});

it('finds multi direct child paths', () => {
  const node = [<div key="1" />, null, <div key="2" />];

  expect(findElementPaths(node)).toEqual(['[0]', '[2]']);
});

it('finds multi fragment child paths', () => {
  const node = (
    <>
      <div />
      {null}
      <div />
    </>
  );

  expect(findElementPaths(node)).toEqual([
    'props.children[0]',
    'props.children[2]',
  ]);
});

it('finds nested paths', () => {
  const node = (
    <div>
      <div>
        <div />
      </div>
      {null}
      <div>
        <>
          <div>
            <div />
            <div />
          </div>
        </>
      </div>
    </div>
  );
  const paths = findElementPaths(node);

  expect(paths).toEqual([
    '',
    'props.children[0]',
    'props.children[0].props.children',
    'props.children[2]',
    'props.children[2].props.children.props.children',
    'props.children[2].props.children.props.children.props.children[0]',
    'props.children[2].props.children.props.children.props.children[1]',
  ]);

  // Ensure paths are valid
  paths.forEach(path => {
    expect(isElement(path === '' ? node : get(node, path))).toBe(true);
  });
});

it('only finds paths outside function children', () => {
  const Comp = (props: { children: () => ReactElement }) => null;
  expect(
    findElementPaths(
      <div>
        <Comp>{() => <div />}</Comp>
      </div>
    )
  ).toEqual(['', 'props.children']);
});
