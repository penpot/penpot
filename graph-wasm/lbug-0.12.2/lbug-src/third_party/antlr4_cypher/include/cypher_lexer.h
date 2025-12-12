
// Generated from Cypher.g4 by ANTLR 4.13.1

#pragma once


#include "antlr4-runtime.h"




class  CypherLexer : public antlr4::Lexer {
public:
  enum {
    T__0 = 1, T__1 = 2, T__2 = 3, T__3 = 4, T__4 = 5, T__5 = 6, T__6 = 7, 
    T__7 = 8, T__8 = 9, T__9 = 10, T__10 = 11, T__11 = 12, T__12 = 13, T__13 = 14, 
    T__14 = 15, T__15 = 16, T__16 = 17, T__17 = 18, T__18 = 19, T__19 = 20, 
    T__20 = 21, T__21 = 22, T__22 = 23, T__23 = 24, T__24 = 25, T__25 = 26, 
    T__26 = 27, T__27 = 28, T__28 = 29, T__29 = 30, T__30 = 31, T__31 = 32, 
    T__32 = 33, T__33 = 34, T__34 = 35, T__35 = 36, T__36 = 37, T__37 = 38, 
    T__38 = 39, T__39 = 40, T__40 = 41, T__41 = 42, T__42 = 43, T__43 = 44, 
    ACYCLIC = 45, ANY = 46, ADD = 47, ALL = 48, ALTER = 49, AND = 50, AS = 51, 
    ASC = 52, ASCENDING = 53, ATTACH = 54, BEGIN = 55, BY = 56, CALL = 57, 
    CASE = 58, CAST = 59, CHECKPOINT = 60, COLUMN = 61, COMMENT = 62, COMMIT = 63, 
    COMMIT_SKIP_CHECKPOINT = 64, CONTAINS = 65, COPY = 66, COUNT = 67, CREATE = 68, 
    CYCLE = 69, DATABASE = 70, DBTYPE = 71, DEFAULT = 72, DELETE = 73, DESC = 74, 
    DESCENDING = 75, DETACH = 76, DISTINCT = 77, DROP = 78, ELSE = 79, END = 80, 
    ENDS = 81, EXISTS = 82, EXPLAIN = 83, EXPORT = 84, EXTENSION = 85, FALSE = 86, 
    FROM = 87, FORCE = 88, GLOB = 89, GRAPH = 90, GROUP = 91, HEADERS = 92, 
    HINT = 93, IMPORT = 94, IF = 95, IN = 96, INCREMENT = 97, INSTALL = 98, 
    IS = 99, JOIN = 100, KEY = 101, LIMIT = 102, LOAD = 103, LOGICAL = 104, 
    MACRO = 105, MATCH = 106, MAXVALUE = 107, MERGE = 108, MINVALUE = 109, 
    MULTI_JOIN = 110, NO = 111, NODE = 112, NOT = 113, NONE = 114, NULL_ = 115, 
    ON = 116, ONLY = 117, OPTIONAL = 118, OR = 119, ORDER = 120, PRIMARY = 121, 
    PROFILE = 122, PROJECT = 123, READ = 124, REL = 125, RENAME = 126, RETURN = 127, 
    ROLLBACK = 128, ROLLBACK_SKIP_CHECKPOINT = 129, SEQUENCE = 130, SET = 131, 
    SHORTEST = 132, START = 133, STARTS = 134, STRUCT = 135, TABLE = 136, 
    THEN = 137, TO = 138, TRAIL = 139, TRANSACTION = 140, TRUE = 141, TYPE = 142, 
    UNION = 143, UNWIND = 144, UNINSTALL = 145, UPDATE = 146, USE = 147, 
    WHEN = 148, WHERE = 149, WITH = 150, WRITE = 151, WSHORTEST = 152, XOR = 153, 
    SINGLE = 154, YIELD = 155, USER = 156, PASSWORD = 157, ROLE = 158, MAP = 159, 
    DECIMAL = 160, STAR = 161, L_SKIP = 162, INVALID_NOT_EQUAL = 163, COLON = 164, 
    DOTDOT = 165, MINUS = 166, FACTORIAL = 167, StringLiteral = 168, EscapedChar = 169, 
    DecimalInteger = 170, HexLetter = 171, HexDigit = 172, Digit = 173, 
    NonZeroDigit = 174, NonZeroOctDigit = 175, ZeroDigit = 176, ExponentDecimalReal = 177, 
    RegularDecimalReal = 178, UnescapedSymbolicName = 179, IdentifierStart = 180, 
    IdentifierPart = 181, EscapedSymbolicName = 182, SP = 183, WHITESPACE = 184, 
    CypherComment = 185, Unknown = 186
  };

  explicit CypherLexer(antlr4::CharStream *input);

  ~CypherLexer() override;


  std::string getGrammarFileName() const override;

  const std::vector<std::string>& getRuleNames() const override;

  const std::vector<std::string>& getChannelNames() const override;

  const std::vector<std::string>& getModeNames() const override;

  const antlr4::dfa::Vocabulary& getVocabulary() const override;

  antlr4::atn::SerializedATNView getSerializedATN() const override;

  const antlr4::atn::ATN& getATN() const override;

  // By default the static state used to implement the lexer is lazily initialized during the first
  // call to the constructor. You can call this function if you wish to initialize the static state
  // ahead of time.
  static void initialize();

private:

  // Individual action functions triggered by action() above.

  // Individual semantic predicate functions triggered by sempred() above.

};

