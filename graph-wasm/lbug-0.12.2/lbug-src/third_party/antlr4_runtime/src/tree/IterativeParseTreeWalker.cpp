/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "tree/ParseTreeListener.h"
#include "tree/ParseTree.h"
#include "tree/ErrorNode.h"

#include "IterativeParseTreeWalker.h"

using namespace antlr4::tree;
using namespace antlrcpp;

void IterativeParseTreeWalker::walk(ParseTreeListener *listener, ParseTree *t) const {
  std::vector<std::pair<ParseTree*, size_t>> stack;
  ParseTree *currentNode = t;
  size_t currentIndex = 0;

  while (currentNode != nullptr) {
    // pre-order visit
    if (ErrorNode::is(*currentNode)) {
      listener->visitErrorNode(downCast<ErrorNode*>(currentNode));
    } else if (TerminalNode::is(*currentNode)) {
      listener->visitTerminal(downCast<TerminalNode*>(currentNode));
    } else {
      enterRule(listener, currentNode);
    }

    // Move down to first child, if it exists.
    if (!currentNode->children.empty()) {
      stack.push_back(std::make_pair(currentNode, currentIndex));
      currentIndex = 0;
      currentNode = currentNode->children[0];
      continue;
    }

    // No child nodes, so walk tree.
    do {
      // post-order visit
      if (!TerminalNode::is(*currentNode)) {
        exitRule(listener, currentNode);
      }

      // No parent, so no siblings.
      if (stack.empty()) {
        currentNode = nullptr;
        currentIndex = 0;
        break;
      }

      // Move to next sibling if possible.
      if (stack.back().first->children.size() > ++currentIndex) {
        currentNode = stack.back().first->children[currentIndex];
        break;
      }

      // No next sibling, so move up.
      std::tie(currentNode, currentIndex) = stack.back();
      stack.pop_back();
    } while (currentNode != nullptr);
  }
}
