/*
 * Copyright (c) 2016-2020, Yann Collet, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under both the BSD-style license (found in the
 * LICENSE file in the root directory of this source tree) and the GPLv2 (found
 * in the COPYING file in the root directory of this source tree).
 * You may select, at your option, one of the above-listed licenses.
 */


/* zstd_decompress_internal:
 * objects and definitions shared within lib/decompress modules */

 #ifndef ZSTD_DECOMPRESS_INTERNAL_H
 #define ZSTD_DECOMPRESS_INTERNAL_H


/*-*******************************************************
 *  Dependencies
 *********************************************************/
#include "zstd/common/mem.h"             /* BYTE, U16, U32 */
#include "zstd/common/zstd_internal.h"   /* ZSTD_seqSymbol */

namespace lbug_zstd {

/*-*******************************************************
 *  Constants
 *********************************************************/
struct ZSTDConstants {
    static const U32 LL_base[MaxLL+1];
    static const U32 OF_base[MaxOff+1];
    static const U32 OF_bits[MaxOff+1];
    static const U32 ML_base[MaxML+1];
};


/*-*******************************************************
 *  Decompression types
 *********************************************************/
 typedef struct {
     U32 fastMode;
     U32 tableLog;
 } ZSTD_seqSymbol_header;

 typedef struct {
     U16  nextState;
     BYTE nbAdditionalBits;
     BYTE nbBits;
     U32  baseValue;
 } ZSTD_seqSymbol;

 #define SEQSYMBOL_TABLE_SIZE(log)   (1 + (1 << (log)))

typedef struct {
    ZSTD_seqSymbol LLTable[SEQSYMBOL_TABLE_SIZE(LLFSELog)];    /* Note : Space reserved for FSE Tables */
    ZSTD_seqSymbol OFTable[SEQSYMBOL_TABLE_SIZE(OffFSELog)];   /* is also used as temporary workspace while building hufTable during DDict creation */
    ZSTD_seqSymbol MLTable[SEQSYMBOL_TABLE_SIZE(MLFSELog)];    /* and therefore must be at least HUF_DECOMPRESS_WORKSPACE_SIZE large */
    HUF_DTable hufTable[HUF_DTABLE_SIZE(HufLog)];  /* can accommodate HUF_decompress4X */
    U32 rep[ZSTD_REP_NUM];
} ZSTD_entropyDTables_t;

typedef enum { ZSTDds_getFrameHeaderSize, ZSTDds_decodeFrameHeader,
               ZSTDds_decodeBlockHeader, ZSTDds_decompressBlock,
               ZSTDds_decompressLastBlock, ZSTDds_checkChecksum,
               ZSTDds_decodeSkippableHeader, ZSTDds_skipFrame } ZSTD_dStage;

typedef enum { zdss_init=0, zdss_loadHeader,
               zdss_read, zdss_load, zdss_flush } ZSTD_dStreamStage;

typedef enum {
    ZSTD_use_indefinitely = -1,  /* Use the dictionary indefinitely */
    ZSTD_dont_use = 0,           /* Do not use the dictionary (if one exists free it) */
    ZSTD_use_once = 1            /* Use the dictionary once and set to ZSTD_dont_use */
} ZSTD_dictUses_e;

typedef enum {
    ZSTD_obm_buffered = 0,  /* Buffer the output */
    ZSTD_obm_stable = 1     /* ZSTD_outBuffer is stable */
} ZSTD_outBufferMode_e;

struct ZSTD_DCtx_s
{
    const ZSTD_seqSymbol* LLTptr;
    const ZSTD_seqSymbol* MLTptr;
    const ZSTD_seqSymbol* OFTptr;
    const HUF_DTable* HUFptr;
    ZSTD_entropyDTables_t entropy;
    U32 workspace[HUF_DECOMPRESS_WORKSPACE_SIZE_U32];   /* space needed when building huffman tables */
    const void* previousDstEnd;   /* detect continuity */
    const void* prefixStart;      /* start of current segment */
    const void* virtualStart;     /* virtual start of previous segment if it was just before current one */
    const void* dictEnd;          /* end of previous segment */
    size_t expected;
    ZSTD_frameHeader fParams;
    U64 decodedSize;
    blockType_e bType;            /* used in ZSTD_decompressContinue(), store blockType between block header decoding and block decompression stages */
    ZSTD_dStage stage;
    U32 litEntropy;
    U32 fseEntropy;
    XXH64_state_t xxhState;
    size_t headerSize;
    ZSTD_format_e format;
    const BYTE* litPtr;
    ZSTD_customMem customMem;
    size_t litSize;
    size_t rleSize;
    size_t staticSize;
    int bmi2;                     /* == 1 if the CPU supports BMI2 and 0 otherwise. CPU support is determined dynamically once per context lifetime. */

    /* dictionary */
    ZSTD_DDict* ddictLocal;
    const ZSTD_DDict* ddict;     /* set by ZSTD_initDStream_usingDDict(), or ZSTD_DCtx_refDDict() */
    U32 dictID;
    int ddictIsCold;             /* if == 1 : dictionary is "new" for working context, and presumed "cold" (not in cpu cache) */
    ZSTD_dictUses_e dictUses;

    /* streaming */
    ZSTD_dStreamStage streamStage;
    char*  inBuff;
    size_t inBuffSize;
    size_t inPos;
    size_t maxWindowSize;
    char*  outBuff;
    size_t outBuffSize;
    size_t outStart;
    size_t outEnd;
    size_t lhSize;
    void* legacyContext;
    U32 previousLegacyVersion;
    U32 legacyVersion;
    U32 hostageByte;
    int noForwardProgress;
    ZSTD_outBufferMode_e outBufferMode;
    ZSTD_outBuffer expectedOutBuffer;

    /* workspace */
    BYTE litBuffer[ZSTD_BLOCKSIZE_MAX + WILDCOPY_OVERLENGTH];
    BYTE headerBuffer[ZSTD_FRAMEHEADERSIZE_MAX];

    size_t oversizedDuration;

#ifdef FUZZING_BUILD_MODE_UNSAFE_FOR_PRODUCTION
    void const* dictContentBeginForFuzzing;
    void const* dictContentEndForFuzzing;
#endif
};  /* typedef'd to ZSTD_DCtx within "zstd.h" */


/*-*******************************************************
 *  Shared internal functions
 *********************************************************/

/*! ZSTD_loadDEntropy() :
 *  dict : must point at beginning of a valid zstd dictionary.
 * @return : size of dictionary header (size of magic number + dict ID + entropy tables) */
size_t ZSTD_loadDEntropy(ZSTD_entropyDTables_t* entropy,
                   const void* const dict, size_t const dictSize);

/*! ZSTD_checkContinuity() :
 *  check if next `dst` follows previous position, where decompression ended.
 *  If yes, do nothing (continue on current segment).
 *  If not, classify previous segment as "external dictionary", and start a new segment.
 *  This function cannot fail. */
void ZSTD_checkContinuity(ZSTD_DCtx* dctx, const void* dst);

}

#endif /* ZSTD_DECOMPRESS_INTERNAL_H */
