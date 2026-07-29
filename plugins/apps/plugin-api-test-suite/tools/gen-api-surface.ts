import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { mkdirSync, writeFileSync } from 'node:fs';
import ts from 'typescript';

/**
 * Generates `src/generated/api-surface.json` from `libs/plugin-types/index.d.ts`
 * using the TypeScript compiler API. The output drives type-aware coverage:
 *
 * - `interfaces`: own (syntactically declared) members per interface — the
 *   coverage denominator.
 * - `graph`: for every interface, all reachable members (including inherited),
 *   each annotated with the interface that declares it and the type it yields.
 *   This lets the recorder attribute an access to the interface the value really
 *   is, instead of matching member names across unrelated interfaces.
 * - `unions`: union aliases (e.g. `Shape`) with the discriminant needed to pick
 *   the concrete variant of a runtime value.
 *
 * Re-run with `pnpm run gen:api` whenever the public Plugin API types change.
 */

const here = dirname(fileURLToPath(import.meta.url));
const typesPath = resolve(here, '../../../libs/plugin-types/index.d.ts');
const outPath = resolve(here, '../src/generated/api-surface.json');

const program = ts.createProgram([typesPath], { skipLibCheck: true });
const checker = program.getTypeChecker();
const source = program.getSourceFile(typesPath);

if (!source) {
  throw new Error(`Could not load Plugin API types at ${typesPath}`);
}

const interfaceDecls = new Map<string, ts.InterfaceDeclaration>();
const unionAliases = new Map<string, ts.TypeAliasDeclaration>();
// Object-literal type aliases (e.g. `type LibraryContext = { local: Library; … }`)
// are treated like interfaces so the recorder can wrap them and follow the chain
// into the types they expose (e.g. Context.library -> LibraryContext.local -> Library).
const objectAliases = new Map<
  string,
  { decl: ts.TypeAliasDeclaration; literal: ts.TypeLiteralNode }
>();

source.forEachChild((node) => {
  if (ts.isInterfaceDeclaration(node)) {
    interfaceDecls.set(node.name.text, node);
  } else if (ts.isTypeAliasDeclaration(node) && ts.isUnionTypeNode(node.type)) {
    unionAliases.set(node.name.text, node);
  } else if (
    ts.isTypeAliasDeclaration(node) &&
    ts.isTypeLiteralNode(node.type)
  ) {
    objectAliases.set(node.name.text, { decl: node, literal: node.type });
  }
});

const knownInterfaces = new Set([
  ...interfaceDecls.keys(),
  ...objectAliases.keys(),
]);
const knownUnions = new Set(unionAliases.keys());

function memberName(member: ts.TypeElement): string | undefined {
  if (
    (ts.isPropertySignature(member) || ts.isMethodSignature(member)) &&
    member.name &&
    (ts.isIdentifier(member.name) || ts.isStringLiteral(member.name))
  ) {
    return member.name.text;
  }
  return undefined;
}

/** True when a declaration carries an `@deprecated` JSDoc tag. */
function isDeprecated(node: ts.Node): boolean {
  return ts.getJSDocTags(node).some((t) => t.tagName.text === 'deprecated');
}

// Own (declared) members per interface — the coverage denominator. Deprecated
// interfaces and members are skipped so deprecated API never counts towards
// coverage (e.g. the legacy `Image` shape, `Color.refId/refFile`).
const interfaces: Record<string, string[]> = {};
for (const [name, decl] of interfaceDecls) {
  if (isDeprecated(decl)) continue;
  const names = new Set<string>();
  for (const member of decl.members) {
    if (isDeprecated(member)) continue;
    const m = memberName(member);
    if (m) names.add(m);
  }
  if (names.size > 0) interfaces[name] = [...names].sort();
}
for (const [name, { decl, literal }] of objectAliases) {
  if (isDeprecated(decl)) continue;
  const names = new Set<string>();
  for (const member of literal.members) {
    if (isDeprecated(member)) continue;
    const m = memberName(member);
    if (m) names.add(m);
  }
  if (names.size > 0) interfaces[name] = [...names].sort();
}

// Honor `Omit<Base, Keys>` in heritage clauses: a member the *public* interface
// removes from an internal base is not part of the reachable surface, so it must
// not count towards coverage. `Penpot extends Omit<Context, 'addListener' |
// 'removeListener'>` is the motivating case — `Context` is the internal interface
// and `Penpot` is the public one — but this applies to any such omission.
function stringLiterals(node: ts.TypeNode): string[] {
  const collect = (n: ts.TypeNode): string[] => {
    if (ts.isLiteralTypeNode(n) && ts.isStringLiteral(n.literal)) {
      return [n.literal.text];
    }
    if (ts.isUnionTypeNode(n)) return n.types.flatMap(collect);
    return [];
  };
  return collect(node);
}

for (const decl of interfaceDecls.values()) {
  for (const clause of decl.heritageClauses ?? []) {
    for (const t of clause.types) {
      if (
        ts.isIdentifier(t.expression) &&
        t.expression.text === 'Omit' &&
        t.typeArguments?.length === 2
      ) {
        const [baseRef, keysArg] = t.typeArguments;
        if (
          ts.isTypeReferenceNode(baseRef) &&
          ts.isIdentifier(baseRef.typeName)
        ) {
          const base = baseRef.typeName.text;
          const omitted = new Set(stringLiterals(keysArg));
          if (interfaces[base] && omitted.size > 0) {
            interfaces[base] = interfaces[base].filter((m) => !omitted.has(m));
          }
        }
      }
    }
  }
}

/**
 * Resolves a type to a tracked interface/union name (+ array flag) by parsing
 * its textual form. Using `typeToString` keeps this resilient across compiler
 * versions, where the structural type-flag APIs differ.
 */
function resolveType(type: ts.Type): { name: string | null; array: boolean } {
  let text = checker.typeToString(type).replace(/^readonly\s+/, '');

  // Unwrap Promise<...>
  const promiseMatch = text.match(/^Promise<(.+)>$/s);
  if (promiseMatch) text = promiseMatch[1].trim();

  // Drop nullish, string-literal and bare-primitive union parts before array
  // detection, so a single tracked type can still be resolved out of unions like
  // `Group | null`, `Fill[] | 'mixed'` or `string | TokenShadowValueString[]`.
  // Dropping primitives is safe: the recorder never wraps primitive values, so a
  // primitive run-time value is returned as-is regardless of the resolved type.
  const primitives = new Set([
    'null',
    'undefined',
    'string',
    'number',
    'boolean',
    'unknown',
    'any',
    'void',
  ]);
  text = text
    .split('|')
    .map((p) => p.trim())
    .filter((p) => !primitives.has(p) && !/^["'].*["']$/.test(p))
    .join(' | ');

  let array = false;
  const arrayMatch = text.match(/^(.+)\[\]$/s) ?? text.match(/^Array<(.+)>$/s);
  if (arrayMatch) {
    array = true;
    text = arrayMatch[1].trim();
  }

  if (knownInterfaces.has(text) || knownUnions.has(text)) {
    return { name: text, array };
  }
  return { name: null, array };
}

// Full member graph per interface (including inherited members).
const graph: Record<string, Record<string, ApiMemberInfoOut>> = {};

type MemberKind = 'method' | 'get' | 'getset';

interface ApiMemberInfoOut {
  decl: string;
  kind: MemberKind;
  type: string | null;
  array: boolean;
}

/** Classifies a member declaration as a method, read-only, or writable property. */
function memberKind(decl: ts.Declaration): MemberKind {
  if (ts.isMethodSignature(decl)) return 'method';
  if (ts.isPropertySignature(decl)) {
    if (decl.type && ts.isFunctionTypeNode(decl.type)) return 'method';
    const readonly = decl.modifiers?.some(
      (m) => m.kind === ts.SyntaxKind.ReadonlyKeyword,
    );
    return readonly ? 'get' : 'getset';
  }
  return 'getset';
}

for (const [name, decl] of interfaceDecls) {
  const type = checker.getTypeAtLocation(decl);
  const entries: Record<string, ApiMemberInfoOut> = {};

  for (const prop of checker.getPropertiesOfType(type)) {
    const declaration = prop.declarations?.[0];
    if (!declaration) continue;
    const parent = declaration.parent;
    if (!parent || !ts.isInterfaceDeclaration(parent)) continue;
    const declName = parent.name.text;
    if (!knownInterfaces.has(declName)) continue;

    const propType = checker.getTypeOfSymbolAtLocation(prop, decl);
    const signatures = propType.getCallSignatures();
    const resolved = resolveType(
      signatures.length > 0 ? signatures[0].getReturnType() : propType,
    );

    entries[prop.name] = {
      decl: declName,
      kind: memberKind(declaration),
      type: resolved.name,
      array: resolved.array,
    };
  }

  graph[name] = entries;
}

// Object-literal aliases: all members are own (no inheritance), so the declaring
// interface is always the alias itself.
for (const [name, { decl, literal }] of objectAliases) {
  const entries: Record<string, ApiMemberInfoOut> = {};
  for (const member of literal.members) {
    const m = memberName(member);
    if (!m) continue;
    const propType = checker.getTypeAtLocation(member);
    const signatures = propType.getCallSignatures();
    const resolved = resolveType(
      signatures.length > 0 ? signatures[0].getReturnType() : propType,
    );
    entries[m] = {
      decl: name,
      kind: memberKind(member),
      type: resolved.name,
      array: resolved.array,
    };
  }
  graph[name] = entries;
  void decl;
}

// Union aliases + discriminants (literal `type` field -> variant interface).
const unions: Record<string, UnionInfoOut> = {};

interface UnionInfoOut {
  variants: string[];
  discriminant: { field: string; map: Record<string, string> } | null;
}

function literalDiscriminant(
  iface: ts.InterfaceDeclaration,
  field: string,
): string | null {
  for (const member of iface.members) {
    if (memberName(member) !== field) continue;
    if (ts.isPropertySignature(member) && member.type) {
      if (
        ts.isLiteralTypeNode(member.type) &&
        ts.isStringLiteral(member.type.literal)
      ) {
        return member.type.literal.text;
      }
    }
  }
  return null;
}

for (const [name, decl] of unionAliases) {
  if (!ts.isUnionTypeNode(decl.type)) continue;
  const variants: string[] = [];
  for (const member of decl.type.types) {
    if (ts.isTypeReferenceNode(member) && ts.isIdentifier(member.typeName)) {
      const variantName = member.typeName.text;
      if (knownInterfaces.has(variantName)) variants.push(variantName);
    }
  }
  if (variants.length === 0) continue;

  // Build a discriminant map using the `type` literal of each variant.
  const map: Record<string, string> = {};
  for (const variant of variants) {
    const lit = literalDiscriminant(interfaceDecls.get(variant)!, 'type');
    if (lit) map[lit] = variant;
  }

  unions[name] = {
    variants,
    discriminant: Object.keys(map).length > 0 ? { field: 'type', map } : null,
  };
}

const surface = {
  interfaces: Object.fromEntries(
    Object.entries(interfaces).sort(([a], [b]) => a.localeCompare(b)),
  ),
  graph: Object.fromEntries(
    Object.entries(graph).sort(([a], [b]) => a.localeCompare(b)),
  ),
  unions: Object.fromEntries(
    Object.entries(unions).sort(([a], [b]) => a.localeCompare(b)),
  ),
};

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(surface, null, 2) + '\n');

const memberCount = Object.values(surface.interfaces).reduce(
  (sum, members) => sum + members.length,
  0,
);
console.log(
  `Wrote ${memberCount} members across ${Object.keys(surface.interfaces).length} ` +
    `interfaces and ${Object.keys(surface.unions).length} unions to ${outPath}`,
);
