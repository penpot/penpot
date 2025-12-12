/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ATNDeserializationOptions.h"
#include "tree/pattern/ParseTreePatternMatcher.h"
#include "dfa/DFA.h"
#include "ParserRuleContext.h"
#include "tree/TerminalNode.h"
#include "tree/ErrorNodeImpl.h"
#include "Lexer.h"
#include "atn/ParserATNSimulator.h"
#include "misc/IntervalSet.h"
#include "atn/RuleStartState.h"
#include "DefaultErrorStrategy.h"
#include "atn/ATNDeserializer.h"
#include "atn/RuleTransition.h"
#include "atn/ATN.h"
#include "Exceptions.h"
#include "ANTLRErrorListener.h"
#include "tree/pattern/ParseTreePattern.h"
#include "internal/Synchronization.h"

#include "atn/ProfilingATNSimulator.h"
#include "atn/ParseInfo.h"

#include "Parser.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::internal;
using namespace antlrcpp;

namespace {

struct BypassAltsAtnCache final {
  std::shared_mutex mutex;
  /// This field maps from the serialized ATN string to the deserialized <seealso cref="ATN"/> with
  /// bypass alternatives.
  ///
  /// <seealso cref= ATNDeserializationOptions#isGenerateRuleBypassTransitions() </seealso>
  std::map<std::vector<int32_t>, std::unique_ptr<const atn::ATN>, std::less<>> map;
};

BypassAltsAtnCache* getBypassAltsAtnCache() {
  static BypassAltsAtnCache* const instance = new BypassAltsAtnCache();
  return instance;
}

}

Parser::TraceListener::TraceListener(Parser *outerInstance_) : outerInstance(outerInstance_) {
}

Parser::TraceListener::~TraceListener() {
}

void Parser::TraceListener::enterEveryRule(ParserRuleContext *ctx) {
  std::cout << "enter   " << outerInstance->getRuleNames()[ctx->getRuleIndex()]
    << ", LT(1)=" << outerInstance->_input->LT(1)->getText() << std::endl;
}

void Parser::TraceListener::visitTerminal(tree::TerminalNode *node) {
  std::cout << "consume " << node->getSymbol() << " rule "
    << outerInstance->getRuleNames()[outerInstance->getContext()->getRuleIndex()] << std::endl;
}

void Parser::TraceListener::visitErrorNode(tree::ErrorNode * /*node*/) {
}

void Parser::TraceListener::exitEveryRule(ParserRuleContext *ctx) {
  std::cout << "exit    " << outerInstance->getRuleNames()[ctx->getRuleIndex()]
    << ", LT(1)=" << outerInstance->_input->LT(1)->getText() << std::endl;
}

Parser::TrimToSizeListener Parser::TrimToSizeListener::INSTANCE;

Parser::TrimToSizeListener::~TrimToSizeListener() {
}

void Parser::TrimToSizeListener::enterEveryRule(ParserRuleContext * /*ctx*/) {
}

void Parser::TrimToSizeListener::visitTerminal(tree::TerminalNode * /*node*/) {
}

void Parser::TrimToSizeListener::visitErrorNode(tree::ErrorNode * /*node*/) {
}

void Parser::TrimToSizeListener::exitEveryRule(ParserRuleContext * ctx) {
  ctx->children.shrink_to_fit();
}

Parser::Parser(TokenStream *input) {
  InitializeInstanceFields();
  setInputStream(input);
}

Parser::~Parser() {
  _tracker.reset();
  delete _tracer;
}

void Parser::reset() {
  if (getInputStream() != nullptr) {
    getInputStream()->seek(0);
  }
  _errHandler->reset(this); // Watch out, this is not shared_ptr.reset().

  _matchedEOF = false;
  _syntaxErrors = 0;
  setTrace(false);
  _precedenceStack.clear();
  _precedenceStack.push_back(0);
  _ctx = nullptr;
  _tracker.reset();

  atn::ATNSimulator *interpreter = getInterpreter<atn::ParserATNSimulator>();
  if (interpreter != nullptr) {
    interpreter->reset();
  }
}

Token* Parser::match(size_t ttype) {
  Token *t = getCurrentToken();
  if (t->getType() == ttype) {
    if (ttype == EOF) {
      _matchedEOF = true;
    }
    _errHandler->reportMatch(this);
    consume();
  } else {
    t = _errHandler->recoverInline(this);
    if (_buildParseTrees && t->getTokenIndex() == INVALID_INDEX) {
      // we must have conjured up a new token during single token insertion
      // if it's not the current symbol
      _ctx->addChild(createErrorNode(t));
    }
  }
  return t;
}

Token* Parser::matchWildcard() {
  Token *t = getCurrentToken();
  if (t->getType() > 0) {
    _errHandler->reportMatch(this);
    consume();
  } else {
    t = _errHandler->recoverInline(this);
    if (_buildParseTrees && t->getTokenIndex() == INVALID_INDEX) {
      // we must have conjured up a new token during single token insertion
      // if it's not the current symbol
      _ctx->addChild(createErrorNode(t));
    }
  }

  return t;
}

void Parser::setBuildParseTree(bool buildParseTrees) {
  this->_buildParseTrees = buildParseTrees;
}

bool Parser::getBuildParseTree() {
  return _buildParseTrees;
}

void Parser::setTrimParseTree(bool trimParseTrees) {
  if (trimParseTrees) {
    if (getTrimParseTree()) {
      return;
    }
    addParseListener(&TrimToSizeListener::INSTANCE);
  } else {
    removeParseListener(&TrimToSizeListener::INSTANCE);
  }
}

bool Parser::getTrimParseTree() {
  return std::find(getParseListeners().begin(), getParseListeners().end(), &TrimToSizeListener::INSTANCE) != getParseListeners().end();
}

std::vector<tree::ParseTreeListener *> Parser::getParseListeners() {
  return _parseListeners;
}

void Parser::addParseListener(tree::ParseTreeListener *listener) {
  if (!listener) {
    throw NullPointerException("listener");
  }

  this->_parseListeners.push_back(listener);
}

void Parser::removeParseListener(tree::ParseTreeListener *listener) {
  if (!_parseListeners.empty()) {
    auto it = std::find(_parseListeners.begin(), _parseListeners.end(), listener);
    if (it != _parseListeners.end()) {
      _parseListeners.erase(it);
    }
  }
}

void Parser::removeParseListeners() {
  _parseListeners.clear();
}

void Parser::triggerEnterRuleEvent() {
  for (auto *listener : _parseListeners) {
    listener->enterEveryRule(_ctx);
    _ctx->enterRule(listener);
  }
}

void Parser::triggerExitRuleEvent() {
  // reverse order walk of listeners
  for (auto it = _parseListeners.rbegin(); it != _parseListeners.rend(); ++it) {
    _ctx->exitRule(*it);
    (*it)->exitEveryRule(_ctx);
  }
}

size_t Parser::getNumberOfSyntaxErrors() {
  return _syntaxErrors;
}

TokenFactory<CommonToken>* Parser::getTokenFactory() {
  return _input->getTokenSource()->getTokenFactory();
}

const atn::ATN& Parser::getATNWithBypassAlts() {
  auto serializedAtn = getSerializedATN();
  if (serializedAtn.empty()) {
    throw UnsupportedOperationException("The current parser does not support an ATN with bypass alternatives.");
  }
  // XXX: using the entire serialized ATN as key into the map is a big resource waste.
  //      How large can that thing become?
  auto *cache = getBypassAltsAtnCache();
  {
    std::shared_lock<std::shared_mutex> lock(cache->mutex);
    auto existing = cache->map.find(serializedAtn);
    if (existing != cache->map.end()) {
      return *existing->second;
    }
  }

  std::unique_lock<std::shared_mutex> lock(cache->mutex);
  auto existing = cache->map.find(serializedAtn);
  if (existing != cache->map.end()) {
    return *existing->second;
  }
  atn::ATNDeserializationOptions deserializationOptions;
  deserializationOptions.setGenerateRuleBypassTransitions(true);
  atn::ATNDeserializer deserializer(deserializationOptions);
  auto atn = deserializer.deserialize(serializedAtn);
  return *cache->map.insert(std::make_pair(std::vector<int32_t>(serializedAtn.begin(), serializedAtn.end()), std::move(atn))).first->second;
}

tree::pattern::ParseTreePattern Parser::compileParseTreePattern(const std::string &pattern, int patternRuleIndex) {
  if (getTokenStream() != nullptr) {
    TokenSource *tokenSource = getTokenStream()->getTokenSource();
    if (is<Lexer*>(tokenSource)) {
      Lexer *lexer = dynamic_cast<Lexer *>(tokenSource);
      return compileParseTreePattern(pattern, patternRuleIndex, lexer);
    }
  }
  throw UnsupportedOperationException("Parser can't discover a lexer to use");
}

tree::pattern::ParseTreePattern Parser::compileParseTreePattern(const std::string &pattern, int patternRuleIndex,
  Lexer *lexer) {
  tree::pattern::ParseTreePatternMatcher m(lexer, this);
  return m.compile(pattern, patternRuleIndex);
}

Ref<ANTLRErrorStrategy> Parser::getErrorHandler() {
  return _errHandler;
}

void Parser::setErrorHandler(Ref<ANTLRErrorStrategy> const& handler) {
  _errHandler = handler;
}

IntStream* Parser::getInputStream() {
  return getTokenStream();
}

void Parser::setInputStream(IntStream *input) {
  setTokenStream(static_cast<TokenStream*>(input));
}

TokenStream* Parser::getTokenStream() {
  return _input;
}

void Parser::setTokenStream(TokenStream *input) {
  _input = nullptr; // Just a reference we don't own.
  reset();
  _input = input;
}

Token* Parser::getCurrentToken() {
  return _input->LT(1);
}

void Parser::notifyErrorListeners(const std::string &msg) {
  notifyErrorListeners(getCurrentToken(), msg, nullptr);
}

void Parser::notifyErrorListeners(Token *offendingToken, const std::string &msg, std::exception_ptr e) {
  _syntaxErrors++;
  size_t line = offendingToken->getLine();
  size_t charPositionInLine = offendingToken->getCharPositionInLine();

  ProxyErrorListener &listener = getErrorListenerDispatch();
  listener.syntaxError(this, offendingToken, line, charPositionInLine, msg, e);
}

Token* Parser::consume() {
  Token *o = getCurrentToken();
  if (o->getType() != EOF) {
    getInputStream()->consume();
  }

  bool hasListener = _parseListeners.size() > 0 && !_parseListeners.empty();
  if (_buildParseTrees || hasListener) {
    if (_errHandler->inErrorRecoveryMode(this)) {
      tree::ErrorNode *node = createErrorNode(o);
      _ctx->addChild(node);
      if (_parseListeners.size() > 0) {
        for (auto *listener : _parseListeners) {
          listener->visitErrorNode(node);
        }
      }
    } else {
      tree::TerminalNode *node = _ctx->addChild(createTerminalNode(o));
      if (_parseListeners.size() > 0) {
        for (auto *listener : _parseListeners) {
          listener->visitTerminal(node);
        }
      }
    }
  }
  return o;
}

void Parser::addContextToParseTree() {
  // Add current context to parent if we have a parent.
  if (_ctx->parent == nullptr)
    return;

  downCast<ParserRuleContext*>(_ctx->parent)->addChild(_ctx);
}

void Parser::enterRule(ParserRuleContext *localctx, size_t state, size_t /*ruleIndex*/) {
  setState(state);
  _ctx = localctx;
  _ctx->start = _input->LT(1);
  if (_buildParseTrees) {
    addContextToParseTree();
  }
  if (_parseListeners.size() > 0) {
    triggerEnterRuleEvent();
  }
}

void Parser::exitRule() {
  if (_matchedEOF) {
    // if we have matched EOF, it cannot consume past EOF so we use LT(1) here
    _ctx->stop = _input->LT(1); // LT(1) will be end of file
  } else {
    _ctx->stop = _input->LT(-1); // stop node is what we just matched
  }

  // trigger event on ctx, before it reverts to parent
  if (_parseListeners.size() > 0) {
    triggerExitRuleEvent();
  }
  setState(_ctx->invokingState);
  _ctx = downCast<ParserRuleContext*>(_ctx->parent);
}

void Parser::enterOuterAlt(ParserRuleContext *localctx, size_t altNum) {
  localctx->setAltNumber(altNum);

  // if we have new localctx, make sure we replace existing ctx
  // that is previous child of parse tree
  if (_buildParseTrees && _ctx != localctx) {
    if (_ctx->parent != nullptr) {
      ParserRuleContext *parent = downCast<ParserRuleContext*>(_ctx->parent);
      parent->removeLastChild();
      parent->addChild(localctx);
    }
  }
  _ctx = localctx;
}

int Parser::getPrecedence() const {
  if (_precedenceStack.empty()) {
    return -1;
  }

  return _precedenceStack.back();
}

void Parser::enterRecursionRule(ParserRuleContext *localctx, size_t ruleIndex) {
  enterRecursionRule(localctx, getATN().ruleToStartState[ruleIndex]->stateNumber, ruleIndex, 0);
}

void Parser::enterRecursionRule(ParserRuleContext *localctx, size_t state, size_t /*ruleIndex*/, int precedence) {
  setState(state);
  _precedenceStack.push_back(precedence);
  _ctx = localctx;
  _ctx->start = _input->LT(1);
  if (!_parseListeners.empty()) {
    triggerEnterRuleEvent(); // simulates rule entry for left-recursive rules
  }
}

void Parser::pushNewRecursionContext(ParserRuleContext *localctx, size_t state, size_t /*ruleIndex*/) {
  ParserRuleContext *previous = _ctx;
  previous->parent = localctx;
  previous->invokingState = state;
  previous->stop = _input->LT(-1);

  _ctx = localctx;
  _ctx->start = previous->start;
  if (_buildParseTrees) {
    _ctx->addChild(previous);
  }

  if (_parseListeners.size() > 0) {
    triggerEnterRuleEvent(); // simulates rule entry for left-recursive rules
  }
}

void Parser::unrollRecursionContexts(ParserRuleContext *parentctx) {
  _precedenceStack.pop_back();
  _ctx->stop = _input->LT(-1);
  ParserRuleContext *retctx = _ctx; // save current ctx (return value)

  // unroll so ctx is as it was before call to recursive method
  if (_parseListeners.size() > 0) {
    while (_ctx != parentctx) {
      triggerExitRuleEvent();
      _ctx = downCast<ParserRuleContext*>(_ctx->parent);
    }
  } else {
    _ctx = parentctx;
  }

  // hook into tree
  retctx->parent = parentctx;

  if (_buildParseTrees && parentctx != nullptr) {
    // add return ctx into invoking rule's tree
    parentctx->addChild(retctx);
  }
}

ParserRuleContext* Parser::getInvokingContext(size_t ruleIndex) {
  ParserRuleContext *p = _ctx;
  while (p) {
    if (p->getRuleIndex() == ruleIndex) {
      return p;
    }
    if (p->parent == nullptr)
      break;
    p = downCast<ParserRuleContext*>(p->parent);
  }
  return nullptr;
}

ParserRuleContext* Parser::getContext() {
  return _ctx;
}

void Parser::setContext(ParserRuleContext *ctx) {
  _ctx = ctx;
}

bool Parser::precpred(RuleContext * /*localctx*/, int precedence) {
  return precedence >= _precedenceStack.back();
}

bool Parser::inContext(const std::string &/*context*/) {
  // TODO: useful in parser?
  return false;
}

bool Parser::isExpectedToken(size_t symbol) {
  const atn::ATN &atn = getInterpreter<atn::ParserATNSimulator>()->atn;
  ParserRuleContext *ctx = _ctx;
  atn::ATNState *s = atn.states[getState()];
  misc::IntervalSet following = atn.nextTokens(s);

  if (following.contains(symbol)) {
    return true;
  }

  if (!following.contains(Token::EPSILON)) {
    return false;
  }

  while (ctx && ctx->invokingState != ATNState::INVALID_STATE_NUMBER && following.contains(Token::EPSILON)) {
    atn::ATNState *invokingState = atn.states[ctx->invokingState];
    const atn::RuleTransition *rt = static_cast<const atn::RuleTransition*>(invokingState->transitions[0].get());
    following = atn.nextTokens(rt->followState);
    if (following.contains(symbol)) {
      return true;
    }

    ctx = downCast<ParserRuleContext*>(ctx->parent);
  }

  if (following.contains(Token::EPSILON) && symbol == EOF) {
    return true;
  }

  return false;
}

bool Parser::isMatchedEOF() const {
  return _matchedEOF;
}

misc::IntervalSet Parser::getExpectedTokens() {
  return getATN().getExpectedTokens(getState(), getContext());
}

misc::IntervalSet Parser::getExpectedTokensWithinCurrentRule() {
  const atn::ATN &atn = getInterpreter<atn::ParserATNSimulator>()->atn;
  atn::ATNState *s = atn.states[getState()];
  return atn.nextTokens(s);
}

size_t Parser::getRuleIndex(const std::string &ruleName) {
  const std::map<std::string, size_t> &m = getRuleIndexMap();
  auto iterator = m.find(ruleName);
  if (iterator == m.end()) {
    return INVALID_INDEX;
  }
  return iterator->second;
}

ParserRuleContext* Parser::getRuleContext() {
  return _ctx;
}

std::vector<std::string> Parser::getRuleInvocationStack() {
  return getRuleInvocationStack(_ctx);
}

std::vector<std::string> Parser::getRuleInvocationStack(RuleContext *p) {
  std::vector<std::string> const& ruleNames = getRuleNames();
  std::vector<std::string> stack;
  RuleContext *run = p;
  while (run != nullptr) {
    // compute what follows who invoked us
    size_t ruleIndex = run->getRuleIndex();
    if (ruleIndex == INVALID_INDEX ) {
      stack.push_back("n/a");
    } else {
      stack.push_back(ruleNames[ruleIndex]);
    }
    if (!RuleContext::is(run->parent)) {
      break;
    }
    run = downCast<RuleContext*>(run->parent);
  }
  return stack;
}

std::vector<std::string> Parser::getDFAStrings() {
  atn::ParserATNSimulator *simulator = getInterpreter<atn::ParserATNSimulator>();
  if (!simulator->decisionToDFA.empty()) {
    UniqueLock<Mutex> lck(_mutex);

    std::vector<std::string> s;
    for (size_t d = 0; d < simulator->decisionToDFA.size(); d++) {
      dfa::DFA &dfa = simulator->decisionToDFA[d];
      s.push_back(dfa.toString(getVocabulary()));
    }
    return s;
  }
  return std::vector<std::string>();
}

void Parser::dumpDFA() {
  atn::ParserATNSimulator *simulator = getInterpreter<atn::ParserATNSimulator>();
  if (!simulator->decisionToDFA.empty()) {
    UniqueLock<Mutex> lck(_mutex);
    bool seenOne = false;
    for (size_t d = 0; d < simulator->decisionToDFA.size(); d++) {
      dfa::DFA &dfa = simulator->decisionToDFA[d];
      if (!dfa.states.empty()) {
        if (seenOne) {
          std::cout << std::endl;
        }
        std::cout << "Decision " << dfa.decision << ":" << std::endl;
        std::cout << dfa.toString(getVocabulary());
        seenOne = true;
      }
    }
  }
}

std::string Parser::getSourceName() {
  return _input->getSourceName();
}

atn::ParseInfo Parser::getParseInfo() const {
  atn::ParserATNSimulator *simulator = getInterpreter<atn::ParserATNSimulator>();
  return atn::ParseInfo(dynamic_cast<atn::ProfilingATNSimulator*>(simulator));
}

void Parser::setProfile(bool profile) {
  atn::ParserATNSimulator *interp = getInterpreter<atn::ParserATNSimulator>();
  atn::PredictionMode saveMode = interp != nullptr ? interp->getPredictionMode() : atn::PredictionMode::LL;
  if (profile) {
    if (!is<atn::ProfilingATNSimulator *>(interp)) {
      setInterpreter(new atn::ProfilingATNSimulator(this)); /* mem-check: replacing existing interpreter which gets deleted. */
    }
  } else if (is<atn::ProfilingATNSimulator *>(interp)) {
    /* mem-check: replacing existing interpreter which gets deleted. */
    atn::ParserATNSimulator *sim = new atn::ParserATNSimulator(this, getATN(), interp->decisionToDFA, interp->getSharedContextCache());
    setInterpreter(sim);
  }
  getInterpreter<atn::ParserATNSimulator>()->setPredictionMode(saveMode);
}

void Parser::setTrace(bool trace) {
  if (!trace) {
    if (_tracer)
      removeParseListener(_tracer);
    delete _tracer;
    _tracer = nullptr;
  } else {
    if (_tracer)
      removeParseListener(_tracer); // Just in case this is triggered multiple times.
    _tracer = new TraceListener(this);
    addParseListener(_tracer);
  }
}

bool Parser::isTrace() const {
  return _tracer != nullptr;
}

tree::TerminalNode *Parser::createTerminalNode(Token *t) {
  return _tracker.createInstance<tree::TerminalNodeImpl>(t);
}

tree::ErrorNode *Parser::createErrorNode(Token *t) {
  return _tracker.createInstance<tree::ErrorNodeImpl>(t);
}

void Parser::InitializeInstanceFields() {
  _errHandler = std::make_shared<DefaultErrorStrategy>();
  _precedenceStack.clear();
  _precedenceStack.push_back(0);
  _buildParseTrees = true;
  _syntaxErrors = 0;
  _matchedEOF = false;
  _input = nullptr;
  _tracer = nullptr;
  _ctx = nullptr;
}

