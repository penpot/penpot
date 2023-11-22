import { isEqual } from 'lodash-es';
import React from 'react';
import { areNodesEqual } from 'react-cosmos-core';
import {
  FixtureContext,
  FixtureContextValue,
} from 'react-cosmos-renderer/client';

type Props = {
  children: React.ReactNode;
};

type State = {
  error: null | string;
};

export class ErrorCatch extends React.Component<Props, State> {
  declare context: FixtureContextValue;
  static contextType = FixtureContext;

  declare prevContext: FixtureContextValue | null;
  static prevContext = null;

  state: State = {
    error: null,
  };

  componentDidCatch(error: Error, info: { componentStack: string }) {
    this.setState({
      error: `${error.message}\n${info.componentStack}`,
    });
  }

  componentDidMount() {
    this.prevContext = this.context;
  }

  componentDidUpdate(prevProps: Props) {
    // A change in fixture (children) or fixture state signifies that the
    // problem that caused the current error might've been solved. If the error
    // persists, it will organically trigger the error state again in the next
    // update
    if (
      this.state.error &&
      (fixtureChanged(this.props.children, prevProps.children) ||
        fixtureStateChanged(
          this.context.fixtureState,
          this.prevContext?.fixtureState
        ))
    ) {
      this.setState({ error: null });
    }

    this.prevContext = this.context;
  }

  render() {
    return this.state.error
      ? this.renderError(this.state.error)
      : this.props.children;
  }

  renderError(error: string) {
    return (
      <>
        <h1>Ouch, something wrong!</h1>
        <pre>{error}</pre>
        <p>Check console for more info.</p>
      </>
    );
  }
}

function fixtureChanged(f1: React.ReactNode, f2: React.ReactNode) {
  return !areNodesEqual(f1, f2, true);
}

function fixtureStateChanged(fS1: object, fS2?: object) {
  return !isEqual(fS1, fS2);
}
