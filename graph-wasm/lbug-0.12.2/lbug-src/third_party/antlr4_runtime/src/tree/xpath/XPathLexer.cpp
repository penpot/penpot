
// Generated from XPathLexer.g4 by ANTLR 4.13.0


#include "XPathLexer.h"


using namespace antlr4;



using namespace antlr4;

namespace {

struct XPathLexerStaticData final {
  XPathLexerStaticData(std::vector<std::string> ruleNames,
                          std::vector<std::string> channelNames,
                          std::vector<std::string> modeNames,
                          std::vector<std::string> literalNames,
                          std::vector<std::string> symbolicNames)
      : ruleNames(std::move(ruleNames)), channelNames(std::move(channelNames)),
        modeNames(std::move(modeNames)), literalNames(std::move(literalNames)),
        symbolicNames(std::move(symbolicNames)),
        vocabulary(this->literalNames, this->symbolicNames) {}

  XPathLexerStaticData(const XPathLexerStaticData&) = delete;
  XPathLexerStaticData(XPathLexerStaticData&&) = delete;
  XPathLexerStaticData& operator=(const XPathLexerStaticData&) = delete;
  XPathLexerStaticData& operator=(XPathLexerStaticData&&) = delete;

  std::vector<antlr4::dfa::DFA> decisionToDFA;
  antlr4::atn::PredictionContextCache sharedContextCache;
  const std::vector<std::string> ruleNames;
  const std::vector<std::string> channelNames;
  const std::vector<std::string> modeNames;
  const std::vector<std::string> literalNames;
  const std::vector<std::string> symbolicNames;
  const antlr4::dfa::Vocabulary vocabulary;
  antlr4::atn::SerializedATNView serializedATN;
  std::unique_ptr<antlr4::atn::ATN> atn;
};

::antlr4::internal::OnceFlag xpathlexerLexerOnceFlag;
#if ANTLR4_USE_THREAD_LOCAL_CACHE
static thread_local
#endif
XPathLexerStaticData *xpathlexerLexerStaticData = nullptr;

void xpathlexerLexerInitialize() {
#if ANTLR4_USE_THREAD_LOCAL_CACHE
  if (xpathlexerLexerStaticData != nullptr) {
    return;
  }
#else
  assert(xpathlexerLexerStaticData == nullptr);
#endif
  auto staticData = std::make_unique<XPathLexerStaticData>(
    std::vector<std::string>{
      "ANYWHERE", "ROOT", "WILDCARD", "BANG", "ID", "NameChar", "NameStartChar", 
      "STRING"
    },
    std::vector<std::string>{
      "DEFAULT_TOKEN_CHANNEL", "HIDDEN"
    },
    std::vector<std::string>{
      "DEFAULT_MODE"
    },
    std::vector<std::string>{
      "", "", "", "'//'", "'/'", "'*'", "'!'"
    },
    std::vector<std::string>{
      "", "TOKEN_REF", "RULE_REF", "ANYWHERE", "ROOT", "WILDCARD", "BANG", 
      "ID", "STRING"
    }
  );
  static const int32_t serializedATNSegment[] = {
  	4,0,8,50,6,-1,2,0,7,0,2,1,7,1,2,2,7,2,2,3,7,3,2,4,7,4,2,5,7,5,2,6,7,6,
  	2,7,7,7,1,0,1,0,1,0,1,1,1,1,1,2,1,2,1,3,1,3,1,4,1,4,5,4,29,8,4,10,4,12,
  	4,32,9,4,1,4,1,4,1,5,1,5,3,5,38,8,5,1,6,1,6,1,7,1,7,5,7,44,8,7,10,7,12,
  	7,47,9,7,1,7,1,7,1,45,0,8,1,3,3,4,5,5,7,6,9,7,11,0,13,0,15,8,1,0,2,5,
  	0,48,57,95,95,183,183,768,879,8255,8256,13,0,65,90,97,122,192,214,216,
  	246,248,767,880,893,895,8191,8204,8205,8304,8591,11264,12271,12289,55295,
  	63744,64975,65008,65535,50,0,1,1,0,0,0,0,3,1,0,0,0,0,5,1,0,0,0,0,7,1,
  	0,0,0,0,9,1,0,0,0,0,15,1,0,0,0,1,17,1,0,0,0,3,20,1,0,0,0,5,22,1,0,0,0,
  	7,24,1,0,0,0,9,26,1,0,0,0,11,37,1,0,0,0,13,39,1,0,0,0,15,41,1,0,0,0,17,
  	18,5,47,0,0,18,19,5,47,0,0,19,2,1,0,0,0,20,21,5,47,0,0,21,4,1,0,0,0,22,
  	23,5,42,0,0,23,6,1,0,0,0,24,25,5,33,0,0,25,8,1,0,0,0,26,30,3,13,6,0,27,
  	29,3,11,5,0,28,27,1,0,0,0,29,32,1,0,0,0,30,28,1,0,0,0,30,31,1,0,0,0,31,
  	33,1,0,0,0,32,30,1,0,0,0,33,34,6,4,0,0,34,10,1,0,0,0,35,38,3,13,6,0,36,
  	38,7,0,0,0,37,35,1,0,0,0,37,36,1,0,0,0,38,12,1,0,0,0,39,40,7,1,0,0,40,
  	14,1,0,0,0,41,45,5,39,0,0,42,44,9,0,0,0,43,42,1,0,0,0,44,47,1,0,0,0,45,
  	46,1,0,0,0,45,43,1,0,0,0,46,48,1,0,0,0,47,45,1,0,0,0,48,49,5,39,0,0,49,
  	16,1,0,0,0,4,0,30,37,45,1,1,4,0
  };
  staticData->serializedATN = antlr4::atn::SerializedATNView(serializedATNSegment, sizeof(serializedATNSegment) / sizeof(serializedATNSegment[0]));

  antlr4::atn::ATNDeserializer deserializer;
  staticData->atn = deserializer.deserialize(staticData->serializedATN);

  const size_t count = staticData->atn->getNumberOfDecisions();
  staticData->decisionToDFA.reserve(count);
  for (size_t i = 0; i < count; i++) { 
    staticData->decisionToDFA.emplace_back(staticData->atn->getDecisionState(i), i);
  }
  xpathlexerLexerStaticData = staticData.release();
}

}

XPathLexer::XPathLexer(CharStream *input) : Lexer(input) {
  XPathLexer::initialize();
  _interpreter = new atn::LexerATNSimulator(this, *xpathlexerLexerStaticData->atn, xpathlexerLexerStaticData->decisionToDFA, xpathlexerLexerStaticData->sharedContextCache);
}

XPathLexer::~XPathLexer() {
  delete _interpreter;
}

std::string XPathLexer::getGrammarFileName() const {
  return "XPathLexer.g4";
}

const std::vector<std::string>& XPathLexer::getRuleNames() const {
  return xpathlexerLexerStaticData->ruleNames;
}

const std::vector<std::string>& XPathLexer::getChannelNames() const {
  return xpathlexerLexerStaticData->channelNames;
}

const std::vector<std::string>& XPathLexer::getModeNames() const {
  return xpathlexerLexerStaticData->modeNames;
}

const dfa::Vocabulary& XPathLexer::getVocabulary() const {
  return xpathlexerLexerStaticData->vocabulary;
}

antlr4::atn::SerializedATNView XPathLexer::getSerializedATN() const {
  return xpathlexerLexerStaticData->serializedATN;
}

const atn::ATN& XPathLexer::getATN() const {
  return *xpathlexerLexerStaticData->atn;
}


void XPathLexer::action(RuleContext *context, size_t ruleIndex, size_t actionIndex) {
  switch (ruleIndex) {
    case 4: IDAction(antlrcpp::downCast<antlr4::RuleContext *>(context), actionIndex); break;

  default:
    break;
  }
}

void XPathLexer::IDAction(antlr4::RuleContext *context, size_t actionIndex) {
  switch (actionIndex) {
    case 0: 
    				if (isupper(getText()[0]))
    				  setType(TOKEN_REF);
    				else
    				  setType(RULE_REF);
    				 break;

  default:
    break;
  }
}



void XPathLexer::initialize() {
#if ANTLR4_USE_THREAD_LOCAL_CACHE
  xpathlexerLexerInitialize();
#else
  ::antlr4::internal::call_once(xpathlexerLexerOnceFlag, xpathlexerLexerInitialize);
#endif
}
