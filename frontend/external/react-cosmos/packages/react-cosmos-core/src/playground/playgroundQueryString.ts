import { buildQueryString, parseQueryString } from '../utils/queryString.js';
import { PlaygroundParams } from './playgroundParams.js';

type SearchParams = {
  fixtureId?: string;
};

export function buildPlaygroundQueryString(params: PlaygroundParams) {
  return buildQueryString(encodePlaygroundSearchParams(params));
}

export function parsePlaygroundQueryString(query: string) {
  return decodePlaygroundSearchParams(parseQueryString<SearchParams>(query));
}

function encodePlaygroundSearchParams(params: PlaygroundParams) {
  const stringParams: SearchParams = {};

  if (params.fixtureId) {
    stringParams.fixtureId = JSON.stringify(params.fixtureId);
  }

  return stringParams;
}

function decodePlaygroundSearchParams(stringParams: SearchParams) {
  const params: PlaygroundParams = {};

  if (stringParams.fixtureId) {
    params.fixtureId = JSON.parse(stringParams.fixtureId);
  }

  return params;
}
