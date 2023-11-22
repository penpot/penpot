export function getChildrenPath(elPath: string) {
  return isRootPath(elPath) ? 'props.children' : `${elPath}.props.children`;
}

export function isRootPath(elPath: string) {
  return elPath === '';
}
