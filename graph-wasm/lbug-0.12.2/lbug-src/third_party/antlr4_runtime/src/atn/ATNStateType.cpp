#include "atn/ATNStateType.h"

std::string antlr4::atn::atnStateTypeName(ATNStateType atnStateType) {
  switch (atnStateType) {
    case ATNStateType::INVALID:
      return "INVALID";
    case ATNStateType::BASIC:
      return "BASIC";
    case ATNStateType::RULE_START:
      return "RULE_START";
    case ATNStateType::BLOCK_START:
      return "BLOCK_START";
    case ATNStateType::PLUS_BLOCK_START:
      return "PLUS_BLOCK_START";
    case ATNStateType::STAR_BLOCK_START:
      return "STAR_BLOCK_START";
    case ATNStateType::TOKEN_START:
      return "TOKEN_START";
    case ATNStateType::RULE_STOP:
      return "RULE_STOP";
    case ATNStateType::BLOCK_END:
      return "BLOCK_END";
    case ATNStateType::STAR_LOOP_BACK:
      return "STAR_LOOP_BACK";
    case ATNStateType::STAR_LOOP_ENTRY:
      return "STAR_LOOP_ENTRY";
    case ATNStateType::PLUS_LOOP_BACK:
      return "PLUS_LOOP_BACK";
    case ATNStateType::LOOP_END:
      return "LOOP_END";
  }
  return "UNKNOWN";
}
