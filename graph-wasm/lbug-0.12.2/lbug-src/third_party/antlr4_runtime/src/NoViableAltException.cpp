/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "Parser.h"

#include "NoViableAltException.h"

using namespace antlr4;

namespace {

// Create a normal shared pointer if the configurations are to be deleted. If not, then
// the shared pointer is created with a deleter that does nothing.
Ref<atn::ATNConfigSet> buildConfigsRef(atn::ATNConfigSet *configs, bool deleteConfigs) {
  if (deleteConfigs) {
    return Ref<atn::ATNConfigSet>(configs);
  } else {
    return Ref<atn::ATNConfigSet>(configs, [](atn::ATNConfigSet *){});
  }
}

}

NoViableAltException::NoViableAltException(Parser *recognizer)
  : NoViableAltException(recognizer, recognizer->getTokenStream(), recognizer->getCurrentToken(),
                         recognizer->getCurrentToken(), nullptr, recognizer->getContext(), false) {
}

NoViableAltException::NoViableAltException(Parser *recognizer, TokenStream *input,Token *startToken,
  Token *offendingToken, atn::ATNConfigSet *deadEndConfigs, ParserRuleContext *ctx, bool deleteConfigs)
  : RecognitionException("No viable alternative", recognizer, input, ctx, offendingToken),
    _deadEndConfigs(buildConfigsRef(deadEndConfigs, deleteConfigs)), _startToken(startToken) {
}

NoViableAltException::~NoViableAltException() {
}

Token* NoViableAltException::getStartToken() const {
  return _startToken;
}

atn::ATNConfigSet* NoViableAltException::getDeadEndConfigs() const {
  return _deadEndConfigs.get();
}
