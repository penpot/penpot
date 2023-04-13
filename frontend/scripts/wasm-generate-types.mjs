import { ClassPrototype, PropertyPrototype } from 'assemblyscript'
import prettier from 'prettier'

/**
 * After initialization all types are available, so we can generate
 * exported types safely.
 *
 * TODO: This will export everything under `wasm` folder, maybe we can
 *       make it more specific? Or even use exported (global?) variables
 *       to register only exported types?
 *
 * @param {Program} program
 */
export function afterInitialize(program) {
  const types = new Map()
  for (const [name, element] of program.elementsByName) {
    if (name.includes("wasm") && element instanceof ClassPrototype) {
      const properties = new Map()
      for (const [name, member] of element.instanceMembers) {
        if (member instanceof PropertyPrototype && member.isField) {
          properties.set(name, { type: member.typeNode.name.identifier.text });
        }
      }
      types.set(element.name, properties);
    }
  }

  let str = ''
  for (const [name, properties] of types) {
    str += `TypeRegistry.register("${name}", ${JSON.stringify(Object.fromEntries(properties), null, 2)});\n\n`
  }
  console.log(prettier.format(str, { parser: 'babel' }))
}

