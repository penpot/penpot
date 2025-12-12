/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "XPathLexer.h"
#include "XPathLexerErrorListener.h"
#include "XPathElement.h"
#include "XPathWildcardAnywhereElement.h"
#include "XPathWildcardElement.h"
#include "XPathTokenAnywhereElement.h"
#include "XPathTokenElement.h"
#include "XPathRuleAnywhereElement.h"
#include "XPathRuleElement.h"

#include "XPath.h"

using namespace antlr4;
using namespace antlr4::tree;
using namespace antlr4::tree::xpath;

const std::string XPath::WILDCARD = "*";
const std::string XPath::NOT = "!";

XPath::XPath(Parser *parser, const std::string &path) {
  _parser = parser;
  _path = path;
}

std::vector<std::unique_ptr<XPathElement>> XPath::split(const std::string &path) {
  ANTLRInputStream in(path);
  XPathLexer lexer(&in);
  lexer.removeErrorListeners();
  XPathLexerErrorListener listener;
  lexer.addErrorListener(&listener);
  CommonTokenStream tokenStream(&lexer);
  try {
    tokenStream.fill();
  } catch (LexerNoViableAltException &) {
    size_t pos = lexer.getCharPositionInLine();
    std::string msg = "Invalid tokens or characters at index " + std::to_string(pos) + " in path '" + path + "'";
    throw IllegalArgumentException(msg);
  }

  std::vector<Token *> tokens = tokenStream.getTokens();
  std::vector<std::unique_ptr<XPathElement>> elements;
  size_t n = tokens.size();
  size_t i = 0;
  bool done = false;
  while (!done && i < n) {
    Token *el = tokens[i];
    Token *next = nullptr;
    switch (el->getType()) {
      case XPathLexer::ROOT:
      case XPathLexer::ANYWHERE: {
        bool anywhere = el->getType() == XPathLexer::ANYWHERE;
        i++;
        next = tokens[i];
        bool invert = next->getType() == XPathLexer::BANG;
        if (invert) {
          i++;
          next = tokens[i];
        }
        std::unique_ptr<XPathElement> pathElement = getXPathElement(next, anywhere);
        pathElement->setInvert(invert);
        elements.push_back(std::move(pathElement));
        i++;
        break;

      }
      case XPathLexer::TOKEN_REF:
      case XPathLexer::RULE_REF:
      case XPathLexer::WILDCARD:
        elements.push_back(getXPathElement(el, false));
        i++;
        break;

      case Token::EOF:
        done = true;
        break;

      default :
        throw IllegalArgumentException("Unknown path element " + el->toString());
    }
  }

  return elements;
}

std::unique_ptr<XPathElement> XPath::getXPathElement(Token *wordToken, bool anywhere) {
  if (wordToken->getType() == Token::EOF) {
    throw IllegalArgumentException("Missing path element at end of path");
  }

  std::string word = wordToken->getText();
  size_t ttype = _parser->getTokenType(word);
  ssize_t ruleIndex = _parser->getRuleIndex(word);
  switch (wordToken->getType()) {
    case XPathLexer::WILDCARD :
      if (anywhere)
        return std::unique_ptr<XPathWildcardAnywhereElement>(new XPathWildcardAnywhereElement());
      return std::unique_ptr<XPathWildcardElement>(new XPathWildcardElement());

    case XPathLexer::TOKEN_REF:
    case XPathLexer::STRING :
      if (ttype == Token::INVALID_TYPE) {
        throw IllegalArgumentException(word + " at index " + std::to_string(wordToken->getStartIndex()) + " isn't a valid token name");
      }
      if (anywhere)
        return std::unique_ptr<XPathTokenAnywhereElement>(new XPathTokenAnywhereElement(word, (int)ttype));
      return std::unique_ptr<XPathTokenElement>(new XPathTokenElement(word, (int)ttype));

    default :
      if (ruleIndex == -1) {
        throw IllegalArgumentException(word + " at index " + std::to_string(wordToken->getStartIndex()) + " isn't a valid rule name");
      }
      if (anywhere)
        return std::unique_ptr<XPathRuleAnywhereElement>(new XPathRuleAnywhereElement(word, (int)ruleIndex));
      return std::unique_ptr<XPathRuleElement>(new XPathRuleElement(word, (int)ruleIndex));
  }
}

static ParserRuleContext dummyRoot;

std::vector<ParseTree *> XPath::findAll(ParseTree *tree, std::string const& xpath, Parser *parser) {
  XPath p(parser, xpath);
  return p.evaluate(tree);
}

std::vector<ParseTree *> XPath::evaluate(ParseTree *t) {
  dummyRoot.children = { t }; // don't set t's parent.

  std::vector<ParseTree *> work = { &dummyRoot };

  size_t i = 0;
  std::vector<std::unique_ptr<XPathElement>> elements = split(_path);

  while (i < elements.size()) {
    std::vector<ParseTree *> next;
    for (auto *node : work) {
      if (!node->children.empty()) {
        // only try to match next element if it has children
        // e.g., //func/*/stat might have a token node for which
        // we can't go looking for stat nodes.
        auto matching = elements[i]->evaluate(node);
        next.insert(next.end(), matching.begin(), matching.end());
      }
    }
    i++;
    work = next;
  }

  return work;
}
