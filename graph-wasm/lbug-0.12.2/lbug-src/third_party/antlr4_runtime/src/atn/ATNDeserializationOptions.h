/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "antlr4-common.h"

namespace antlr4 {
namespace atn {

class ANTLR4CPP_PUBLIC ATNDeserializationOptions final {
public:
  ATNDeserializationOptions()
    : _readOnly(false), _verifyATN(true), _generateRuleBypassTransitions(false) {}

  // TODO: Is this useful? If so we should mark it as explicit, otherwise remove it.
  ATNDeserializationOptions(ATNDeserializationOptions *options);

  ATNDeserializationOptions(const ATNDeserializationOptions&) = default;

  ATNDeserializationOptions& operator=(const ATNDeserializationOptions&) = default;

  static const ATNDeserializationOptions& getDefaultOptions();

  bool isReadOnly() const { return _readOnly; }

  void makeReadOnly();

  bool isVerifyATN() const { return _verifyATN; }

  void setVerifyATN(bool verify);

  bool isGenerateRuleBypassTransitions() const { return _generateRuleBypassTransitions; }

  void setGenerateRuleBypassTransitions(bool generate);

private:
  void throwIfReadOnly() const;

  bool _readOnly;
  bool _verifyATN;
  bool _generateRuleBypassTransitions;
};

} // namespace atn
} // namespace antlr4
