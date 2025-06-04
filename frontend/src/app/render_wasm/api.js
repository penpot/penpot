export function setObject(object) {
  console.log(object.id)
}

export function setObjects(objects, fn) {
  objects.forEach((object) => fn(objects, object))
}
