/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "antlr4-common.h"

namespace antlr4 {
namespace tree {

  class ANTLR4CPP_PUBLIC ParseTreeWalker {
  public:
    static ParseTreeWalker &DEFAULT;

    virtual ~ParseTreeWalker() = default;

    /**
    * <summary>
    * Performs a walk on the given parse tree starting at the root and going down recursively
	* with depth-first search. On each node, <seealso cref="ParseTreeWalker#enterRule"/> is called before
    * recursively walking down into child nodes, then
    * <seealso cref="ParseTreeWalker#exitRule"/> is called after the recursive call to wind up.
	* </summary>
    * <param name='listener'> The listener used by the walker to process grammar rules </param>
	* <param name='t'> The parse tree to be walked on </param>
    */
    virtual void walk(ParseTreeListener *listener, ParseTree *t) const;

  protected:

    /**
    * <summary>
    * Enters a grammar rule by first triggering the generic event <seealso cref="ParseTreeListener#enterEveryRule"/>
	* then by triggering the event specific to the given parse tree node
	* </summary>
    * <param name='listener'> The listener responding to the trigger events </param>
	* <param name='r'> The grammar rule containing the rule context </param>
    */
    virtual void enterRule(ParseTreeListener *listener, ParseTree *r) const;

    /**
    * <summary>
    * Exits a grammar rule by first triggering the event specific to the given parse tree node
	* then by triggering the generic event <seealso cref="ParseTreeListener#exitEveryRule"/>
	* </summary>
    * <param name='listener'> The listener responding to the trigger events </param>
	* <param name='r'> The grammar rule containing the rule context </param>
    */
    virtual void exitRule(ParseTreeListener *listener, ParseTree *r) const;
  };

} // namespace tree
} // namespace antlr4
