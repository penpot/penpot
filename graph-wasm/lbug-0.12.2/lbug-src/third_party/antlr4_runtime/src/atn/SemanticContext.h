/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "Recognizer.h"
#include "support/CPPUtils.h"
#include "atn/SemanticContextType.h"

namespace antlr4 {
namespace atn {

  /// A tree structure used to record the semantic context in which
  ///  an ATN configuration is valid.  It's either a single predicate,
  ///  a conjunction "p1 && p2", or a sum of products "p1||p2".
  ///
  ///  I have scoped the AND, OR, and Predicate subclasses of
  ///  SemanticContext within the scope of this outer class.
  class ANTLR4CPP_PUBLIC SemanticContext : public std::enable_shared_from_this<SemanticContext> {
  public:
    virtual ~SemanticContext() = default;

    SemanticContextType getContextType() const { return _contextType; }

    /// <summary>
    /// For context independent predicates, we evaluate them without a local
    /// context (i.e., null context). That way, we can evaluate them without
    /// having to create proper rule-specific context during prediction (as
    /// opposed to the parser, which creates them naturally). In a practical
    /// sense, this avoids a cast exception from RuleContext to myruleContext.
    /// <p/>
    /// For context dependent predicates, we must pass in a local context so that
    /// references such as $arg evaluate properly as _localctx.arg. We only
    /// capture context dependent predicates in the context in which we begin
    /// prediction, so we passed in the outer context here in case of context
    /// dependent predicate evaluation.
    /// </summary>
    virtual bool eval(Recognizer *parser, RuleContext *parserCallStack) const = 0;

    /**
     * Evaluate the precedence predicates for the context and reduce the result.
     *
     * @param parser The parser instance.
     * @param parserCallStack
     * @return The simplified semantic context after precedence predicates are
     * evaluated, which will be one of the following values.
     * <ul>
     * <li>{@link #NONE}: if the predicate simplifies to {@code true} after
     * precedence predicates are evaluated.</li>
     * <li>{@code null}: if the predicate simplifies to {@code false} after
     * precedence predicates are evaluated.</li>
     * <li>{@code this}: if the semantic context is not changed as a result of
     * precedence predicate evaluation.</li>
     * <li>A non-{@code null} {@link SemanticContext}: the new simplified
     * semantic context after precedence predicates are evaluated.</li>
     * </ul>
     */
    virtual Ref<const SemanticContext> evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const;

    virtual size_t hashCode() const = 0;

    virtual bool equals(const SemanticContext &other) const = 0;

    virtual std::string toString() const = 0;

    static Ref<const SemanticContext> And(Ref<const SemanticContext> a, Ref<const SemanticContext> b);

    /// See also: ParserATNSimulator::getPredsForAmbigAlts.
    static Ref<const SemanticContext> Or(Ref<const SemanticContext> a, Ref<const SemanticContext> b);

    class Empty;
    class Predicate;
    class PrecedencePredicate;
    class Operator;
    class AND;
    class OR;

  protected:
    explicit SemanticContext(SemanticContextType contextType) : _contextType(contextType) {}

  private:
    const SemanticContextType _contextType;
  };

  inline bool operator==(const SemanticContext &lhs, const SemanticContext &rhs) {
    return lhs.equals(rhs);
  }

  inline bool operator!=(const SemanticContext &lhs, const SemanticContext &rhs) {
    return !operator==(lhs, rhs);
  }

  class ANTLR4CPP_PUBLIC SemanticContext::Empty : public SemanticContext{
  public:
    /**
     * The default {@link SemanticContext}, which is semantically equivalent to
     * a predicate of the form {@code {true}?}.
     */
    static const Ref<const SemanticContext> Instance;
  };

  class ANTLR4CPP_PUBLIC SemanticContext::Predicate final : public SemanticContext {
  public:
    static bool is(const SemanticContext &semanticContext) { return semanticContext.getContextType() == SemanticContextType::PREDICATE; }

    static bool is(const SemanticContext *semanticContext) { return semanticContext != nullptr && is(*semanticContext); }

    const size_t ruleIndex;
    const size_t predIndex;
    const bool isCtxDependent; // e.g., $i ref in pred

    Predicate(size_t ruleIndex, size_t predIndex, bool isCtxDependent);

    bool eval(Recognizer *parser, RuleContext *parserCallStack) const override;
    size_t hashCode() const override;
    bool equals(const SemanticContext &other) const override;
    std::string toString() const override;
  };

  class ANTLR4CPP_PUBLIC SemanticContext::PrecedencePredicate final : public SemanticContext {
  public:
    static bool is(const SemanticContext &semanticContext) { return semanticContext.getContextType() == SemanticContextType::PRECEDENCE; }

    static bool is(const SemanticContext *semanticContext) { return semanticContext != nullptr && is(*semanticContext); }

    const int precedence;

    explicit PrecedencePredicate(int precedence);

    bool eval(Recognizer *parser, RuleContext *parserCallStack) const override;
    Ref<const SemanticContext> evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const override;
    size_t hashCode() const override;
    bool equals(const SemanticContext &other) const override;
    std::string toString() const override;
  };

  /**
   * This is the base class for semantic context "operators", which operate on
   * a collection of semantic context "operands".
   *
   * @since 4.3
   */
  class ANTLR4CPP_PUBLIC SemanticContext::Operator : public SemanticContext {
  public:
    static bool is(const SemanticContext &semanticContext) {
      const auto contextType = semanticContext.getContextType();
      return contextType == SemanticContextType::AND || contextType == SemanticContextType::OR;
    }

    static bool is(const SemanticContext *semanticContext) { return semanticContext != nullptr && is(*semanticContext); }

    /**
     * Gets the operands for the semantic context operator.
     *
     * @return a collection of {@link SemanticContext} operands for the
     * operator.
     *
     * @since 4.3
     */

    virtual const std::vector<Ref<const SemanticContext>>& getOperands() const = 0;

  protected:
    using SemanticContext::SemanticContext;
  };

  /**
   * A semantic context which is true whenever none of the contained contexts
   * is false.
   */
  class ANTLR4CPP_PUBLIC SemanticContext::AND final : public SemanticContext::Operator {
  public:
    static bool is(const SemanticContext &semanticContext) { return semanticContext.getContextType() == SemanticContextType::AND; }

    static bool is(const SemanticContext *semanticContext) { return semanticContext != nullptr && is(*semanticContext); }

    AND(Ref<const SemanticContext> a, Ref<const SemanticContext> b) ;

    const std::vector<Ref<const SemanticContext>>& getOperands() const override;

    /**
     * The evaluation of predicates by this context is short-circuiting, but
     * unordered.</p>
     */
    bool eval(Recognizer *parser, RuleContext *parserCallStack) const override;
    Ref<const SemanticContext> evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const override;
    size_t hashCode() const override;
    bool equals(const SemanticContext &other) const override;
    std::string toString() const override;

  private:
    std::vector<Ref<const SemanticContext>> _opnds;
  };

  /**
   * A semantic context which is true whenever at least one of the contained
   * contexts is true.
   */
  class ANTLR4CPP_PUBLIC SemanticContext::OR final : public SemanticContext::Operator {
  public:
    static bool is(const SemanticContext &semanticContext) { return semanticContext.getContextType() == SemanticContextType::OR; }

    static bool is(const SemanticContext *semanticContext) { return semanticContext != nullptr && is(*semanticContext); }

    OR(Ref<const SemanticContext> a, Ref<const SemanticContext> b);

    const std::vector<Ref<const SemanticContext>>& getOperands() const override;

    /**
     * The evaluation of predicates by this context is short-circuiting, but
     * unordered.
     */
    bool eval(Recognizer *parser, RuleContext *parserCallStack) const override;
    Ref<const SemanticContext> evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const override;
    size_t hashCode() const override;
    bool equals(const SemanticContext &other) const override;
    std::string toString() const override;

  private:
    std::vector<Ref<const SemanticContext>> _opnds;
  };

}  // namespace atn
}  // namespace antlr4

namespace std {

  template <>
  struct hash<::antlr4::atn::SemanticContext> {
    size_t operator()(const ::antlr4::atn::SemanticContext &semanticContext) const {
      return semanticContext.hashCode();
    }
  };

}  // namespace std
