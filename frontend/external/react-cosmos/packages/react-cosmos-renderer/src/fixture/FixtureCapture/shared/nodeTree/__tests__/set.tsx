import React from 'react';
import { setElementAtPath } from '../index.js';

it('sets root child', () => {
  const node = <div />;
  const newNode = setElementAtPath(node, '', element => ({
    ...element,
    props: {
      className: 'root',
    },
  }));

  expect(newNode).toEqual(<div className="root" />);

  // Ensure source element isn't mutated
  expect(node).toEqual(<div />);
});

it('sets fragment child', () => {
  const node = (
    <>
      <div />
    </>
  );
  const newNode = setElementAtPath(node, 'props.children', element => ({
    ...element,
    props: {
      className: 'root',
    },
  }));

  expect(newNode).toEqual(
    <>
      <div className="root" />
    </>
  );

  // Ensure source element isn't mutated
  expect(node).toEqual(
    <>
      <div />
    </>
  );
});

it('sets array child', () => {
  const node = [<div key="0" />];
  const newNode = setElementAtPath(node, '[0]', element => ({
    ...element,
    props: {
      className: 'root',
    },
  }));

  expect(newNode).toEqual([<div key="0" className="root" />]);

  // Ensure source element isn't mutated
  expect(node).toEqual([<div key="0" />]);
});

it('sets nested children', () => {
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

  let newNode = setElementAtPath(
    node,
    'props.children[0].props.children',
    element => ({
      ...element,
      props: {
        className: 'deep',
      },
    })
  );

  newNode = setElementAtPath(
    newNode,
    'props.children[2].props.children.props.children.props.children[1]',
    element => ({
      ...element,
      props: {
        className: 'deeper',
      },
    })
  );

  expect(newNode).toEqual(
    <div>
      <div>
        <div className="deep" />
      </div>
      {null}
      <div>
        <>
          <div>
            <div />
            <div className="deeper" />
          </div>
        </>
      </div>
    </div>
  );

  // Ensure source element isn't mutated
  expect(node).toEqual(
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
});
