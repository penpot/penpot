#pragma once

/*
** This code taken from the SQLite test library (can be found at
** https://www.sqlite.org/sqllogictest/doc/trunk/about.wiki).
** Originally found on the internet. The original header comment follows this comment.
** The code has been refactored, but the algorithm stays the same.
*/
/*
 * This code implements the MD5 message-digest algorithm.
 * The algorithm is due to Ron Rivest.  This code was
 * written by Colin Plumb in 1993, no copyright is claimed.
 * This code is in the public domain; do with it what you wish.
 *
 * Equivalent code is available from RSA Data Security, Inc.
 * This code has been tested against that, and is equivalent,
 * except that you don't need to include two pages of legalese
 * with every copy.
 *
 * To compute the message digest of a chunk of bytes, declare an
 * MD5Context structure, pass it to MD5Init, call MD5Update as
 * needed on buffers full of bytes, and then call MD5Final, which
 * will fill a supplied 16-byte array with the digest.
 */

#include <cstdint>

namespace lbug {
namespace common {

class MD5 {
    struct Context {
        int isInit;
        uint32_t buf[4];
        uint32_t bits[2];
        unsigned char in[64];
    };
    typedef struct Context MD5Context;

    // Status of an MD5 hash. - changed from static global variables to private members
    MD5Context ctx{};
    int isInit = 0;
    char zResult[34] = "";

    // Note: this code is harmless on little-endian machines.
    void byteReverse(unsigned char* buf, unsigned longs);

    // The core of the MD5 algorithm, this alters an existing MD5 hash to
    // reflect the addition of 16 longwords of new data.  MD5Update blocks
    // the data and converts bytes into longwords for this routine.
    void MD5Transform(uint32_t buf[4], const uint32_t in[16]);

    // Start MD5 accumulation.  Set bit count to 0 and buffer to mysterious
    // initialization constants.
    void MD5Init();

    // Update context to reflect the concatenation of another buffer full
    // of bytes.
    void MD5Update(const unsigned char* buf, unsigned int len);

    // Final wrapup - pad to 64-byte boundary with the bit pattern
    // 1 0* (64-bit count of bits processed, MSB-first)
    void MD5Final(unsigned char digest[16]);

    // Convert a digest into base-16.  digest should be declared as
    // "unsigned char digest[16]" in the calling function.  The MD5
    // digest is stored in the first 16 bytes.  zBuf should
    // be "char zBuf[33]".
    static void DigestToBase16(const unsigned char* digest, char* zBuf);

public:
    // Add additional text to the current MD5 hash.
    // note: original name changed from md5_add
    void addToMD5(const char* z, uint32_t len) {
        if (!isInit) {
            MD5Init();
            isInit = 1;
        }
        MD5Update((unsigned char*)z, len);
    }

    // Compute the final signature.  Reset the hash generator in preparation
    // for the next round.
    // note: original name changed from md5_finish
    const char* finishMD5() {
        if (isInit) {
            unsigned char digest[16];
            MD5Final(digest);
            isInit = 0;
            DigestToBase16(digest, zResult);
        }
        return zResult;
    }
};

} // namespace common
} // namespace lbug
