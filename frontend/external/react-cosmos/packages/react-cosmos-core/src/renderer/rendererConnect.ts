import { FixtureState } from '../fixtureState/types.js';
import { FixtureId, FixtureList } from '../userModules/fixtureTypes.js';

// FYI: Renderer ids are self assigned in remote environments, so uniqueness
// cannot be established by consensus
export type RendererId = string;

export type PingRenderersRequest = {
  type: 'pingRenderers';
};

export type ReloadRendererRequest = {
  type: 'reloadRenderer';
  payload: {
    rendererId: RendererId;
  };
};

export type SelectFixtureRequest = {
  type: 'selectFixture';
  payload: {
    rendererId: RendererId;
    fixtureId: FixtureId;
    fixtureState: FixtureState;
  };
};

export type UnselectFixtureRequest = {
  type: 'unselectFixture';
  payload: {
    rendererId: RendererId;
  };
};

export type SetFixtureStateRequest = {
  type: 'setFixtureState';
  payload: {
    rendererId: RendererId;
    // The fixture ID is sent alongside the fixture state change to ensure
    // that the fixture state is only paired with its corresponding fixture
    fixtureId: FixtureId;
    fixtureState: FixtureState;
  };
};

export type RendererRequest =
  | PingRenderersRequest
  | ReloadRendererRequest
  | SelectFixtureRequest
  | UnselectFixtureRequest
  | SetFixtureStateRequest;

export type RendererReadyResponse = {
  type: 'rendererReady';
  payload: {
    rendererId: RendererId;
    selectedFixtureId?: FixtureId | null;
  };
};

export type RendererErrorResponse = {
  type: 'rendererError';
  payload: {
    rendererId: RendererId;
  };
};

export type FixtureListUpdateResponse = {
  type: 'fixtureListUpdate';
  payload: {
    rendererId: RendererId;
    fixtures: FixtureList;
  };
};

// Caused by an organic state change inside the renderer. Also dispatched
// after a fixtureSelect request, when rendering stateful components, as their
// initial state is read.
export type FixtureStateChangeResponse = {
  type: 'fixtureStateChange';
  payload: {
    rendererId: RendererId;
    // The fixture ID is sent alongside the fixture state to ensure that the
    // fixture state is only paired with its corresponding fixture
    fixtureId: FixtureId;
    // Entire fixture state is included
    fixtureState: FixtureState;
  };
};

export type PlaygroundCommandResponse = {
  type: 'playgroundCommand';
  payload: {
    command: string;
  };
};

export type RendererResponse =
  | RendererReadyResponse
  | RendererErrorResponse
  | FixtureListUpdateResponse
  | FixtureStateChangeResponse
  | PlaygroundCommandResponse;

export type RendererConnect<
  Request = RendererRequest,
  Response = RendererResponse,
> = {
  postMessage: (msg: Response) => unknown;
  onMessage(handler: (msg: Request) => unknown): () => void;
};
