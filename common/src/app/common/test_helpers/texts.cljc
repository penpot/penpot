;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.texts
  "Text content in a short notation, for tests of text overrides in copies.

   A text is a vector of paragraphs, optionally led by a map of root styles
   (`{:valign \"bottom\"}`). A paragraph is a vector of runs, optionally led
   by a map of paragraph styles (`:align`, `:direction`, `:line-height`). A
   run is `[letters]` or `[letters styles]`, where styles use the short names
   below and list only what differs from `base-style`:

     [[[\"Price: \"] [\"$10\" {:weight 700}]]]
     [[{:align \"center\"} [\"Free shipping\" {:size 18}]]]

   Run styles: `:family` (a key of `font-ids`), `:weight`, `:italic`, `:size`,
   `:spacing` (letter spacing), `:case` (text transform), `:decoration`,
   `:color` (a key of `colors`) and `:typography` (the label of a typography
   of the file `:file1`). Any text attribute can also be given by its own
   key, for values the short names cannot say.

   `content` builds what the DOM editor writes (`from_dom`): every run
   carries every run style, fills included, and every paragraph carries its
   paragraph styles plus the styles of its first run. What the WASM and v1
   editors write is in `text-editor-output-test`. `view` reads content back into the
   notation and joins adjacent runs with equal styles, so tests compare what
   a text shows, not how it is split into nodes."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.text :as txt]
   [clojure.string :as str]))

(def font-ids
  "Font family name -> font id, for the families the notation knows."
  {"sourcesanspro" "sourcesanspro"
   "Inter"         "gfont-inter"
   "Roboto"        "gfont-roboto"})

(def colors
  {:black  "#000000"
   :red    "#FF0000"
   :blue   "#0000FF"
   :purple "#7B3FD1"})

(def base-style
  "The run styles of a text with no styles given."
  {:font-id         "sourcesanspro"
   :font-family     "sourcesanspro"
   :font-variant-id "regular"
   :font-size       "14"
   :font-weight     "400"
   :font-style      "normal"
   :letter-spacing  "0"
   :text-transform  "none"
   :text-decoration "none"
   :fills           [{:fill-color "#000000" :fill-opacity 1}]})

(def base-paragraph-style
  {:text-align     "left"
   :text-direction "ltr"
   :line-height    "1.2"})

(def base-root-style
  {:vertical-align "top"})

(def ^:private named-weights
  "Weight -> the name the variant ids of `sourcesanspro` give it."
  {400 "regular" 700 "bold" 900 "black"})

(defn variant-id
  "The id of the variant of `family` with `weight` (a number), italic or not."
  [family weight italic?]
  (let [weight (if (or (= weight 400) (= family "sourcesanspro"))
                 (get named-weights weight (str weight))
                 (str weight))]
    (cond
      (and (= weight "regular") italic?) "italic"
      italic?                            (str weight "italic")
      :else                              weight)))

(defn- ->number
  [s]
  (cond
    (number? s)               s
    (re-matches #"-?\d+" s)   (d/parse-integer s)
    :else                     (d/parse-double s)))

(defn- raw-attrs
  "The keys of `style` that are text attributes given by their own name.
   `:line-height` is a short name too, and is read as one."
  [style]
  (select-keys style (disj txt/text-all-attrs :line-height)))

(defn- run-attrs
  [{:keys [family weight italic size spacing case decoration color typography] :as style}]
  (let [weight (or weight 400)]
    (cond-> (assoc base-style
                   :font-weight     (str weight)
                   :font-style      (if italic "italic" "normal")
                   :font-variant-id (variant-id (or family "sourcesanspro") weight italic))
      family     (assoc :font-family family :font-id (get font-ids family))
      size       (assoc :font-size (str size))
      spacing    (assoc :letter-spacing (str spacing))
      case       (assoc :text-transform case)
      decoration (assoc :text-decoration decoration)
      color      (assoc :fills [{:fill-color (get colors color) :fill-opacity 1}])
      typography (assoc :typography-ref-id (thi/id typography)
                        :typography-ref-file (thi/id :file1))
      :always    (merge (raw-attrs style)))))

(defn- paragraph-attrs
  [{:keys [align direction line-height] :as style}]
  (cond-> base-paragraph-style
    align       (assoc :text-align align)
    direction   (assoc :text-direction direction)
    line-height (assoc :line-height (str line-height))
    :always     (merge (raw-attrs style))))

(defn- split-styles
  "Split a vector into its leading styles map (or nil) and the rest."
  [v]
  (if (map? (first v))
    [(first v) (rest v)]
    [nil v]))

(defn content
  "Build the text content written as `text` in the notation."
  [text]
  (let [[root-style paragraphs] (split-styles text)
        paragraph (fn [p]
                    (let [[style runs] (split-styles p)
                          spans (mapv (fn [[letters style]]
                                        (assoc (run-attrs style) :text letters))
                                      runs)]
                      (merge (dissoc (first spans) :text)
                             (paragraph-attrs style)
                             {:type "paragraph" :children spans})))]
    {:type "root"
     :vertical-align (:valign root-style "top")
     :children [{:type "paragraph-set"
                 :children (mapv paragraph paragraphs)}]}))

(defn- style-diff
  "The short-name styles of `node` that differ from `base-style`. A value the
   short names cannot say shows under its own attribute."
  [node]
  (let [{:keys [font-id font-family font-variant-id font-size font-weight font-style
                letter-spacing text-transform text-decoration fills
                typography-ref-id typography-ref-file]} node
        weight  (->number font-weight)
        italic? (= font-style "italic")
        color   (when (and (= 1 (count fills))
                           (= 1 (:fill-opacity (first fills)))
                           (= #{:fill-color :fill-opacity} (set (keys (first fills)))))
                  (some (fn [[k v]] (when (= v (:fill-color (first fills))) k)) colors))
        ;; spans written by `txt/change-text` also carry the paragraph styles
        node    (cond-> node
                  (= (:text-align node) "left")    (dissoc :text-align)
                  (= (:text-direction node) "ltr") (dissoc :text-direction))
        known   #{:text :type :key :children :line-height
                  :font-id :font-family :font-variant-id :font-size :font-weight :font-style
                  :letter-spacing :text-transform :text-decoration :fills
                  :typography-ref-id :typography-ref-file}]
    (cond-> {}
      (not= font-family "sourcesanspro")
      (assoc :family font-family)

      (not= font-id (get font-ids font-family))
      (assoc :font-id font-id)

      (not= weight 400)
      (assoc :weight weight)

      italic?
      (assoc :italic true)

      (not (#{"normal" "italic"} font-style))
      (assoc :font-style font-style)

      (not= font-variant-id (variant-id font-family weight italic?))
      (assoc :font-variant-id font-variant-id)

      (not= font-size "14")
      (assoc :size (->number font-size))

      (not= letter-spacing "0")
      (assoc :spacing (->number letter-spacing))

      (not= text-transform "none")
      (assoc :case text-transform)

      (not= text-decoration "none")
      (assoc :decoration text-decoration)

      (and (some? color) (not= color :black))
      (assoc :color color)

      (nil? color)
      (assoc :fills fills)

      (some? typography-ref-id)
      (assoc :typography (thi/label typography-ref-id))

      (and (some? typography-ref-id) (not= typography-ref-file (thi/id :file1)))
      (assoc :typography-ref-file typography-ref-file)

      :always
      (merge (apply dissoc node known)))))

(defn- paragraph-diff
  "The paragraph styles of `paragraph` in the notation. A paragraph without
   `:line-height` (as the WASM editor writes them) takes its first run's."
  [paragraph]
  (let [{:keys [text-align text-direction]} paragraph
        line-height (or (:line-height paragraph)
                        (:line-height (first (:children paragraph))))]
    (cond-> {}
      (not= text-align "left")    (assoc :align text-align)
      (not= text-direction "ltr") (assoc :direction text-direction)
      (not= line-height "1.2")    (assoc :line-height (->number line-height)))))

(defn- join-runs
  [runs]
  (reduce (fn [acc [letters style :as run]]
            (let [[prev-letters prev-style] (peek acc)]
              (if (and (some? prev-letters) (= prev-style style))
                (conj (pop acc) [(str prev-letters letters) style])
                (conj acc run))))
          []
          runs))

(defn- view-run
  [[letters style]]
  (if (empty? style) [letters] [letters style]))

(defn view
  "Read `content` back into the notation."
  [content]
  (let [root-diff  (when (not= (:vertical-align content "top") "top")
                     {:valign (:vertical-align content)})
        paragraph  (fn [p]
                     (let [runs  (->> (:children p)
                                      (map (fn [node] [(:text node) (style-diff node)]))
                                      (join-runs))
                           runs  (if (> (count runs) 1)
                                   (remove (comp empty? first) runs)
                                   runs)
                           style (paragraph-diff p)]
                       (cond->> (mapv view-run runs)
                         (seq style) (into [style]))))
        paragraphs (mapv paragraph (txt/node-seq txt/is-paragraph-node? content))]
    (cond->> paragraphs
      (some? root-diff) (into [root-diff]))))

(defn normalize
  "`text` as `view` gives it back once built: equal styles joined, base
   styles left out."
  [text]
  (view (content text)))

(defn text-of
  "The text of the shape `label` in `file`, in the notation."
  [file label]
  (view (:content (ths/get-shape file label))))

(defn set-content
  "Replace the content of the text shape `label` with `content`, through the
   production change path, as an editor commit does."
  [file label content]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            #(assoc % :content content)
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

(defn set-text
  "Replace the content of the text shape `label` with `text` in the notation,
   as `set-content` does."
  [file label text]
  (set-content file label (content text)))

(defn add-text-component-with-copy
  "Add the component :component1, whose main :main-root holds the text
   :main-child written as `text`, and its copy :copy-root, whose text is
   :copy-child."
  [file text]
  (-> file
      (tho/add-frame-with-text :main-root :main-child ""
                               :child-params {:content (content text)})
      (thc/make-component :component1 :main-root)
      (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]})))

;; ---- Edits of a text in the notation

(def typography-styles
  "The short-name styles a typography sets: on runs, and `:line-height` on
   paragraphs."
  #{:family :weight :italic :size :spacing :case :line-height})

(defn- update-paragraphs
  "Apply `f` to each paragraph of `text` as `[styles runs]`; `f` returns the
   same pair."
  [text f]
  (let [[root-style paragraphs] (split-styles text)
        paragraphs (mapv (fn [p]
                           (let [[style runs] (f (split-styles p))]
                             (cond->> (vec runs)
                               (seq style) (into [style]))))
                         paragraphs)]
    (cond->> paragraphs
      (seq root-style) (into [root-style]))))

(defn- detach-typography
  "Drop the typography of runs when `k` is a style the typography sets, as
   the design panel does before changing it."
  [runs k]
  (if (contains? typography-styles k)
    (map (fn [[letters style]] [letters (dissoc style :typography)]) runs)
    runs))

(defn restyle
  "Set the run style `k` to `v` on every run of `text`.
  Named colors and explicit fills replace each other. Font edits replace
  stale raw identities while preserving the other font unit."
  [text k v]
  (update-paragraphs text
                     (fn [[style runs]]
                       [style (map (fn [[letters run-style]]
                                     [letters (cond-> (assoc run-style k v)
                                                (= k :color) (dissoc :fills)
                                                (= k :fills) (dissoc :color)
                                                (= k :family) (dissoc :font-family :font-id)
                                                (= k :weight) (dissoc :font-weight :font-variant-id)
                                                (= k :italic) (dissoc :font-style :font-variant-id))])
                                   (detach-typography runs k))])))

(defn restyle-paragraphs
  "Set the paragraph style `k` to `v` on every paragraph of `text`."
  [text k v]
  (update-paragraphs text
                     (fn [[style runs]]
                       [(assoc style k v) (detach-typography runs k)])))

(defn set-valign
  [text v]
  (let [[root-style paragraphs] (split-styles text)]
    (into [(assoc root-style :valign v)] paragraphs)))

(defn set-letters
  "Replace the letters of `text` with `letters`, a line break per paragraph,
   all in the styles of its first paragraph and first run."
  [text letters]
  (let [[root-style paragraphs] (split-styles text)
        [p-style runs] (split-styles (first paragraphs))
        [_ run-style]  (first runs)
        paragraphs (mapv (fn [line]
                           (cond->> [[line run-style]]
                             (seq p-style) (into [p-style])))
                         (str/split letters #"\n" -1))]
    (cond->> paragraphs
      (seq root-style) (into [root-style]))))

(defn apply-typography
  "Apply the typography `label`, whose styles are `styles` (see
   `typography-styles`), to all of `text`."
  [text label styles]
  (update-paragraphs text
                     (fn [[style runs]]
                       [(cond-> style
                          (contains? styles :line-height) (assoc :line-height (:line-height styles)))
                        (map (fn [[letters run-style]]
                               [letters (merge run-style
                                               (dissoc styles :line-height)
                                               {:typography label})])
                             runs)])))

;; ---- The override rule for texts
;;
;; Which overrides a copy text must hold, given its content and its near
;; main's: "different means touched" (`mem:common/component-sync-contract`)
;; for the text model of decisions (k)-(o). The letters (with line breaks)
;; are compared whole; each style unit is compared per letter, against the
;; main's style placed onto the copy's letters as sync places it:
;; 1. a unit with one value across the main applies to every letter;
;; 2. a letter both texts share, in a stretch of at least
;;    `min-shared-stretch` equal letters found by a letter diff, takes the
;;    main's value on that letter;
;; 3. any other letter takes the value of the letter before it, across line
;;    breaks; letters at the start take the first shared letter after them;
;;    with nothing shared, the main's first letter.
;; Paragraph units go by paragraph: a paragraph takes the unit of the main's
;; paragraph that holds its first shared letter, else follows the paragraph
;; before it. Vertical alignment compares the roots.

(def min-shared-stretch 3)

(def run-units
  "Style unit -> the run attributes it covers."
  {:family     [:font-family :font-id]
   :weight     [:font-weight :font-style :font-variant-id]
   :size       [:font-size]
   :spacing    [:letter-spacing]
   :decoration [:text-decoration]
   :case       [:text-transform]
   :fills      [:fills]
   :typography [:typography-ref-id :typography-ref-file]})

(def paragraph-units
  {:align       [:text-align]
   :direction   [:text-direction]
   :line-height [:line-height]})

(defn- letters-of
  "The letters of `content`, one entry per letter with its paragraph index
   and run node; line breaks are `\\n` entries with no run."
  [content]
  (let [paragraphs (vec (txt/node-seq txt/is-paragraph-node? content))]
    (vec
     (mapcat (fn [i p]
               (concat (when (pos? i) [{:letter \newline :paragraph i}])
                       (for [node (:children p)
                             c    (:text node)]
                         {:letter c :paragraph i :node node})))
             (range)
             paragraphs))))

(defn- lcs-pairs
  "The pairs [i j] of equal letters of `a` and `b` along a longest common
   subsequence, in order."
  [a b]
  (let [n (count a) m (count b)
        table (reduce (fn [t [i j]]
                        (assoc t [i j]
                               (if (= (nth a i) (nth b j))
                                 (inc (get t [(inc i) (inc j)] 0))
                                 (max (get t [(inc i) j] 0) (get t [i (inc j)] 0)))))
                      {}
                      (for [i (range (dec n) -1 -1) j (range (dec m) -1 -1)] [i j]))]
    (loop [i 0 j 0 acc []]
      (cond
        (or (>= i n) (>= j m)) acc
        (= (nth a i) (nth b j)) (recur (inc i) (inc j) (conj acc [i j]))
        (>= (get table [(inc i) j] 0) (get table [i (inc j)] 0)) (recur (inc i) j acc)
        :else (recur i (inc j) acc)))))

(defn shared-letters
  "Index in `b` -> index in `a` of the letters the strings share, in
   stretches of at least `min-shared-stretch` consecutive letters."
  [a b]
  (let [stretches (->> (lcs-pairs (vec a) (vec b))
                       (reduce (fn [acc [i j :as pair]]
                                 (let [[pi pj] (peek (peek acc))]
                                   (if (and pi (= i (inc pi)) (= j (inc pj)))
                                     (conj (pop acc) (conj (peek acc) pair))
                                     (conj acc [pair]))))
                               []))]
    (into {}
          (comp (filter #(>= (count %) min-shared-stretch))
                cat
                (map (fn [[i j]] [j i])))
          stretches)))

(defn- unit-value
  [node attrs]
  (let [v (select-keys node attrs)]
    ;; a default the editors leave out is the same as the default written
    (cond-> v
      (and (contains? (set attrs) :fills) (nil? (:fills v))) (assoc :fills nil))))

(defn- place-values
  "The values of the target's letters, from `source-values` (one per source
   letter, nil for line breaks) by rules 1-3, given `shared` (target index ->
   source index) and the target's line breaks `breaks?` (per letter)."
  [source-values shared breaks?]
  (let [values   (remove nil? source-values)
        n        (count breaks?)]
    (if (<= (count (distinct values)) 1)
      (mapv (fn [b] (when-not b (first values))) breaks?)
      (let [direct   (mapv (fn [i] (when-not (nth breaks? i)
                                     (some->> (get shared i) (nth source-values))))
                           (range n))
            first-shared (some (fn [i] (when (some? (nth direct i)) (nth direct i))) (range n))
            fallback (or first-shared (first values))]
        (first
         (reduce (fn [[acc prev] i]
                   (cond
                     (nth breaks? i)          [(conj acc nil) prev]
                     (some? (nth direct i))   [(conj acc (nth direct i)) (nth direct i)]
                     :else                    (let [v (or prev fallback)] [(conj acc v) v])))
                 [[] nil]
                 (range n)))))))

(defn override-units
  "The overrides a copy whose content is `copy` must hold against its near
   main's content `main`: `:letters`, `:valign`, and the keys of `run-units`
   and `paragraph-units` whose values differ from the main's placed onto the
   copy's letters."
  [main copy]
  (let [ml       (letters-of main)
        cl       (letters-of copy)
        shared   (shared-letters (map :letter ml) (map :letter cl))
        breaks?  (mapv #(nil? (:node %)) cl)
        main-ps  (vec (txt/node-seq txt/is-paragraph-node? main))
        copy-ps  (vec (txt/node-seq txt/is-paragraph-node? copy))
        run-diff (for [[unit attrs] run-units
                       :let [source (mapv #(some-> (:node %) (unit-value attrs)) ml)
                             placed (place-values source shared breaks?)
                             actual (mapv #(some-> (:node %) (unit-value attrs)) cl)]
                       :when (not= placed actual)]
                   unit)
        ;; a paragraph's first shared letter names the main paragraph it
        ;; follows
        para-of  (fn [i]
                   (some (fn [[k {:keys [paragraph]}]]
                           (when (= paragraph i)
                             (some->> (get shared k) (nth ml) :paragraph)))
                         (map-indexed vector cl)))
        para-diff (for [[unit attrs] paragraph-units
                        :let [values (mapv #(unit-value % attrs) main-ps)
                              placed (if (<= (count (distinct values)) 1)
                                       (vec (repeat (count copy-ps) (first values)))
                                       (first
                                        (reduce (fn [[acc prev] i]
                                                  (let [v (or (some->> (para-of i) (nth values))
                                                              prev
                                                              (first values))]
                                                    [(conj acc v) v]))
                                                [[] nil]
                                                (range (count copy-ps)))))
                              actual (mapv #(unit-value % attrs) copy-ps)]
                        :when (not= placed actual)]
                    unit)]
    (cond-> (into #{} (concat run-diff para-diff))
      (not= (txt/content->text main) (txt/content->text copy))
      (conj :letters)

      (not= (:vertical-align main "top") (:vertical-align copy "top"))
      (conj :valign))))
