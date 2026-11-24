;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-compare.common
  "What the compare mode says about a diff entry, kept free of rendering:
  labels, values, one-line summaries, the page/board a change lives on,
  the list the changes make and the marks they leave on the canvas, how a
  conflict splits into the properties in conflict and the ones only one
  side touched, and what the copy rule decides for the copies of a changed
  component."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.common.time :as ct]
   [app.common.types.component :as ctk]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]))

;; --- Merge / update confirmations

(defn confirm-merge!
  "Open the merge dialog for `branch` (merge into main is irreversible and
  deletes the branch; the dialog asks for a comment for the merge's
  record). `excluded` is the per-change selection of the comparison, if
  any."
  ([branch] (confirm-merge! branch nil))
  ([branch excluded]
   (modal/show! :merge-branch {:branch branch :excluded excluded})))

(defn confirm-update!
  "Ask for confirmation before updating `branch` from main (overwrites
  conflicting branch changes). `excluded` is the per-change selection of
  the comparison, if any."
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
   :token-active-sets   i/tokens
   :tokens-source       i/tokens})

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

(defn- shape-type-label
  [shape-type]
  (case shape-type
    :frame            (tr "workspace.branches.compare.shape.board")
    (:rect :line)     (tr "workspace.branches.compare.shape.rect")
    :circle           (tr "workspace.branches.compare.shape.ellipse")
    :path             (tr "workspace.branches.compare.shape.path")
    :text             (tr "workspace.branches.compare.shape.text")
    (:image :svg-raw) (tr "workspace.branches.compare.shape.image")
    :group            (tr "workspace.branches.compare.shape.group")
    :bool             (tr "workspace.branches.compare.shape.bool")
    (tr "workspace.branches.compare.type.shape")))

(defn type-label
  "The name of an item's specific type: type-accurate for shapes
  (board/text/rectangle/component/...), generic for other kinds, nil for a
  kind with no name."
  [item]
  (case (:kind item)
    :shape (let [{:keys [component? component-copy? variant? shape-type]} item]
             (cond
               variant?        (tr "workspace.branches.compare.shape.variant")
               component?      (tr "workspace.branches.compare.shape.component")
               component-copy? (tr "workspace.branches.compare.shape.component-copy")
               :else           (shape-type-label shape-type)))
    :component           (tr "workspace.branches.compare.type.component")
    :color               (tr "workspace.branches.compare.type.color")
    :typography          (tr "workspace.branches.compare.type.typography")
    :media               (tr "workspace.branches.compare.type.media")
    :token               (tr "workspace.branches.compare.type.token")
    :page                (tr "workspace.branches.compare.type.page")
    :page-attrs          (tr "workspace.branches.compare.type.page-settings")
    :page-order          (tr "workspace.branches.compare.type.page-order")
    :page-guide          (tr "workspace.branches.compare.type.guides")
    :page-flow           (tr "workspace.branches.compare.type.flows")
    :page-grid           (tr "workspace.branches.compare.type.grids")
    (:page-plugin :file-plugin) (tr "workspace.branches.compare.type.plugin-data")
    :file-option         (tr "workspace.branches.compare.type.file-options")
    (:token-set :token-set-rename) (tr "workspace.branches.compare.type.token-set")
    :token-set-order     (tr "workspace.branches.compare.type.token-set-order")
    :token-theme         (tr "workspace.branches.compare.type.theme")
    :token-active-themes (tr "workspace.branches.compare.type.active-themes")
    :token-active-sets   (tr "workspace.branches.compare.type.active-sets")
    :tokens-source       (tr "workspace.branches.compare.type.tokens-source")
    nil))

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
    :content         (tr "workspace.branches.compare.attr.text")
    :fills           (tr "workspace.branches.compare.attr.fill")
    :strokes         (tr "workspace.branches.compare.attr.stroke")
    :layout-gap      (tr "workspace.branches.compare.attr.gap")
    :layout-padding  (tr "workspace.branches.compare.attr.padding")
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
  the active locale writes numbers (\"439,5\" in Spanish). It goes
  through `Intl.NumberFormat`: once a plugin has loaded, the SES lockdown
  turns `Number.prototype.toLocaleString` into `toString`, options and
  all."
  [v]
  (let [locale (or (some-> i18n/*current-locale*
                           (str/replace "_" "-"))
                   js/undefined)]
    (.format (js/Intl.NumberFormat. locale #js {:maximumFractionDigits 2}) v)))

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

;; --- Scope

(def ^:private page-kinds
  #{:page :page-order})

(defn scope
  "Where a diff entry belongs, as the filters of the list tell them
  apart: `:layers` for what lives on a page (layers, and the pages
  themselves), `:library` for what the whole file shares (components,
  colours, typographies, tokens, file options)."
  [item]
  (if (or (some? (:page-id item)) (contains? page-kinds (:kind item)))
    :layers
    :library))

(defn status-tone
  "The colour of the bar on a change row: what happened to the entry. A
  copy the copy rule settled is information, not a change."
  [status]
  (case status
    :added    "success"
    :deleted  "error"
    :conflict "warning"
    :settled  "neutral"
    "info"))

;; --- One-line summaries

(defn- change-phrase
  "What changing `attr` did, as a short phrase: the kind of change rather
  than its values, which the detail pane shows."
  [attr]
  (case attr
    :content                          (tr "workspace.branches.compare.summary.text")
    :name                             (tr "workspace.branches.compare.summary.renamed")
    (:x :y)                           (tr "workspace.branches.compare.summary.moved")
    (:width :height)                  (tr "workspace.branches.compare.summary.resized")
    :rotation                         (tr "workspace.branches.compare.summary.rotated")
    (:parent-id :frame-id)            (tr "workspace.branches.compare.summary.reparented")
    :hidden                           (tr "workspace.branches.compare.summary.visibility")
    (:layout-gap :layout-padding :layout-padding-type :layout-gap-type)
    (tr "workspace.branches.compare.summary.spacing")
    (:layout :layout-flex-dir :layout-wrap-type :layout-justify-content
             :layout-align-items :layout-align-content :layout-justify-items
             :layout-grid-rows :layout-grid-columns :layout-grid-cells)
    (tr "workspace.branches.compare.summary.layout")
    (tr "workspace.branches.compare.summary.attr" (attr-label attr))))

(defn change-summary
  "One line that says what a change did, in words: what was added or
  deleted, or the kinds of change a modification made (\"Fill changed\",
  \"Moved\"), two at most and a count of the rest. The values belong to
  the detail pane."
  [item]
  (let [status  (:status item)
        attrs   (visible-attrs (:changed-attrs item))
        lbl     (type-label item)
        phrases (into [] (comp (map (comp change-phrase first)) (distinct)) attrs)
        shown   (take 2 phrases)
        more    (- (count phrases) (count shown))]
    (case status
      :added   (tr "workspace.branches.compare.subtitle-new" (or lbl ""))
      :deleted (tr "workspace.branches.compare.summary.deleted" (or lbl ""))
      :settled (tr "workspace.branches.compare.copies.settled-row")
      (if (empty? phrases)
        (or lbl (tr "workspace.branches.compare.summary.changed"))
        (cond-> (str/join ", " shown)
          (pos? more)
          (dm/str " " (tr "workspace.branches.compare.summary.more" (i18n/c more))))))))

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

(defn- cascade-entry?
  "An added or deleted layer that carries its shape: one that can take
  the layers added or deleted with it."
  [item]
  (and (= :shape (:kind item))
       (contains? #{:added :deleted} (:status item))
       (some? (:shape item))))

(defn fold-cascades
  "The added or deleted layers that only follow a container added or
  deleted with them, folded under the outermost one: deleting a group
  deletes its layers too, and listing each one apart would repeat the
  same change. Returns a map from the key (`dwcp/item-key`) of each
  outermost entry to the entries it holds."
  [items]
  (let [by-id   (into {}
                      (keep (fn [item]
                              (when (cascade-entry? item)
                                [[(:page-id item) (:id item)] item])))
                      items)
        root-of (fn [item]
                  (loop [cur item root nil depth 0]
                    (let [parent (get by-id [(:page-id item) (get-in cur [:shape :parent-id])])]
                      (if (and parent (= (:status parent) (:status item)) (< depth 64))
                        (recur parent parent (inc depth))
                        root))))]
    (reduce (fn [acc item]
              (if-let [root (when (cascade-entry? item) (root-of item))]
                (update acc (dwcp/item-key root) (fnil conj []) item)
                acc))
            {}
            items)))

;; --- What goes with an added or deleted layer

(defn- lost-interactions
  "The interactions of modified entry `entry` that lead into `ids` on the
  `from` side and are gone on the `to` side."
  [entry ids from to]
  (let [values (get-in entry [:changed-attrs :interactions])
        kept   (set (get values to))]
    (filterv #(and (contains? ids (:destination %)) (not (contains? kept %)))
             (get values from))))

(defn- size-of
  "Width and height of `shape` on `side`, taking the changed ones from
  diff entry `entry`."
  [shape entry side]
  (let [attrs (:changed-attrs entry)]
    [(get-in attrs [:width side] (:width shape))
     (get-in attrs [:height side] (:height shape))]))

(defn shape-effects
  "What else changes with the added or deleted layer `item`, read from
  the other entries of the diff (`items`):

    `:interactions` the layers whose prototype interactions led into it
                    and go with it (`{:id :label :count}`);
    `:flows`        the flows that started on it and go with it (names);
    `:layouts`      the boards with a layout around it that resize
                    (`{:id :name :layout :from [w h] :to [w h]}`) or
                    whose other layers move (`{... :moved n}`).

  Penpot makes these changes itself when a layer goes, so the diff lists
  them as entries of their own; this ties them to their cause. `objects`
  is the page as the side that holds the layer has it, `inner` the
  entries folded under it (`fold-cascades`), and `incoming` says the
  changes are the source's, going to the branch, rather than the branch's
  going to the source."
  [item items objects inner incoming]
  (let [from     (if incoming :branch :source)
        to       (if incoming :source :branch)
        deleted? (= :deleted (:status item))
        ids      (into #{(:id item)} (map :id) inner)
        page-id  (:page-id item)
        on-page  (filterv #(= page-id (:page-id %)) items)
        modified (into {}
                       (comp (filter #(and (= :shape (:kind %)) (= :modified (:status %))))
                             (map (juxt :id identity)))
                       on-page)
        ancestors (loop [id (:parent-id (get objects (:id item))) out [] depth 0]
                    (let [shape (get objects id)]
                      (if (and shape (not= id uuid/zero) (< depth 64))
                        (recur (:parent-id shape) (conj out shape) (inc depth))
                        out)))]
    {:interactions
     (if deleted?
       (into []
             (keep (fn [[id entry]]
                     (when-not (contains? ids id)
                       (let [lost (lost-interactions entry ids from to)]
                         (when (seq lost)
                           {:id id :label (:label entry) :count (count lost)})))))
             modified)
       [])

     :flows
     (if deleted?
       (into []
             (keep (fn [entry]
                     (when (and (= :page-flow (:kind entry))
                                (= :deleted (:status entry))
                                (contains? ids (get-in entry [:flow :starting-frame])))
                       (or (get-in entry [:flow :name]) (:label entry)))))
             on-page)
       [])

     :layouts
     (into []
           (keep (fn [{:keys [id layout] :as shape}]
                   (when (some? layout)
                     (let [entry  (get modified id)
                           before (size-of shape entry from)
                           after  (size-of shape entry to)
                           moved  (count (filter (fn [[sid e]]
                                                   (and (= id (:parent-id (get objects sid)))
                                                        (not (contains? ids sid))
                                                        (some #(contains? (:changed-attrs e) %) [:x :y])))
                                                 modified))]
                       (cond
                         (not= before after)
                         {:id id :name (:name shape) :layout layout :from before :to after}

                         (pos? moved)
                         {:id id :name (:name shape) :layout layout :moved moved})))))
           ancestors)}))

(defn group-by-place
  "Group the clean changes `items` by page, and inside a page by the
  top-level board that holds them, reading names from the open file's
  `pages-index`. Entries that belong to no page (components, colours,
  typographies, tokens) go to a last `:library` group, after a `:pages`
  group for the pages added, deleted or reordered. Each group is
  `{:id :name :page-id :rows [item] :boards [{:id :name :rows}]}`, with
  `:rows` holding the entries outside any board. A board groups its
  changes only when it holds two or more of `items`. `:count` leaves out
  the copies the copy rule settled (`bm/settled?`)."
  [items pages-index pages]
  (let [by-page (group-by :page-id items)
        page-group
        (fn [page-id page-items]
          (let [page    (get pages-index page-id)
                objects (:objects page)
                placed  (map (fn [item]
                               [item (when (= :shape (:kind item))
                                       (top-board objects (:id item)))])
                             page-items)
                ;; a board folds its changes only when it holds two or
                ;; more of the listed ones: a lone change stays loose, as
                ;; the fold would only repeat it
                sizes   (frequencies (keep (comp :id second) placed))
                placed  (map (fn [[item board]]
                               [item (when (> (get sizes (:id board) 0) 1) board)])
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
             :name    (or (:name page) (tr "workspace.branches.compare.unknown-page"))
             :rows    (into [] (comp (remove second) (map first)) placed)
             :boards  boards
             :count   (count (remove bm/settled? page-items))}))
        page-order (into {} (map-indexed (fn [idx id] [id idx])) pages)
        loose      (get by-page nil)
        page-kind? #(contains? page-kinds (:kind %))
        page-rows  (filterv page-kind? loose)
        library    (filterv (complement page-kind?) loose)]
    (cond-> (->> (dissoc by-page nil)
                 (map (fn [[page-id page-items]] (page-group page-id page-items)))
                 (sort-by #(get page-order (:page-id %) 9999))
                 vec)
      (seq page-rows)
      (conj {:id     "pages"
             :name   (tr "workspace.branches.compare.group.pages")
             :rows   page-rows
             :boards []
             :count  (count page-rows)})

      (seq library)
      (conj {:id     "library"
             :name   (tr "workspace.branches.compare.group.library")
             :rows   library
             :boards []
             :count  (count library)}))))

;; --- The list of changes

(defn selectable-item?
  "An entry the per-change selection covers: a clean change. A conflict
  keeps its resolution instead, and an `:unsupported` entry is a refusal
  the merge raises whatever the selection says."
  [item]
  (contains? #{:added :modified :deleted} (:status item)))

(defn conflict?
  [item]
  (= :conflict (:status item)))

(defn row-keys
  "The change keys a row stands for: its own and those of the layers
  folded under it (`fold-cascades`), which travel or stay with it."
  [item inner]
  (into #{(:key item)} (map :key) inner))

(defn change-model
  "What the list of a comparison shows, from its `diff` and the open
  file (`pages-index`, `pages`), with the filter in use (`:filter`) and
  the per-change selection `excluded` applied:

    `:items`     every change and conflict of the diff;
    `:cascades`  the entries folded under another one (`fold-cascades`);
    `:listed`    the entries the list shows, the folded ones left out;
    `:counted`   the listed ones that count as changes: a copy the copy
                 rule settled is shown, never counted;
    `:counts`    how many each filter keeps (`:all`, `:layers`,
                 `:library`, `:left-out`);
    `:conflicts` the conflicts the filter keeps;
    `:groups`    the clean changes it keeps, by place (`group-by-place`);
    `:rows`      the entries it keeps, in the order the list shows them;
    `:decisions` how many conflicts wait for a choice;
    `:kept`      how many clean changes travel, and `:left-out` how many
                 stay: a folded layer goes with its container, so both
                 count the rows the list shows."
  [diff {:keys [excluded pages-index pages] active-filter :filter}]
  (let [items      (into (vec (:changes diff)) (:conflicts diff))
        excluded   (or excluded #{})
        cascades   (fold-cascades items)
        folded     (into #{} (comp (mapcat val) (map dwcp/item-key)) cascades)
        listed     (filterv #(not (contains? folded (dwcp/item-key %))) items)
        left-out?  #(and (selectable-item? %) (contains? excluded (:key %)))
        visible    (filterv (fn [item]
                              (case active-filter
                                :left-out (left-out? item)
                                :layers   (= :layers (scope item))
                                :library  (= :library (scope item))
                                true))
                            listed)
        conflicts  (filterv conflict? visible)
        groups     (group-by-place (filterv (complement conflict?) visible) pages-index pages)
        selectable (filterv selectable-item? listed)
        left-out   (count (filter left-out? selectable))
        counted    (filterv (complement bm/settled?) listed)]
    {:items     items
     :cascades  cascades
     :listed    listed
     :counted   counted
     :counts    {:all      (count counted)
                 :layers   (count (filter #(= :layers (scope %)) counted))
                 :library  (count (filter #(= :library (scope %)) counted))
                 :left-out left-out}
     :conflicts conflicts
     :groups    groups
     :rows      (into conflicts
                      (mapcat (fn [{:keys [rows boards]}]
                                (concat rows (mapcat :rows boards))))
                      groups)
     :decisions (count (filter conflict? items))
     :kept      (- (count selectable) left-out)
     :left-out  left-out}))

(defn step
  "The key of the entry `offset` rows away from `selected` in `rows`,
  going round at the ends; the first row when nothing is chosen. Nil for
  an empty list."
  [rows selected offset]
  (when (seq rows)
    (let [pos (first (keep-indexed (fn [idx item]
                                     (when (= selected (dwcp/item-key item)) idx))
                                   rows))]
      (dwcp/item-key (if (nil? pos)
                       (first rows)
                       (nth rows (mod (+ pos offset) (count rows))))))))

(defn next-pending
  "The key of the first row after `selected` (going round) that `done?`
  does not hold for, or nil when every row is done. `rows` are the
  entries in the order of the list."
  [rows selected done?]
  (let [pos   (or (first (keep-indexed (fn [idx item]
                                         (when (= selected (dwcp/item-key item)) idx))
                                       rows))
                  -1)
        start (inc pos)
        ahead (concat (drop start rows) (take start rows))]
    (some (fn [item]
            (when (and (not= selected (dwcp/item-key item)) (not (done? item)))
              (dwcp/item-key item)))
          ahead)))

;; --- Conflicts: which properties conflict and what one side did alone

(defn- attr-sources
  "Every changed attr of a conflict with who changed it, using the base
  value both sides started from. `:conflict` attrs moved on both sides; a
  `:source` or `:branch` attr moved on that side alone, so its value is
  not in doubt. Without a base (both sides added the entity) every attr
  conflicts."
  [conflict]
  (let [base      (:base conflict)
        has-base? (map? base)]
    (->> (:changed-attrs conflict)
         (map (fn [[attr {:keys [source branch]}]]
                (let [base-v (get base attr)]
                  {:attr   attr
                   :base   base-v
                   :source source
                   :branch branch
                   :origin (cond
                             (not has-base?)    :conflict
                             (= base-v source)  :branch
                             (= base-v branch)  :source
                             :else              :conflict)})))
         (sort-by (comp attr-label :attr))
         vec)))

(defn conflict-attr-rows
  "The attrs of a conflict a person decides on (see `attr-sources`).
  Technical attrs are left out: they follow the visible ones they come
  with. When only technical attrs conflict, they are shown instead, so the
  conflict can still be decided."
  [conflict]
  (let [all     (attr-sources conflict)
        visible (filterv #(not (contains? technical-attrs (:attr %))) all)]
    (if (and (not-any? #(= :conflict (:origin %)) visible)
             (some #(= :conflict (:origin %)) all))
      all
      visible)))

(defn conflicting-attrs
  [rows]
  (into [] (comp (filter #(= :conflict (:origin %))) (map :attr)) rows))

(defn- hidden-conflicts
  "The conflicting attrs `conflict-attr-rows` does not show."
  [conflict]
  (let [shown (into #{} (map :attr) (conflict-attr-rows conflict))]
    (into []
          (comp (filter #(= :conflict (:origin %)))
                (map :attr)
                (remove shown))
          (attr-sources conflict))))

(defn prefill-resolution
  "The per-attr resolution a conflict starts with: every attr one side
  changed alone takes that side. The conflicting attrs stay out, so the
  conflict is not resolved until the user picks them; a conflict without
  conflicting attrs comes out resolved."
  [conflict]
  (into {}
        (comp (remove #(= :conflict (:origin %)))
              (map (juxt :attr :origin)))
        (attr-sources conflict)))

(defn per-attr?
  "Whether a conflict can be decided property by property."
  [conflict]
  (and (seq (:changed-attrs conflict))
       (map? (:source conflict))
       (map? (:branch conflict))))

(defn choose-attr
  "The resolution of `conflict` once `attr` takes `side`, starting from
  `res` (nil, a whole-side keyword or a per-attr map)."
  [conflict res attr side]
  (let [seed (cond
               (map? res)        res
               (= res :branch)   (zipmap (keys (:changed-attrs conflict)) (repeat :branch))
               (= res :source)   (zipmap (keys (:changed-attrs conflict)) (repeat :source))
               :else             (prefill-resolution conflict))]
    (reduce (fn [res hidden] (assoc res hidden side))
            (assoc seed attr side)
            (hidden-conflicts conflict))))

(defn choose-side
  "The resolution of `conflict` when every conflicting attr takes `side`; the
  independent edits keep the side that made them."
  [conflict side]
  (if (per-attr? conflict)
    (let [rows (conflict-attr-rows conflict)]
      (reduce (fn [res attr] (assoc res attr side))
              (prefill-resolution conflict)
              (into (conflicting-attrs rows) (hidden-conflicts conflict))))
    side))

(defn attr-choice
  "Currently-selected side (`:source`/`:branch`) for `attr` under
  resolution `res`, or nil when undecided."
  [res attr]
  (cond
    (= res :branch) :branch
    (= res :source) :source
    (map? res)      (get res attr)
    :else           nil))

(defn dropped-values
  "What a decided conflict leaves behind: for each attr where the other
  side changed the value and lost, `{:attr :side :value}` naming the side
  whose edit is dropped."
  [conflict res]
  (let [rows (conflict-attr-rows conflict)]
    (into []
          (keep (fn [{:keys [attr base source branch]}]
                  (let [choice (attr-choice res attr)
                        has-base? (map? (:base conflict))]
                    (cond
                      (and (= choice :branch) (or (not has-base?) (not= source base)))
                      {:attr attr :side :source :value source}

                      (and (= choice :source) (or (not has-base?) (not= branch base)))
                      {:attr attr :side :branch :value branch}

                      :else nil))))
          rows)))

(defn paragraph-rows
  "The paragraphs of one side of a text conflict, each marked `:kept` when
  this side edited it, `:dropped` when only the other side edited it (so
  keeping this side drops that edit), or nil when neither did."
  [conflict side]
  (let [other  (if (= side :source) :branch :source)
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

;; --- Conflicts: what the user decides, and how far along they are

(defn conflict-reason-label
  "Why both sides clash on an entity, or nil for any other reason."
  [reason]
  (case reason
    :modify-modify (tr "workspace.branches.conflicts.reason.modify-modify")
    :add-add       (tr "workspace.branches.conflicts.reason.add-add")
    :delete-modify (tr "workspace.branches.conflicts.reason.delete-modify")
    :modify-delete (tr "workspace.branches.conflicts.reason.modify-delete")
    nil))

(defn text-conflict?
  "A conflict whose text content both sides rewrote."
  [conflict]
  (boolean
   (and (per-attr? conflict)
        (some #(and (= :content (:attr %)) (= :conflict (:origin %)))
              (conflict-attr-rows conflict)))))

(defn dropped-paragraphs
  "The paragraph edits keeping `side` drops."
  [conflict side]
  (let [other (if (= side :source) :branch :source)]
    (filterv #(= :kept (:status %)) (paragraph-rows conflict other))))

(defn loses-work?
  "Whether every choice on a text conflict drops a paragraph edit: both
  sides edited different paragraphs."
  [conflict]
  (boolean
   (and (text-conflict? conflict)
        (seq (dropped-paragraphs conflict :source))
        (seq (dropped-paragraphs conflict :branch)))))

(defn pending-attr
  "The property of `conflict` in turn under resolution `res`: `attr` when
  it is one of its conflicting properties, else the first one without a
  choice, else the first one. Nil for a conflict that cannot be split by
  property."
  [conflict res attr]
  (when (per-attr? conflict)
    (let [attrs (conflicting-attrs (conflict-attr-rows conflict))]
      (or (some #{attr} attrs)
          (some #(when (nil? (attr-choice res %)) %) attrs)
          (first attrs)))))

(defn conflict-model
  "How far along the `conflicts` of a merge or an update are under
  `resolutions`: the ones that still wait for a choice (`:pending`), the
  ones decided (`:decided`), and both in the order the list shows them
  (`:rows`)."
  [conflicts resolutions]
  (let [resolved? #(bm/conflict-resolved? % (get resolutions (:id %)))
        pending   (filterv (complement resolved?) conflicts)
        decided   (filterv resolved? conflicts)]
    {:conflicts (vec conflicts)
     :pending   pending
     :decided   decided
     :rows      (into pending decided)}))

(defn prefill-resolutions
  "The resolutions the `conflicts` without one in `resolutions` start
  with (`prefill-resolution`), by conflict id: the edits one side made
  alone are not in doubt, so only the conflicting properties wait."
  [conflicts resolutions]
  (into {}
        (keep (fn [conflict]
                (when (and (per-attr? conflict)
                           (nil? (get resolutions (:id conflict))))
                  [(:id conflict) (prefill-resolution conflict)])))
        conflicts))

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

(defn- uses?
  "Whether `shape` depends on the library entry `item`: it copies the
  component, fills or strokes with the colour, writes with the
  typography or applies the token."
  [shape {:keys [kind id label]}]
  (case kind
    :component  (and (= id (:component-id shape))
                     (not (:main-instance shape)))
    :color      (or (some #(= id (:fill-color-ref-id %)) (:fills shape))
                    (some #(= id (:stroke-color-ref-id %)) (:strokes shape)))
    :typography (and (= :text (:type shape))
                     (some #(= id (:typography-ref-id %))
                           (txt/node-seq (:content shape))))
    :token      (some #(= label %) (vals (:applied-tokens shape)))
    false))

(defn usages
  "The layers of the file `data` that depend on the library entry `item`
  (a component, colour, typography or token), each as
  `{:page-id :shape}`; nil for an entry no layer can depend on."
  [data item]
  (when (contains? #{:component :color :typography :token} (:kind item))
    (into []
          (mapcat (fn [[page-id page]]
                    (eduction (filter #(uses? % item))
                              (map (fn [shape] {:page-id page-id :shape shape}))
                              (vals (:objects page)))))
          (:pages-index data))))

;; --- The marks of the changes on the canvas

(defn- shape-rect
  [shape]
  (select-keys (:selrect shape) [:x :y :width :height]))

(defn change-marks
  "The marks the entries `items` leave on the page `page-id`, whose
  layers are `objects`: `{:key :label :status :rect}` for each one that
  is a layer of that page with a place (`dwcp/item-rect`). A layer the
  open file lacks (deleted here, or added on the other side) is marked
  where the diff says it is. `status-of` gives the status drawn for an
  entry; the entries `excluded` holds leave no mark, as they stay out."
  [items {:keys [page-id objects excluded status-of]}]
  (let [excluded  (or excluded #{})
        status-of (or status-of :status)]
    (into []
          (keep (fn [item]
                  (when (and (= page-id (:page-id item))
                             (not (contains? excluded (:key item))))
                    (when-let [rect (dwcp/item-rect objects item)]
                      {:key    (dwcp/item-key item)
                       :label  (:label item)
                       :status (status-of item)
                       :rect   rect}))))
          items)))

(defn usage-marks
  "The marks of the layers of page `page-id` that depend on the library
  entry `item` (`usages`): the change of a token or a colour shows on
  the layers that use it."
  [data item page-id]
  (into []
        (comp (filter #(= page-id (:page-id %)))
              (map (fn [{:keys [shape]}]
                     {:key    (dm/str (dwcp/item-key item) "/" (:id shape))
                      :label  (:name shape)
                      :status :usage
                      :rect   (shape-rect shape)})))
        (usages data item)))

;; --- Component copies: what follows a changed component

(defn group-label
  "Human-readable name of a sync group (`app.common.types.component/sync-attrs`):
  the properties a copy overrides together. The layout groups read as the
  layout or the element's place in it, a swap slot as a swapped
  component."
  [group]
  (let [gname (name group)]
    (case group
      :name-group          (tr "workspace.branches.compare.copies.group.name")
      :fill-group          (tr "workspace.branches.compare.copies.group.fill")
      :stroke-group        (tr "workspace.branches.compare.copies.group.stroke")
      :content-group       (tr "workspace.branches.compare.copies.group.content")
      :geometry-group      (tr "workspace.branches.compare.copies.group.geometry")
      :radius-group        (tr "workspace.branches.compare.copies.group.radius")
      :visibility-group    (tr "workspace.branches.compare.copies.group.visibility")
      :modifiable-group    (tr "workspace.branches.compare.copies.group.lock")
      :text-font-group     (tr "workspace.branches.compare.copies.group.font")
      :text-display-group  (tr "workspace.branches.compare.copies.group.text-display")
      :layer-effects-group (tr "workspace.branches.compare.copies.group.layer")
      :shadow-group        (tr "workspace.branches.compare.copies.group.shadow")
      :blur-group          (tr "workspace.branches.compare.copies.group.blur")
      :mask-group          (tr "workspace.branches.compare.copies.group.mask")
      :constraints-group   (tr "workspace.branches.compare.copies.group.constraints")
      :exports-group       (tr "workspace.branches.compare.copies.group.exports")
      :grids-group         (tr "workspace.branches.compare.copies.group.grids")
      :show-content        (tr "workspace.branches.compare.copies.group.clip")
      (cond
        (ctk/swap-slot? group)                 (tr "workspace.branches.compare.copies.group.swap")
        (str/starts-with? gname "layout-item") (tr "workspace.branches.compare.copies.group.layout-item")
        (str/starts-with? gname "layout")      (tr "workspace.branches.compare.copies.group.layout")
        :else (-> gname (str/replace #"-group$" "") (str/replace "-" " ") str/capital)))))

(defn token-slot-label
  "A token slot (token attr of `:applied-tokens`) in words: the property
  the token drives."
  [slot]
  (tr "workspace.branches.compare.copies.token-slot" (str/lower (attr-label slot))))

(defn reason-label
  "Why the copy rule gave a property to a side, in words."
  [reason]
  (case reason
    :follows-component (tr "workspace.branches.compare.copies.reason.follows-component")
    :override-source   (tr "workspace.branches.compare.copies.reason.override-main")
    :override-branch   (tr "workspace.branches.compare.copies.reason.override-branch")
    (tr "workspace.branches.compare.copies.reason.override-both")))

(defn side-label
  [side]
  (if (= side :source)
    (tr "workspace.branches.compare.side.main")
    (tr "workspace.branches.compare.side.branch")))

(defn copies-entry
  "The `:copies` entry of the summary (`app.common.files.branch-merge/compute-merge`)
  for the component diff entry `item` belongs to: the component itself,
  its main instance, or a layer inside that main, found by walking up
  `objects`, the page of `item` as the open file holds it. Nil when
  `item` is about no changed component."
  [copies item objects]
  (when (seq copies)
    (case (:kind item)
      :component
      (some #(when (= (:id item) (:component-id %)) %) copies)

      :shape
      (let [by-main (into {}
                          (comp (filter #(= (:page-id item) (:main-page-id %)))
                                (map (juxt :main-id identity)))
                          copies)]
        (loop [id (:id item) depth 0]
          (cond
            (contains? by-main id)                         (get by-main id)
            (or (nil? id) (= id uuid/zero) (> depth 64))   nil
            :else (recur (get-in objects [id :parent-id]) (inc depth)))))

      nil)))

(defn copy-follow-counts
  "How many copies follow each changed component, keyed both by the
  component id and by its main instance id, as the change rows know
  either one."
  [copies]
  (reduce (fn [acc {:keys [component-id main-id] :as entry}]
            (let [n (count (:copies entry))]
              (assoc acc component-id n main-id n)))
          {}
          copies))

(defn copy-keeps
  "What copy `copy` (one of a `:copies` entry's copies) keeps of its own,
  per side: `{side [label ...]}`, with the sync groups it keeps an
  override of and the token slots that side overrode."
  [copy]
  (let [groups (reduce (fn [acc [group side]]
                         (update acc side (fnil conj []) (group-label group)))
                       {}
                       (sort-by (comp group-label key) (:keeps copy)))]
    (reduce (fn [acc [slot {:keys [side reason]}]]
              (if (and (contains? #{:source :branch} side)
                       (contains? #{:override-source :override-branch} reason))
                (update acc side (fnil conj []) (token-slot-label slot))
                acc))
            groups
            (sort-by (comp name key) (:token-slots copy)))))

(defn copy-open-slots
  "The token slots of copy `copy` both sides overrode: the user decides
  them."
  [copy]
  (into []
        (comp (filter (fn [[_ {:keys [side]}]] (= :open side)))
              (map (comp token-slot-label key)))
        (sort-by (comp name key) (:token-slots copy))))

(defn settled-rows
  "The attrs and token slots of `settled` (a copy entry's `:settled`)
  the copy rule decided, each `{:label :side :reason}`, attrs first."
  [settled]
  (-> []
      (into (map (fn [[attr {:keys [side reason]}]]
                   {:label (attr-label attr) :side side :reason reason}))
            (sort-by (comp attr-label key) (:attrs settled)))
      (into (map (fn [[slot {:keys [side reason]}]]
                   {:label (token-slot-label slot) :side side :reason reason}))
            (sort-by (comp name key) (:token-slots settled)))))

(defn settled-open
  "What the user still decides on a copy conflict with `settled`: the
  open attrs, `:applied-tokens` aside, then the open token slots, in
  words."
  [settled]
  (-> []
      (into (comp (remove #{:applied-tokens}) (map attr-label))
            (sort-by attr-label (:open-attrs settled)))
      (into (map token-slot-label)
            (sort-by name (:open-token-slots settled)))))
