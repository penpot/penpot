/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <atomic>

#include "Recognizer.h"
#include "atn/ATN.h"
#include "atn/ATNState.h"
#include "atn/PredictionContextType.h"

namespace antlr4 {

  class RuleContext;

namespace atn {

  class ATN;
  class ArrayPredictionContext;
  class SingletonPredictionContext;
  class PredictionContextCache;
  class PredictionContextMergeCache;

  class ANTLR4CPP_PUBLIC PredictionContext {
  public:
    /// Represents $ in local context prediction, which means wildcard.
    /// *+x = *.
    static const Ref<const PredictionContext> EMPTY;

    /// Represents $ in an array in full context mode, when $
    /// doesn't mean wildcard: $ + x = [$,x]. Here,
    /// $ = EMPTY_RETURN_STATE.
    // ml: originally Integer.MAX_VALUE, which would be -1 for us, but this is already used in places where
    //     -1 is converted to unsigned, so we use a different value here. Any value does the job provided it doesn't
    //     conflict with real return states.
    static constexpr size_t EMPTY_RETURN_STATE = std::numeric_limits<size_t>::max() - 9;

    // dispatch
    static Ref<const PredictionContext> merge(Ref<const PredictionContext> a,
                                              Ref<const PredictionContext> b,
                                              bool rootIsWildcard,
                                              PredictionContextMergeCache *mergeCache);

    /// <summary>
    /// Merge two <seealso cref="SingletonPredictionContext"/> instances.
    ///
    /// <p/>
    ///
    /// Stack tops equal, parents merge is same; return left graph.<br/>
    /// <embed src="images/SingletonMerge_SameRootSamePar.svg" type="image/svg+xml"/>
    ///
    /// <p/>
    ///
    /// Same stack top, parents differ; merge parents giving array node, then
    /// remainders of those graphs. A new root node is created to point to the
    /// merged parents.<br/>
    /// <embed src="images/SingletonMerge_SameRootDiffPar.svg" type="image/svg+xml"/>
    ///
    /// <p/>
    ///
    /// Different stack tops pointing to same parent. Make array node for the
    /// root where both element in the root point to the same (original)
    /// parent.<br/>
    /// <embed src="images/SingletonMerge_DiffRootSamePar.svg" type="image/svg+xml"/>
    ///
    /// <p/>
    ///
    /// Different stack tops pointing to different parents. Make array node for
    /// the root where each element points to the corresponding original
    /// parent.<br/>
    /// <embed src="images/SingletonMerge_DiffRootDiffPar.svg" type="image/svg+xml"/>
    /// </summary>
    /// <param name="a"> the first <seealso cref="SingletonPredictionContext"/> </param>
    /// <param name="b"> the second <seealso cref="SingletonPredictionContext"/> </param>
    /// <param name="rootIsWildcard"> {@code true} if this is a local-context merge,
    /// otherwise false to indicate a full-context merge </param>
    /// <param name="mergeCache"> </param>
    static Ref<const PredictionContext> mergeSingletons(Ref<const SingletonPredictionContext> a,
                                                        Ref<const SingletonPredictionContext> b,
                                                        bool rootIsWildcard,
                                                        PredictionContextMergeCache *mergeCache);

    /**
     * Handle case where at least one of {@code a} or {@code b} is
     * {@link #EMPTY}. In the following diagrams, the symbol {@code $} is used
     * to represent {@link #EMPTY}.
     *
     * <h2>Local-Context Merges</h2>
     *
     * <p>These local-context merge operations are used when {@code rootIsWildcard}
     * is true.</p>
     *
     * <p>{@link #EMPTY} is superset of any graph; return {@link #EMPTY}.<br>
     * <embed src="images/LocalMerge_EmptyRoot.svg" type="image/svg+xml"/></p>
     *
     * <p>{@link #EMPTY} and anything is {@code #EMPTY}, so merged parent is
     * {@code #EMPTY}; return left graph.<br>
     * <embed src="images/LocalMerge_EmptyParent.svg" type="image/svg+xml"/></p>
     *
     * <p>Special case of last merge if local context.<br>
     * <embed src="images/LocalMerge_DiffRoots.svg" type="image/svg+xml"/></p>
     *
     * <h2>Full-Context Merges</h2>
     *
     * <p>These full-context merge operations are used when {@code rootIsWildcard}
     * is false.</p>
     *
     * <p><embed src="images/FullMerge_EmptyRoots.svg" type="image/svg+xml"/></p>
     *
     * <p>Must keep all contexts; {@link #EMPTY} in array is a special value (and
     * null parent).<br>
     * <embed src="images/FullMerge_EmptyRoot.svg" type="image/svg+xml"/></p>
     *
     * <p><embed src="images/FullMerge_SameRoot.svg" type="image/svg+xml"/></p>
     *
     * @param a the first {@link SingletonPredictionContext}
     * @param b the second {@link SingletonPredictionContext}
     * @param rootIsWildcard {@code true} if this is a local-context merge,
     * otherwise false to indicate a full-context merge
     */
    static Ref<const PredictionContext> mergeRoot(Ref<const SingletonPredictionContext> a,
                                                  Ref<const SingletonPredictionContext> b,
                                                  bool rootIsWildcard);

    /**
     * Merge two {@link ArrayPredictionContext} instances.
     *
     * <p>Different tops, different parents.<br>
     * <embed src="images/ArrayMerge_DiffTopDiffPar.svg" type="image/svg+xml"/></p>
     *
     * <p>Shared top, same parents.<br>
     * <embed src="images/ArrayMerge_ShareTopSamePar.svg" type="image/svg+xml"/></p>
     *
     * <p>Shared top, different parents.<br>
     * <embed src="images/ArrayMerge_ShareTopDiffPar.svg" type="image/svg+xml"/></p>
     *
     * <p>Shared top, all shared parents.<br>
     * <embed src="images/ArrayMerge_ShareTopSharePar.svg" type="image/svg+xml"/></p>
     *
     * <p>Equal tops, merge parents and reduce top to
     * {@link SingletonPredictionContext}.<br>
     * <embed src="images/ArrayMerge_EqualTop.svg" type="image/svg+xml"/></p>
     */
    static Ref<const PredictionContext> mergeArrays(Ref<const ArrayPredictionContext> a,
                                                    Ref<const ArrayPredictionContext> b,
                                                    bool rootIsWildcard,
                                                    PredictionContextMergeCache *mergeCache);

    static std::string toDOTString(const Ref<const PredictionContext> &context);

    static Ref<const PredictionContext> getCachedContext(const Ref<const PredictionContext> &context,
                                                         PredictionContextCache &contextCache);

    static std::vector<Ref<const PredictionContext>> getAllContextNodes(const Ref<const PredictionContext> &context);

    /// Convert a RuleContext tree to a PredictionContext graph.
    /// Return EMPTY if outerContext is empty.
    static Ref<const PredictionContext> fromRuleContext(const ATN &atn, RuleContext *outerContext);

    PredictionContext(const PredictionContext&) = delete;

    virtual ~PredictionContext() = default;

    PredictionContext& operator=(const PredictionContext&) = delete;
    PredictionContext& operator=(PredictionContext&&) = delete;

    PredictionContextType getContextType() const { return _contextType; }

    virtual size_t size() const = 0;
    virtual const Ref<const PredictionContext>& getParent(size_t index) const = 0;
    virtual size_t getReturnState(size_t index) const = 0;

    /// This means only the EMPTY (wildcard? not sure) context is in set.
    virtual bool isEmpty() const = 0;
    bool hasEmptyPath() const;

    size_t hashCode() const;

    virtual bool equals(const PredictionContext &other) const = 0;

    virtual std::string toString() const = 0;

    std::vector<std::string> toStrings(Recognizer *recognizer, int currentState) const;
    std::vector<std::string> toStrings(Recognizer *recognizer,
                                       const Ref<const PredictionContext> &stop,
                                       int currentState) const;

  protected:
    explicit PredictionContext(PredictionContextType contextType);

    PredictionContext(PredictionContext&& other);

    virtual size_t hashCodeImpl() const = 0;

    size_t cachedHashCode() const { return _hashCode.load(std::memory_order_relaxed); }

  private:
    const PredictionContextType _contextType;
    mutable std::atomic<size_t> _hashCode;
  };

  inline bool operator==(const PredictionContext &lhs, const PredictionContext &rhs) {
    return lhs.equals(rhs);
  }

  inline bool operator!=(const PredictionContext &lhs, const PredictionContext &rhs) {
    return !operator==(lhs, rhs);
  }

}  // namespace atn
}  // namespace antlr4

namespace std {

  template <>
  struct hash<::antlr4::atn::PredictionContext> {
    size_t operator()(const ::antlr4::atn::PredictionContext &predictionContext) const {
      return predictionContext.hashCode();
    }
  };

}  // namespace std
