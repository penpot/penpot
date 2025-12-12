
/* **************************************************************************************
 *   ADVANCED AND EXPERIMENTAL FUNCTIONS
 ****************************************************************************************
 * The definitions in the following section are considered experimental.
 * They are provided for advanced scenarios.
 * They should never be used with a dynamic library, as prototypes may change in the future.
 * Use them only in association with static linking.
 * ***************************************************************************************/

#ifndef ZSTD_H_ZSTD_STATIC_LINKING_ONLY
#define ZSTD_H_ZSTD_STATIC_LINKING_ONLY

namespace lbug_zstd {

/****************************************************************************************
 *   experimental API (static linking only)
 ****************************************************************************************
 * The following symbols and constants
 * are not planned to join "stable API" status in the near future.
 * They can still change in future versions.
 * Some of them are planned to remain in the static_only section indefinitely.
 * Some of them might be removed in the future (especially when redundant with existing stable functions)
 * ***************************************************************************************/

#define ZSTD_FRAMEHEADERSIZE_PREFIX(format) ((format) == ZSTD_f_zstd1 ? 5 : 1)   /* minimum input size required to query frame header size */
#define ZSTD_FRAMEHEADERSIZE_MIN(format)    ((format) == ZSTD_f_zstd1 ? 6 : 2)
#define ZSTD_FRAMEHEADERSIZE_MAX   18   /* can be useful for static allocation */
#define ZSTD_SKIPPABLEHEADERSIZE    8

/* compression parameter bounds */
#define ZSTD_WINDOWLOG_MAX_32    30
#define ZSTD_WINDOWLOG_MAX_64    31
#define ZSTD_WINDOWLOG_MAX     ((int)(sizeof(size_t) == 4 ? ZSTD_WINDOWLOG_MAX_32 : ZSTD_WINDOWLOG_MAX_64))
#define ZSTD_WINDOWLOG_MIN       10
#define ZSTD_HASHLOG_MAX       ((ZSTD_WINDOWLOG_MAX < 30) ? ZSTD_WINDOWLOG_MAX : 30)
#define ZSTD_HASHLOG_MIN          6
#define ZSTD_CHAINLOG_MAX_32     29
#define ZSTD_CHAINLOG_MAX_64     30
#define ZSTD_CHAINLOG_MAX      ((int)(sizeof(size_t) == 4 ? ZSTD_CHAINLOG_MAX_32 : ZSTD_CHAINLOG_MAX_64))
#define ZSTD_CHAINLOG_MIN        ZSTD_HASHLOG_MIN
#define ZSTD_SEARCHLOG_MAX      (ZSTD_WINDOWLOG_MAX-1)
#define ZSTD_SEARCHLOG_MIN        1
#define ZSTD_MINMATCH_MAX         7   /* only for ZSTD_fast, other strategies are limited to 6 */
#define ZSTD_MINMATCH_MIN         3   /* only for ZSTD_btopt+, faster strategies are limited to 4 */
#define ZSTD_TARGETLENGTH_MAX    ZSTD_BLOCKSIZE_MAX
#define ZSTD_TARGETLENGTH_MIN     0   /* note : comparing this constant to an unsigned results in a tautological test */
#define ZSTD_STRATEGY_MIN        ZSTD_fast
#define ZSTD_STRATEGY_MAX        ZSTD_btultra2


#define ZSTD_OVERLAPLOG_MIN       0
#define ZSTD_OVERLAPLOG_MAX       9

#define ZSTD_WINDOWLOG_LIMIT_DEFAULT 27   /* by default, the streaming decoder will refuse any frame
                                           * requiring larger than (1<<ZSTD_WINDOWLOG_LIMIT_DEFAULT) window size,
                                           * to preserve host's memory from unreasonable requirements.
                                           * This limit can be overridden using ZSTD_DCtx_setParameter(,ZSTD_d_windowLogMax,).
                                           * The limit does not apply for one-pass decoders (such as ZSTD_decompress()), since no additional memory is allocated */


/* LDM parameter bounds */
#define ZSTD_LDM_HASHLOG_MIN      ZSTD_HASHLOG_MIN
#define ZSTD_LDM_HASHLOG_MAX      ZSTD_HASHLOG_MAX
#define ZSTD_LDM_MINMATCH_MIN        4
#define ZSTD_LDM_MINMATCH_MAX     4096
#define ZSTD_LDM_BUCKETSIZELOG_MIN   1
#define ZSTD_LDM_BUCKETSIZELOG_MAX   8
#define ZSTD_LDM_HASHRATELOG_MIN     0
#define ZSTD_LDM_HASHRATELOG_MAX (ZSTD_WINDOWLOG_MAX - ZSTD_HASHLOG_MIN)

/* Advanced parameter bounds */
#define ZSTD_TARGETCBLOCKSIZE_MIN   64
#define ZSTD_TARGETCBLOCKSIZE_MAX   ZSTD_BLOCKSIZE_MAX
#define ZSTD_SRCSIZEHINT_MIN        0
#define ZSTD_SRCSIZEHINT_MAX        INT_MAX

/* internal */
#define ZSTD_HASHLOG3_MAX           17


/* ---  Advanced types  --- */

typedef struct ZSTD_CCtx_params_s ZSTD_CCtx_params;

typedef struct {
    unsigned int matchPos; /* Match pos in dst */
    /* If seqDef.offset > 3, then this is seqDef.offset - 3
     * If seqDef.offset < 3, then this is the corresponding repeat offset
     * But if seqDef.offset < 3 and litLength == 0, this is the
     *   repeat offset before the corresponding repeat offset
     * And if seqDef.offset == 3 and litLength == 0, this is the
     *   most recent repeat offset - 1
     */
    unsigned int offset;
    unsigned int litLength; /* Literal length */
    unsigned int matchLength; /* Match length */
    /* 0 when seq not rep and seqDef.offset otherwise
     * when litLength == 0 this will be <= 4, otherwise <= 3 like normal
     */
    unsigned int rep;
} ZSTD_Sequence;

typedef struct {
    unsigned windowLog;       /**< largest match distance : larger == more compression, more memory needed during decompression */
    unsigned chainLog;        /**< fully searched segment : larger == more compression, slower, more memory (useless for fast) */
    unsigned hashLog;         /**< dispatch table : larger == faster, more memory */
    unsigned searchLog;       /**< nb of searches : larger == more compression, slower */
    unsigned minMatch;        /**< match length searched : larger == faster decompression, sometimes less compression */
    unsigned targetLength;    /**< acceptable match size for optimal parser (only) : larger == more compression, slower */
    ZSTD_strategy strategy;   /**< see ZSTD_strategy definition above */
} ZSTD_compressionParameters;

typedef struct {
    int contentSizeFlag; /**< 1: content size will be in frame header (when known) */
    int checksumFlag;    /**< 1: generate a 32-bits checksum using XXH64 algorithm at end of frame, for error detection */
    int noDictIDFlag;    /**< 1: no dictID will be saved into frame header (dictID is only useful for dictionary compression) */
} ZSTD_frameParameters;

typedef struct {
    ZSTD_compressionParameters cParams;
    ZSTD_frameParameters fParams;
} ZSTD_parameters;

typedef enum {
    ZSTD_dct_auto = 0,       /* dictionary is "full" when starting with ZSTD_MAGIC_DICTIONARY, otherwise it is "rawContent" */
    ZSTD_dct_rawContent = 1, /* ensures dictionary is always loaded as rawContent, even if it starts with ZSTD_MAGIC_DICTIONARY */
    ZSTD_dct_fullDict = 2    /* refuses to load a dictionary if it does not respect Zstandard's specification, starting with ZSTD_MAGIC_DICTIONARY */
} ZSTD_dictContentType_e;

typedef enum {
    ZSTD_dlm_byCopy = 0,  /**< Copy dictionary content internally */
    ZSTD_dlm_byRef = 1    /**< Reference dictionary content -- the dictionary buffer must outlive its users. */
} ZSTD_dictLoadMethod_e;

typedef enum {
    ZSTD_f_zstd1 = 0,           /* zstd frame format, specified in zstd_compression_format.md (default) */
    ZSTD_f_zstd1_magicless = 1  /* Variant of zstd frame format, without initial 4-bytes magic number.
                                 * Useful to save 4 bytes per generated frame.
                                 * Decoder cannot recognise automatically this format, requiring this instruction. */
} ZSTD_format_e;

typedef enum {
    /* Note: this enum and the behavior it controls are effectively internal
     * implementation details of the compressor. They are expected to continue
     * to evolve and should be considered only in the context of extremely
     * advanced performance tuning.
     *
     * Zstd currently supports the use of a CDict in three ways:
     *
     * - The contents of the CDict can be copied into the working context. This
     *   means that the compression can search both the dictionary and input
     *   while operating on a single set of internal tables. This makes
     *   the compression faster per-byte of input. However, the initial copy of
     *   the CDict's tables incurs a fixed cost at the beginning of the
     *   compression. For small compressions (< 8 KB), that copy can dominate
     *   the cost of the compression.
     *
     * - The CDict's tables can be used in-place. In this model, compression is
     *   slower per input byte, because the compressor has to search two sets of
     *   tables. However, this model incurs no start-up cost (as long as the
     *   working context's tables can be reused). For small inputs, this can be
     *   faster than copying the CDict's tables.
     *
     * - The CDict's tables are not used at all, and instead we use the working
     *   context alone to reload the dictionary and use params based on the source
     *   size. See ZSTD_compress_insertDictionary() and ZSTD_compress_usingDict().
     *   This method is effective when the dictionary sizes are very small relative
     *   to the input size, and the input size is fairly large to begin with.
     *
     * Zstd has a simple internal heuristic that selects which strategy to use
     * at the beginning of a compression. However, if experimentation shows that
     * Zstd is making poor choices, it is possible to override that choice with
     * this enum.
     */
    ZSTD_dictDefaultAttach = 0, /* Use the default heuristic. */
    ZSTD_dictForceAttach   = 1, /* Never copy the dictionary. */
    ZSTD_dictForceCopy     = 2, /* Always copy the dictionary. */
    ZSTD_dictForceLoad     = 3  /* Always reload the dictionary */
} ZSTD_dictAttachPref_e;

typedef enum {
  ZSTD_lcm_auto = 0,          /**< Automatically determine the compression mode based on the compression level.
                               *   Negative compression levels will be uncompressed, and positive compression
                               *   levels will be compressed. */
  ZSTD_lcm_huffman = 1,       /**< Always attempt Huffman compression. Uncompressed literals will still be
                               *   emitted if Huffman compression is not profitable. */
  ZSTD_lcm_uncompressed = 2   /**< Always emit uncompressed literals. */
} ZSTD_literalCompressionMode_e;


/***************************************
*  Frame size functions
***************************************/

/*! ZSTD_findDecompressedSize() :
 *  `src` should point to the start of a series of ZSTD encoded and/or skippable frames
 *  `srcSize` must be the _exact_ size of this series
 *       (i.e. there should be a frame boundary at `src + srcSize`)
 *  @return : - decompressed size of all data in all successive frames
 *            - if the decompressed size cannot be determined: ZSTD_CONTENTSIZE_UNKNOWN
 *            - if an error occurred: ZSTD_CONTENTSIZE_ERROR
 *
 *   note 1 : decompressed size is an optional field, that may not be present, especially in streaming mode.
 *            When `return==ZSTD_CONTENTSIZE_UNKNOWN`, data to decompress could be any size.
 *            In which case, it's necessary to use streaming mode to decompress data.
 *   note 2 : decompressed size is always present when compression is done with ZSTD_compress()
 *   note 3 : decompressed size can be very large (64-bits value),
 *            potentially larger than what local system can handle as a single memory segment.
 *            In which case, it's necessary to use streaming mode to decompress data.
 *   note 4 : If source is untrusted, decompressed size could be wrong or intentionally modified.
 *            Always ensure result fits within application's authorized limits.
 *            Each application can set its own limits.
 *   note 5 : ZSTD_findDecompressedSize handles multiple frames, and so it must traverse the input to
 *            read each contained frame header.  This is fast as most of the data is skipped,
 *            however it does mean that all frame data must be present and valid. */
ZSTDLIB_API unsigned long long ZSTD_findDecompressedSize(const void* src, size_t srcSize);

/*! ZSTD_decompressBound() :
 *  `src` should point to the start of a series of ZSTD encoded and/or skippable frames
 *  `srcSize` must be the _exact_ size of this series
 *       (i.e. there should be a frame boundary at `src + srcSize`)
 *  @return : - upper-bound for the decompressed size of all data in all successive frames
 *            - if an error occured: ZSTD_CONTENTSIZE_ERROR
 *
 *  note 1  : an error can occur if `src` contains an invalid or incorrectly formatted frame.
 *  note 2  : the upper-bound is exact when the decompressed size field is available in every ZSTD encoded frame of `src`.
 *            in this case, `ZSTD_findDecompressedSize` and `ZSTD_decompressBound` return the same value.
 *  note 3  : when the decompressed size field isn't available, the upper-bound for that frame is calculated by:
 *              upper-bound = # blocks * min(128 KB, Window_Size)
 */
ZSTDLIB_API unsigned long long ZSTD_decompressBound(const void* src, size_t srcSize);

/*! ZSTD_frameHeaderSize() :
 *  srcSize must be >= ZSTD_FRAMEHEADERSIZE_PREFIX.
 * @return : size of the Frame Header,
 *           or an error code (if srcSize is too small) */
ZSTDLIB_API size_t ZSTD_frameHeaderSize(const void* src, size_t srcSize);

/*! ZSTD_getSequences() :
 * Extract sequences from the sequence store
 * zc can be used to insert custom compression params.
 * This function invokes ZSTD_compress2
 * @return : number of sequences extracted
 */
ZSTDLIB_API size_t ZSTD_getSequences(ZSTD_CCtx* zc, ZSTD_Sequence* outSeqs,
    size_t outSeqsSize, const void* src, size_t srcSize);


/***************************************
*  Memory management
***************************************/

/*! ZSTD_estimate*() :
 *  These functions make it possible to estimate memory usage
 *  of a future {D,C}Ctx, before its creation.
 *
 *  ZSTD_estimateCCtxSize() will provide a memory budget large enough
 *  for any compression level up to selected one.
 *  Note : Unlike ZSTD_estimateCStreamSize*(), this estimate
 *         does not include space for a window buffer.
 *         Therefore, the estimation is only guaranteed for single-shot compressions, not streaming.
 *  The estimate will assume the input may be arbitrarily large,
 *  which is the worst case.
 *
 *  When srcSize can be bound by a known and rather "small" value,
 *  this fact can be used to provide a tighter estimation
 *  because the CCtx compression context will need less memory.
 *  This tighter estimation can be provided by more advanced functions
 *  ZSTD_estimateCCtxSize_usingCParams(), which can be used in tandem with ZSTD_getCParams(),
 *  and ZSTD_estimateCCtxSize_usingCCtxParams(), which can be used in tandem with ZSTD_CCtxParams_setParameter().
 *  Both can be used to estimate memory using custom compression parameters and arbitrary srcSize limits.
 *
 *  Note 2 : only single-threaded compression is supported.
 *  ZSTD_estimateCCtxSize_usingCCtxParams() will return an error code if ZSTD_c_nbWorkers is >= 1.
 */
ZSTDLIB_API size_t ZSTD_estimateCCtxSize(int compressionLevel);
ZSTDLIB_API size_t ZSTD_estimateCCtxSize_usingCParams(ZSTD_compressionParameters cParams);
ZSTDLIB_API size_t ZSTD_estimateCCtxSize_usingCCtxParams(const ZSTD_CCtx_params* params);
ZSTDLIB_API size_t ZSTD_estimateDCtxSize(void);

/*! ZSTD_estimateCStreamSize() :
 *  ZSTD_estimateCStreamSize() will provide a budget large enough for any compression level up to selected one.
 *  It will also consider src size to be arbitrarily "large", which is worst case.
 *  If srcSize is known to always be small, ZSTD_estimateCStreamSize_usingCParams() can provide a tighter estimation.
 *  ZSTD_estimateCStreamSize_usingCParams() can be used in tandem with ZSTD_getCParams() to create cParams from compressionLevel.
 *  ZSTD_estimateCStreamSize_usingCCtxParams() can be used in tandem with ZSTD_CCtxParams_setParameter(). Only single-threaded compression is supported. This function will return an error code if ZSTD_c_nbWorkers is >= 1.
 *  Note : CStream size estimation is only correct for single-threaded compression.
 *  ZSTD_DStream memory budget depends on window Size.
 *  This information can be passed manually, using ZSTD_estimateDStreamSize,
 *  or deducted from a valid frame Header, using ZSTD_estimateDStreamSize_fromFrame();
 *  Note : if streaming is init with function ZSTD_init?Stream_usingDict(),
 *         an internal ?Dict will be created, which additional size is not estimated here.
 *         In this case, get total size by adding ZSTD_estimate?DictSize */
ZSTDLIB_API size_t ZSTD_estimateCStreamSize(int compressionLevel);
ZSTDLIB_API size_t ZSTD_estimateCStreamSize_usingCParams(ZSTD_compressionParameters cParams);
ZSTDLIB_API size_t ZSTD_estimateCStreamSize_usingCCtxParams(const ZSTD_CCtx_params* params);
ZSTDLIB_API size_t ZSTD_estimateDStreamSize(size_t windowSize);
ZSTDLIB_API size_t ZSTD_estimateDStreamSize_fromFrame(const void* src, size_t srcSize);

/*! ZSTD_estimate?DictSize() :
 *  ZSTD_estimateCDictSize() will bet that src size is relatively "small", and content is copied, like ZSTD_createCDict().
 *  ZSTD_estimateCDictSize_advanced() makes it possible to control compression parameters precisely, like ZSTD_createCDict_advanced().
 *  Note : dictionaries created by reference (`ZSTD_dlm_byRef`) are logically smaller.
 */
ZSTDLIB_API size_t ZSTD_estimateCDictSize(size_t dictSize, int compressionLevel);
ZSTDLIB_API size_t ZSTD_estimateCDictSize_advanced(size_t dictSize, ZSTD_compressionParameters cParams, ZSTD_dictLoadMethod_e dictLoadMethod);
ZSTDLIB_API size_t ZSTD_estimateDDictSize(size_t dictSize, ZSTD_dictLoadMethod_e dictLoadMethod);

/*! ZSTD_initStatic*() :
 *  Initialize an object using a pre-allocated fixed-size buffer.
 *  workspace: The memory area to emplace the object into.
 *             Provided pointer *must be 8-bytes aligned*.
 *             Buffer must outlive object.
 *  workspaceSize: Use ZSTD_estimate*Size() to determine
 *                 how large workspace must be to support target scenario.
 * @return : pointer to object (same address as workspace, just different type),
 *           or NULL if error (size too small, incorrect alignment, etc.)
 *  Note : zstd will never resize nor malloc() when using a static buffer.
 *         If the object requires more memory than available,
 *         zstd will just error out (typically ZSTD_error_memory_allocation).
 *  Note 2 : there is no corresponding "free" function.
 *           Since workspace is allocated externally, it must be freed externally too.
 *  Note 3 : cParams : use ZSTD_getCParams() to convert a compression level
 *           into its associated cParams.
 *  Limitation 1 : currently not compatible with internal dictionary creation, triggered by
 *                 ZSTD_CCtx_loadDictionary(), ZSTD_initCStream_usingDict() or ZSTD_initDStream_usingDict().
 *  Limitation 2 : static cctx currently not compatible with multi-threading.
 *  Limitation 3 : static dctx is incompatible with legacy support.
 */
ZSTDLIB_API ZSTD_CCtx*    ZSTD_initStaticCCtx(void* workspace, size_t workspaceSize);
ZSTDLIB_API ZSTD_CStream* ZSTD_initStaticCStream(void* workspace, size_t workspaceSize);    /**< same as ZSTD_initStaticCCtx() */

ZSTDLIB_API ZSTD_DCtx*    ZSTD_initStaticDCtx(void* workspace, size_t workspaceSize);
ZSTDLIB_API ZSTD_DStream* ZSTD_initStaticDStream(void* workspace, size_t workspaceSize);    /**< same as ZSTD_initStaticDCtx() */

ZSTDLIB_API const ZSTD_CDict* ZSTD_initStaticCDict(
                                        void* workspace, size_t workspaceSize,
                                        const void* dict, size_t dictSize,
                                        ZSTD_dictLoadMethod_e dictLoadMethod,
                                        ZSTD_dictContentType_e dictContentType,
                                        ZSTD_compressionParameters cParams);

ZSTDLIB_API const ZSTD_DDict* ZSTD_initStaticDDict(
                                        void* workspace, size_t workspaceSize,
                                        const void* dict, size_t dictSize,
                                        ZSTD_dictLoadMethod_e dictLoadMethod,
                                        ZSTD_dictContentType_e dictContentType);


/*! Custom memory allocation :
 *  These prototypes make it possible to pass your own allocation/free functions.
 *  ZSTD_customMem is provided at creation time, using ZSTD_create*_advanced() variants listed below.
 *  All allocation/free operations will be completed using these custom variants instead of regular <stdlib.h> ones.
 */
typedef void* (*ZSTD_allocFunction) (void* opaque, size_t size);
typedef void  (*ZSTD_freeFunction) (void* opaque, void* address);
typedef struct { ZSTD_allocFunction customAlloc; ZSTD_freeFunction customFree; void* opaque; } ZSTD_customMem;

ZSTDLIB_API ZSTD_CCtx*    ZSTD_createCCtx_advanced(ZSTD_customMem customMem);
ZSTDLIB_API ZSTD_CStream* ZSTD_createCStream_advanced(ZSTD_customMem customMem);
ZSTDLIB_API ZSTD_DCtx*    ZSTD_createDCtx_advanced(ZSTD_customMem customMem);
ZSTDLIB_API ZSTD_DStream* ZSTD_createDStream_advanced(ZSTD_customMem customMem);

ZSTDLIB_API ZSTD_CDict* ZSTD_createCDict_advanced(const void* dict, size_t dictSize,
                                                  ZSTD_dictLoadMethod_e dictLoadMethod,
                                                  ZSTD_dictContentType_e dictContentType,
                                                  ZSTD_compressionParameters cParams,
                                                  ZSTD_customMem customMem);

ZSTDLIB_API ZSTD_DDict* ZSTD_createDDict_advanced(const void* dict, size_t dictSize,
                                                  ZSTD_dictLoadMethod_e dictLoadMethod,
                                                  ZSTD_dictContentType_e dictContentType,
                                                  ZSTD_customMem customMem);



/***************************************
*  Advanced compression functions
***************************************/

/*! ZSTD_createCDict_byReference() :
 *  Create a digested dictionary for compression
 *  Dictionary content is just referenced, not duplicated.
 *  As a consequence, `dictBuffer` **must** outlive CDict,
 *  and its content must remain unmodified throughout the lifetime of CDict.
 *  note: equivalent to ZSTD_createCDict_advanced(), with dictLoadMethod==ZSTD_dlm_byRef */
ZSTDLIB_API ZSTD_CDict* ZSTD_createCDict_byReference(const void* dictBuffer, size_t dictSize, int compressionLevel);

/*! ZSTD_getCParams() :
 * @return ZSTD_compressionParameters structure for a selected compression level and estimated srcSize.
 * `estimatedSrcSize` value is optional, select 0 if not known */
ZSTDLIB_API ZSTD_compressionParameters ZSTD_getCParams(int compressionLevel, unsigned long long estimatedSrcSize, size_t dictSize);

/*! ZSTD_getParams() :
 *  same as ZSTD_getCParams(), but @return a full `ZSTD_parameters` object instead of sub-component `ZSTD_compressionParameters`.
 *  All fields of `ZSTD_frameParameters` are set to default : contentSize=1, checksum=0, noDictID=0 */
ZSTDLIB_API ZSTD_parameters ZSTD_getParams(int compressionLevel, unsigned long long estimatedSrcSize, size_t dictSize);

/*! ZSTD_checkCParams() :
 *  Ensure param values remain within authorized range.
 * @return 0 on success, or an error code (can be checked with ZSTD_isError()) */
ZSTDLIB_API size_t ZSTD_checkCParams(ZSTD_compressionParameters params);

/*! ZSTD_adjustCParams() :
 *  optimize params for a given `srcSize` and `dictSize`.
 * `srcSize` can be unknown, in which case use ZSTD_CONTENTSIZE_UNKNOWN.
 * `dictSize` must be `0` when there is no dictionary.
 *  cPar can be invalid : all parameters will be clamped within valid range in the @return struct.
 *  This function never fails (wide contract) */
ZSTDLIB_API ZSTD_compressionParameters ZSTD_adjustCParams(ZSTD_compressionParameters cPar, unsigned long long srcSize, size_t dictSize);

/*! ZSTD_compress_advanced() :
 *  Note : this function is now DEPRECATED.
 *         It can be replaced by ZSTD_compress2(), in combination with ZSTD_CCtx_setParameter() and other parameter setters.
 *  This prototype will be marked as deprecated and generate compilation warning on reaching v1.5.x */
ZSTDLIB_API size_t ZSTD_compress_advanced(ZSTD_CCtx* cctx,
                                          void* dst, size_t dstCapacity,
                                    const void* src, size_t srcSize,
                                    const void* dict,size_t dictSize,
                                          ZSTD_parameters params);

/*! ZSTD_compress_usingCDict_advanced() :
 *  Note : this function is now REDUNDANT.
 *         It can be replaced by ZSTD_compress2(), in combination with ZSTD_CCtx_loadDictionary() and other parameter setters.
 *  This prototype will be marked as deprecated and generate compilation warning in some future version */
ZSTDLIB_API size_t ZSTD_compress_usingCDict_advanced(ZSTD_CCtx* cctx,
                                              void* dst, size_t dstCapacity,
                                        const void* src, size_t srcSize,
                                        const ZSTD_CDict* cdict,
                                              ZSTD_frameParameters fParams);


/*! ZSTD_CCtx_loadDictionary_byReference() :
 *  Same as ZSTD_CCtx_loadDictionary(), but dictionary content is referenced, instead of being copied into CCtx.
 *  It saves some memory, but also requires that `dict` outlives its usage within `cctx` */
ZSTDLIB_API size_t ZSTD_CCtx_loadDictionary_byReference(ZSTD_CCtx* cctx, const void* dict, size_t dictSize);

/*! ZSTD_CCtx_loadDictionary_advanced() :
 *  Same as ZSTD_CCtx_loadDictionary(), but gives finer control over
 *  how to load the dictionary (by copy ? by reference ?)
 *  and how to interpret it (automatic ? force raw mode ? full mode only ?) */
ZSTDLIB_API size_t ZSTD_CCtx_loadDictionary_advanced(ZSTD_CCtx* cctx, const void* dict, size_t dictSize, ZSTD_dictLoadMethod_e dictLoadMethod, ZSTD_dictContentType_e dictContentType);

/*! ZSTD_CCtx_refPrefix_advanced() :
 *  Same as ZSTD_CCtx_refPrefix(), but gives finer control over
 *  how to interpret prefix content (automatic ? force raw mode (default) ? full mode only ?) */
ZSTDLIB_API size_t ZSTD_CCtx_refPrefix_advanced(ZSTD_CCtx* cctx, const void* prefix, size_t prefixSize, ZSTD_dictContentType_e dictContentType);

/* ===   experimental parameters   === */
/* these parameters can be used with ZSTD_setParameter()
 * they are not guaranteed to remain supported in the future */

 /* Enables rsyncable mode,
  * which makes compressed files more rsync friendly
  * by adding periodic synchronization points to the compressed data.
  * The target average block size is ZSTD_c_jobSize / 2.
  * It's possible to modify the job size to increase or decrease
  * the granularity of the synchronization point.
  * Once the jobSize is smaller than the window size,
  * it will result in compression ratio degradation.
  * NOTE 1: rsyncable mode only works when multithreading is enabled.
  * NOTE 2: rsyncable performs poorly in combination with long range mode,
  * since it will decrease the effectiveness of synchronization points,
  * though mileage may vary.
  * NOTE 3: Rsyncable mode limits maximum compression speed to ~400 MB/s.
  * If the selected compression level is already running significantly slower,
  * the overall speed won't be significantly impacted.
  */
 #define ZSTD_c_rsyncable ZSTD_c_experimentalParam1

/* Select a compression format.
 * The value must be of type ZSTD_format_e.
 * See ZSTD_format_e enum definition for details */
#define ZSTD_c_format ZSTD_c_experimentalParam2

/* Force back-reference distances to remain < windowSize,
 * even when referencing into Dictionary content (default:0) */
#define ZSTD_c_forceMaxWindow ZSTD_c_experimentalParam3

/* Controls whether the contents of a CDict
 * are used in place, or copied into the working context.
 * Accepts values from the ZSTD_dictAttachPref_e enum.
 * See the comments on that enum for an explanation of the feature. */
#define ZSTD_c_forceAttachDict ZSTD_c_experimentalParam4

/* Controls how the literals are compressed (default is auto).
 * The value must be of type ZSTD_literalCompressionMode_e.
 * See ZSTD_literalCompressionMode_t enum definition for details.
 */
#define ZSTD_c_literalCompressionMode ZSTD_c_experimentalParam5

/* Tries to fit compressed block size to be around targetCBlockSize.
 * No target when targetCBlockSize == 0.
 * There is no guarantee on compressed block size (default:0) */
#define ZSTD_c_targetCBlockSize ZSTD_c_experimentalParam6

/* User's best guess of source size.
 * Hint is not valid when srcSizeHint == 0.
 * There is no guarantee that hint is close to actual source size,
 * but compression ratio may regress significantly if guess considerably underestimates */
#define ZSTD_c_srcSizeHint ZSTD_c_experimentalParam7

/*! ZSTD_CCtx_getParameter() :
 *  Get the requested compression parameter value, selected by enum ZSTD_cParameter,
 *  and store it into int* value.
 * @return : 0, or an error code (which can be tested with ZSTD_isError()).
 */
ZSTDLIB_API size_t ZSTD_CCtx_getParameter(ZSTD_CCtx* cctx, ZSTD_cParameter param, int* value);


/*! ZSTD_CCtx_params :
 *  Quick howto :
 *  - ZSTD_createCCtxParams() : Create a ZSTD_CCtx_params structure
 *  - ZSTD_CCtxParams_setParameter() : Push parameters one by one into
 *                                     an existing ZSTD_CCtx_params structure.
 *                                     This is similar to
 *                                     ZSTD_CCtx_setParameter().
 *  - ZSTD_CCtx_setParametersUsingCCtxParams() : Apply parameters to
 *                                    an existing CCtx.
 *                                    These parameters will be applied to
 *                                    all subsequent frames.
 *  - ZSTD_compressStream2() : Do compression using the CCtx.
 *  - ZSTD_freeCCtxParams() : Free the memory.
 *
 *  This can be used with ZSTD_estimateCCtxSize_advanced_usingCCtxParams()
 *  for static allocation of CCtx for single-threaded compression.
 */
ZSTDLIB_API ZSTD_CCtx_params* ZSTD_createCCtxParams(void);
ZSTDLIB_API size_t ZSTD_freeCCtxParams(ZSTD_CCtx_params* params);

/*! ZSTD_CCtxParams_reset() :
 *  Reset params to default values.
 */
ZSTDLIB_API size_t ZSTD_CCtxParams_reset(ZSTD_CCtx_params* params);

/*! ZSTD_CCtxParams_init() :
 *  Initializes the compression parameters of cctxParams according to
 *  compression level. All other parameters are reset to their default values.
 */
ZSTDLIB_API size_t ZSTD_CCtxParams_init(ZSTD_CCtx_params* cctxParams, int compressionLevel);

/*! ZSTD_CCtxParams_init_advanced() :
 *  Initializes the compression and frame parameters of cctxParams according to
 *  params. All other parameters are reset to their default values.
 */
ZSTDLIB_API size_t ZSTD_CCtxParams_init_advanced(ZSTD_CCtx_params* cctxParams, ZSTD_parameters params);

/*! ZSTD_CCtxParams_setParameter() :
 *  Similar to ZSTD_CCtx_setParameter.
 *  Set one compression parameter, selected by enum ZSTD_cParameter.
 *  Parameters must be applied to a ZSTD_CCtx using ZSTD_CCtx_setParametersUsingCCtxParams().
 * @result : 0, or an error code (which can be tested with ZSTD_isError()).
 */
ZSTDLIB_API size_t ZSTD_CCtxParams_setParameter(ZSTD_CCtx_params* params, ZSTD_cParameter param, int value);

/*! ZSTD_CCtxParams_getParameter() :
 * Similar to ZSTD_CCtx_getParameter.
 * Get the requested value of one compression parameter, selected by enum ZSTD_cParameter.
 * @result : 0, or an error code (which can be tested with ZSTD_isError()).
 */
ZSTDLIB_API size_t ZSTD_CCtxParams_getParameter(ZSTD_CCtx_params* params, ZSTD_cParameter param, int* value);

/*! ZSTD_CCtx_setParametersUsingCCtxParams() :
 *  Apply a set of ZSTD_CCtx_params to the compression context.
 *  This can be done even after compression is started,
 *    if nbWorkers==0, this will have no impact until a new compression is started.
 *    if nbWorkers>=1, new parameters will be picked up at next job,
 *       with a few restrictions (windowLog, pledgedSrcSize, nbWorkers, jobSize, and overlapLog are not updated).
 */
ZSTDLIB_API size_t ZSTD_CCtx_setParametersUsingCCtxParams(
        ZSTD_CCtx* cctx, const ZSTD_CCtx_params* params);

/*! ZSTD_compressStream2_simpleArgs() :
 *  Same as ZSTD_compressStream2(),
 *  but using only integral types as arguments.
 *  This variant might be helpful for binders from dynamic languages
 *  which have troubles handling structures containing memory pointers.
 */
ZSTDLIB_API size_t ZSTD_compressStream2_simpleArgs (
                            ZSTD_CCtx* cctx,
                            void* dst, size_t dstCapacity, size_t* dstPos,
                      const void* src, size_t srcSize, size_t* srcPos,
                            ZSTD_EndDirective endOp);


/***************************************
*  Advanced decompression functions
***************************************/

/*! ZSTD_isFrame() :
 *  Tells if the content of `buffer` starts with a valid Frame Identifier.
 *  Note : Frame Identifier is 4 bytes. If `size < 4`, @return will always be 0.
 *  Note 2 : Legacy Frame Identifiers are considered valid only if Legacy Support is enabled.
 *  Note 3 : Skippable Frame Identifiers are considered valid. */
ZSTDLIB_API unsigned ZSTD_isFrame(const void* buffer, size_t size);

/*! ZSTD_createDDict_byReference() :
 *  Create a digested dictionary, ready to start decompression operation without startup delay.
 *  Dictionary content is referenced, and therefore stays in dictBuffer.
 *  It is important that dictBuffer outlives DDict,
 *  it must remain read accessible throughout the lifetime of DDict */
ZSTDLIB_API ZSTD_DDict* ZSTD_createDDict_byReference(const void* dictBuffer, size_t dictSize);

/*! ZSTD_DCtx_loadDictionary_byReference() :
 *  Same as ZSTD_DCtx_loadDictionary(),
 *  but references `dict` content instead of copying it into `dctx`.
 *  This saves memory if `dict` remains around.,
 *  However, it's imperative that `dict` remains accessible (and unmodified) while being used, so it must outlive decompression. */
ZSTDLIB_API size_t ZSTD_DCtx_loadDictionary_byReference(ZSTD_DCtx* dctx, const void* dict, size_t dictSize);

/*! ZSTD_DCtx_loadDictionary_advanced() :
 *  Same as ZSTD_DCtx_loadDictionary(),
 *  but gives direct control over
 *  how to load the dictionary (by copy ? by reference ?)
 *  and how to interpret it (automatic ? force raw mode ? full mode only ?). */
ZSTDLIB_API size_t ZSTD_DCtx_loadDictionary_advanced(ZSTD_DCtx* dctx, const void* dict, size_t dictSize, ZSTD_dictLoadMethod_e dictLoadMethod, ZSTD_dictContentType_e dictContentType);

/*! ZSTD_DCtx_refPrefix_advanced() :
 *  Same as ZSTD_DCtx_refPrefix(), but gives finer control over
 *  how to interpret prefix content (automatic ? force raw mode (default) ? full mode only ?) */
ZSTDLIB_API size_t ZSTD_DCtx_refPrefix_advanced(ZSTD_DCtx* dctx, const void* prefix, size_t prefixSize, ZSTD_dictContentType_e dictContentType);

/*! ZSTD_DCtx_setMaxWindowSize() :
 *  Refuses allocating internal buffers for frames requiring a window size larger than provided limit.
 *  This protects a decoder context from reserving too much memory for itself (potential attack scenario).
 *  This parameter is only useful in streaming mode, since no internal buffer is allocated in single-pass mode.
 *  By default, a decompression context accepts all window sizes <= (1 << ZSTD_WINDOWLOG_LIMIT_DEFAULT)
 * @return : 0, or an error code (which can be tested using ZSTD_isError()).
 */
ZSTDLIB_API size_t ZSTD_DCtx_setMaxWindowSize(ZSTD_DCtx* dctx, size_t maxWindowSize);

/* ZSTD_d_format
 * experimental parameter,
 * allowing selection between ZSTD_format_e input compression formats
 */
#define ZSTD_d_format ZSTD_d_experimentalParam1
/* ZSTD_d_stableOutBuffer
 * Experimental parameter.
 * Default is 0 == disabled. Set to 1 to enable.
 *
 * Tells the decompressor that the ZSTD_outBuffer will ALWAYS be the same
 * between calls, except for the modifications that zstd makes to pos (the
 * caller must not modify pos). This is checked by the decompressor, and
 * decompression will fail if it ever changes. Therefore the ZSTD_outBuffer
 * MUST be large enough to fit the entire decompressed frame. This will be
 * checked when the frame content size is known. The data in the ZSTD_outBuffer
 * in the range [dst, dst + pos) MUST not be modified during decompression
 * or you will get data corruption.
 *
 * When this flags is enabled zstd won't allocate an output buffer, because
 * it can write directly to the ZSTD_outBuffer, but it will still allocate
 * an input buffer large enough to fit any compressed block. This will also
 * avoid the memcpy() from the internal output buffer to the ZSTD_outBuffer.
 * If you need to avoid the input buffer allocation use the buffer-less
 * streaming API.
 *
 * NOTE: So long as the ZSTD_outBuffer always points to valid memory, using
 * this flag is ALWAYS memory safe, and will never access out-of-bounds
 * memory. However, decompression WILL fail if you violate the preconditions.
 *
 * WARNING: The data in the ZSTD_outBuffer in the range [dst, dst + pos) MUST
 * not be modified during decompression or you will get data corruption. This
 * is because zstd needs to reference data in the ZSTD_outBuffer to regenerate
 * matches. Normally zstd maintains its own buffer for this purpose, but passing
 * this flag tells zstd to use the user provided buffer.
 */
#define ZSTD_d_stableOutBuffer ZSTD_d_experimentalParam2

/*! ZSTD_DCtx_setFormat() :
 *  Instruct the decoder context about what kind of data to decode next.
 *  This instruction is mandatory to decode data without a fully-formed header,
 *  such ZSTD_f_zstd1_magicless for example.
 * @return : 0, or an error code (which can be tested using ZSTD_isError()). */
ZSTDLIB_API size_t ZSTD_DCtx_setFormat(ZSTD_DCtx* dctx, ZSTD_format_e format);

/*! ZSTD_decompressStream_simpleArgs() :
 *  Same as ZSTD_decompressStream(),
 *  but using only integral types as arguments.
 *  This can be helpful for binders from dynamic languages
 *  which have troubles handling structures containing memory pointers.
 */
ZSTDLIB_API size_t ZSTD_decompressStream_simpleArgs (
                            ZSTD_DCtx* dctx,
                            void* dst, size_t dstCapacity, size_t* dstPos,
                      const void* src, size_t srcSize, size_t* srcPos);


/********************************************************************
*  Advanced streaming functions
*  Warning : most of these functions are now redundant with the Advanced API.
*  Once Advanced API reaches "stable" status,
*  redundant functions will be deprecated, and then at some point removed.
********************************************************************/

/*=====   Advanced Streaming compression functions  =====*/
/**! ZSTD_initCStream_srcSize() :
 * This function is deprecated, and equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     ZSTD_CCtx_refCDict(zcs, NULL); // clear the dictionary (if any)
 *     ZSTD_CCtx_setParameter(zcs, ZSTD_c_compressionLevel, compressionLevel);
 *     ZSTD_CCtx_setPledgedSrcSize(zcs, pledgedSrcSize);
 *
 * pledgedSrcSize must be correct. If it is not known at init time, use
 * ZSTD_CONTENTSIZE_UNKNOWN. Note that, for compatibility with older programs,
 * "0" also disables frame content size field. It may be enabled in the future.
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t
ZSTD_initCStream_srcSize(ZSTD_CStream* zcs,
                         int compressionLevel,
                         unsigned long long pledgedSrcSize);

/**! ZSTD_initCStream_usingDict() :
 * This function is deprecated, and is equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     ZSTD_CCtx_setParameter(zcs, ZSTD_c_compressionLevel, compressionLevel);
 *     ZSTD_CCtx_loadDictionary(zcs, dict, dictSize);
 *
 * Creates of an internal CDict (incompatible with static CCtx), except if
 * dict == NULL or dictSize < 8, in which case no dict is used.
 * Note: dict is loaded with ZSTD_dct_auto (treated as a full zstd dictionary if
 * it begins with ZSTD_MAGIC_DICTIONARY, else as raw content) and ZSTD_dlm_byCopy.
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t
ZSTD_initCStream_usingDict(ZSTD_CStream* zcs,
                     const void* dict, size_t dictSize,
                           int compressionLevel);

/**! ZSTD_initCStream_advanced() :
 * This function is deprecated, and is approximately equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     // Pseudocode: Set each zstd parameter and leave the rest as-is.
 *     for ((param, value) : params) {
 *         ZSTD_CCtx_setParameter(zcs, param, value);
 *     }
 *     ZSTD_CCtx_setPledgedSrcSize(zcs, pledgedSrcSize);
 *     ZSTD_CCtx_loadDictionary(zcs, dict, dictSize);
 *
 * dict is loaded with ZSTD_dct_auto and ZSTD_dlm_byCopy.
 * pledgedSrcSize must be correct.
 * If srcSize is not known at init time, use value ZSTD_CONTENTSIZE_UNKNOWN.
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t
ZSTD_initCStream_advanced(ZSTD_CStream* zcs,
                    const void* dict, size_t dictSize,
                          ZSTD_parameters params,
                          unsigned long long pledgedSrcSize);

/**! ZSTD_initCStream_usingCDict() :
 * This function is deprecated, and equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     ZSTD_CCtx_refCDict(zcs, cdict);
 *
 * note : cdict will just be referenced, and must outlive compression session
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t ZSTD_initCStream_usingCDict(ZSTD_CStream* zcs, const ZSTD_CDict* cdict);

/**! ZSTD_initCStream_usingCDict_advanced() :
 *   This function is DEPRECATED, and is approximately equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     // Pseudocode: Set each zstd frame parameter and leave the rest as-is.
 *     for ((fParam, value) : fParams) {
 *         ZSTD_CCtx_setParameter(zcs, fParam, value);
 *     }
 *     ZSTD_CCtx_setPledgedSrcSize(zcs, pledgedSrcSize);
 *     ZSTD_CCtx_refCDict(zcs, cdict);
 *
 * same as ZSTD_initCStream_usingCDict(), with control over frame parameters.
 * pledgedSrcSize must be correct. If srcSize is not known at init time, use
 * value ZSTD_CONTENTSIZE_UNKNOWN.
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t
ZSTD_initCStream_usingCDict_advanced(ZSTD_CStream* zcs,
                               const ZSTD_CDict* cdict,
                                     ZSTD_frameParameters fParams,
                                     unsigned long long pledgedSrcSize);

/*! ZSTD_resetCStream() :
 * This function is deprecated, and is equivalent to:
 *     ZSTD_CCtx_reset(zcs, ZSTD_reset_session_only);
 *     ZSTD_CCtx_setPledgedSrcSize(zcs, pledgedSrcSize);
 *
 *  start a new frame, using same parameters from previous frame.
 *  This is typically useful to skip dictionary loading stage, since it will re-use it in-place.
 *  Note that zcs must be init at least once before using ZSTD_resetCStream().
 *  If pledgedSrcSize is not known at reset time, use macro ZSTD_CONTENTSIZE_UNKNOWN.
 *  If pledgedSrcSize > 0, its value must be correct, as it will be written in header, and controlled at the end.
 *  For the time being, pledgedSrcSize==0 is interpreted as "srcSize unknown" for compatibility with older programs,
 *  but it will change to mean "empty" in future version, so use macro ZSTD_CONTENTSIZE_UNKNOWN instead.
 * @return : 0, or an error code (which can be tested using ZSTD_isError())
 *  Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t ZSTD_resetCStream(ZSTD_CStream* zcs, unsigned long long pledgedSrcSize);


typedef struct {
    unsigned long long ingested;   /* nb input bytes read and buffered */
    unsigned long long consumed;   /* nb input bytes actually compressed */
    unsigned long long produced;   /* nb of compressed bytes generated and buffered */
    unsigned long long flushed;    /* nb of compressed bytes flushed : not provided; can be tracked from caller side */
    unsigned currentJobID;         /* MT only : latest started job nb */
    unsigned nbActiveWorkers;      /* MT only : nb of workers actively compressing at probe time */
} ZSTD_frameProgression;

/* ZSTD_getFrameProgression() :
 * tells how much data has been ingested (read from input)
 * consumed (input actually compressed) and produced (output) for current frame.
 * Note : (ingested - consumed) is amount of input data buffered internally, not yet compressed.
 * Aggregates progression inside active worker threads.
 */
ZSTDLIB_API ZSTD_frameProgression ZSTD_getFrameProgression(const ZSTD_CCtx* cctx);

/*! ZSTD_toFlushNow() :
 *  Tell how many bytes are ready to be flushed immediately.
 *  Useful for multithreading scenarios (nbWorkers >= 1).
 *  Probe the oldest active job, defined as oldest job not yet entirely flushed,
 *  and check its output buffer.
 * @return : amount of data stored in oldest job and ready to be flushed immediately.
 *  if @return == 0, it means either :
 *  + there is no active job (could be checked with ZSTD_frameProgression()), or
 *  + oldest job is still actively compressing data,
 *    but everything it has produced has also been flushed so far,
 *    therefore flush speed is limited by production speed of oldest job
 *    irrespective of the speed of concurrent (and newer) jobs.
 */
ZSTDLIB_API size_t ZSTD_toFlushNow(ZSTD_CCtx* cctx);


/*=====   Advanced Streaming decompression functions  =====*/
/**
 * This function is deprecated, and is equivalent to:
 *
 *     ZSTD_DCtx_reset(zds, ZSTD_reset_session_only);
 *     ZSTD_DCtx_loadDictionary(zds, dict, dictSize);
 *
 * note: no dictionary will be used if dict == NULL or dictSize < 8
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t ZSTD_initDStream_usingDict(ZSTD_DStream* zds, const void* dict, size_t dictSize);

/**
 * This function is deprecated, and is equivalent to:
 *
 *     ZSTD_DCtx_reset(zds, ZSTD_reset_session_only);
 *     ZSTD_DCtx_refDDict(zds, ddict);
 *
 * note : ddict is referenced, it must outlive decompression session
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t ZSTD_initDStream_usingDDict(ZSTD_DStream* zds, const ZSTD_DDict* ddict);

/**
 * This function is deprecated, and is equivalent to:
 *
 *     ZSTD_DCtx_reset(zds, ZSTD_reset_session_only);
 *
 * re-use decompression parameters from previous init; saves dictionary loading
 * Note : this prototype will be marked as deprecated and generate compilation warnings on reaching v1.5.x
 */
ZSTDLIB_API size_t ZSTD_resetDStream(ZSTD_DStream* zds);


/*********************************************************************
*  Buffer-less and synchronous inner streaming functions
*
*  This is an advanced API, giving full control over buffer management, for users which need direct control over memory.
*  But it's also a complex one, with several restrictions, documented below.
*  Prefer normal streaming API for an easier experience.
********************************************************************* */

/**
  Buffer-less streaming compression (synchronous mode)

  A ZSTD_CCtx object is required to track streaming operations.
  Use ZSTD_createCCtx() / ZSTD_freeCCtx() to manage resource.
  ZSTD_CCtx object can be re-used multiple times within successive compression operations.

  Start by initializing a context.
  Use ZSTD_compressBegin(), or ZSTD_compressBegin_usingDict() for dictionary compression,
  or ZSTD_compressBegin_advanced(), for finer parameter control.
  It's also possible to duplicate a reference context which has already been initialized, using ZSTD_copyCCtx()

  Then, consume your input using ZSTD_compressContinue().
  There are some important considerations to keep in mind when using this advanced function :
  - ZSTD_compressContinue() has no internal buffer. It uses externally provided buffers only.
  - Interface is synchronous : input is consumed entirely and produces 1+ compressed blocks.
  - Caller must ensure there is enough space in `dst` to store compressed data under worst case scenario.
    Worst case evaluation is provided by ZSTD_compressBound().
    ZSTD_compressContinue() doesn't guarantee recover after a failed compression.
  - ZSTD_compressContinue() presumes prior input ***is still accessible and unmodified*** (up to maximum distance size, see WindowLog).
    It remembers all previous contiguous blocks, plus one separated memory segment (which can itself consists of multiple contiguous blocks)
  - ZSTD_compressContinue() detects that prior input has been overwritten when `src` buffer overlaps.
    In which case, it will "discard" the relevant memory section from its history.

  Finish a frame with ZSTD_compressEnd(), which will write the last block(s) and optional checksum.
  It's possible to use srcSize==0, in which case, it will write a final empty block to end the frame.
  Without last block mark, frames are considered unfinished (hence corrupted) by compliant decoders.

  `ZSTD_CCtx` object can be re-used (ZSTD_compressBegin()) to compress again.
*/

/*=====   Buffer-less streaming compression functions  =====*/
ZSTDLIB_API size_t ZSTD_compressBegin(ZSTD_CCtx* cctx, int compressionLevel);
ZSTDLIB_API size_t ZSTD_compressBegin_usingDict(ZSTD_CCtx* cctx, const void* dict, size_t dictSize, int compressionLevel);
ZSTDLIB_API size_t ZSTD_compressBegin_advanced(ZSTD_CCtx* cctx, const void* dict, size_t dictSize, ZSTD_parameters params, unsigned long long pledgedSrcSize); /**< pledgedSrcSize : If srcSize is not known at init time, use ZSTD_CONTENTSIZE_UNKNOWN */
ZSTDLIB_API size_t ZSTD_compressBegin_usingCDict(ZSTD_CCtx* cctx, const ZSTD_CDict* cdict); /**< note: fails if cdict==NULL */
ZSTDLIB_API size_t ZSTD_compressBegin_usingCDict_advanced(ZSTD_CCtx* const cctx, const ZSTD_CDict* const cdict, ZSTD_frameParameters const fParams, unsigned long long const pledgedSrcSize);   /* compression parameters are already set within cdict. pledgedSrcSize must be correct. If srcSize is not known, use macro ZSTD_CONTENTSIZE_UNKNOWN */
ZSTDLIB_API size_t ZSTD_copyCCtx(ZSTD_CCtx* cctx, const ZSTD_CCtx* preparedCCtx, unsigned long long pledgedSrcSize); /**<  note: if pledgedSrcSize is not known, use ZSTD_CONTENTSIZE_UNKNOWN */

ZSTDLIB_API size_t ZSTD_compressContinue(ZSTD_CCtx* cctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize);
ZSTDLIB_API size_t ZSTD_compressEnd(ZSTD_CCtx* cctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize);


/*-
  Buffer-less streaming decompression (synchronous mode)

  A ZSTD_DCtx object is required to track streaming operations.
  Use ZSTD_createDCtx() / ZSTD_freeDCtx() to manage it.
  A ZSTD_DCtx object can be re-used multiple times.

  First typical operation is to retrieve frame parameters, using ZSTD_getFrameHeader().
  Frame header is extracted from the beginning of compressed frame, so providing only the frame's beginning is enough.
  Data fragment must be large enough to ensure successful decoding.
 `ZSTD_frameHeaderSize_max` bytes is guaranteed to always be large enough.
  @result : 0 : successful decoding, the `ZSTD_frameHeader` structure is correctly filled.
           >0 : `srcSize` is too small, please provide at least @result bytes on next attempt.
           errorCode, which can be tested using ZSTD_isError().

  It fills a ZSTD_frameHeader structure with important information to correctly decode the frame,
  such as the dictionary ID, content size, or maximum back-reference distance (`windowSize`).
  Note that these values could be wrong, either because of data corruption, or because a 3rd party deliberately spoofs false information.
  As a consequence, check that values remain within valid application range.
  For example, do not allocate memory blindly, check that `windowSize` is within expectation.
  Each application can set its own limits, depending on local restrictions.
  For extended interoperability, it is recommended to support `windowSize` of at least 8 MB.

  ZSTD_decompressContinue() needs previous data blocks during decompression, up to `windowSize` bytes.
  ZSTD_decompressContinue() is very sensitive to contiguity,
  if 2 blocks don't follow each other, make sure that either the compressor breaks contiguity at the same place,
  or that previous contiguous segment is large enough to properly handle maximum back-reference distance.
  There are multiple ways to guarantee this condition.

  The most memory efficient way is to use a round buffer of sufficient size.
  Sufficient size is determined by invoking ZSTD_decodingBufferSize_min(),
  which can @return an error code if required value is too large for current system (in 32-bits mode).
  In a round buffer methodology, ZSTD_decompressContinue() decompresses each block next to previous one,
  up to the moment there is not enough room left in the buffer to guarantee decoding another full block,
  which maximum size is provided in `ZSTD_frameHeader` structure, field `blockSizeMax`.
  At which point, decoding can resume from the beginning of the buffer.
  Note that already decoded data stored in the buffer should be flushed before being overwritten.

  There are alternatives possible, for example using two or more buffers of size `windowSize` each, though they consume more memory.

  Finally, if you control the compression process, you can also ignore all buffer size rules,
  as long as the encoder and decoder progress in "lock-step",
  aka use exactly the same buffer sizes, break contiguity at the same place, etc.

  Once buffers are setup, start decompression, with ZSTD_decompressBegin().
  If decompression requires a dictionary, use ZSTD_decompressBegin_usingDict() or ZSTD_decompressBegin_usingDDict().

  Then use ZSTD_nextSrcSizeToDecompress() and ZSTD_decompressContinue() alternatively.
  ZSTD_nextSrcSizeToDecompress() tells how many bytes to provide as 'srcSize' to ZSTD_decompressContinue().
  ZSTD_decompressContinue() requires this _exact_ amount of bytes, or it will fail.

 @result of ZSTD_decompressContinue() is the number of bytes regenerated within 'dst' (necessarily <= dstCapacity).
  It can be zero : it just means ZSTD_decompressContinue() has decoded some metadata item.
  It can also be an error code, which can be tested with ZSTD_isError().

  A frame is fully decoded when ZSTD_nextSrcSizeToDecompress() returns zero.
  Context can then be reset to start a new decompression.

  Note : it's possible to know if next input to present is a header or a block, using ZSTD_nextInputType().
  This information is not required to properly decode a frame.

  == Special case : skippable frames ==

  Skippable frames allow integration of user-defined data into a flow of concatenated frames.
  Skippable frames will be ignored (skipped) by decompressor.
  The format of skippable frames is as follows :
  a) Skippable frame ID - 4 Bytes, Little endian format, any value from 0x184D2A50 to 0x184D2A5F
  b) Frame Size - 4 Bytes, Little endian format, unsigned 32-bits
  c) Frame Content - any content (User Data) of length equal to Frame Size
  For skippable frames ZSTD_getFrameHeader() returns zfhPtr->frameType==ZSTD_skippableFrame.
  For skippable frames ZSTD_decompressContinue() always returns 0 : it only skips the content.
*/

/*=====   Buffer-less streaming decompression functions  =====*/
typedef enum { ZSTD_frame, ZSTD_skippableFrame } ZSTD_frameType_e;
typedef struct {
    unsigned long long frameContentSize; /* if == ZSTD_CONTENTSIZE_UNKNOWN, it means this field is not available. 0 means "empty" */
    unsigned long long windowSize;       /* can be very large, up to <= frameContentSize */
    unsigned blockSizeMax;
    ZSTD_frameType_e frameType;          /* if == ZSTD_skippableFrame, frameContentSize is the size of skippable content */
    unsigned headerSize;
    unsigned dictID;
    unsigned checksumFlag;
} ZSTD_frameHeader;

/*! ZSTD_getFrameHeader() :
 *  decode Frame Header, or requires larger `srcSize`.
 * @return : 0, `zfhPtr` is correctly filled,
 *          >0, `srcSize` is too small, value is wanted `srcSize` amount,
 *           or an error code, which can be tested using ZSTD_isError() */
ZSTDLIB_API size_t ZSTD_getFrameHeader(ZSTD_frameHeader* zfhPtr, const void* src, size_t srcSize);   /**< doesn't consume input */
/*! ZSTD_getFrameHeader_advanced() :
 *  same as ZSTD_getFrameHeader(),
 *  with added capability to select a format (like ZSTD_f_zstd1_magicless) */
ZSTDLIB_API size_t ZSTD_getFrameHeader_advanced(ZSTD_frameHeader* zfhPtr, const void* src, size_t srcSize, ZSTD_format_e format);
ZSTDLIB_API size_t ZSTD_decodingBufferSize_min(unsigned long long windowSize, unsigned long long frameContentSize);  /**< when frame content size is not known, pass in frameContentSize == ZSTD_CONTENTSIZE_UNKNOWN */

ZSTDLIB_API size_t ZSTD_decompressBegin(ZSTD_DCtx* dctx);
ZSTDLIB_API size_t ZSTD_decompressBegin_usingDict(ZSTD_DCtx* dctx, const void* dict, size_t dictSize);
ZSTDLIB_API size_t ZSTD_decompressBegin_usingDDict(ZSTD_DCtx* dctx, const ZSTD_DDict* ddict);

ZSTDLIB_API size_t ZSTD_nextSrcSizeToDecompress(ZSTD_DCtx* dctx);
ZSTDLIB_API size_t ZSTD_decompressContinue(ZSTD_DCtx* dctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize);

/* misc */
ZSTDLIB_API void   ZSTD_copyDCtx(ZSTD_DCtx* dctx, const ZSTD_DCtx* preparedDCtx);
typedef enum { ZSTDnit_frameHeader, ZSTDnit_blockHeader, ZSTDnit_block, ZSTDnit_lastBlock, ZSTDnit_checksum, ZSTDnit_skippableFrame } ZSTD_nextInputType_e;
ZSTDLIB_API ZSTD_nextInputType_e ZSTD_nextInputType(ZSTD_DCtx* dctx);




/* ============================ */
/**       Block level API       */
/* ============================ */

/*!
    Block functions produce and decode raw zstd blocks, without frame metadata.
    Frame metadata cost is typically ~12 bytes, which can be non-negligible for very small blocks (< 100 bytes).
    But users will have to take in charge needed metadata to regenerate data, such as compressed and content sizes.

    A few rules to respect :
    - Compressing and decompressing require a context structure
      + Use ZSTD_createCCtx() and ZSTD_createDCtx()
    - It is necessary to init context before starting
      + compression : any ZSTD_compressBegin*() variant, including with dictionary
      + decompression : any ZSTD_decompressBegin*() variant, including with dictionary
      + copyCCtx() and copyDCtx() can be used too
    - Block size is limited, it must be <= ZSTD_getBlockSize() <= ZSTD_BLOCKSIZE_MAX == 128 KB
      + If input is larger than a block size, it's necessary to split input data into multiple blocks
      + For inputs larger than a single block, consider using regular ZSTD_compress() instead.
        Frame metadata is not that costly, and quickly becomes negligible as source size grows larger than a block.
    - When a block is considered not compressible enough, ZSTD_compressBlock() result will be 0 (zero) !
      ===> In which case, nothing is produced into `dst` !
      + User __must__ test for such outcome and deal directly with uncompressed data
      + A block cannot be declared incompressible if ZSTD_compressBlock() return value was != 0.
        Doing so would mess up with statistics history, leading to potential data corruption.
      + ZSTD_decompressBlock() _doesn't accept uncompressed data as input_ !!
      + In case of multiple successive blocks, should some of them be uncompressed,
        decoder must be informed of their existence in order to follow proper history.
        Use ZSTD_insertBlock() for such a case.
*/

/*=====   Raw zstd block functions  =====*/
ZSTDLIB_API size_t ZSTD_getBlockSize   (const ZSTD_CCtx* cctx);
ZSTDLIB_API size_t ZSTD_compressBlock  (ZSTD_CCtx* cctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize);
ZSTDLIB_API size_t ZSTD_decompressBlock(ZSTD_DCtx* dctx, void* dst, size_t dstCapacity, const void* src, size_t srcSize);
ZSTDLIB_API size_t ZSTD_insertBlock    (ZSTD_DCtx* dctx, const void* blockStart, size_t blockSize);  /**< insert uncompressed block into `dctx` history. Useful for multi-blocks decompression. */

}

#endif   /* ZSTD_H_ZSTD_STATIC_LINKING_ONLY */
