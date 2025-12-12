/*
 * bitpackinghelpers.h
 *
 *  Created on: Jul 11, 2012
 *      Author: lemire
 */

#ifndef BITPACKINGHELPERS_H_
#define BITPACKINGHELPERS_H_

#include "bitpacking.h"

namespace FastPForLib {

// Note that this only packs 8 values
inline void fastunpack_quarter(const uint8_t *__restrict in, uint8_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastunpack0(in, out);
    break;
  case 1:
    __fastunpack1(in, out);
    break;
  case 2:
    __fastunpack2(in, out);
    break;
  case 3:
    __fastunpack3(in, out);
    break;
  case 4:
    __fastunpack4(in, out);
    break;
  case 5:
    __fastunpack5(in, out);
    break;
  case 6:
    __fastunpack6(in, out);
    break;
  case 7:
    __fastunpack7(in, out);
    break;
  case 8:
    __fastunpack8(in, out);
    break;
  default:
    throw std::logic_error("Invalid bit width for bitpacking");
  }
}

// Note that this only packs 8 values
inline void fastpack_quarter(const uint8_t *__restrict in, uint8_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastpack0(in, out);
    break;
  case 1:
    __fastpack1(in, out);
    break;
  case 2:
    __fastpack2(in, out);
    break;
  case 3:
    __fastpack3(in, out);
    break;
  case 4:
    __fastpack4(in, out);
    break;
  case 5:
    __fastpack5(in, out);
    break;
  case 6:
    __fastpack6(in, out);
    break;
  case 7:
    __fastpack7(in, out);
    break;
  case 8:
    __fastpack8(in, out);
    break;
  default:
  	throw std::logic_error("Invalid bit width for bitpacking");
  }
}

// Note that this only packs 16 values
inline void fastunpack_half(const uint16_t *__restrict in, uint16_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastunpack0(in, out);
    break;
  case 1:
    __fastunpack1(in, out);
    break;
  case 2:
    __fastunpack2(in, out);
    break;
  case 3:
    __fastunpack3(in, out);
    break;
  case 4:
    __fastunpack4(in, out);
    break;
  case 5:
    __fastunpack5(in, out);
    break;
  case 6:
    __fastunpack6(in, out);
    break;
  case 7:
    __fastunpack7(in, out);
    break;
  case 8:
    __fastunpack8(in, out);
    break;
  case 9:
    __fastunpack9(in, out);
    break;
  case 10:
    __fastunpack10(in, out);
    break;
  case 11:
    __fastunpack11(in, out);
    break;
  case 12:
    __fastunpack12(in, out);
    break;
  case 13:
    __fastunpack13(in, out);
    break;
  case 14:
    __fastunpack14(in, out);
  break;
  case 15:
    __fastunpack15(in, out);
    break;
  case 16:
    __fastunpack16(in, out);
    break;
  default:
    throw std::logic_error("Invalid bit width for bitpacking");
  }
}

// Note that this only packs 16 values
inline void fastpack_half(const uint16_t *__restrict in, uint16_t *__restrict out, const uint32_t bit) {
    // Could have used function pointers instead of switch.
    // Switch calls do offer the compiler more opportunities for optimization in
    // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastpack0(in, out);
    break;
  case 1:
    __fastpack1(in, out);
    break;
  case 2:
    __fastpack2(in, out);
    break;
  case 3:
    __fastpack3(in, out);
    break;
  case 4:
    __fastpack4(in, out);
    break;
  case 5:
    __fastpack5(in, out);
    break;
  case 6:
    __fastpack6(in, out);
    break;
  case 7:
    __fastpack7(in, out);
    break;
  case 8:
    __fastpack8(in, out);
    break;
  case 9:
    __fastpack9(in, out);
    break;
  case 10:
    __fastpack10(in, out);
    break;
  case 11:
    __fastpack11(in, out);
    break;
  case 12:
    __fastpack12(in, out);
    break;
  case 13:
    __fastpack13(in, out);
    break;
  case 14:
    __fastpack14(in, out);
    break;
  case 15:
    __fastpack15(in, out);
    break;
  case 16:
    __fastpack16(in, out);
    break;
  default:
    throw std::logic_error("Invalid bit width for bitpacking");
  }
}

inline void fastunpack(const uint8_t *__restrict in, uint8_t *__restrict out, const uint32_t bit) {
  for (uint8_t i = 0; i < 4; i++) {
    fastunpack_quarter(in + (i*bit), out+(i*8), bit);
  }
}

inline void fastunpack(const uint16_t *__restrict in,
        		    uint16_t *__restrict out, const uint32_t bit) {
  fastunpack_half(in, out, bit);
  fastunpack_half(in + bit, out+16, bit);
}


inline void fastunpack(const uint32_t *__restrict in,
                       uint32_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastunpack0(in, out);
    break;
  case 1:
    __fastunpack1(in, out);
    break;
  case 2:
    __fastunpack2(in, out);
    break;
  case 3:
    __fastunpack3(in, out);
    break;
  case 4:
    __fastunpack4(in, out);
    break;
  case 5:
    __fastunpack5(in, out);
    break;
  case 6:
    __fastunpack6(in, out);
    break;
  case 7:
    __fastunpack7(in, out);
    break;
  case 8:
    __fastunpack8(in, out);
    break;
  case 9:
    __fastunpack9(in, out);
    break;
  case 10:
    __fastunpack10(in, out);
    break;
  case 11:
    __fastunpack11(in, out);
    break;
  case 12:
    __fastunpack12(in, out);
    break;
  case 13:
    __fastunpack13(in, out);
    break;
  case 14:
    __fastunpack14(in, out);
    break;
  case 15:
    __fastunpack15(in, out);
    break;
  case 16:
    __fastunpack16(in, out);
    break;
  case 17:
    __fastunpack17(in, out);
    break;
  case 18:
    __fastunpack18(in, out);
    break;
  case 19:
    __fastunpack19(in, out);
    break;
  case 20:
    __fastunpack20(in, out);
    break;
  case 21:
    __fastunpack21(in, out);
    break;
  case 22:
    __fastunpack22(in, out);
    break;
  case 23:
    __fastunpack23(in, out);
    break;
  case 24:
    __fastunpack24(in, out);
    break;
  case 25:
    __fastunpack25(in, out);
    break;
  case 26:
    __fastunpack26(in, out);
    break;
  case 27:
    __fastunpack27(in, out);
    break;
  case 28:
    __fastunpack28(in, out);
    break;
  case 29:
    __fastunpack29(in, out);
    break;
  case 30:
    __fastunpack30(in, out);
    break;
  case 31:
    __fastunpack31(in, out);
    break;
  case 32:
    __fastunpack32(in, out);
    break;
  default:
    break;
  }
}

inline void fastunpack(const uint32_t *__restrict in,
                       uint64_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastunpack0(in, out);
    break;
  case 1:
    __fastunpack1(in, out);
    break;
  case 2:
    __fastunpack2(in, out);
    break;
  case 3:
    __fastunpack3(in, out);
    break;
  case 4:
    __fastunpack4(in, out);
    break;
  case 5:
    __fastunpack5(in, out);
    break;
  case 6:
    __fastunpack6(in, out);
    break;
  case 7:
    __fastunpack7(in, out);
    break;
  case 8:
    __fastunpack8(in, out);
    break;
  case 9:
    __fastunpack9(in, out);
    break;
  case 10:
    __fastunpack10(in, out);
    break;
  case 11:
    __fastunpack11(in, out);
    break;
  case 12:
    __fastunpack12(in, out);
    break;
  case 13:
    __fastunpack13(in, out);
    break;
  case 14:
    __fastunpack14(in, out);
    break;
  case 15:
    __fastunpack15(in, out);
    break;
  case 16:
    __fastunpack16(in, out);
    break;
  case 17:
    __fastunpack17(in, out);
    break;
  case 18:
    __fastunpack18(in, out);
    break;
  case 19:
    __fastunpack19(in, out);
    break;
  case 20:
    __fastunpack20(in, out);
    break;
  case 21:
    __fastunpack21(in, out);
    break;
  case 22:
    __fastunpack22(in, out);
    break;
  case 23:
    __fastunpack23(in, out);
    break;
  case 24:
    __fastunpack24(in, out);
    break;
  case 25:
    __fastunpack25(in, out);
    break;
  case 26:
    __fastunpack26(in, out);
    break;
  case 27:
    __fastunpack27(in, out);
    break;
  case 28:
    __fastunpack28(in, out);
    break;
  case 29:
    __fastunpack29(in, out);
    break;
  case 30:
    __fastunpack30(in, out);
    break;
  case 31:
    __fastunpack31(in, out);
    break;
  case 32:
    __fastunpack32(in, out);
    break;
  case 33:
    __fastunpack33(in, out);
    break;
  case 34:
    __fastunpack34(in, out);
    break;
  case 35:
    __fastunpack35(in, out);
    break;
  case 36:
    __fastunpack36(in, out);
    break;
  case 37:
    __fastunpack37(in, out);
    break;
  case 38:
    __fastunpack38(in, out);
    break;
  case 39:
    __fastunpack39(in, out);
    break;
  case 40:
    __fastunpack40(in, out);
    break;
  case 41:
    __fastunpack41(in, out);
    break;
  case 42:
    __fastunpack42(in, out);
    break;
  case 43:
    __fastunpack43(in, out);
    break;
  case 44:
    __fastunpack44(in, out);
    break;
  case 45:
    __fastunpack45(in, out);
    break;
  case 46:
    __fastunpack46(in, out);
    break;
  case 47:
    __fastunpack47(in, out);
    break;
  case 48:
    __fastunpack48(in, out);
    break;
  case 49:
    __fastunpack49(in, out);
    break;
  case 50:
    __fastunpack50(in, out);
    break;
  case 51:
    __fastunpack51(in, out);
    break;
  case 52:
    __fastunpack52(in, out);
    break;
  case 53:
    __fastunpack53(in, out);
    break;
  case 54:
    __fastunpack54(in, out);
    break;
  case 55:
    __fastunpack55(in, out);
    break;
  case 56:
    __fastunpack56(in, out);
    break;
  case 57:
    __fastunpack57(in, out);
    break;
  case 58:
    __fastunpack58(in, out);
    break;
  case 59:
    __fastunpack59(in, out);
    break;
  case 60:
    __fastunpack60(in, out);
    break;
  case 61:
    __fastunpack61(in, out);
    break;
  case 62:
    __fastunpack62(in, out);
    break;
  case 63:
    __fastunpack63(in, out);
    break;
  case 64:
    __fastunpack64(in, out);
    break;
  default:
    break;
  }
}

inline void fastpack(const uint8_t *__restrict in, uint8_t *__restrict out, const uint32_t bit) {
  for (uint8_t i = 0; i < 4; i++) {
    fastpack_quarter(in+(i*8), out + (i*bit), bit);
  }
}

// Compress all 32 values
inline void fastpack(const uint16_t *__restrict in,
                     uint16_t *__restrict out, const uint32_t bit) {
  fastpack_half(in, out, bit);
  fastpack_half(in+16, out + bit, bit);
}

inline void fastpack(const uint32_t *__restrict in,
                     uint32_t *__restrict out, const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastpack0(in, out);
    break;
  case 1:
    __fastpack1(in, out);
    break;
  case 2:
    __fastpack2(in, out);
    break;
  case 3:
    __fastpack3(in, out);
    break;
  case 4:
    __fastpack4(in, out);
    break;
  case 5:
    __fastpack5(in, out);
    break;
  case 6:
    __fastpack6(in, out);
    break;
  case 7:
    __fastpack7(in, out);
    break;
  case 8:
    __fastpack8(in, out);
    break;
  case 9:
    __fastpack9(in, out);
    break;
  case 10:
    __fastpack10(in, out);
    break;
  case 11:
    __fastpack11(in, out);
    break;
  case 12:
    __fastpack12(in, out);
    break;
  case 13:
    __fastpack13(in, out);
    break;
  case 14:
    __fastpack14(in, out);
    break;
  case 15:
    __fastpack15(in, out);
    break;
  case 16:
    __fastpack16(in, out);
    break;
  case 17:
    __fastpack17(in, out);
    break;
  case 18:
    __fastpack18(in, out);
    break;
  case 19:
    __fastpack19(in, out);
    break;
  case 20:
    __fastpack20(in, out);
    break;
  case 21:
    __fastpack21(in, out);
    break;
  case 22:
    __fastpack22(in, out);
    break;
  case 23:
    __fastpack23(in, out);
    break;
  case 24:
    __fastpack24(in, out);
    break;
  case 25:
    __fastpack25(in, out);
    break;
  case 26:
    __fastpack26(in, out);
    break;
  case 27:
    __fastpack27(in, out);
    break;
  case 28:
    __fastpack28(in, out);
    break;
  case 29:
    __fastpack29(in, out);
    break;
  case 30:
    __fastpack30(in, out);
    break;
  case 31:
    __fastpack31(in, out);
    break;
  case 32:
    __fastpack32(in, out);
    break;
  default:
    break;
  }
}

inline void fastpack(const uint64_t *__restrict in,
                     uint32_t *__restrict out, const uint32_t bit) {
  switch (bit) {
  case 0:
    __fastpack0(in, out);
    break;
  case 1:
    __fastpack1(in, out);
    break;
  case 2:
    __fastpack2(in, out);
    break;
  case 3:
    __fastpack3(in, out);
    break;
  case 4:
    __fastpack4(in, out);
    break;
  case 5:
    __fastpack5(in, out);
    break;
  case 6:
    __fastpack6(in, out);
    break;
  case 7:
    __fastpack7(in, out);
    break;
  case 8:
    __fastpack8(in, out);
    break;
  case 9:
    __fastpack9(in, out);
    break;
  case 10:
    __fastpack10(in, out);
    break;
  case 11:
    __fastpack11(in, out);
    break;
  case 12:
    __fastpack12(in, out);
    break;
  case 13:
    __fastpack13(in, out);
    break;
  case 14:
    __fastpack14(in, out);
    break;
  case 15:
    __fastpack15(in, out);
    break;
  case 16:
    __fastpack16(in, out);
    break;
  case 17:
    __fastpack17(in, out);
    break;
  case 18:
    __fastpack18(in, out);
    break;
  case 19:
    __fastpack19(in, out);
    break;
  case 20:
    __fastpack20(in, out);
    break;
  case 21:
    __fastpack21(in, out);
    break;
  case 22:
    __fastpack22(in, out);
    break;
  case 23:
    __fastpack23(in, out);
    break;
  case 24:
    __fastpack24(in, out);
    break;
  case 25:
    __fastpack25(in, out);
    break;
  case 26:
    __fastpack26(in, out);
    break;
  case 27:
    __fastpack27(in, out);
    break;
  case 28:
    __fastpack28(in, out);
    break;
  case 29:
    __fastpack29(in, out);
    break;
  case 30:
    __fastpack30(in, out);
    break;
  case 31:
    __fastpack31(in, out);
    break;
  case 32:
    __fastpack32(in, out);
    break;
  case 33:
    __fastpack33(in, out);
    break;
  case 34:
    __fastpack34(in, out);
    break;
  case 35:
    __fastpack35(in, out);
    break;
  case 36:
    __fastpack36(in, out);
    break;
  case 37:
    __fastpack37(in, out);
    break;
  case 38:
    __fastpack38(in, out);
    break;
  case 39:
    __fastpack39(in, out);
    break;
  case 40:
    __fastpack40(in, out);
    break;
  case 41:
    __fastpack41(in, out);
    break;
  case 42:
    __fastpack42(in, out);
    break;
  case 43:
    __fastpack43(in, out);
    break;
  case 44:
    __fastpack44(in, out);
    break;
  case 45:
    __fastpack45(in, out);
    break;
  case 46:
    __fastpack46(in, out);
    break;
  case 47:
    __fastpack47(in, out);
    break;
  case 48:
    __fastpack48(in, out);
    break;
  case 49:
    __fastpack49(in, out);
    break;
  case 50:
    __fastpack50(in, out);
    break;
  case 51:
    __fastpack51(in, out);
    break;
  case 52:
    __fastpack52(in, out);
    break;
  case 53:
    __fastpack53(in, out);
    break;
  case 54:
    __fastpack54(in, out);
    break;
  case 55:
    __fastpack55(in, out);
    break;
  case 56:
    __fastpack56(in, out);
    break;
  case 57:
    __fastpack57(in, out);
    break;
  case 58:
    __fastpack58(in, out);
    break;
  case 59:
    __fastpack59(in, out);
    break;
  case 60:
    __fastpack60(in, out);
    break;
  case 61:
    __fastpack61(in, out);
    break;
  case 62:
    __fastpack62(in, out);
    break;
  case 63:
    __fastpack63(in, out);
    break;
  case 64:
    __fastpack64(in, out);
    break;
  default:
    break;
  }
}

/*assumes that integers fit in the prescribed number of bits*/
inline void fastpackwithoutmask(const uint32_t *__restrict in,
                                uint32_t *__restrict out,
                                const uint32_t bit) {
  // Could have used function pointers instead of switch.
  // Switch calls do offer the compiler more opportunities for optimization in
  // theory. In this case, it makes no difference with a good compiler.
  switch (bit) {
  case 0:
    __fastpackwithoutmask0(in, out);
    break;
  case 1:
    __fastpackwithoutmask1(in, out);
    break;
  case 2:
    __fastpackwithoutmask2(in, out);
    break;
  case 3:
    __fastpackwithoutmask3(in, out);
    break;
  case 4:
    __fastpackwithoutmask4(in, out);
    break;
  case 5:
    __fastpackwithoutmask5(in, out);
    break;
  case 6:
    __fastpackwithoutmask6(in, out);
    break;
  case 7:
    __fastpackwithoutmask7(in, out);
    break;
  case 8:
    __fastpackwithoutmask8(in, out);
    break;
  case 9:
    __fastpackwithoutmask9(in, out);
    break;
  case 10:
    __fastpackwithoutmask10(in, out);
    break;
  case 11:
    __fastpackwithoutmask11(in, out);
    break;
  case 12:
    __fastpackwithoutmask12(in, out);
    break;
  case 13:
    __fastpackwithoutmask13(in, out);
    break;
  case 14:
    __fastpackwithoutmask14(in, out);
    break;
  case 15:
    __fastpackwithoutmask15(in, out);
    break;
  case 16:
    __fastpackwithoutmask16(in, out);
    break;
  case 17:
    __fastpackwithoutmask17(in, out);
    break;
  case 18:
    __fastpackwithoutmask18(in, out);
    break;
  case 19:
    __fastpackwithoutmask19(in, out);
    break;
  case 20:
    __fastpackwithoutmask20(in, out);
    break;
  case 21:
    __fastpackwithoutmask21(in, out);
    break;
  case 22:
    __fastpackwithoutmask22(in, out);
    break;
  case 23:
    __fastpackwithoutmask23(in, out);
    break;
  case 24:
    __fastpackwithoutmask24(in, out);
    break;
  case 25:
    __fastpackwithoutmask25(in, out);
    break;
  case 26:
    __fastpackwithoutmask26(in, out);
    break;
  case 27:
    __fastpackwithoutmask27(in, out);
    break;
  case 28:
    __fastpackwithoutmask28(in, out);
    break;
  case 29:
    __fastpackwithoutmask29(in, out);
    break;
  case 30:
    __fastpackwithoutmask30(in, out);
    break;
  case 31:
    __fastpackwithoutmask31(in, out);
    break;
  case 32:
    __fastpackwithoutmask32(in, out);
    break;
  default:
    break;
  }
}

inline void fastpackwithoutmask(const uint64_t *__restrict in,
                                uint32_t *__restrict out,
                                const uint32_t bit) {
  switch (bit) {
  case 0:
    __fastpackwithoutmask0(in, out);
    break;
  case 1:
    __fastpackwithoutmask1(in, out);
    break;
  case 2:
    __fastpackwithoutmask2(in, out);
    break;
  case 3:
    __fastpackwithoutmask3(in, out);
    break;
  case 4:
    __fastpackwithoutmask4(in, out);
    break;
  case 5:
    __fastpackwithoutmask5(in, out);
    break;
  case 6:
    __fastpackwithoutmask6(in, out);
    break;
  case 7:
    __fastpackwithoutmask7(in, out);
    break;
  case 8:
    __fastpackwithoutmask8(in, out);
    break;
  case 9:
    __fastpackwithoutmask9(in, out);
    break;
  case 10:
    __fastpackwithoutmask10(in, out);
    break;
  case 11:
    __fastpackwithoutmask11(in, out);
    break;
  case 12:
    __fastpackwithoutmask12(in, out);
    break;
  case 13:
    __fastpackwithoutmask13(in, out);
    break;
  case 14:
    __fastpackwithoutmask14(in, out);
    break;
  case 15:
    __fastpackwithoutmask15(in, out);
    break;
  case 16:
    __fastpackwithoutmask16(in, out);
    break;
  case 17:
    __fastpackwithoutmask17(in, out);
    break;
  case 18:
    __fastpackwithoutmask18(in, out);
    break;
  case 19:
    __fastpackwithoutmask19(in, out);
    break;
  case 20:
    __fastpackwithoutmask20(in, out);
    break;
  case 21:
    __fastpackwithoutmask21(in, out);
    break;
  case 22:
    __fastpackwithoutmask22(in, out);
    break;
  case 23:
    __fastpackwithoutmask23(in, out);
    break;
  case 24:
    __fastpackwithoutmask24(in, out);
    break;
  case 25:
    __fastpackwithoutmask25(in, out);
    break;
  case 26:
    __fastpackwithoutmask26(in, out);
    break;
  case 27:
    __fastpackwithoutmask27(in, out);
    break;
  case 28:
    __fastpackwithoutmask28(in, out);
    break;
  case 29:
    __fastpackwithoutmask29(in, out);
    break;
  case 30:
    __fastpackwithoutmask30(in, out);
    break;
  case 31:
    __fastpackwithoutmask31(in, out);
    break;
  case 32:
    __fastpackwithoutmask32(in, out);
    break;
  case 33:
    __fastpackwithoutmask33(in, out);
    break;
  case 34:
    __fastpackwithoutmask34(in, out);
    break;
  case 35:
    __fastpackwithoutmask35(in, out);
    break;
  case 36:
    __fastpackwithoutmask36(in, out);
    break;
  case 37:
    __fastpackwithoutmask37(in, out);
    break;
  case 38:
    __fastpackwithoutmask38(in, out);
    break;
  case 39:
    __fastpackwithoutmask39(in, out);
    break;
  case 40:
    __fastpackwithoutmask40(in, out);
    break;
  case 41:
    __fastpackwithoutmask41(in, out);
    break;
  case 42:
    __fastpackwithoutmask42(in, out);
    break;
  case 43:
    __fastpackwithoutmask43(in, out);
    break;
  case 44:
    __fastpackwithoutmask44(in, out);
    break;
  case 45:
    __fastpackwithoutmask45(in, out);
    break;
  case 46:
    __fastpackwithoutmask46(in, out);
    break;
  case 47:
    __fastpackwithoutmask47(in, out);
    break;
  case 48:
    __fastpackwithoutmask48(in, out);
    break;
  case 49:
    __fastpackwithoutmask49(in, out);
    break;
  case 50:
    __fastpackwithoutmask50(in, out);
    break;
  case 51:
    __fastpackwithoutmask51(in, out);
    break;
  case 52:
    __fastpackwithoutmask52(in, out);
    break;
  case 53:
    __fastpackwithoutmask53(in, out);
    break;
  case 54:
    __fastpackwithoutmask54(in, out);
    break;
  case 55:
    __fastpackwithoutmask55(in, out);
    break;
  case 56:
    __fastpackwithoutmask56(in, out);
    break;
  case 57:
    __fastpackwithoutmask57(in, out);
    break;
  case 58:
    __fastpackwithoutmask58(in, out);
    break;
  case 59:
    __fastpackwithoutmask59(in, out);
    break;
  case 60:
    __fastpackwithoutmask60(in, out);
    break;
  case 61:
    __fastpackwithoutmask61(in, out);
    break;
  case 62:
    __fastpackwithoutmask62(in, out);
    break;
  case 63:
    __fastpackwithoutmask63(in, out);
    break;
  case 64:
    __fastpackwithoutmask64(in, out);
    break;
  default:
    break;
  }
}

template <uint32_t BlockSize, typename IntType = uint32_t>
uint32_t *packblockup(const IntType * source, uint32_t *out,
                      const uint32_t bit) {
  for (uint32_t j = 0; j != BlockSize; j += 32) {
    fastpack(source + j, out, bit);
    out += bit;
  }
  return out;
}

template <uint32_t BlockSize, typename IntType = uint32_t>
const uint32_t *unpackblock(const uint32_t *source, IntType *out,
                            const uint32_t bit) {
  for (uint32_t j = 0; j != BlockSize; j += 32) {
    fastunpack(source, out + j, bit);
    source += bit;
  }
  return source;
}

} // namespace FastPForLib

#endif /* BITPACKINGHELPERS_H_ */
