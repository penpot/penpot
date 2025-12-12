/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cassert>

#include "support/BitSet.h"
#include "atn/PredictionContext.h"
#include "atn/ATNConfig.h"
#include "FlatHashSet.h"

namespace antlr4 {
namespace atn {

  /// Specialized set that can track info about the set, with support for combining similar configurations using a
  /// graph-structured stack.
  class ANTLR4CPP_PUBLIC ATNConfigSet {
  public:
    /// Track the elements as they are added to the set; supports get(i)
    std::vector<Ref<ATNConfig>> configs;

    // TODO: these fields make me pretty uncomfortable but nice to pack up info together, saves recomputation
    // TODO: can we track conflicts as they are added to save scanning configs later?
    size_t uniqueAlt = 0;

    /** Currently this is only used when we detect SLL conflict; this does
     *  not necessarily represent the ambiguous alternatives. In fact,
     *  I should also point out that this seems to include predicated alternatives
     *  that have predicates that evaluate to false. Computed in computeTargetState().
     */
    antlrcpp::BitSet conflictingAlts;

    // Used in parser and lexer. In lexer, it indicates we hit a pred
    // while computing a closure operation.  Don't make a DFA state from this.
    bool hasSemanticContext = false;
    bool dipsIntoOuterContext = false;

    /// Indicates that this configuration set is part of a full context
    /// LL prediction. It will be used to determine how to merge $. With SLL
    /// it's a wildcard whereas it is not for LL context merge.
    const bool fullCtx = true;

    ATNConfigSet();

    ATNConfigSet(const ATNConfigSet &other);

    ATNConfigSet(ATNConfigSet&&) = delete;

    explicit ATNConfigSet(bool fullCtx);

    virtual ~ATNConfigSet() = default;

    bool add(const Ref<ATNConfig> &config);

    /// <summary>
    /// Adding a new config means merging contexts with existing configs for
    /// {@code (s, i, pi, _)}, where {@code s} is the
    /// <seealso cref="ATNConfig#state"/>, {@code i} is the <seealso cref="ATNConfig#alt"/>, and
    /// {@code pi} is the <seealso cref="ATNConfig#semanticContext"/>. We use
    /// {@code (s,i,pi)} as key.
    /// <p/>
    /// This method updates <seealso cref="#dipsIntoOuterContext"/> and
    /// <seealso cref="#hasSemanticContext"/> when necessary.
    /// </summary>
    bool add(const Ref<ATNConfig> &config, PredictionContextMergeCache *mergeCache);

    bool addAll(const ATNConfigSet &other);

    std::vector<ATNState*> getStates() const;

    /**
     * Gets the complete set of represented alternatives for the configuration
     * set.
     *
     * @return the set of represented alternatives in this configuration set
     *
     * @since 4.3
     */
    antlrcpp::BitSet getAlts() const;
    std::vector<Ref<const SemanticContext>> getPredicates() const;

    const Ref<ATNConfig>& get(size_t i) const;

    void optimizeConfigs(ATNSimulator *interpreter);

    size_t size() const;
    bool isEmpty() const;
    void clear();
    bool isReadonly() const;
    void setReadonly(bool readonly);

    virtual size_t hashCode() const;

    virtual bool equals(const ATNConfigSet &other) const;

    virtual std::string toString() const;

  private:
    struct ATNConfigHasher final {
      const ATNConfigSet* atnConfigSet;

      size_t operator()(const ATNConfig *other) const {
        assert(other != nullptr);
        return atnConfigSet->hashCode(*other);
      }
    };

    struct ATNConfigComparer final {
      const ATNConfigSet* atnConfigSet;

      bool operator()(const ATNConfig *lhs, const ATNConfig *rhs) const {
        assert(lhs != nullptr);
        assert(rhs != nullptr);
        return atnConfigSet->equals(*lhs, *rhs);
      }
    };

    mutable std::atomic<size_t> _cachedHashCode = 0;

    /// Indicates that the set of configurations is read-only. Do not
    /// allow any code to manipulate the set; DFA states will point at
    /// the sets and they must not change. This does not protect the other
    /// fields; in particular, conflictingAlts is set after
    /// we've made this readonly.
    bool _readonly = false;

    virtual size_t hashCode(const ATNConfig &atnConfig) const;

    virtual bool equals(const ATNConfig &lhs, const ATNConfig &rhs) const;

    using LookupContainer = FlatHashSet<ATNConfig*, ATNConfigHasher, ATNConfigComparer>;

    /// All configs but hashed by (s, i, _, pi) not including context. Wiped out
    /// when we go readonly as this set becomes a DFA state.
    LookupContainer _configLookup;
  };

  inline bool operator==(const ATNConfigSet &lhs, const ATNConfigSet &rhs) { return lhs.equals(rhs); }

  inline bool operator!=(const ATNConfigSet &lhs, const ATNConfigSet &rhs) { return !operator==(lhs, rhs); }

} // namespace atn
} // namespace antlr4

namespace std {

template <>
struct hash<::antlr4::atn::ATNConfigSet> {
  size_t operator()(const ::antlr4::atn::ATNConfigSet &atnConfigSet) const {
    return atnConfigSet.hashCode();
  }
};

} // namespace std
