export function parseQueryString<T extends {}>(query: string): T {
  return Object.fromEntries(new URLSearchParams(query)) as T;
}

export function buildQueryString(searchParams: {}): string {
  const queryString = new URLSearchParams(searchParams).toString();
  return queryString && `?${queryString}`;
}
