import { FixtureId } from '../userModules/fixtureTypes.js';
import { buildQueryString, parseQueryString } from '../utils/queryString.js';

type RendererParams = {
  fixtureId?: FixtureId;
  locked?: boolean;
};

export type RendererSearchParams = {
  fixtureId?: string;
  locked?: string;
};

export function buildRendererQueryString(params: RendererParams) {
  return buildQueryString(encodeRendererSearchParams(params));
}

export function parseRendererQueryString(query: string) {
  return decodeRendererSearchParams(
    parseQueryString<RendererSearchParams>(query)
  );
}

function encodeRendererSearchParams(params: RendererParams) {
  const stringParams: RendererSearchParams = {};

  if (params.fixtureId) {
    stringParams.fixtureId = JSON.stringify(params.fixtureId);
  }

  if (params.locked) {
    stringParams.locked = 'true';
  }

  return stringParams;
}

function decodeRendererSearchParams(stringParams: RendererSearchParams) {
  const params: RendererParams = {};

  if (stringParams.fixtureId) {
    params.fixtureId = JSON.parse(stringParams.fixtureId);
  }

  if (stringParams.locked) {
    params.locked = stringParams.locked === 'true';
  }

  return params;
}
