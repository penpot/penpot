/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 *
 * Port of app.common/uuid_impl.js (+ encoding helpers from encoding_impl.js)
 * for skia-rs-wasm / TypeScript. Matches Penpot ClojureScript `uuid/next` (v8).
 */

// --- encoding_impl subset (hex + base62) ------------------------------------

const hexMap: string[] = []
for (let i = 0; i < 256; i++) {
  hexMap[i] = (i + 0x100).toString(16).substring(1)
}

function hexToBuffer(input: string): ArrayBuffer {
  if (typeof input !== 'string') {
    throw new TypeError('Expected input to be a string')
  }
  input = input.replace(/-/g, '')
  if (input.length % 2 !== 0) {
    throw new RangeError('Expected string to be an even number of characters')
  }
  const view = new Uint8Array(input.length / 2)
  for (let i = 0; i < input.length; i += 2) {
    view[i / 2] = parseInt(input.substring(i, i + 2), 16)
  }
  return view.buffer
}

function bufferToHex(source: Uint8Array | ArrayBufferView | number[], isUuid: boolean): string {
  let src: Uint8Array
  if (source instanceof Uint8Array) {
    src = source
  } else if (ArrayBuffer.isView(source)) {
    src = new Uint8Array(source.buffer, source.byteOffset, source.byteLength)
  } else if (Array.isArray(source)) {
    src = Uint8Array.from(source)
  } else {
    throw new TypeError('Unexpected source type')
  }
  if (src.length !== 16) {
    throw new RangeError('only 16 bytes array is allowed')
  }
  const spacer = isUuid ? '-' : ''
  let i = 0
  return (
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    spacer +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    spacer +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    spacer +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    spacer +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]! +
    hexMap[src[i++]!]!
  )
}

// base-x encoding (MIT, see common encoding_impl.js)
function getBaseCodec(ALPHABET: string) {
  if (ALPHABET.length >= 255) {
    throw new TypeError('Alphabet too long')
  }
  const BASE_MAP = new Uint8Array(256)
  BASE_MAP.fill(255)
  for (let i = 0; i < ALPHABET.length; i++) {
    const x = ALPHABET.charAt(i)
    const xc = x.charCodeAt(0)
    if (BASE_MAP[xc] !== 255) {
      throw new TypeError(`${x} is ambiguous`)
    }
    BASE_MAP[xc] = i
  }
  const BASE = ALPHABET.length
  const LEADER = ALPHABET.charAt(0)
  const iFACTOR = Math.log(256) / Math.log(BASE)

  function encode(source: Uint8Array | ArrayBufferView | number[]): string {
    let src: Uint8Array
    if (source instanceof Uint8Array) {
      src = source
    } else if (ArrayBuffer.isView(source)) {
      src = new Uint8Array(source.buffer, source.byteOffset, source.byteLength)
    } else if (Array.isArray(source)) {
      src = Uint8Array.from(source)
    } else {
      throw new TypeError('Expected Uint8Array')
    }
    if (src.length === 0) {
      return ''
    }
    let zeroes = 0
    let length = 0
    let pbegin = 0
    const pend = src.length
    while (pbegin !== pend && src[pbegin] === 0) {
      pbegin++
      zeroes++
    }
    const size = (((pend - pbegin) * iFACTOR + 1) >>> 0) as number
    const b58 = new Uint8Array(size)
    while (pbegin !== pend) {
      let carry = src[pbegin]!
      let i = 0
      for (let it1 = size - 1; (carry !== 0 || i < length) && it1 !== -1; it1--, i++) {
        carry += (256 * b58[it1]!) >>> 0
        b58[it1] = carry % BASE
        carry = (carry / BASE) >>> 0
      }
      if (carry !== 0) {
        throw new Error('Non-zero carry')
      }
      length = i
      pbegin++
    }
    let it2 = size - length
    while (it2 !== size && b58[it2] === 0) {
      it2++
    }
    let str = LEADER.repeat(zeroes)
    for (; it2 < size; ++it2) {
      str += ALPHABET.charAt(b58[it2]!)
    }
    return str
  }

  return { encode }
}

const BASE62 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
const bufferToBase62 = getBaseCodec(BASE62).encode

// --- uuid_impl --------------------------------------------------------------

const timeRef = 1640995200000 // ms since 2022-01-01T00:00:00

type NodeRequire = (id: string) => { randomBytes: (size: number) => Uint8Array }

const fill: (buf: Uint8Array) => Uint8Array = (() => {
  const g = globalThis as typeof globalThis & { crypto?: Crypto }
  if (typeof g.crypto !== 'undefined' && typeof g.crypto.getRandomValues !== 'undefined') {
    return (buf: Uint8Array) => {
      g.crypto!.getRandomValues(buf)
      return buf
    }
  }
  const nodeRequire = (globalThis as unknown as { require?: NodeRequire }).require
  if (typeof nodeRequire === 'function') {
    const randomBytes = nodeRequire('crypto').randomBytes
    return (buf: Uint8Array) => {
      const bytes = randomBytes(buf.length)
      buf.set(bytes)
      return buf
    }
  }
  console.warn('No SRNG available, switching back to Math.random.')
  return (buf: Uint8Array) => {
    for (let i = 0, r = 0; i < buf.length; i++) {
      if ((i & 0x03) === 0) {
        r = Math.random() * 0x100000000
      }
      buf[i] = (r >>> ((i & 0x03) << 3)) & 0xff
    }
    return buf
  }
})()

function getBigUint64(view: DataView, byteOffset: number, le: boolean): bigint {
  const a = view.getUint32(byteOffset, le)
  const b = view.getUint32(byteOffset + 4, le)
  const leMask = Number(!!le)
  const beMask = Number(!le)
  return (BigInt(a * beMask + b * leMask) << 32n) | BigInt(a * leMask + b * beMask)
}

function setBigUint64(view: DataView, byteOffset: number, value: bigint, le: boolean): void {
  const hi = Number(value >> 32n)
  const lo = Number(value & 0xffffffffn)
  if (le) {
    view.setUint32(byteOffset + 4, hi, le)
    view.setUint32(byteOffset, lo, le)
  } else {
    view.setUint32(byteOffset, hi, le)
    view.setUint32(byteOffset + 4, lo, le)
  }
}

function currentTimestamp(ref: number): bigint {
  return BigInt.asUintN(64, BigInt(Date.now() - ref))
}

const tmpBuff = new ArrayBuffer(8)
const tmpView = new DataView(tmpBuff)
const tmpInt8 = new Uint8Array(tmpBuff)

function nextLong(): bigint {
  fill(tmpInt8)
  return getBigUint64(tmpView, 0, false)
}

export const shortID = (function shortIDFactory() {
  const buff = new ArrayBuffer(8)
  const int8 = new Uint8Array(buff)
  const view = new DataView(buff)
  const base = 0x0000_0000_0000_0000n
  return function shortID(_ts?: bigint): string {
    void _ts
    const tss = currentTimestamp(timeRef)
    const msb = base | (nextLong() & 0xffff_ffff_0000_0000n) | (tss & 0x0000_0000_ffff_ffffn)
    setBigUint64(view, 0, msb, false)
    return bufferToBase62(int8)
  }
})()

export const v4 = (function v4Factory() {
  const arr = new Uint8Array(16)
  return function v4(): string {
    fill(arr)
    arr[6] = (arr[6]! & 0x0f) | 0x40
    arr[8] = (arr[8]! & 0x3f) | 0x80
    return bufferToHex(arr, true)
  }
})()

export type V8Factory = {
  (): string
  create: (ts: bigint, lastRd: bigint, lastCs: bigint) => string
  fromArray: (u8data: Uint8Array | Int8Array) => string
  fromPair: (hi: bigint, lo: bigint) => string
  fromUnsignedParts: (a: number, b: number, c: number, d: number) => string
  getBytes: (uuid: string) => Int8Array
  getHi: (uuid: string) => bigint
  getLo: (uuid: string) => bigint
  getUnsignedParts: (uuid: string) => Uint32Array
  setTag: (tag: bigint | number | string) => void
}

export const v8: V8Factory = (function v8Factory(): V8Factory {
  const buff = new ArrayBuffer(16)
  const int8 = new Uint8Array(buff)
  const view = new DataView(buff)

  const maxCs = 0x0000_0000_0000_3fffn

  let countCs: bigint | number = 0n
  let lastRd = 0n
  let lastCs = 0n
  let lastTs = 0n
  const baseMsb = 0x0000_0000_0000_8000n
  const baseLsb = 0x8000_0000_0000_0000n

  lastRd = nextLong() & 0xffff_ffff_ffff_f0ffn
  lastCs = nextLong() & maxCs

  const create = function create(ts: bigint, rd: bigint, cs: bigint): string {
    const msb = baseMsb | (rd & 0xffff_ffff_ffff_0fffn)
    const lsb = baseLsb | ((ts << 14n) & 0x3fff_ffff_ffff_c000n) | cs
    setBigUint64(view, 0, msb, false)
    setBigUint64(view, 8, lsb, false)
    return bufferToHex(int8, true)
  }

  const factory = function v8(): string {
    while (true) {
      const ts = currentTimestamp(timeRef)
      if (ts - lastTs < 0) {
        lastRd = (lastRd & 0x0000_0000_0000_0f00n) | (nextLong() & 0xffff_ffff_ffff_f0ffn)
        countCs = 0n
        continue
      }
      if (lastTs === ts) {
        if (countCs < maxCs) {
          lastCs = (lastCs + 1n) & maxCs
          countCs++
        } else {
          continue
        }
      } else {
        lastTs = ts
        lastCs = nextLong() & maxCs
        countCs = 0
      }
      return create(ts, lastRd, lastCs)
    }
  }

  const fillBytes = (uuid: string): void => {
    let rest: number
    int8[0] = ((rest = parseInt(uuid.slice(0, 8), 16)) >>> 24) as number
    int8[1] = (rest >>> 16) & 0xff
    int8[2] = (rest >>> 8) & 0xff
    int8[3] = rest & 0xff
    int8[4] = ((rest = parseInt(uuid.slice(9, 13), 16)) >>> 8) as number
    int8[5] = rest & 0xff
    int8[6] = ((rest = parseInt(uuid.slice(14, 18), 16)) >>> 8) as number
    int8[7] = rest & 0xff
    int8[8] = ((rest = parseInt(uuid.slice(19, 23), 16)) >>> 8) as number
    int8[9] = rest & 0xff
    int8[10] = (((rest = parseInt(uuid.slice(24, 36), 16)) / 0x10000000000) & 0xff) as number
    int8[11] = (rest / 0x100000000) & 0xff
    int8[12] = (rest >>> 24) & 0xff
    int8[13] = (rest >>> 16) & 0xff
    int8[14] = (rest >>> 8) & 0xff
    int8[15] = rest & 0xff
  }

  const fromPair = (hi: bigint, lo: bigint): string => {
    view.setBigInt64(0, hi)
    view.setBigInt64(8, lo)
    return bufferToHex(int8, true)
  }

  const getHi = (uuid: string): bigint => {
    fillBytes(uuid)
    return view.getBigInt64(0)
  }

  const getLo = (uuid: string): bigint => {
    fillBytes(uuid)
    return view.getBigInt64(8)
  }

  const getBytes = (uuid: string): Int8Array => {
    fillBytes(uuid)
    return Int8Array.from(int8)
  }

  const getUnsignedParts = (uuid: string): Uint32Array => {
    fillBytes(uuid)
    const result = new Uint32Array(4)
    result[0] = view.getUint32(0)
    result[1] = view.getUint32(4)
    result[2] = view.getUint32(8)
    result[3] = view.getUint32(12)
    return result
  }

  const fromUnsignedParts = (a: number, b: number, c: number, d: number): string => {
    view.setUint32(0, a)
    view.setUint32(4, b)
    view.setUint32(8, c)
    view.setUint32(12, d)
    return bufferToHex(int8, true)
  }

  const fromArray = (u8data: Uint8Array | Int8Array): string => {
    int8.set(u8data)
    return bufferToHex(int8, true)
  }

  const setTag = (tag: bigint | number | string): void => {
    const t = BigInt.asUintN(64, BigInt(tag))
    if (t > 0x0000_0000_0000_000fn) {
      throw new Error('illegal arguments: tag value should fit in 4bits')
    }
    lastRd = (lastRd & 0xffff_ffff_ffff_f0ffn) | ((t << 8n) & 0x0000_0000_0000_0f00n)
  }

  factory.create = create
  factory.fromArray = fromArray
  factory.fromPair = fromPair
  factory.fromUnsignedParts = fromUnsignedParts
  factory.getBytes = getBytes
  factory.getHi = getHi
  factory.getLo = getLo
  factory.getUnsignedParts = getUnsignedParts
  factory.setTag = setTag
  return factory as V8Factory
})()

export function shortV8(uuid: string): string {
  const buff = hexToBuffer(uuid)
  const short = new Uint8Array(buff, 4)
  return bufferToBase62(short)
}

/** Matches uuid_impl.js `custom` (duplicate `hi` check on second branch). */
export function custom(hi: bigint | number | string, lo: bigint | number | string): string {
  let h: bigint | number | string = hi
  let l: bigint | number | string = lo
  if (typeof h !== 'bigint') {
    h = BigInt(hi)
  }
  if (typeof h !== 'bigint') {
    l = BigInt(lo)
  }
  return v8.fromPair(h as bigint, l as bigint)
}

export function fromBytes(data: Uint8Array | Int8Array): string {
  if (data instanceof Uint8Array) {
    return v8.fromArray(data)
  }
  if (data instanceof Int8Array) {
    return v8.fromArray(data)
  }
  throw new Error('invalid array type received')
}

export function getBytes(uuid: string): Int8Array {
  return v8.getBytes(uuid)
}

export function getUnsignedParts(uuid: string): Uint32Array {
  return v8.getUnsignedParts(uuid)
}

export function fromUnsignedParts(a: number, b: number, c: number, d: number): string {
  return v8.fromUnsignedParts(a, b, c, d)
}

export function getHi(uuid: string): bigint {
  return v8.getHi(uuid)
}

export function getLo(uuid: string): bigint {
  return v8.getLo(uuid)
}
