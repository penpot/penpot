/* ******************************************************************
 * huff0 huffman codec,
 * part of Finite State Entropy library
 * Copyright (c) 2013-2020, Yann Collet, Facebook, Inc.
 *
 * You can contact the author at :
 * - Source repository : https://github.com/Cyan4973/FiniteStateEntropy
 *
 * This source code is licensed under both the BSD-style license (found in the
 * LICENSE file in the root directory of this source tree) and the GPLv2 (found
 * in the COPYING file in the root directory of this source tree).
 * You may select, at your option, one of the above-listed licenses.
****************************************************************** */

#ifndef HUF_H_298734234
#define HUF_H_298734234

/* *** Dependencies *** */
#include <stddef.h>    /* size_t */


/* *** library symbols visibility *** */
/* Note : when linking with -fvisibility=hidden on gcc, or by default on Visual,
 *        HUF symbols remain "private" (internal symbols for library only).
 *        Set macro FSE_DLL_EXPORT to 1 if you want HUF symbols visible on DLL interface */
#if defined(FSE_DLL_EXPORT) && (FSE_DLL_EXPORT==1) && defined(__GNUC__) && (__GNUC__ >= 4)
#  define HUF_PUBLIC_API __attribute__ ((visibility ("default")))
#elif defined(FSE_DLL_EXPORT) && (FSE_DLL_EXPORT==1)   /* Visual expected */
#  define HUF_PUBLIC_API __declspec(dllexport)
#elif defined(FSE_DLL_IMPORT) && (FSE_DLL_IMPORT==1)
#  define HUF_PUBLIC_API __declspec(dllimport)  /* not required, just to generate faster code (saves a function pointer load from IAT and an indirect jump) */
#else
#  define HUF_PUBLIC_API
#endif

namespace lbug_zstd {

/* ========================== */
/* ***  simple functions  *** */
/* ========================== */

/** HUF_compress() :
 *  Compress content from buffer 'src', of size 'srcSize', into buffer 'dst'.
 * 'dst' buffer must be already allocated.
 *  Compression runs faster if `dstCapacity` >= HUF_compressBound(srcSize).
 * `srcSize` must be <= `HUF_BLOCKSIZE_MAX` == 128 KB.
 * @return : size of compressed data (<= `dstCapacity`).
 *  Special values : if return == 0, srcData is not compressible => Nothing is stored within dst !!!
 *                   if HUF_isError(return), compression failed (more details using HUF_getErrorName())
 */
HUF_PUBLIC_API size_t HUF_compress(void* dst, size_t dstCapacity,
                             const void* src, size_t srcSize);

/** HUF_decompress() :
 *  Decompress HUF data from buffer 'cSrc', of size 'cSrcSize',
 *  into already allocated buffer 'dst', of minimum size 'dstSize'.
 * `originalSize` : **must** be the ***exact*** size of original (uncompressed) data.
 *  Note : in contrast with FSE, HUF_decompress can regenerate
 *         RLE (cSrcSize==1) and uncompressed (cSrcSize==dstSize) data,
 *         because it knows size to regenerate (originalSize).
 * @return : size of regenerated data (== originalSize),
 *           or an error code, which can be tested using HUF_isError()
 */
HUF_PUBLIC_API size_t HUF_decompress(void* dst,  size_t originalSize,
                               const void* cSrc, size_t cSrcSize);


/* ***   Tool functions *** */
#define HUF_BLOCKSIZE_MAX (128 * 1024)                  /**< maximum input size for a single block compressed with HUF_compress */
HUF_PUBLIC_API size_t HUF_compressBound(size_t size);   /**< maximum compressed size (worst case) */

/* Error Management */
HUF_PUBLIC_API unsigned    HUF_isError(size_t code);       /**< tells if a return value is an error code */
HUF_PUBLIC_API const char* HUF_getErrorName(size_t code);  /**< provides error code string (useful for debugging) */


/* ***   Advanced function   *** */

/** HUF_compress2() :
 *  Same as HUF_compress(), but offers control over `maxSymbolValue` and `tableLog`.
 * `maxSymbolValue` must be <= HUF_SYMBOLVALUE_MAX .
 * `tableLog` must be `<= HUF_TABLELOG_MAX` . */
HUF_PUBLIC_API size_t HUF_compress2 (void* dst, size_t dstCapacity,
                               const void* src, size_t srcSize,
                               unsigned maxSymbolValue, unsigned tableLog);

/** HUF_compress4X_wksp() :
 *  Same as HUF_compress2(), but uses externally allocated `workSpace`.
 * `workspace` must have minimum alignment of 4, and be at least as large as HUF_WORKSPACE_SIZE */
#define HUF_WORKSPACE_SIZE ((6 << 10) + 256)
#define HUF_WORKSPACE_SIZE_U32 (HUF_WORKSPACE_SIZE / sizeof(U32))
HUF_PUBLIC_API size_t HUF_compress4X_wksp (void* dst, size_t dstCapacity,
                                     const void* src, size_t srcSize,
                                     unsigned maxSymbolValue, unsigned tableLog,
                                     void* workSpace, size_t wkspSize);

#endif   /* HUF_H_298734234 */
