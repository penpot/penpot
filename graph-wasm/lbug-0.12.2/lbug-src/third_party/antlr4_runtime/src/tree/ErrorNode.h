/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "tree/TerminalNode.h"

namespace antlr4 {
namespace tree {

  class ANTLR4CPP_PUBLIC ErrorNode : public TerminalNode {
  public:
    static bool is(const tree::ParseTree &parseTree) { return parseTree.getTreeType() == tree::ParseTreeType::ERROR; }

    static bool is(const tree::ParseTree *parseTree) { return parseTree != nullptr && is(*parseTree); }

  protected:
    using TerminalNode::TerminalNode;
  };

} // namespace tree
} // namespace antlr4
