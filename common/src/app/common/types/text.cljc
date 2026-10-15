;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

 (ns app.common.types.text
   (:require
    [app.common.data :as d]
    [app.common.data.macros :as dm]
    [app.common.flags :as flags]
    [app.common.math :as mth]
    [app.common.types.color :as clr]
    [app.common.types.fills :as types.fills]
    [clojure.set :as set]
    [clojure.walk :as walk]
    [cuerdas.core :as str]))

;; -- Attrs

(def text-typography-attrs
  [:typography-ref-id
   :typography-ref-file])

(def text-fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(def text-font-attrs
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style])

(def text-align-attrs
  [:text-align])

(def text-direction-attrs
  [:text-direction])

(def text-spacing-attrs
  [:line-height
   :letter-spacing])

(def text-valign-attrs
  [:vertical-align])

(def text-decoration-attrs
  [:text-decoration])

(def text-transform-attrs
  [:text-transform])

(def font-size-min 3)
(def font-size-max 1000)
(def spacing-min -200)
(def spacing-max 200)
(def text-transform-values
  #{"uppercase" "capitalize" "lowercase" "none" "unset"})

(def ^:private numeric-text-re
  #"^-?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)$")

(defn- valid-numeric-text-in-range?
  [value min-value max-value]
  (and (string? value)
       (re-matches numeric-text-re value)
       (let [value (d/parse-double value)]
         (and (some? value)
              (<= min-value value max-value)))))

(defn valid-font-size?
  [value]
  (valid-numeric-text-in-range? value font-size-min font-size-max))

(defn valid-line-height?
  [value]
  (valid-numeric-text-in-range? value spacing-min spacing-max))

(defn valid-letter-spacing?
  [value]
  (valid-numeric-text-in-range? value spacing-min spacing-max))

(defn valid-text-transform?
  [value]
  (contains? text-transform-values value))

(def text-fills
  [:fills])

(def shape-attrs
  [:grow-type])

(def root-attrs
  text-valign-attrs)

(def paragraph-attrs
  (d/concat-vec
   text-align-attrs
   text-direction-attrs))

(def text-node-attrs
  (d/concat-vec
   text-typography-attrs
   text-font-attrs
   text-spacing-attrs
   text-decoration-attrs
   text-transform-attrs
   text-fills))

(def text-span-attrs
  "Inline text span attrs. Line-height is paragraph-level in the DOM editor;
   it may still be stored redundantly on span nodes."
  (vec (remove #{:line-height} text-node-attrs)))

(defn text-node-attr?
  [attr]
  (d/index-of text-node-attrs attr))

(def text-all-attrs (d/concat-set shape-attrs root-attrs paragraph-attrs text-node-attrs))

(def text-style-attrs
  (d/concat-vec root-attrs paragraph-attrs text-node-attrs))

(def default-root-attrs
  {:vertical-align "top"})

(def default-text-fills
  [{:fill-color clr/black
    :fill-opacity 1}])

(def default-paragraph-attrs
  {:text-align "left"
   :text-direction "ltr"})

(def default-text-attrs
  {:typography-ref-file nil
   :typography-ref-id nil
   :font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id "regular"
   :font-size "14"
   :font-weight "400"
   :font-style "normal"
   :line-height "1.2"
   :letter-spacing "0"
   :text-transform "none"
   :text-align "left"
   :text-decoration "none"
   :text-direction "ltr"})

(defn get-default-text-fills
  "Return calculated default text fills"
  []
  (if (contains? flags/*current* :frontend-binary-fills)
    (types.fills/from-plain default-text-fills)
    default-text-fills))

(defn get-default-text-attrs
  "Return calculated default text attrs.

  NOTE: is implemented as function because it needs resolve at runtime
  the activated flag for properly encode the fills"
  []
  (assoc default-text-attrs :fills (get-default-text-fills)))

(def typography-fields
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style
   :line-height
   :letter-spacing
   :text-transform])

(def default-typography
  (-> default-text-attrs
      (select-keys typography-fields)
      (assoc :name "Source Sans Pro Regular")))

(defn node-seq
  ([root] (node-seq identity root))
  ([match? root]
   (->> (tree-seq map? :children root)
        (filter match?)
        (seq))))

(defn is-text-node?
  [node]
  (and (nil? (:type node))
       (string? (:text node))))

(defn is-paragraph-set-node?
  [node]
  (= "paragraph-set" (:type node)))

(defn is-paragraph-node?
  [node]
  (= "paragraph" (:type node)))

(defn is-root-node?
  [node]
  (= "root" (:type node)))

(defn is-node?
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-paragraph-set-node? node)
      ^boolean (is-root-node? node)))

(defn is-content-node?
  "Only matches content nodes, ignoring the paragraph-set nodes."
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-root-node? node)))

(defn transform-nodes
  ([transform root]
   (transform-nodes identity transform root))
  ([pred transform root]
   (walk/postwalk
    (fn [item]
      (if (and (is-node? item) (pred item))
        (transform item)
        item))
    root)))

(defn update-text-content
  [shape pred-fn update-fn attrs]
  (let [update-attrs-fn #(update-fn % attrs)
        transform   #(transform-nodes pred-fn update-attrs-fn %)]
    (-> shape
        (update :content transform))))

(defn generate-shape-name
  "The name of a text whose letters are `text`: its first 280 characters,
  each line break a space."
  [text]
  (let [text (str/replace text #"\s*\n\s*" " ")]
    (subs text 0 (min 280 (count text)))))

(defn- compare-text-attr
  "Compare two attribute values and return true if they are different.
   Take into account the following:
    - Only process keys that belong to text node attrs (ignore deprecated
      attributes or other things that may be attached).
    - Consider nil values, empty strings or empty lists all equal.
    - Normalize numeric values (legacy) into strings.
    - No value is equal than the default value.
    - Numeric attrs (e.g. line-height) compare with float tolerance so
      editor/WASM round-trips like \"1.3333333333333333\" vs \"1.33333\"
      do not count as a real style change (avoids detaching tokens)."
  [key value1 value2]
  (when (text-node-attr? key)
    (let [default-value (get default-text-attrs key)
          normalize-value (fn [value]
                            (as-> value $
                              (if (number? $) (str $) $)
                              (if (or (d/empty? $) (= $ default-value))
                                nil
                                $)))
          value1' (normalize-value value1)
          value2' (normalize-value value2)]
      (cond
        (= value1' value2')
        false

        :else
        (let [n1 (when (string? value1') (d/parse-double value1'))
              n2 (when (string? value2') (d/parse-double value2'))]
          (if (and (some? n1) (some? n2))
            (not (mth/close? n1 n2))
            true))))))

(defn- compare-text-content
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the differences info.
   If the structures are equal, it returns an empty set. If the structure
   has changed, it returns :text-content-structure. There are two
   callbacks to specify what to return when there is a text change with
   the same structure, and when attributes change."
  [a b {:keys [text-cb attribute-cb] :as callbacks}]
  (cond
    ;; If a and b are equal, there is no diff
    (= a b)
    #{}

    ;; If one is a map and the other isn't, the structure is different.
    ;; Compare by category (map?), not by `type`: a map's underlying
    ;; PersistentArrayMap/PersistentHashMap representation depends on its
    ;; key count, not on the shape of the content tree, so two maps that
    ;; only differ in size can otherwise report a false structure diff.
    (not= (map? a) (map? b))
    #{:text-content-structure}

    ;; If they are maps, check the keys
    (map? a)
    (let [keys (-> (set/union (set (keys a)) (set (keys b)))
                   (disj :key))] ;; We have to ignore :key because it is a draft artifact
      (reduce
       (fn [acc k]
         (let [v1 (get a k)
               v2 (get b k)]
           (cond
             ;; If the key is :children, keep digging
             (= k :children)
             (if (not= (count v1) (count v2))
               #{:text-content-structure}
               (into acc
                     (apply set/union
                            (map #(compare-text-content %1 %2 callbacks) v1 v2))))

             ;; If the key is :text, and they are different, it is a text differece
             (= k :text)
             (if (not= v1 v2)
               (text-cb acc)
               acc)

             :else
             ;; If the key is not :text, and they are different, it is an attribute difference.
             ;; Take into account that some processes remove empty attributes, so in some
             ;; cases we will compare [] with nil, and this is not a difference.
             (if (compare-text-attr k v1 v2)
               (attribute-cb acc k)
               acc))))
       #{}
       keys))

    :else
    #{:text-content-structure}))

(defn equal-attrs?
  "Given a text structure, and a map of attrs, check that all the internal attrs in
   paragraphs and sentences have the same attrs"
  ([item attrs]
   ;; Ignore the root attrs of the content. We only want to check paragraphs and sentences
   (equal-attrs? item attrs true))
  ([item attrs ignore?]
   (let [item-attrs (dissoc item :text :type :key :children)]
     (and
      (or ignore?
          (empty? item-attrs)
          (= attrs (dissoc item :text :type :key :children)))
      (every? #(equal-attrs? % attrs false) (:children item))))))

(defn get-first-paragraph-text-attrs
  "Given a content text structure, extract it's first paragraph
   text attrs"
  [content]
  (-> content
      (dm/get-in [:children 0 :children 0])
      (dissoc :text :type :key :children)))

(defn get-diff-type
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the type of differences.
   The possibilities are
     :text-content-text
     :text-content-attribute
     :text-content-structure"
  [a b]
  (compare-text-content a b
                        {:text-cb      (fn [acc] (conj acc :text-content-text))
                         :attribute-cb (fn [acc _] (conj acc :text-content-attribute))}))

(defn get-diff-attrs
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the attributes that have changed.
   This is independent of the text structure, so if the structure changes
   but the attributes are the same, it will return an empty set.

   Line-height on text nodes is ignored: it is a paragraph-level attribute
   and may be stored redundantly on spans (e.g. after token apply)."
  [a b]
  (let [strip-span-line-height
        #(transform-nodes is-text-node? (fn [node] (dissoc node :line-height)) %)
        a (strip-span-line-height a)
        b (strip-span-line-height b)
        diff-attrs (compare-text-content a b
                                         {:text-cb      identity
                                          :attribute-cb (fn [acc attr] (conj acc attr))})]
    (if-not (contains? diff-attrs :text-content-structure)
      diff-attrs
      (let [;; get attrs of the first paragraph of the first paragraph-set
            attrs (get-first-paragraph-text-attrs a)]
        (if (and (equal-attrs? a attrs)
                 (equal-attrs? b attrs))
          #{}
          (disj diff-attrs :text-content-structure))))))

;; TODO We know that there are cases that the blocks of texts are separated
;; differently: ["one" " " "two"], ["one " "two"], ["one" " two"]
;; so this won't work for 100% of the situations. But it's good enough for now,
;; we can iterate on the solution again in the future if needed.
(defn equal-structure?
  "Given two content text structures, check that the structures are equal.
   This means that all the :children keys at any level has the same number of
   entries"
  [a b]
  (cond
    (and (not= (type a) (type b))
         (not (and (map? a) (map? b)))) ;; Sometimes they are both maps but of different subtypes
    false

    (map? a)
    (let [children-a (:children a)
          children-b (:children b)]
      (if (not= (count children-a) (count children-b))
        false
        (every? true?
                (map equal-structure? children-a children-b))))

    :else
    true))

(defn copy-text-keys
  "Given two equal content text structures, deep copy all the keys :text
   from origin to destiny"
  [origin destiny]
  (cond
    (map? origin)
    (into {}
          (for [k (keys destiny) :when (not= k :key)] ;; We ignore :key because it is a draft artifact
            (cond
              (= :children k)
              [k (vec (map #(copy-text-keys %1 %2) (get origin k) (get destiny k)))]
              (= :text k)
              [k (:text origin)]
              :else
              [k (get destiny k)])))))

(defn copy-attrs-keys
  "Given a content text structure and a list of attrs, copy that
   attrs values on all the content tree"
  [content attrs]
  (into {}
        (for [[k v] content]
          (if (= :children k)
            [k (vec (map #(copy-attrs-keys %1 attrs) v))]
            [k (get attrs k v)]))))

;; -- Letters and styles
;;
;; A text seen as letters with a style each, for the text overrides of
;; component copies (`mem:common/component-sync-contract`).

(def style-units
  "Text override group -> the level of the content its attributes live on
   (`:level`), its attributes (`:attrs`) and, when not all of them, those
   that tell two styles apart (`:compare`): the variant id follows from the
   family, weight and style. Line height lives on runs in the WASM editor
   and on paragraphs in the others, so a run without it takes its
   paragraph's."
  {:text-content-family      {:level :run :attrs [:font-family :font-id]}
   :text-content-weight      {:level :run :attrs [:font-weight :font-style :font-variant-id]
                              :compare [:font-weight :font-style]}
   :text-content-size        {:level :run :attrs [:font-size]}
   :text-content-line-height {:level :run :attrs [:line-height]}
   :text-content-spacing     {:level :run :attrs [:letter-spacing]}
   :text-content-decoration  {:level :run :attrs [:text-decoration]}
   :text-content-case        {:level :run :attrs [:text-transform]}
   :text-content-fills       {:level :run :attrs [:fills :fill-color :fill-opacity :fill-color-ref-id
                                                  :fill-color-ref-file :fill-color-gradient]}
   :text-content-typography  {:level :run :attrs [:typography-ref-id :typography-ref-file]}
   :text-content-align       {:level :paragraph :attrs [:text-align]}
   :text-content-direction   {:level :paragraph :attrs [:text-direction]}
   :text-content-valign      {:level :root :attrs [:vertical-align]}})

(def text-groups
  "The override groups of a copy text besides `:content-group`."
  (conj (set (keys style-units)) :text-content-text))

(def ^:private attr->unit
  (into {} (for [[unit {:keys [attrs]}] style-units attr attrs] [attr unit])))

(defn style-unit-of
  "The style unit (a key of `style-units`) a text attribute belongs to."
  [attr]
  (get attr->unit attr))

(def min-shared-stretch
  "The fewest equal letters in a row that two texts share."
  3)

(def ^:private max-diff-edits
  "Past this many edits the letter diff gives up on the letters between the
   common start and end of two texts."
  1000)

(defn split-letters
  "The letters of the string `s`, a string each (code points, so that a
   letter outside the basic plane stays whole)."
  [s]
  #?(:clj  (into [] (map #(String. (Character/toChars %))) (iterator-seq (.iterator (.codePoints ^String s))))
     :cljs (vec (js/Array.from s))))

(defn- lcs-pairs
  "The pairs [i j] of equal items of the vectors `a` and `b` along a longest
   common subsequence, in order (Myers' diff), or nil when it needs more
   than `max-d` edits."
  [a b max-d]
  (let [n      (count a)
        m      (count b)
        off    (+ n m 1)
        v      (#?(:clj long-array :cljs make-array) (+ (* 2 off) 1))
        get-v  (fn [k] (aget v (+ off k)))
        snap   (fn [d] (mapv #(get-v %) (range (- (inc d)) (+ d 2))))
        prev-k (fn [value-at k d]
                 (if (or (= k (- d))
                         (and (not= k d) (< (value-at (dec k)) (value-at (inc k)))))
                   (inc k)
                   (dec k)))]
    (aset v (+ off 1) 0)
    (loop [d 0 trace []]
      (when (<= d max-d)
        (let [trace (conj trace (snap d))
              done? (loop [k (- d)]
                      (when (<= k d)
                        (let [pk (prev-k get-v k d)
                              x  (if (= pk (inc k)) (get-v pk) (inc (get-v pk)))
                              x  (loop [x x]
                                   (if (and (< x n) (< (- x k) m) (= (nth a x) (nth b (- x k))))
                                     (recur (inc x))
                                     x))]
                          (aset v (+ off k) x)
                          (if (and (>= x n) (>= (- x k) m))
                            true
                            (recur (+ k 2))))))]
          (if-not done?
            (recur (inc d) trace)
            (loop [d d x n y m pairs '()]
              (if (neg? d)
                (vec pairs)
                (let [snap   (nth trace d)
                      get-s  #(nth snap (+ % d 1))
                      k      (- x y)
                      pk     (prev-k get-s k d)
                      px     (get-s pk)
                      py     (- px pk)
                      pairs  (loop [x x y y pairs pairs]
                               (if (and (> x px) (> y py) (> x 0) (> y 0))
                                 (recur (dec x) (dec y) (conj pairs [(dec x) (dec y)]))
                                 pairs))]
                  (recur (dec d) px py pairs))))))))))

(defn- shared-index
  "Index in `b` -> index in `a`, a vector with nil for the letters of `b`
   not shared (see `shared-letters`)."
  [a b]
  (let [a      (vec a)
        b      (vec b)
        n      (count a)
        m      (count b)
        prefix (loop [i 0]
                 (if (and (< i n) (< i m) (= (nth a i) (nth b i))) (recur (inc i)) i))
        suffix (loop [i 0]
                 (if (and (< (+ prefix i) n) (< (+ prefix i) m)
                          (= (nth a (- n i 1)) (nth b (- m i 1))))
                   (recur (inc i))
                   i))
        middle (lcs-pairs (subvec a prefix (- n suffix))
                          (subvec b prefix (- m suffix))
                          max-diff-edits)
        pairs  (-> []
                   (into (map (fn [k] [k k])) (range prefix))
                   (into (map (fn [[i j]] [(+ prefix i) (+ prefix j)])) middle)
                   (into (map (fn [t] [(+ (- n suffix) t) (+ (- m suffix) t)])) (range suffix)))
        np     (count pairs)
        ;; the pairs in [from, to) are one stretch
        write  (fn [out from to]
                 (if (>= (- to from) min-shared-stretch)
                   (reduce (fn [out k] (let [[i j] (nth pairs k)] (assoc! out j i))) out (range from to))
                   out))]
    (loop [k 1 from 0 out (transient (vec (repeat m nil)))]
      (cond
        (zero? np) (persistent! out)
        (= k np)   (persistent! (write out from k))
        :else      (let [[i j]   (nth pairs k)
                         [pi pj] (nth pairs (dec k))]
                     (if (and (= i (inc pi)) (= j (inc pj)))
                       (recur (inc k) from out)
                       (recur (inc k) k (write out from k))))))))

(defn shared-letters
  "Index in `b` -> index in `a` of the letters (vectors of letters) the two
   texts share: equal letters along a letter diff, in stretches of at least
   `min-shared-stretch` in a row."
  [a b]
  (into {} (keep-indexed (fn [j i] (when (some? i) [j i]))) (shared-index a b)))

(defn- normalize-value
  [value]
  (let [value (if (number? value) (str value) value)]
    (when-not (d/empty? value)
      (or (when (and (string? value) (re-matches numeric-text-re value))
            (some-> (d/parse-double value) (mth/precision 3)))
          value))))

(def ^:private default-style-values
  (update-vals (assoc default-text-attrs :vertical-align "top") normalize-value))

(defn- normalize-style-value
  "A value of the text attribute `attr` in a form equal for values that show
   the same: no value, an empty one and the default are nil, and numbers
   (or numeric strings) are rounded."
  [attr value]
  (let [value (normalize-value value)]
    (when (not= value (get default-style-values attr))
      value)))

(defn- style-of
  "The attributes `attrs` of a letter in the run `run` of the paragraph
   `paragraph`, those the run lacks taken from the paragraph."
  [paragraph run attrs]
  (reduce (fn [acc attr]
            (cond
              (contains? run attr)       (assoc acc attr (get run attr))
              (contains? paragraph attr) (assoc acc attr (get paragraph attr))
              :else                      acc))
          {}
          attrs))

(def ^:private run-attrs
  (into [] (comp (filter #(= :run (:level %))) (mapcat :attrs)) (vals style-units)))

(defn- full-run-styles
  "Per run of `letters`, `[style key]`: its style in every run unit, and
   that style with its values normalized (`normalize-style-value`)."
  [letters]
  (let [paragraphs (:paragraphs letters)]
    (mapv (fn [{:keys [paragraph node]}]
            (let [style (style-of (nth paragraphs paragraph) node run-attrs)]
              [style (into {}
                           (keep (fn [[attr value]]
                                   (when-some [value (normalize-style-value attr value)]
                                     [attr value])))
                           style)]))
          (:runs letters))))

(defn content->letters
  "The letters of `content` with their styles:
   - `:runs`: one entry per run with letters and per paragraph with none,
     `{:paragraph i :node run}`, the latter with `:empty? true`;
   - `:letters`: one entry per letter, `{:letter s :paragraph i :run node
     :ri k}` (`k` its index in `:runs`), and `{:letter \"\\n\" :paragraph
     i}` before every paragraph but the first;
   - `:paragraphs`: the paragraph nodes, without children; one with no
     letters keeps its first run in `:empty-run` and its index in `:runs` in
     `:empty-ri`;
   - `:root` and `:paragraph-set`: those nodes, without children.
   Nodes keep every attribute but `:children` and `:text`."
  [content]
  (let [paragraph-set (d/seek is-paragraph-set-node? (:children content))
        paragraphs    (vec (node-seq is-paragraph-node? content))]
    (loop [i          0
           runs       (transient [])
           letters    (transient [])
           paragraphs' (transient [])]
      (if (= i (count paragraphs))
        (let [letters {:root          (dissoc content :children)
                       :paragraph-set (dissoc paragraph-set :children)
                       :paragraphs    (persistent! paragraphs')
                       :runs          (persistent! runs)
                       :letters       (persistent! letters)}]
          (assoc letters :run-styles (delay (full-run-styles letters))))
        (let [paragraph (nth paragraphs i)
              letters   (cond-> letters (pos? i) (conj! {:letter "\n" :paragraph i}))
              [runs letters]
              (reduce (fn [[runs letters] run]
                        (let [split (split-letters (:text run ""))]
                          (if (empty? split)
                            [runs letters]
                            (let [node (dissoc run :text)
                                  ri   (count runs)]
                              [(conj! runs {:paragraph i :node node})
                               (reduce #(conj! %1 {:letter %2 :paragraph i :run node :ri ri})
                                       letters
                                       split)]))))
                      [runs letters]
                      (:children paragraph))
              no-letters? (not (some #(seq (:text % "")) (:children paragraph)))
              node      (dissoc paragraph :children)]
          (if no-letters?
            (let [run (-> paragraph :children first (dissoc :text))]
              (recur (inc i)
                     (conj! runs {:paragraph i :node run :empty? true})
                     letters
                     (conj! paragraphs' (assoc node :empty-run run :empty-ri (count runs)))))
            (recur (inc i) runs letters (conj! paragraphs' node))))))))

(defn letters->content
  "Build the content of `letters` (as `content->letters` gives them):
   adjacent letters with equal runs join in one run."
  [{:keys [root paragraph-set paragraphs letters]}]
  (let [by-paragraph (group-by :paragraph (filter :run letters))
        runs         (fn [letters]
                       (->> letters
                            (reduce (fn [acc {:keys [letter run]}]
                                      (let [[prev-run prev-letters] (peek acc)]
                                        (if (and (some? prev-run)
                                                 (or (identical? prev-run run) (= prev-run run)))
                                          (conj (pop acc) [prev-run (conj prev-letters letter)])
                                          (conj acc [run [letter]]))))
                                    [])
                            (mapv (fn [[run letters]] (assoc run :text (apply str letters))))))
        paragraph    (fn [i {:keys [empty-run] :as paragraph}]
                       (-> (dissoc paragraph :empty-run :empty-ri)
                           (assoc :children (if-let [letters (get by-paragraph i)]
                                              (runs letters)
                                              [(assoc empty-run :text "")]))))]
    (assoc root :children [(assoc paragraph-set
                                  :type "paragraph-set"
                                  :children (into [] (map-indexed paragraph) paragraphs))])))

(defn letters-text
  "The letters of `letters` (as `content->letters` gives them) as one
   string, with line breaks."
  [letters]
  (apply str (map :letter (:letters letters))))

(defn unit-style
  "The attributes of the style unit `unit` (a key of `style-units`) of a
   letter entry of `letters`, a paragraph index for a paragraph unit, or
   nothing for the root."
  [letters unit at]
  (let [{:keys [level attrs]} (get style-units unit)]
    (case level
      :root      (select-keys (:root letters) attrs)
      :paragraph (select-keys (get-in letters [:paragraphs at]) attrs)
      :run       (let [{:keys [paragraph run]} at
                       paragraph (get-in letters [:paragraphs paragraph])]
                   (style-of paragraph (or run (:empty-run paragraph)) attrs)))))

(defn same-style?
  "Whether two styles of a unit (maps of its attributes) show the same."
  [a b]
  (let [attrs (into (set (keys a)) (keys b))]
    (every? #(= (normalize-style-value % (get a %)) (normalize-style-value % (get b %))) attrs)))

(defn- style-key
  "A style of `unit` in a form equal for styles that show the same."
  [unit style]
  (let [{:keys [attrs compare]} (get style-units unit)]
    (into {}
          (keep (fn [[attr value]]
                  (when-some [value (normalize-style-value attr value)]
                    [attr value])))
          (select-keys style (or compare attrs)))))

(def ^:private typography-units
  "The units a typography sets."
  #{:text-content-family :text-content-weight :text-content-size
    :text-content-line-height :text-content-spacing :text-content-case})

(defn text-overrides
  "The text override groups in the touched groups `touched`. Files older
   than the feature components/text-overrides hold `:text-content-attribute`
   (any style) and `:text-content-structure` (letters and styles), which
   stand for every group they may cover."
  [touched]
  (cond-> (into #{} (filter text-groups) touched)
    (contains? touched :text-content-attribute)
    (into (keys style-units))

    (contains? touched :text-content-structure)
    (into text-groups)))

(defn- letter-map
  "Index in the letters of `dst` -> index in those of `src` (a vector, nil
   for a letter not shared), of the letters the two texts share; every
   letter when they have the same letters."
  [src dst]
  (let [a (mapv :letter (:letters src))
        b (mapv :letter (:letters dst))]
    (if (= a b)
      (vec (range (count b)))
      (shared-index a b))))

(defn- empty-paragraphs
  [letters]
  (keep-indexed (fn [i paragraph] (when (contains? paragraph :empty-run) i))
                (:paragraphs letters)))

(defn- place-run-values
  "The values of the runs of `src` (`values`, by run index; `keys` tell
   them apart) placed on the letters of `dst`, given `lmap` (`letter-map`):
   1. a value the same across `src` applies to every letter;
   2. a letter both texts share takes its value in `src`;
   3. any other letter takes the value of the letter before it, across line
      breaks; letters at the start take the first shared letter after them,
      and with nothing shared, the first letter of `src`.
   Gives `:letters`, a value per letter of `dst` (nil for a line break),
   and `:empty`, paragraph index -> value for its paragraphs with no
   letters."
  [src dst lmap values ks]
  (let [runs    (:runs src)
        filled  (into [] (remove #(:empty? (nth runs %))) (range (count runs)))
        ris     (if (seq filled) filled (range (count runs)))
        letters (:letters dst)
        n       (count letters)]
    (when (seq ris)
      (if (<= (count (into #{} (map #(nth ks %)) ris)) 1)
        (let [value (nth values (first ris))]
          {:letters (mapv #(when (:run %) value) letters)
           :empty   (into {} (map (fn [i] [i value])) (empty-paragraphs dst))})
        (let [src-letters (:letters src)
              direct      (fn [j] (some->> (get lmap j) (nth src-letters) :ri (nth values)))
              fallback    (or (some direct (range n)) (nth values (first ris)))
              no-letters? (set (empty-paragraphs dst))]
          (loop [j       0
                 prev    nil
                 out     (transient [])
                 empties (if (no-letters? 0) {0 fallback} {})]
            (if (= j n)
              {:letters (persistent! out) :empty empties}
              (let [{:keys [run paragraph]} (nth letters j)]
                (if (some? run)
                  (let [value (or (direct j) prev fallback)]
                    (recur (inc j) value (conj! out value) empties))
                  (recur (inc j) prev (conj! out nil)
                         (cond-> empties
                           (no-letters? paragraph) (assoc paragraph (or prev fallback)))))))))))))

(defn- paragraph-map
  "Paragraph index in `dst` -> the paragraph of `src` that holds its first
   shared letter, given `lmap` (`letter-map`)."
  [src dst lmap]
  (reduce (fn [acc [j {:keys [paragraph run]}]]
            (if (or (nil? run) (contains? acc paragraph))
              acc
              (if-let [i (get lmap j)]
                (assoc acc paragraph (:paragraph (nth (:letters src) i)))
                acc)))
          {}
          (map-indexed vector (:letters dst))))

(defn- place-paragraph-values
  "The values of the paragraphs of `src` (`values`, by paragraph; `keys`
   tell them apart) placed on the paragraphs of `dst`: a value the same
   across `src` applies to all; otherwise a paragraph takes the value of
   the paragraph that holds its first shared letter, else the value of the
   paragraph before it, else the first paragraph's of `src`."
  [src dst lmap values ks]
  (let [n (count (:paragraphs dst))]
    (when (seq values)
      (if (<= (count (distinct ks)) 1)
        (vec (repeat n (first values)))
        (let [pmap (if (= (letters-text src) (letters-text dst))
                     (zipmap (range n) (range n))
                     (paragraph-map src dst lmap))]
          (first
           (reduce (fn [[acc prev] i]
                     (let [value (or (some->> (get pmap i) (nth values)) prev (first values))]
                       [(conj acc value) value]))
                   [[] nil]
                   (range n))))))))

(defn- unit-values
  "The styles of `unit` on `letters` (by run, by paragraph, or the root's),
   and their keys (`style-key`)."
  [letters unit]
  (let [{:keys [level attrs compare]} (get style-units unit)]
    (case level
      :run       (let [full @(:run-styles letters)]
                   [(mapv #(select-keys (first %) attrs) full)
                    (mapv #(select-keys (second %) (or compare attrs)) full)])
      :paragraph (let [styles (mapv #(unit-style letters unit %) (range (count (:paragraphs letters))))]
                   [styles (mapv #(style-key unit %) styles)])
      :root      (let [style (unit-style letters unit nil)]
                   [style (style-key unit style)]))))

(defn- place-unit
  "The values of `unit` of `src` (`src-values`, as `unit-values` gives them)
   placed on `dst`; with `keys?`, the keys of its styles, else the styles."
  [src dst lmap unit [styles ks] keys?]
  (let [values (if keys? ks styles)]
    (case (get-in style-units [unit :level])
      :run       (place-run-values src dst lmap values ks)
      :paragraph (place-paragraph-values src dst lmap values ks)
      :root      values)))

(defn- write-style
  "Set the attributes `attrs` of `node` to those of `style`; with `present?`
   only those `node` already has."
  [node style attrs present?]
  (reduce (fn [node attr]
            (cond
              (and present? (not (contains? node attr))) node
              (contains? style attr)                      (assoc node attr (get style attr))
              :else                                       (dissoc node attr)))
          node
          attrs))

(defn- drop-typography
  "Remove the typography reference of `node`."
  [node]
  (cond-> node
    (contains? node :typography-ref-id)   (assoc :typography-ref-id nil)
    (contains? node :typography-ref-file) (assoc :typography-ref-file nil)))

(defn- write-units
  "`dst` (as `content->letters` gives it) with the placed styles of `units`
   (unit -> what `place-unit` gives) written on its runs, paragraphs and
   root; without typography references when `drop-typo?`. Run styles go on
   the runs, and on the paragraphs that hold run styles (as the DOM and v1
   editors write them); a line height goes on a run only when it has one,
   and on a paragraph when it has one or no run of it has."
  [dst placed drop-typo?]
  (let [run-units   (filterv #(= :run (get-in style-units [% :level])) (keys placed))
        ;; a run that already shows the placed style keeps its own values;
        ;; but its variant id names a font of its own family, so with a new
        ;; family it takes the placed one
        family?     (contains? placed :text-content-family)
        write-run   (fn [node styles]
                      (cond-> (reduce (fn [node [unit style]]
                                        (let [attrs (get-in style-units [unit :attrs])]
                                          (if (and (= (style-key unit (select-keys node attrs)) (style-key unit style))
                                                   (not (and family? (= unit :text-content-weight))))
                                            node
                                            (write-style node style attrs (= unit :text-content-line-height)))))
                                      node
                                      (map vector run-units styles))
                        drop-typo? drop-typography))
        cache       (volatile! {})
        new-run     (fn [ri node styles]
                      (let [k [ri styles]]
                        (or (get @cache k)
                            (let [run (write-run node styles)]
                              (vswap! cache assoc k run)
                              run))))
        letters     (into []
                          (map-indexed
                           (fn [j {:keys [run ri] :as entry}]
                             (if (some? run)
                               (assoc entry :run (new-run ri run (mapv #(get-in placed [% :letters j]) run-units)))
                               entry)))
                          (:letters dst))
        first-letter (reduce (fn [acc [j {:keys [run paragraph]}]]
                               (cond-> acc
                                 (and (some? run) (not (contains? acc paragraph)))
                                 (assoc paragraph j)))
                             {}
                             (map-indexed vector (:letters dst)))
        first-style (fn [i unit]
                      (if-some [j (get first-letter i)]
                        (get-in placed [unit :letters j])
                        (get-in placed [unit :empty i])))
        lh-on-runs? (into #{}
                          (keep (fn [{:keys [paragraph node]}]
                                  (when (contains? node :line-height) paragraph)))
                          (:runs dst))
        paragraphs  (into []
                          (map-indexed
                           (fn [i paragraph]
                             (let [styles    (mapv #(first-style i %) run-units)
                                   paragraph (reduce (fn [paragraph [unit style]]
                                                       (write-style paragraph style (get-in style-units [unit :attrs])
                                                                    (or (not= unit :text-content-line-height)
                                                                        (contains? lh-on-runs? i))))
                                                     paragraph
                                                     (map vector run-units styles))
                                   paragraph (reduce (fn [paragraph [unit values]]
                                                       (if (= :paragraph (get-in style-units [unit :level]))
                                                         (write-style paragraph (nth values i)
                                                                      (get-in style-units [unit :attrs]) false)
                                                         paragraph))
                                                     paragraph
                                                     placed)]
                               (cond-> paragraph
                                 drop-typo?                       drop-typography
                                 (contains? paragraph :empty-run) (update :empty-run write-run styles)))))
                          (:paragraphs dst))
        root        (reduce (fn [root [unit value]]
                              (if (= :root (get-in style-units [unit :level]))
                                (write-style root value (get-in style-units [unit :attrs]) false)
                                root))
                            (:root dst)
                            placed)]
    (-> (assoc dst :letters letters :paragraphs paragraphs :root root)
        (dissoc :run-styles))))

(defn- same-everywhere?
  "Whether a unit has one value across two texts, given the keys of its
   styles in each (as `unit-values` gives them), so placing it changes
   nothing."
  [src-ks dst-ks]
  (if (map? src-ks)
    (= src-ks dst-ks)
    (<= (count (into (set src-ks) dst-ks)) 1)))

(defn merge-overrides
  "The content of a copy text whose content is `copy` and whose main's is
   `main`, keeping from `copy` the text override groups `groups`
   (`text-groups`) and taking the rest from `main`: the letters of one
   side, and each style unit of its side placed on them (`place-run-values`,
   `place-paragraph-values`). A typography reference stays only when every
   unit the typography sets comes from its side."
  [copy main groups]
  (let [own-letters? (contains? groups :text-content-text)
        [base other] (if own-letters? [copy main] [main copy])
        from-copy?   #(contains? groups %)
        units        (filter #(not= own-letters? (from-copy? %)) (keys style-units))
        typography   (from-copy? :text-content-typography)
        drop-typo?   (boolean (some #(not= typography (from-copy? %)) typography-units))]
    (if (or (nil? base) (nil? other) (and (empty? units) (not drop-typo?)))
      base
      (let [src    (content->letters other)
            dst    (content->letters base)
            lmap   (delay (letter-map src dst))
            placed (into {}
                         (keep (fn [unit]
                                 (let [src-values (unit-values src unit)]
                                   (when-not (same-everywhere? (second src-values)
                                                               (second (unit-values dst unit)))
                                     (when-some [values (place-unit src dst @lmap unit src-values false)]
                                       [unit values])))))
                         units)]
        (if (and (empty? placed) (not drop-typo?))
          base
          (letters->content (write-units dst placed drop-typo?)))))))

(defn- unit-differs?
  "Whether the styles of `unit` of `b` differ from those of `a` placed on
   the letters of `b`."
  [a b lmap unit]
  (let [placed (place-unit a b lmap unit (unit-values a unit) true)
        [_ ks] (unit-values b unit)]
    (case (get-in style-units [unit :level])
      :run       (or (not= (:letters placed)
                           (mapv #(some->> (:ri %) (nth ks)) (:letters b)))
                     (not= (:empty placed)
                           (into {} (map (fn [i] [i (nth ks (get-in b [:paragraphs i :empty-ri]))]))
                                 (empty-paragraphs b))))
      :paragraph (not= placed ks)
      :root      (not= placed ks))))

(defn override-groups
  "The text override groups a copy whose content is `copy` must hold
   against its main's content `main`: those where the copy differs from
   the main placed on its letters (\"different means touched\")."
  [main copy]
  (let [main (content->letters main)
        copy (content->letters copy)
        lmap (delay (letter-map main copy))]
    (cond-> (into #{}
                  (filter #(and (not (same-everywhere? (second (unit-values main %))
                                                       (second (unit-values copy %))))
                                (unit-differs? main copy @lmap %)))
                  (keys style-units))
      (not= (letters-text main) (letters-text copy))
      (conj :text-content-text))))

(defn- unit-changed?
  "Whether an edit from `old` to `new` changes `unit`: a letter or
   paragraph both share changes its style or, when the letters changed, a
   style appears that `old` did not have."
  [old new lmap same-letters? unit]
  (let [level      (get-in style-units [unit :level])
        [_ old-ks] (unit-values old unit)
        [_ new-ks] (unit-values new unit)]
    (case level
      :root
      (not= old-ks new-ks)

      :paragraph
      (let [pmap (if same-letters?
                   (zipmap (range (count new-ks)) (range))
                   (paragraph-map old new lmap))]
        (boolean
         (or (some (fn [[j i]] (not= (nth old-ks i) (nth new-ks j))) pmap)
             (and (not same-letters?)
                  (not-every? (set old-ks) new-ks)))))

      :run
      (boolean
       (or (some (fn [[j i]]
                   (let [o (:ri (nth (:letters old) i))
                         n (:ri (nth (:letters new) j))]
                     (and (some? o) (some? n) (not= (nth old-ks o) (nth new-ks n)))))
                 (keep-indexed (fn [j i] (when (some? i) [j i])) lmap))
           (if same-letters?
             (some #(not= (nth old-ks (get-in old [:paragraphs % :empty-ri]))
                          (nth new-ks (get-in new [:paragraphs % :empty-ri])))
                   (empty-paragraphs new))
             (not-every? (set old-ks) new-ks)))))))

(defn changed-groups
  "The text override groups an edit of a copy text from the content `old`
   to `new` sets: `:text-content-text` when the letters or line breaks
   change, and each style unit the edit changes (`unit-changed?`). Letters
   typed in the style of a neighbour change no style."
  [old new]
  (let [old           (content->letters old)
        new           (content->letters new)
        same-letters? (= (letters-text old) (letters-text new))
        lmap          (delay (letter-map old new))]
    (cond-> (into #{}
                  (filter #(and (not (same-everywhere? (second (unit-values old %))
                                                       (second (unit-values new %))))
                                (unit-changed? old new @lmap same-letters? %)))
                  (keys style-units))
      (not same-letters?)
      (conj :text-content-text))))

(defn update-font-variants
  "`content` with the variant id of each node that names a font set to
   `(variant-of node)`, when that gives one. Sync and the variant switch
   can take a text's family from one side and its weight from the other,
   leaving a variant id of another family; the frontend, which knows the
   fonts, picks the family's closest variant through this."
  [content variant-of]
  (transform-nodes #(and (is-content-node? %) (some? (:font-id %)))
                   (fn [node]
                     (if-some [variant-id (variant-of node)]
                       (assoc node :font-variant-id variant-id)
                       node))
                   content))

(defn content-has-text?
  [content search]
  (let [search-lower (str/lower search)]
    (->> (node-seq is-text-node? content)
         (some #(str/includes? (str/lower (:text %)) search-lower))
         (boolean))))

(defn replace-all-case-insensitive
  [text search replacement]
  (let [text-lower   (str/lower text)
        search-lower (str/lower search)
        search-len   (count search)]
    (loop [result "" idx 0]
      (let [found (str/index-of text-lower search-lower idx)]
        (if (nil? found)
          (str result (subs text idx))
          (recur (str result (subs text idx found) replacement)
                 (+ found search-len)))))))

(defn replace-text-in-content
  [content search replacement]
  (transform-nodes
   is-text-node?
   (fn [node] (update node :text replace-all-case-insensitive search replacement))
   content))

(defn content->text
  "Given a root node of a text content extracts the texts with its associated styles"
  [content]
  (letfn [(add-node [acc node]
            (cond
              (is-paragraph-node? node)
              (conj acc [])

              (is-text-node? node)
              (let [i (dec (count acc))]
                (update acc i conj (:text node)))

              :else
              acc))]
    (->> (node-seq content)
         (reduce add-node [])
         (map #(str/join "" %))
         (str/join "\n"))))

(defn content->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node]
  (letfn
   [(rec-style-text-map [acc node style]
      (let [node-style (merge style (select-keys node text-all-attrs))
            head (or (-> acc first) [{} ""])
            [head-style head-text] head

            new-acc
            (cond
              (not (is-text-node? node))
              (reduce #(rec-style-text-map %1 %2 node-style) acc (:children node))

              (not= head-style node-style)
              (cons [node-style (:text node "")] acc)

              :else
              (cons [node-style (dm/str head-text "" (:text node))] (rest acc)))

            ;; We add an end-of-line when finish a paragraph
            new-acc
            (if (= (:type node) "paragraph")
              (let [[hs ht] (first new-acc)]
                (cons [hs (dm/str ht "\n")] (rest new-acc)))
              new-acc)]
        new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn change-text
  "Changes the content of the text shape to use the text as argument. Will use the styles of the
   first paragraph and text that is present in the shape (and override the rest)"
  [content text & {:as styles}]
  (let [root-styles (select-keys content root-attrs)

        paragraph-style
        (merge
         default-text-attrs
         styles
         (select-keys (->> content (node-seq is-paragraph-node?) first) text-all-attrs))

        text-style
        (merge
         default-text-attrs
         styles
         (select-keys (->> content (node-seq is-text-node?) first) text-all-attrs))

        paragraph-texts
        (str/split text "\n")

        paragraphs
        (->> paragraph-texts
             (mapv
              (fn [pt]
                (merge
                 paragraph-style
                 {:type "paragraph"
                  :children [(merge {:text pt} text-style)]}))))]


    (d/patch-object
     {:type "root"
      :children
      [{:type "paragraph-set"
        :children paragraphs}]}
     root-styles)))
