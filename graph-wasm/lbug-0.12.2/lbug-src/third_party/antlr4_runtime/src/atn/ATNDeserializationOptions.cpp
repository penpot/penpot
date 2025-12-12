/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ATNDeserializationOptions.h"
#include "Exceptions.h"

using namespace antlr4;
using namespace antlr4::atn;

ATNDeserializationOptions::ATNDeserializationOptions(ATNDeserializationOptions *options)
    : _readOnly(false), _verifyATN(options->_verifyATN),
      _generateRuleBypassTransitions(options->_generateRuleBypassTransitions) {}

const ATNDeserializationOptions& ATNDeserializationOptions::getDefaultOptions() {
  static const ATNDeserializationOptions* const defaultOptions = new ATNDeserializationOptions();
  return *defaultOptions;
}

void ATNDeserializationOptions::makeReadOnly() {
  _readOnly = true;
}

void ATNDeserializationOptions::setVerifyATN(bool verify) {
  throwIfReadOnly();
  _verifyATN = verify;
}

void ATNDeserializationOptions::setGenerateRuleBypassTransitions(bool generate) {
  throwIfReadOnly();
  _generateRuleBypassTransitions = generate;
}

void ATNDeserializationOptions::throwIfReadOnly() const {
  if (isReadOnly()) {
    throw IllegalStateException("ATNDeserializationOptions is read only.");
  }
}
