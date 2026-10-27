;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.common
  "What the compare and conflict dialogs say about a diff entry, kept free
  of rendering: labels, values, categories, one-line summaries, the
  page/board a change lives on, and how a conflict splits into the
  properties that clash and the ones only one side touched."
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]))

;; --- Merge / update confirmations

(defn confirm-merge!
  "Open the merge dialog for `branch` (merge into main is irreversible; the
  dialog also lets the user choose whether to keep the branch archived).
  `excluded` is the compare dialog's per-change selection, if any."
  ([branch] (confirm-merge! branch nil))
  ([branch excluded]
   (modal/show! :merge-branch {:branch branch :excluded excluded})))

(defn confirm-update!
  "Ask for confirmation before updating `branch` from main (overwrites
  conflicting branch changes). `excluded` is the compare dialog's
  per-change selection, if any."
  ([branch] (confirm-update! branch nil))
  ([branch excluded]
   (st/emit! (modal/show {:type :confirm
                          :title (tr "workspace.branches.update.confirm-title")
                          :message (tr "workspace.branches.update.confirm-message" (:name branch))
                          :accept-label (tr "workspace.branches.update.confirm-accept")
                          :accept-style :primary
                          :on-accept (fn [_] (st/emit! (dwb/update-branch-from-main branch
                                                                                    {:excluded excluded})))}))))

;; --- Icons and type labels

(def kind->icon
  {:shape               i/board
   :component           i/component
   :color               i/picker
   :typography          i/text
   :media               i/img
   :page                i/document
   :page-attrs          i/document
   :page-order          i/document
   :page-guide          i/document
   :page-flow           i/document
   :page-grid           i/document
   :page-plugin         i/document
   :file-plugin         i/document
   :file-option         i/document
   :token               i/tokens
   :token-set           i/tokens
   :token-set-rename    i/tokens
   :token-set-order     i/tokens
   :token-theme         i/tokens
   :token-active-themes i/tokens
   :token-active-sets   i/tokens})

(def ^:private shape-type->icon
  {:frame "board" :rect "rectangle" :line "rectangle" :circle "ellipse"
   :path "path" :text "text" :image "img" :svg-raw "img" :group "group"
   :bool "boolean-union"})

(defn shape-icon-id
  "Type-accurate icon for a shape diff entry (component nature wins over
  raw type), falling back to the board icon for unknown types."
  [{:keys [shape-type component? component-copy? variant? masked?]}]
  (cond
    variant?        "variant"
    component?      "component"
    component-copy? "component-copy"
    (and (= :group shape-type) masked?) "mask"
    :else           (get shape-type->icon shape-type "board")))

(defn entry-icon
  [item]
  (if (= :shape (:kind item))
    (shape-icon-id item)
    (get kind->icon (:kind item) i/git-branch)))

(def ^:private kind->type-label
  {:shape               "workspace.branches.compare.type.shape"
   :component           "workspace.branches.compare.type.component"
   :color               "workspace.branches.compare.type.color"
   :typography          "workspace.branches.compare.type.typography"
   :media               "workspace.branches.compare.type.media"
   :token               "workspace.branches.compare.type.token"
   :page                "workspace.branches.review.type.page"
   :page-attrs          "workspace.branches.review.type.page-settings"
   :page-order          "workspace.branches.review.type.page-order"
   :page-guide          "workspace.branches.review.type.guides"
   :page-flow           "workspace.branches.review.type.flows"
   :page-grid           "workspace.branches.review.type.grids"
   :page-plugin         "workspace.branches.review.type.plugin-data"
   :file-plugin         "workspace.branches.review.type.plugin-data"
   :file-option         "workspace.branches.review.type.file-options"
   :token-set           "workspace.branches.review.type.token-set"
   :token-set-rename    "workspace.branches.review.type.token-set"
   :token-set-order     "workspace.branches.review.type.token-set-order"
   :token-theme         "workspace.branches.review.type.theme"
   :token-active-themes "workspace.branches.review.type.active-themes"
   :token-active-sets   "workspace.branches.review.type.active-sets"})

(def ^:private shape-type->label
  {:frame "workspace.branches.compare.shape.board"
   :rect "workspace.branches.compare.shape.rect"
   :line "workspace.branches.compare.shape.rect"
   :circle "workspace.branches.compare.shape.ellipse"
   :path "workspace.branches.compare.shape.path"
   :text "workspace.branches.compare.shape.text"
   :image "workspace.branches.compare.shape.image"
   :svg-raw "workspace.branches.compare.shape.image"
   :group "workspace.branches.compare.shape.group"
   :bool "workspace.branches.compare.shape.bool"})

(defn type-label-key
  "i18n key for an item's specific type — type-accurate for shapes
  (board/text/rectangle/component/…), generic for other kinds."
  [item]
  (if (= :shape (:kind item))
    (let [{:keys [component? component-copy? variant? shape-type]} item]
      (cond
        variant?        "workspace.branches.compare.shape.variant"
        component?      "workspace.branches.compare.shape.component"
        component-copy? "workspace.branches.compare.shape.component-copy"
        :else           (get shape-type->label shape-type
                             "workspace.branches.compare.type.shape")))
    (get kind->type-label (:kind item))))

;; --- Property labels and values

(defn attr-label
  "Human-readable label for a changed property key. The named attrs are
  the ones whose raw key means nothing to a user, so each carries a
  catalogue entry. Every other attr keeps its own key, humanised
  (kebab-case -> Capitalized words): a technical identifier rather than
  prose, and the last resort for an attr the catalogue does not name."
  [attr]
  (case attr
    (:rx :ry :r1 :r2 :r3 :r4) (tr "workspace.branches.compare.attr.corner-radius")
    :content         (tr "workspace.branches.review.attr.text")
    :fills           (tr "workspace.branches.review.attr.fill")
    :strokes         (tr "workspace.branches.review.attr.stroke")
    :layout-gap      (tr "workspace.branches.review.attr.gap")
    :layout-padding  (tr "workspace.branches.review.attr.padding")
    :grow-type       (tr "workspace.branches.compare.attr.text-auto-grow")
    :blend-mode      (tr "workspace.branches.compare.attr.blend-mode")
    :shape-ref       (tr "workspace.branches.compare.attr.component-reference")
    :component-id    (tr "workspace.branches.compare.attr.component")
    :component-file  (tr "workspace.branches.compare.attr.component-library")
    :hidden          (tr "workspace.branches.compare.attr.visibility")
    :blocked         (tr "workspace.branches.compare.attr.locked")
    :proportion-lock (tr "workspace.branches.compare.attr.lock-proportions")
    :parent-id       (tr "workspace.branches.compare.attr.parent")
    :frame-id        (tr "workspace.branches.compare.attr.container")
    (-> (name attr) (str/replace #"-" " ") str/capital)))

(defn hex-color
  "Return a usable hex string when `v` looks like a color (a hex string, a
  color map, or a fills/strokes vector), else nil."
  [v]
  (let [s (cond
            (string? v) v
            (and (map? v) (string? (:color v))) (:color v)
            (and (map? v) (string? (:fill-color v))) (:fill-color v)
            (and (map? v) (string? (:stroke-color v))) (:stroke-color v)
            (and (sequential? v) (map? (first v)))
            (or (:fill-color (first v)) (:stroke-color (first v)) (:color (first v)))
            :else nil)]
    ;; only valid hex lengths (3/4/6/8 digits); a bare string must carry
    ;; the leading `#`, otherwise casual words like "cafe" become swatches
    (when (and (string? s)
               (re-matches #"#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3,4}" s))
      s)))

(defn- fmt-number
  "At most two decimals, trailing zeros stripped (89.99999 -> \"90\",
  439.4999 -> \"439.5\", 386 -> \"386\"), grouped and punctuated the way
  the active locale writes numbers (\"439,5\" in Spanish)."
  [v]
  (let [locale (-> i18n/*current-locale*
                   (str/replace "_" "-"))]
    (.toLocaleString v locale #js {:maximumFractionDigits 2})))

(defn- short-str
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 40)
      (str (subs s 0 40) "…")
      s)))

(defn text-content?
  "True for the value of a text layer's `:content` attr."
  [v]
  (and (map? v) (= "root" (:type v))))

(defn content-paragraphs
  "The paragraphs of a text layer's content, as plain strings."
  [content]
  (if (text-content? content)
    (str/split (txt/content->text content) "\n")
    []))

(defn display-val
  "Compact, human-readable rendering of a property value. Raw uuids and
  collections are summarized rather than dumped (they carry no meaning to
  the user as raw data); a text layer's content reads as its text."
  [v]
  (cond
    (nil? v)         "—"
    (text-content? v) (display-val (str/join " " (content-paragraphs v)))
    (string? v)      (if (> (count v) 32) (str (subs v 0 32) "…") v)
    (number? v)      (fmt-number v)
    (boolean? v)     (if v
                       (tr "workspace.branches.compare.value-true")
                       (tr "workspace.branches.compare.value-false"))
    (keyword? v)     (name v)
    (ct/inst? v)     (ct/format-inst v :localized-date-time)
    (uuid? v)        (str (subs (str v) 0 8) "…")
    (sequential? v)  (or (hex-color v)
                         (tr "workspace.branches.compare.n-items" (i18n/c (count v))))
    (map? v)         (or (hex-color v)
                         (tr "workspace.branches.compare.n-props" (i18n/c (count v))))
    :else            (short-str v)))

(def ^:private technical-attrs
  "Attrs that say how the file stores a layer rather than how it looks.
  They stay in the folded technical details instead of the property
  table."
  #{:id :parent-id :frame-id :shape-ref :component-id :component-file
    :component-root :main-instance :touched :transform :transform-inverse
    :selrect :points :position-data :plugin-data :applied-tokens :shapes
    :remote-synced :variant-id :variant-name :variant-properties})

(defn visible-attrs
  "The changed attrs a person reads: the ones that are not technical."
  [changed-attrs]
  (into [] (remove (fn [[attr _]] (contains? technical-attrs attr))) changed-attrs))

;; --- Categories

(def ^:private copy-attrs #{:content :name})
(def ^:private colour-attrs #{:fills :strokes :fill-color :stroke-color :color :gradient
                              :shadow :opacity :blend-mode})
(def ^:private layout-attrs #{:layout :layout-flex-dir :layout-gap :layout-padding
                              :layout-padding-type :layout-gap-type :layout-wrap-type
                              :layout-justify-content :layout-align-items
                              :layout-align-content :layout-justify-items
                              :layout-grid-rows :layout-grid-columns :layout-grid-cells
                              :layout-item-margin :layout-item-h-sizing
                              :layout-item-v-sizing :layout-item-align-self
                              :x :y :width :height :rotation :constraints-h
                              :constraints-v})

(def categories
  "Category order, the tone, label key and icon of each one. Colour is
  kept for what happened to an entry (added, modified, deleted, in
  conflict), so the categories are neutral and told apart by their icon;
  decisions alone stay in the conflict colour, as they are conflicts."
  [[:decisions  "warning" "workspace.branches.review.category.decisions"  i/triangle-alert]
   [:copy       "neutral" "workspace.branches.review.category.copy"       i/text]
   [:colour     "neutral" "workspace.branches.review.category.colour"     i/swatches]
   [:layout     "neutral" "workspace.branches.review.category.layout"     i/flex]
   [:components "neutral" "workspace.branches.review.category.components" i/component]
   [:variants   "neutral" "workspace.branches.review.category.variants"   i/variant]
   [:tokens     "neutral" "workspace.branches.review.category.tokens"     i/tokens]
   [:other      "neutral" "workspace.branches.review.category.other"      i/layers]])

(def category->tone
  (into {} (map (fn [[k tone _ _]] [k tone])) categories))

(def category->label
  (into {} (map (fn [[k _ label _]] [k label])) categories))

(def category->icon
  (into {} (map (fn [[k _ _ icon]] [k icon])) categories))

(defn category
  "The one category a diff entry falls under, most specific first: a
  conflict is a decision; a variant is a variant change; a component or
  a copy is a component change; a token is a token change; the attrs of a modified
  layer decide between copy, colour and layout."
  [item]
  (let [kind  (:kind item)
        attrs (set (keys (:changed-attrs item)))]
    (cond
      (= :conflict (:status item)) :decisions
      (:variant? item) :variants
      (or (= :component kind) (:component? item) (:component-copy? item))
      :components
      (contains? #{:token :token-set :token-set-rename :token-set-order :token-theme
                   :token-active-themes :token-active-sets} kind)
      :tokens
      (contains? #{:color} kind) :colour
      (contains? #{:typography} kind) :copy
      (some copy-attrs attrs) :copy
      (some colour-attrs attrs) :colour
      (some layout-attrs attrs) :layout
      :else :other)))

(defn status-tone
  "The colour of the bar on a change row: what happened to the entry."
  [status]
  (case status
    :added    "success"
    :deleted  "error"
    :conflict "warning"
    "info"))

;; --- One-line summaries

(defn- change-phrase
  "What changing `attr` did, as a short phrase: the kind of change rather
  than its values, which the detail pane shows."
  [attr]
  (case attr
    :content                          (tr "workspace.branches.review.summary.text")
    :name                             (tr "workspace.branches.review.summary.renamed")
    (:x :y)                           (tr "workspace.branches.review.summary.moved")
    (:width :height)                  (tr "workspace.branches.review.summary.resized")
    :rotation                         (tr "workspace.branches.review.summary.rotated")
    (:parent-id :frame-id)            (tr "workspace.branches.review.summary.reparented")
    :hidden                           (tr "workspace.branches.review.summary.visibility")
    (:layout-gap :layout-padding :layout-padding-type :layout-gap-type)
    (tr "workspace.branches.review.summary.spacing")
    (:layout :layout-flex-dir :layout-wrap-type :layout-justify-content
             :layout-align-items :layout-align-content :layout-justify-items
             :layout-grid-rows :layout-grid-columns :layout-grid-cells)
    (tr "workspace.branches.review.summary.layout")
    (tr "workspace.branches.review.summary.attr" (attr-label attr))))

(defn change-summary
  "One line that says what a change did, in words: what was added or
  deleted, or the kinds of change a modification made (\"Fill changed\",
  \"Moved\"), two at most and a count of the rest. The values belong to
  the detail pane."
  [item]
  (let [status  (:status item)
        attrs   (visible-attrs (:changed-attrs item))
        lbl     (some-> (type-label-key item) tr)
        phrases (into [] (comp (map (comp change-phrase first)) (distinct)) attrs)
        shown   (take 2 phrases)
        more    (- (count phrases) (count shown))]
    (case status
      :added   (tr "workspace.branches.compare.subtitle-new" (or lbl ""))
      :deleted (tr "workspace.branches.review.summary.deleted" (or lbl ""))
      (if (empty? phrases)
        (or lbl (tr "workspace.branches.review.summary.changed"))
        (cond-> (str/join ", " shown)
          (pos? more)
          (dm/str " " (tr "workspace.branches.review.summary.more" (i18n/c more))))))))

;; --- Where a change lives: page and board

(defn top-board
  "The top-level board that holds shape `id` in `objects` (the shape itself
  when it is one), or nil when the shape is not in `objects` or sits on
  the page root."
  [objects id]
  (loop [shape (get objects id)
         depth 0]
    (when (and shape (< depth 64))
      (let [frame-id (:frame-id shape)]
        (if (and (= :frame (:type shape))
                 (or (nil? frame-id) (= frame-id uuid/zero)))
          (when-not (= (:id shape) uuid/zero) shape)
          (recur (get objects (if (= :frame (:type shape))
                                frame-id
                                (or frame-id (:parent-id shape))))
                 (inc depth)))))))

(defn group-by-place
  "Group the clean changes (`[index item]` pairs) by page, and inside a
  page by the top-level board that holds them, reading names from the
  open file's `pages-index`. Entries that belong to no page (components,
  colours, typographies, tokens) go to a last `:library` group, after a
  `:pages` group for the pages added, deleted or reordered. Each
  group is `{:id :name :page-id :rows [[idx item]] :boards [{:id :name
  :rows}]}`, with `:rows` holding the entries outside any board. A board
  groups its changes only when it holds two or more of `rows`."
  [rows pages-index pages]
  (let [by-page (group-by (fn [[_ item]] (:page-id item)) rows)
        page-group
        (fn [page-id page-rows]
          (let [page    (get pages-index page-id)
                objects (:objects page)
                placed  (map (fn [[_ item :as row]]
                               (let [board (when (= :shape (:kind item))
                                             (top-board objects (:id item)))]
                                 [row board]))
                             page-rows)
                ;; a board folds its changes only when it holds two or
                ;; more of the listed ones: a lone change stays loose, as
                ;; the fold would only repeat it
                sizes   (frequencies (keep (comp :id second) placed))
                placed  (map (fn [[row board]]
                               [row (when (> (get sizes (:id board) 0) 1) board)])
                             placed)
                boards  (->> placed
                             (filter second)
                             (group-by (comp :id second))
                             (map (fn [[board-id entries]]
                                    {:id board-id
                                     :name (:name (second (first entries)))
                                     :rows (mapv first entries)}))
                             (sort-by :name)
                             vec)]
            {:id      (str page-id)
             :page-id page-id
             :name    (or (:name page) (tr "workspace.branches.review.unknown-page"))
             :rows    (into [] (comp (remove second) (map first)) placed)
             :boards  boards
             :count   (count page-rows)}))
        page-order (into {} (map-indexed (fn [idx id] [id idx])) pages)
        loose      (get by-page nil)
        page-kind  #(contains? #{:page :page-order} (:kind (second %)))
        page-rows  (filterv page-kind loose)
        library    (filterv (complement page-kind) loose)]
    (cond-> (->> (dissoc by-page nil)
                 (map (fn [[page-id page-rows]] (page-group page-id page-rows)))
                 (sort-by #(get page-order (:page-id %) 9999))
                 vec)
      (seq page-rows)
      (conj {:id     "pages"
             :name   (tr "workspace.branches.review.group.pages")
             :rows   page-rows
             :boards []
             :count  (count page-rows)})

      (seq library)
      (conj {:id     "library"
             :name   (tr "workspace.branches.review.group.library")
             :rows   library
             :boards []
             :count  (count library)}))))

;; --- Conflicts: what clashes and what one side did alone

(defn- attr-sources
  "Every changed attr of a conflict with who changed it, using the base
  value both sides started from. `:clash` attrs moved on both sides; a
  `:main` or `:branch` attr moved on that side alone, so its value is not
  in doubt. Without a base (both sides added the entity) every attr
  clashes."
  [conflict]
  (let [base      (:base conflict)
        has-base? (map? base)]
    (->> (:changed-attrs conflict)
         (map (fn [[attr {:keys [main branch]}]]
                (let [base-v (get base attr)]
                  {:attr   attr
                   :base   base-v
                   :main   main
                   :branch branch
                   :source (cond
                             (not has-base?)    :clash
                             (= base-v main)    :branch
                             (= base-v branch)  :main
                             :else              :clash)})))
         (sort-by (comp attr-label :attr))
         vec)))

(defn conflict-attr-rows
  "The attrs of a conflict a person decides on (see `attr-sources`).
  Technical attrs are left out: they follow the visible ones they come
  with. When only technical attrs clash, they are shown instead, so the
  conflict can still be decided."
  [conflict]
  (let [all     (attr-sources conflict)
        visible (filterv #(not (contains? technical-attrs (:attr %))) all)]
    (if (and (not-any? #(= :clash (:source %)) visible)
             (some #(= :clash (:source %)) all))
      all
      visible)))

(defn clash-attrs
  [rows]
  (into [] (comp (filter #(= :clash (:source %))) (map :attr)) rows))

(defn- hidden-clashes
  "The clashing attrs `conflict-attr-rows` does not show."
  [conflict]
  (let [shown (into #{} (map :attr) (conflict-attr-rows conflict))]
    (into []
          (comp (filter #(= :clash (:source %)))
                (map :attr)
                (remove shown))
          (attr-sources conflict))))

(defn prefill-resolution
  "The per-attr resolution a conflict starts with: every attr one side
  changed alone takes that side. The clashing attrs stay out, so the
  conflict is not resolved until the user picks them; a conflict without
  a clash comes out resolved."
  [conflict]
  (into {}
        (comp (remove #(= :clash (:source %)))
              (map (juxt :attr :source)))
        (attr-sources conflict)))

(defn per-attr?
  "Whether a conflict can be decided property by property."
  [conflict]
  (and (seq (:changed-attrs conflict))
       (map? (:main conflict))
       (map? (:branch conflict))))

(defn choose-attr
  "The resolution of `conflict` once `attr` takes `side`, starting from
  `res` (nil, a whole-side keyword or a per-attr map)."
  [conflict res attr side]
  (let [seed (cond
               (map? res)      res
               (= res :branch) (zipmap (keys (:changed-attrs conflict)) (repeat :branch))
               (= res :main)   (zipmap (keys (:changed-attrs conflict)) (repeat :main))
               :else           (prefill-resolution conflict))]
    (reduce (fn [res hidden] (assoc res hidden side))
            (assoc seed attr side)
            (hidden-clashes conflict))))

(defn choose-side
  "The resolution of `conflict` when every clashing attr takes `side`; the
  independent edits keep the side that made them."
  [conflict side]
  (if (per-attr? conflict)
    (let [rows (conflict-attr-rows conflict)]
      (reduce (fn [res attr] (assoc res attr side))
              (prefill-resolution conflict)
              (into (clash-attrs rows) (hidden-clashes conflict))))
    side))

(defn attr-choice
  "Currently-selected side (`:main`/`:branch`) for `attr` under resolution
  `res`, or nil when undecided."
  [res attr]
  (cond
    (= res :branch) :branch
    (= res :main)   :main
    (map? res)      (get res attr)
    :else           nil))

(defn dropped-values
  "What a decided conflict leaves behind: for each attr where the other
  side changed the value and lost, `{:attr :side :value}` naming the side
  whose edit is dropped."
  [conflict res]
  (let [rows (conflict-attr-rows conflict)]
    (into []
          (keep (fn [{:keys [attr base main branch]}]
                  (let [choice (attr-choice res attr)
                        has-base? (map? (:base conflict))]
                    (cond
                      (and (= choice :branch) (or (not has-base?) (not= main base)))
                      {:attr attr :side :main :value main}

                      (and (= choice :main) (or (not has-base?) (not= branch base)))
                      {:attr attr :side :branch :value branch}

                      :else nil))))
          rows)))

(defn paragraph-rows
  "The paragraphs of one side of a text conflict, each marked `:kept` when
  this side edited it, `:dropped` when only the other side edited it (so
  keeping this side drops that edit), or nil when neither did."
  [conflict side]
  (let [other  (if (= side :main) :branch :main)
        base   (content-paragraphs (get-in conflict [:base :content]))
        mine   (content-paragraphs (get-in conflict [side :content]))
        theirs (content-paragraphs (get-in conflict [other :content]))]
    (into []
          (map-indexed
           (fn [idx text]
             (let [b (nth base idx nil)
                   t (nth theirs idx nil)]
               {:index  idx
                :text   text
                :status (cond
                          (not= text b) :kept
                          (and (some? t) (not= t b)) :dropped
                          :else nil)})))
          mine)))

;; --- What a new or deleted entry is, where it lands, what it affects

(defn page-contents
  "The top-level boards of `page` and how many layers it holds, the root
  frame aside."
  [page]
  (let [objects (:objects page)
        root    (get objects uuid/zero)]
    {:boards (into []
                   (comp (map #(get objects %))
                         (filter #(= :frame (:type %))))
                   (:shapes root))
     :layers (max 0 (dec (count objects)))}))

(defn shape-path
  "The names of the containers that hold shape `id`, from the outermost
  board to its direct parent."
  [objects id]
  (loop [parent-id (:parent-id (get objects id))
         path      ()
         depth     0]
    (let [parent (get objects parent-id)]
      (if (and parent (not= parent-id uuid/zero) (< depth 64))
        (recur (:parent-id parent) (conj path (:name parent)) (inc depth))
        (vec path)))))

(defn- all-shapes
  [data]
  (mapcat (comp vals :objects) (vals (:pages-index data))))

(defn component-copies
  "How many copies of component `component-id` the file `data` holds."
  [data component-id]
  (count (filter #(and (= component-id (:component-id %))
                       (not (:main-instance %)))
                 (all-shapes data))))

(defn color-usages
  "How many layers of `data` fill or stroke with library colour
  `color-id`."
  [data color-id]
  (count (filter (fn [shape]
                   (or (some #(= color-id (:fill-color-ref-id %)) (:fills shape))
                       (some #(= color-id (:stroke-color-ref-id %)) (:strokes shape))))
                 (all-shapes data))))

(defn typography-usages
  "How many text layers of `data` use library typography
  `typography-id`."
  [data typography-id]
  (count (filter (fn [shape]
                   (and (= :text (:type shape))
                        (some #(= typography-id (:typography-ref-id %))
                              (txt/node-seq (:content shape)))))
                 (all-shapes data))))

(defn token-usages
  "How many layers of `data` apply the token named `token-name`."
  [data token-name]
  (count (filter (fn [shape]
                   (some #(= token-name %) (vals (:applied-tokens shape))))
                 (all-shapes data))))
