// From here https://gist.github.com/jed/982883
export function uuid(a?: string): string {
  return a
    ? (Number(a) ^ ((Math.random() * 16) >> (Number(a) / 4))).toString(16)
    : ('' + 1e7 + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, uuid);
}
