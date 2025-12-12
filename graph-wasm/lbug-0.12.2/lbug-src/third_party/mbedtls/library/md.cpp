/**
 * \file md.c
 *
 * \brief Generic message digest wrapper for mbed TLS
 *
 * \author Adriaan de Jong <dejong@fox-it.com>
 *
 *  Copyright The Mbed TLS Contributors
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

#include "common.h"

#if defined(MBEDTLS_MD_C)

#include "mbedtls/error.h"
#include "mbedtls/md.h"
#include "mbedtls/platform_util.h"
#include "md_wrap.h"

#if defined(MBEDTLS_MD5_C)
#include "mbedtls/md5.h"
#endif
#if defined(MBEDTLS_RIPEMD160_C)
#include "mbedtls/ripemd160.h"
#endif
#if defined(MBEDTLS_SHA1_C)
#include "mbedtls/sha1.h"
#endif
#if defined(MBEDTLS_SHA256_C)
#include "mbedtls/sha256.h"
#endif
#if defined(MBEDTLS_SHA512_C)
#include "mbedtls/sha512.h"
#endif

#if defined(MBEDTLS_PLATFORM_C)
#include "mbedtls/platform.h"
#else
#include <stdlib.h>
#define mbedtls_calloc calloc
#define mbedtls_free free
#endif

#include <string.h>

#if defined(MBEDTLS_FS_IO)
#include <stdio.h>
#endif

#if defined(MBEDTLS_MD5_C)
const mbedtls_md_info_t mbedtls_md5_info = {
    "MD5",
    MBEDTLS_MD_MD5,
    16,
    64,
};
#endif

#if defined(MBEDTLS_RIPEMD160_C)
const mbedtls_md_info_t mbedtls_ripemd160_info = {
    "RIPEMD160",
    MBEDTLS_MD_RIPEMD160,
    20,
    64,
};
#endif

#if defined(MBEDTLS_SHA1_C)
const mbedtls_md_info_t mbedtls_sha1_info = {
    "SHA1",
    MBEDTLS_MD_SHA1,
    20,
    64,
};
#endif

#if defined(MBEDTLS_SHA224_C)
const mbedtls_md_info_t mbedtls_sha224_info = {
    "SHA224",
    MBEDTLS_MD_SHA224,
    28,
    64,
};
#endif

#if defined(MBEDTLS_SHA256_C)
const mbedtls_md_info_t mbedtls_sha256_info = {
    "SHA256",
    MBEDTLS_MD_SHA256,
    32,
    64,
};
#endif

#if defined(MBEDTLS_SHA384_C)
const mbedtls_md_info_t mbedtls_sha384_info = {
    "SHA384",
    MBEDTLS_MD_SHA384,
    48,
    128,
};
#endif

#if defined(MBEDTLS_SHA512_C)
const mbedtls_md_info_t mbedtls_sha512_info = {
    "SHA512",
    MBEDTLS_MD_SHA512,
    64,
    128,
};
#endif

/*
 * Reminder: update profiles in x509_crt.c when adding a new hash!
 */
static const int supported_digests[] = {

#if defined(MBEDTLS_SHA512_C)
    MBEDTLS_MD_SHA512,
#endif

#if defined(MBEDTLS_SHA384_C)
    MBEDTLS_MD_SHA384,
#endif

#if defined(MBEDTLS_SHA256_C)
    MBEDTLS_MD_SHA256,
#endif
#if defined(MBEDTLS_SHA224_C)
    MBEDTLS_MD_SHA224,
#endif

#if defined(MBEDTLS_SHA1_C)
    MBEDTLS_MD_SHA1,
#endif

#if defined(MBEDTLS_RIPEMD160_C)
    MBEDTLS_MD_RIPEMD160,
#endif

#if defined(MBEDTLS_MD5_C)
    MBEDTLS_MD_MD5,
#endif

    MBEDTLS_MD_NONE};

const int* mbedtls_md_list(void) {
    return (supported_digests);
}

const mbedtls_md_info_t* mbedtls_md_info_from_string(const char* md_name) {
    if (NULL == md_name)
        return (NULL);

        /* Get the appropriate digest information */
#if defined(MBEDTLS_MD5_C)
    if (!strcmp("MD5", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_MD5);
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    if (!strcmp("RIPEMD160", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_RIPEMD160);
#endif
#if defined(MBEDTLS_SHA1_C)
    if (!strcmp("SHA1", md_name) || !strcmp("SHA", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_SHA1);
#endif
#if defined(MBEDTLS_SHA224_C)
    if (!strcmp("SHA224", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_SHA224);
#endif
#if defined(MBEDTLS_SHA256_C)
    if (!strcmp("SHA256", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
#endif
#if defined(MBEDTLS_SHA384_C)
    if (!strcmp("SHA384", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_SHA384);
#endif
#if defined(MBEDTLS_SHA512_C)
    if (!strcmp("SHA512", md_name))
        return mbedtls_md_info_from_type(MBEDTLS_MD_SHA512);
#endif
    return (NULL);
}

const mbedtls_md_info_t* mbedtls_md_info_from_type(mbedtls_md_type_t md_type) {
    switch (md_type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (&mbedtls_md5_info);
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (&mbedtls_ripemd160_info);
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (&mbedtls_sha1_info);
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (&mbedtls_sha224_info);
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (&mbedtls_sha256_info);
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (&mbedtls_sha384_info);
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (&mbedtls_sha512_info);
#endif
    default:
        return (NULL);
    }
}

void mbedtls_md_init(mbedtls_md_context_t* ctx) {
    memset(ctx, 0, sizeof(mbedtls_md_context_t));
}

void mbedtls_md_free(mbedtls_md_context_t* ctx) {
    if (ctx == NULL || ctx->md_info == NULL)
        return;

    if (ctx->md_ctx != NULL) {
        switch (ctx->md_info->type) {
#if defined(MBEDTLS_MD5_C)
        case MBEDTLS_MD_MD5:
            mbedtls_md5_free(ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_RIPEMD160_C)
        case MBEDTLS_MD_RIPEMD160:
            mbedtls_ripemd160_free(ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_SHA1_C)
        case MBEDTLS_MD_SHA1:
            mbedtls_sha1_free(ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_SHA224_C)
        case MBEDTLS_MD_SHA224:
            mbedtls_sha256_free((mbedtls_sha256_context*)ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_SHA256_C)
        case MBEDTLS_MD_SHA256:
            mbedtls_sha256_free((mbedtls_sha256_context*)ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_SHA384_C)
        case MBEDTLS_MD_SHA384:
            mbedtls_sha512_free(ctx->md_ctx);
            break;
#endif
#if defined(MBEDTLS_SHA512_C)
        case MBEDTLS_MD_SHA512:
            mbedtls_sha512_free(ctx->md_ctx);
            break;
#endif
        default:
            /* Shouldn't happen */
            break;
        }
        mbedtls_free(ctx->md_ctx);
    }

    if (ctx->hmac_ctx != NULL) {
        mbedtls_platform_zeroize(ctx->hmac_ctx, 2 * ctx->md_info->block_size);
        mbedtls_free(ctx->hmac_ctx);
    }

    mbedtls_platform_zeroize(ctx, sizeof(mbedtls_md_context_t));
}

int mbedtls_md_clone(mbedtls_md_context_t* dst, const mbedtls_md_context_t* src) {
    if (dst == NULL || dst->md_info == NULL || src == NULL || src->md_info == NULL ||
        dst->md_info != src->md_info) {
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }

    switch (src->md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        mbedtls_md5_clone(dst->md_ctx, src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        mbedtls_ripemd160_clone(dst->md_ctx, src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        mbedtls_sha1_clone(dst->md_ctx, src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        mbedtls_sha256_clone((mbedtls_sha256_context*)dst->md_ctx,
            (mbedtls_sha256_context*)src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        mbedtls_sha256_clone((mbedtls_sha256_context*)dst->md_ctx,
            (mbedtls_sha256_context*)src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        mbedtls_sha512_clone(dst->md_ctx, src->md_ctx);
        break;
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        mbedtls_sha512_clone(dst->md_ctx, src->md_ctx);
        break;
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }

    return (0);
}

#define ALLOC(type)                                                                                \
    do {                                                                                           \
        ctx->md_ctx =                                                                              \
            (mbedtls_##type##_context*)mbedtls_calloc(1, sizeof(mbedtls_##type##_context));        \
        if (ctx->md_ctx == NULL)                                                                   \
            return (MBEDTLS_ERR_MD_ALLOC_FAILED);                                                  \
        mbedtls_##type##_init((mbedtls_##type##_context*)ctx->md_ctx);                             \
    } while (0)

int mbedtls_md_setup(mbedtls_md_context_t* ctx, const mbedtls_md_info_t* md_info, int hmac) {
    if (md_info == NULL || ctx == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    ctx->md_info = md_info;
    ctx->md_ctx = NULL;
    ctx->hmac_ctx = NULL;

    switch (md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        ALLOC(md5);
        break;
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        ALLOC(ripemd160);
        break;
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        ALLOC(sha1);
        break;
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        ALLOC(sha256);
        break;
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        ALLOC(sha256);
        break;
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        ALLOC(sha512);
        break;
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        ALLOC(sha512);
        break;
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }

    if (hmac != 0) {
        ctx->hmac_ctx = mbedtls_calloc(2, md_info->block_size);
        if (ctx->hmac_ctx == NULL) {
            mbedtls_md_free(ctx);
            return (MBEDTLS_ERR_MD_ALLOC_FAILED);
        }
    }

    return (0);
}
#undef ALLOC

int mbedtls_md_starts(mbedtls_md_context_t* ctx) {
    if (ctx == NULL || ctx->md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    switch (ctx->md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (mbedtls_md5_starts(ctx->md_ctx));
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (mbedtls_ripemd160_starts(ctx->md_ctx));
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (mbedtls_sha1_starts(ctx->md_ctx));
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (mbedtls_sha256_starts((mbedtls_sha256_context*)ctx->md_ctx, 1));
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (mbedtls_sha256_starts((mbedtls_sha256_context*)ctx->md_ctx, 0));
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (mbedtls_sha512_starts(ctx->md_ctx, 1));
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (mbedtls_sha512_starts(ctx->md_ctx, 0));
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }
}

int mbedtls_md_update(mbedtls_md_context_t* ctx, const unsigned char* input, size_t ilen) {
    if (ctx == NULL || ctx->md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    switch (ctx->md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (mbedtls_md5_update(ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (mbedtls_ripemd160_update(ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (mbedtls_sha1_update(ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (mbedtls_sha256_update((mbedtls_sha256_context*)ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (mbedtls_sha256_update((mbedtls_sha256_context*)ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (mbedtls_sha512_update(ctx->md_ctx, input, ilen));
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (mbedtls_sha512_update(ctx->md_ctx, input, ilen));
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }
}

int mbedtls_md_finish(mbedtls_md_context_t* ctx, unsigned char* output) {
    if (ctx == NULL || ctx->md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    switch (ctx->md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (mbedtls_md5_finish(ctx->md_ctx, output));
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (mbedtls_ripemd160_finish(ctx->md_ctx, output));
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (mbedtls_sha1_finish(ctx->md_ctx, output));
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (mbedtls_sha256_finish((mbedtls_sha256_context*)ctx->md_ctx, output));
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (mbedtls_sha256_finish((mbedtls_sha256_context*)(mbedtls_sha256_context*)ctx->md_ctx,
            output));
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (mbedtls_sha512_finish(ctx->md_ctx, output));
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (mbedtls_sha512_finish(ctx->md_ctx, output));
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }
}

int mbedtls_md(const mbedtls_md_info_t* md_info, const unsigned char* input, size_t ilen,
    unsigned char* output) {
    if (md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    switch (md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (mbedtls_md5(input, ilen, output));
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (mbedtls_ripemd160(input, ilen, output));
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (mbedtls_sha1(input, ilen, output));
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (mbedtls_sha256(input, ilen, output, 1));
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (mbedtls_sha256(input, ilen, output, 0));
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (mbedtls_sha512(input, ilen, output, 1));
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (mbedtls_sha512(input, ilen, output, 0));
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }
}

#if defined(MBEDTLS_FS_IO)
int mbedtls_md_file(const mbedtls_md_info_t* md_info, const char* path, unsigned char* output) {
    int ret = MBEDTLS_ERR_ERROR_CORRUPTION_DETECTED;
    FILE* f;
    size_t n;
    mbedtls_md_context_t ctx;
    unsigned char buf[1024];

    if (md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    if ((f = fopen(path, "rb")) == NULL)
        return (MBEDTLS_ERR_MD_FILE_IO_ERROR);

    mbedtls_md_init(&ctx);

    if ((ret = mbedtls_md_setup(&ctx, md_info, 0)) != 0)
        goto cleanup;

    if ((ret = mbedtls_md_starts(&ctx)) != 0)
        goto cleanup;

    while ((n = fread(buf, 1, sizeof(buf), f)) > 0)
        if ((ret = mbedtls_md_update(&ctx, buf, n)) != 0)
            goto cleanup;

    if (ferror(f) != 0)
        ret = MBEDTLS_ERR_MD_FILE_IO_ERROR;
    else
        ret = mbedtls_md_finish(&ctx, output);

cleanup:
    mbedtls_platform_zeroize(buf, sizeof(buf));
    fclose(f);
    mbedtls_md_free(&ctx);

    return (ret);
}
#endif /* MBEDTLS_FS_IO */

int mbedtls_md_hmac_starts(mbedtls_md_context_t* ctx, const unsigned char* key, size_t keylen) {
    int ret = MBEDTLS_ERR_ERROR_CORRUPTION_DETECTED;
    unsigned char sum[MBEDTLS_MD_MAX_SIZE];
    unsigned char *ipad, *opad;
    size_t i;

    if (ctx == NULL || ctx->md_info == NULL || ctx->hmac_ctx == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    if (keylen > (size_t)ctx->md_info->block_size) {
        if ((ret = mbedtls_md_starts(ctx)) != 0)
            goto cleanup;
        if ((ret = mbedtls_md_update(ctx, key, keylen)) != 0)
            goto cleanup;
        if ((ret = mbedtls_md_finish(ctx, sum)) != 0)
            goto cleanup;

        keylen = ctx->md_info->size;
        key = sum;
    }

    ipad = (unsigned char*)ctx->hmac_ctx;
    opad = (unsigned char*)ctx->hmac_ctx + ctx->md_info->block_size;

    memset(ipad, 0x36, ctx->md_info->block_size);
    memset(opad, 0x5C, ctx->md_info->block_size);

    for (i = 0; i < keylen; i++) {
        ipad[i] = (unsigned char)(ipad[i] ^ key[i]);
        opad[i] = (unsigned char)(opad[i] ^ key[i]);
    }

    if ((ret = mbedtls_md_starts(ctx)) != 0)
        goto cleanup;
    if ((ret = mbedtls_md_update(ctx, ipad, ctx->md_info->block_size)) != 0)
        goto cleanup;

cleanup:
    mbedtls_platform_zeroize(sum, sizeof(sum));

    return (ret);
}

int mbedtls_md_hmac_update(mbedtls_md_context_t* ctx, const unsigned char* input, size_t ilen) {
    if (ctx == NULL || ctx->md_info == NULL || ctx->hmac_ctx == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    return (mbedtls_md_update(ctx, input, ilen));
}

int mbedtls_md_hmac_finish(mbedtls_md_context_t* ctx, unsigned char* output) {
    int ret = MBEDTLS_ERR_ERROR_CORRUPTION_DETECTED;
    unsigned char tmp[MBEDTLS_MD_MAX_SIZE];
    unsigned char* opad;

    if (ctx == NULL || ctx->md_info == NULL || ctx->hmac_ctx == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    opad = (unsigned char*)ctx->hmac_ctx + ctx->md_info->block_size;

    if ((ret = mbedtls_md_finish(ctx, tmp)) != 0)
        return (ret);
    if ((ret = mbedtls_md_starts(ctx)) != 0)
        return (ret);
    if ((ret = mbedtls_md_update(ctx, opad, ctx->md_info->block_size)) != 0)
        return (ret);
    if ((ret = mbedtls_md_update(ctx, tmp, ctx->md_info->size)) != 0)
        return (ret);
    return (mbedtls_md_finish(ctx, output));
}

int mbedtls_md_hmac_reset(mbedtls_md_context_t* ctx) {
    int ret = MBEDTLS_ERR_ERROR_CORRUPTION_DETECTED;
    unsigned char* ipad;

    if (ctx == NULL || ctx->md_info == NULL || ctx->hmac_ctx == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    ipad = (unsigned char*)ctx->hmac_ctx;

    if ((ret = mbedtls_md_starts(ctx)) != 0)
        return (ret);
    return (mbedtls_md_update(ctx, ipad, ctx->md_info->block_size));
}

int mbedtls_md_hmac(const mbedtls_md_info_t* md_info, const unsigned char* key, size_t keylen,
    const unsigned char* input, size_t ilen, unsigned char* output) {
    mbedtls_md_context_t ctx;
    int ret = MBEDTLS_ERR_ERROR_CORRUPTION_DETECTED;

    if (md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    mbedtls_md_init(&ctx);

    if ((ret = mbedtls_md_setup(&ctx, md_info, 1)) != 0)
        goto cleanup;

    if ((ret = mbedtls_md_hmac_starts(&ctx, key, keylen)) != 0)
        goto cleanup;
    if ((ret = mbedtls_md_hmac_update(&ctx, input, ilen)) != 0)
        goto cleanup;
    if ((ret = mbedtls_md_hmac_finish(&ctx, output)) != 0)
        goto cleanup;

cleanup:
    mbedtls_md_free(&ctx);

    return (ret);
}

int mbedtls_md_process(mbedtls_md_context_t* ctx, const unsigned char* data) {
    if (ctx == NULL || ctx->md_info == NULL)
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);

    switch (ctx->md_info->type) {
#if defined(MBEDTLS_MD5_C)
    case MBEDTLS_MD_MD5:
        return (mbedtls_internal_md5_process(ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_RIPEMD160_C)
    case MBEDTLS_MD_RIPEMD160:
        return (mbedtls_internal_ripemd160_process(ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_SHA1_C)
    case MBEDTLS_MD_SHA1:
        return (mbedtls_internal_sha1_process(ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_SHA224_C)
    case MBEDTLS_MD_SHA224:
        return (mbedtls_internal_sha256_process((mbedtls_sha256_context*)ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_SHA256_C)
    case MBEDTLS_MD_SHA256:
        return (mbedtls_internal_sha256_process((mbedtls_sha256_context*)ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_SHA384_C)
    case MBEDTLS_MD_SHA384:
        return (mbedtls_internal_sha512_process(ctx->md_ctx, data));
#endif
#if defined(MBEDTLS_SHA512_C)
    case MBEDTLS_MD_SHA512:
        return (mbedtls_internal_sha512_process(ctx->md_ctx, data));
#endif
    default:
        return (MBEDTLS_ERR_MD_BAD_INPUT_DATA);
    }
}

unsigned char mbedtls_md_get_size(const mbedtls_md_info_t* md_info) {
    if (md_info == NULL)
        return (0);

    return md_info->size;
}

mbedtls_md_type_t mbedtls_md_get_type(const mbedtls_md_info_t* md_info) {
    if (md_info == NULL)
        return (MBEDTLS_MD_NONE);

    return md_info->type;
}

const char* mbedtls_md_get_name(const mbedtls_md_info_t* md_info) {
    if (md_info == NULL)
        return (NULL);

    return md_info->name;
}

#endif /* MBEDTLS_MD_C */
