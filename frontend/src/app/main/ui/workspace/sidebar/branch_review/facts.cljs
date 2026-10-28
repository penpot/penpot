;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.facts
  "What the detail pane says about an entry that has no before and after:
  a new or deleted page or layer, and the library entries other layers
  depend on. It answers what the entry is, where it lands and what it
  affects, from the open file and, for a layer it lacks, from the diff.
  It also says what the copy rule does with the copies of a changed
  component."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.preview-card :refer [preview-card*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private max-names
  "How many board names a page lists before it counts the rest."
  6)

(defn- names-line
  "`names` joined, the first `max-names` of them and a count of the rest."
  [names]
  (dm/str (str/join ", " (take max-names names))
          (when (> (count names) max-names)
            (dm/str " " (tr "workspace.branches.review.summary.more"
                            (i18n/c (- (count names) max-names)))))))

(mf/defc fact-list*
  "Label and value rows, one per fact."
  {::mf/private true}
  [{:keys [rows]}]
  [:div {:class (stl/css :facts)}
   ;; keyed by place: a label may repeat (a layout row per board)
   (for [[idx [label value]] (map-indexed vector (remove nil? rows))]
     [:div {:class (stl/css :fact) :key idx}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :fact-label)} label]
      [:div {:class (stl/css :fact-value)} value]])])

(mf/defc section-title*
  {::mf/private true}
  [{:keys [children]}]
  [:> text* {:as "span" :typography t/body-small :class (stl/css :section-title)}
   children])

;; --- Pages

(mf/defc page-facts*
  "A new or deleted page: what it holds, drawn and counted, and the
  changes listed on it (`children`)."
  [{:keys [item data open-side incoming change-count children]}]
  (let [page     (get-in data [:pages-index (:id item)])
        deleted? (= :deleted (:status item))
        {:keys [boards layers]} (when page (brc/page-contents page))
        names    (map :name boards)]
    [:*
     (if page
       [:*
        [:> preview-card* {:label (brc/side-label open-side)
                           :side (name open-side)
                           :detail (:name page)}
         [:> brp/page-preview* {:objects (:objects page)}]]
        [:> fact-list*
         {:rows [[(tr "workspace.branches.review.facts.contents")
                  (if (zero? layers)
                    (tr "workspace.branches.review.facts.empty-page")
                    (dm/str (tr "workspace.branches.review.facts.boards" (i18n/c (count boards)))
                            " - "
                            (tr "workspace.branches.review.facts.layers" (i18n/c layers))))]
                 (when (seq names)
                   [(tr "workspace.branches.review.facts.board-names")
                    (names-line names)])]}]
        (when (and deleted? (pos? layers))
          [:> brl/notice* {:level :warning
                           :title (tr "workspace.branches.review.facts.page-goes")}
           (tr (if incoming
                 "workspace.branches.review.facts.page-goes-update"
                 "workspace.branches.review.facts.page-goes-merge")
               (tr "workspace.branches.review.facts.boards" (i18n/c (count boards)))
               (tr "workspace.branches.review.facts.layers" (i18n/c layers)))])]

       [:> preview-card* {:label (brc/side-label (if (= open-side :main) :branch :main))
                          :side (if (= open-side :main) "branch" "main")}
        [:> brp/entity-preview* {:kind :page
                                 :value nil
                                 :missing {:reason :unavailable
                                           :side (if (= open-side :main) :branch :main)
                                           :open-side open-side}}]])

     (when (pos? change-count)
       [:div {:class (stl/css :section)}
        [:> section-title* {}
         (tr "workspace.branches.review.facts.changes-on-page" (str change-count))]
        [:div {:class (stl/css :inner-list)} children]])]))

;; --- Layers

(defn- shape-rows
  "The facts that say what a layer is: its type, size, place, fill, text
  and the component it copies."
  [shape data type-label]
  (let [{:keys [width height x y fills content component-id]} shape
        fill      (some :fill-color fills)
        text      (first (remove str/blank? (brc/content-paragraphs content)))
        component (when (and component-id (not (:main-instance shape)))
                    (get-in data [:components component-id :name]))]
    (cond-> []
      type-label
      (conj [(tr "workspace.branches.review.facts.type") type-label])

      (and (number? width) (number? height))
      (conj [(tr "workspace.branches.review.facts.size")
             (dm/str (brc/display-val width) " × " (brc/display-val height))])

      (and (number? x) (number? y))
      (conj [(tr "workspace.branches.review.facts.position")
             (dm/str (brc/display-val x) ", " (brc/display-val y))])

      fill
      (conj [(tr "workspace.branches.review.attr.fill")
             (mf/html [:> brl/value-chip* {:value fill}])])

      text
      (conj [(tr "workspace.branches.review.attr.text") (brc/display-val text)])

      component
      (conj [(tr "workspace.branches.compare.attr.component") component]))))

;; --- What goes with a layer

(defn- size-line
  [[w h]]
  (dm/str (brc/display-val w) " × " (brc/display-val h)))

(defn- layout-row
  "A board with a layout around the layer: how it resizes, or how many
  of its other layers move."
  [{:keys [name layout from to moved]}]
  [(tr "workspace.branches.review.category.layout")
   (dm/str name " (" (tr (if (= :grid layout) "labels.grid-layout" "labels.flex-layout")) ") - "
           (if moved
             (tr "workspace.branches.review.effects.moved" (i18n/c moved))
             (dm/str (size-line from) " → " (size-line to))))])

(mf/defc effects*
  "What else changes with an added or deleted layer (`brc/shape-effects`):
  the prototype links and flows that go with it first, as warnings, then
  the layouts around it that adapt and the layers it holds. Nothing
  shows when none of it applies."
  {::mf/private true}
  [{:keys [item items objects inner incoming]}]
  (let [{:keys [interactions flows layouts]}
        (mf/with-memo [item items objects inner incoming]
          (brc/shape-effects item items objects inner incoming))
        names    (mapv (comp :label second) inner)
        deleted? (= :deleted (:status item))
        rows     (cond-> (mapv layout-row layouts)
                   (seq names)
                   (conj [(tr "workspace.branches.review.facts.contents") (names-line names)]))]
    (when (or (seq interactions) (seq flows) (seq rows))
      [:div {:class (stl/css :section)}
       [:> section-title* {}
        (tr (if deleted?
              "workspace.branches.review.effects.title-deleted"
              "workspace.branches.review.effects.title-added"))]
       (when (seq interactions)
         [:> brl/notice* {:level :warning
                          :title (tr "workspace.branches.review.effects.interactions"
                                     (i18n/c (reduce + (map :count interactions))))}
          (tr "workspace.branches.review.effects.interactions-from"
              (names-line (map :label interactions)))])
       (when (seq flows)
         [:> brl/notice* {:level :warning
                          :title (tr "workspace.branches.review.effects.flows"
                                     (i18n/c (count flows)))}
          (names-line flows)])
       (when (seq rows)
         [:> fact-list* {:rows rows}])])))

(mf/defc shape-facts*
  "A new or deleted layer: where it lands (or was), what it is and, for a
  deleted main component, the copies it leaves behind. `inner` are the
  layers added or deleted with it (`brc/fold-cascades`)."
  [{:keys [item items inner data open-side incoming]}]
  (let [open-objs (get-in data [:pages-index (:page-id item) :objects])
        ;; a layer the open file lacks is read on the other side, where
        ;; the changes of `items` put it and its deleted parents back
        objects  (mf/with-memo [open-objs items item open-side]
                   (if (or (contains? open-objs (:id item)) (nil? open-objs))
                     open-objs
                     (brp/side-objects open-objs
                                       (filterv #(= (:page-id item) (:page-id %)) items)
                                       (if (= open-side :main) :branch :main)
                                       open-side)))
        shape    (get objects (:id item))
        page     (get-in data [:pages-index (:page-id item) :name])
        deleted? (= :deleted (:status item))
        type-lbl (some-> (brc/type-label-key item) tr)
        copies   (when (and deleted? shape (:main-instance shape))
                   (brc/component-copies data (:component-id shape)))]
    (when shape
      [:*
       [:> fact-list*
        {:rows (into [[(tr (if deleted?
                             "workspace.branches.review.facts.where-was"
                             "workspace.branches.review.facts.where-lands"))
                       (str/join " › " (cons page (brc/shape-path objects (:id item))))]]
                     (shape-rows shape data type-lbl))}]
       (when (and copies (pos? copies))
         [:> brl/notice* {:level :warning
                          :title (tr "workspace.branches.review.facts.copies-orphaned-title")}
          (tr (if incoming
                "workspace.branches.review.facts.copies-orphaned-update"
                "workspace.branches.review.facts.copies-orphaned-merge")
              (i18n/c copies))])
       [:> effects* {:item item :items items :objects objects
                     :inner inner :incoming incoming}]])))

;; --- Library entries other layers depend on

(defn- usages
  [item data]
  (case (:kind item)
    :component  (brc/component-copies data (:id item))
    :color      (brc/color-usages data (:id item))
    :typography (brc/typography-usages data (:id item))
    :token      (brc/token-usages data (:label item))
    nil))

(mf/defc usage-facts*
  "How many layers of the open file depend on a component, colour,
  typography or token, and what a merge does to them when it deletes
  it."
  [{:keys [item data]}]
  (let [n        (usages item data)
        deleted? (= :deleted (:status item))
        kind     (:kind item)]
    (when (some? n)
      (cond
        (and deleted? (pos? n))
        [:> brl/notice* {:level :warning
                         :title (tr "workspace.branches.review.facts.used-title" (i18n/c n))}
         (tr (if (= kind :component)
               "workspace.branches.review.facts.deleted-component-used"
               "workspace.branches.review.facts.deleted-used")
             (i18n/c n))]

        deleted?
        [:> brl/notice* {:level :success
                         :title (tr "workspace.branches.review.facts.unused-title")}
         (tr "workspace.branches.review.facts.deleted-unused")]

        :else
        [:> fact-list*
         {:rows [[(tr "workspace.branches.review.facts.used-by")
                  (tr (if (= kind :component)
                        "workspace.branches.review.facts.copies"
                        "workspace.branches.review.facts.layers")
                      (i18n/c n))]]}]))))

;; --- Component copies

(defn- lower-list
  [labels]
  (str/lower (str/join ", " labels)))

(defn- copy-lines
  "What one copy does after the integration, a line each: the overrides
  it keeps and the side they come from, the token slots left to the
  user, and the groups that follow the component; or that it follows
  every change."
  [copy]
  (let [keeps   (brc/copy-keeps copy)
        open    (brc/copy-open-slots copy)
        follows (sort (map brc/group-label (:follows copy)))
        own?    (or (seq keeps) (seq open))]
    (cond-> []
      (not own?)
      (conj (tr "workspace.branches.review.copies.follows-all"))

      (seq (:main keeps))
      (conj (tr "workspace.branches.review.copies.keeps-main" (lower-list (:main keeps))))

      (seq (:branch keeps))
      (conj (tr "workspace.branches.review.copies.keeps-branch" (lower-list (:branch keeps))))

      (seq open)
      (conj (tr "workspace.branches.review.copies.slots-open" (lower-list open)))

      (and own? (seq follows))
      (conj (tr "workspace.branches.review.copies.follows" (lower-list follows))))))

(defn- keeps-own?
  [copy]
  (or (seq (brc/copy-keeps copy)) (seq (brc/copy-open-slots copy))))

(defn- component-name
  "The name of component `id`: its own `:copies` entry, else the open
  file's components, else a short id."
  [copies data id]
  (or (some #(when (= id (:component-id %)) (:component-name %)) copies)
      (get-in data [:components id :name])
      (subs (str id) 0 8)))

(mf/defc copies-facts*
  "The copies of a component the integration changes (`entry`, one of the
  summary's `copies`), folded under how many follow it: each with its
  page and what it keeps of its own. An entry with `:via` changes only
  through a copy its main holds of another changed component, which
  the head line names."
  [{:keys [entry copies data]}]
  (let [open*     (mf/use-state false)
        open?     (deref open*)
        on-toggle (mf/use-fn #(swap! open* not))
        via       (:via entry)
        own       (:copies entry)
        n         (count own)
        keeping   (count (filter keeps-own? own))]
    (when (pos? n)
      [:div {:class (stl/css :section)}
       [:> brl/group-head*
        {:label (cond-> (tr "workspace.branches.review.copies.follow" (i18n/c n))
                  (pos? keeping)
                  (dm/str " - " (tr "workspace.branches.review.copies.keeping" (i18n/c keeping))))
         :is-open open?
         :on-toggle on-toggle}]
       (when via
         [:> text* {:as "p" :typography t/body-small :class (stl/css :copy-via)}
          (tr "workspace.branches.review.copies.via"
              (:component-name entry) (component-name copies data via))])
       (when open?
         [:div {:class (stl/css :copy-list)}
          (for [copy own]
            [:div {:class (stl/css :copy) :key (str (:id copy))}
             [:div {:class (stl/css :copy-head)}
              [:> i/icon* {:icon-id i/component-copy :size "s" :class (stl/css :copy-icon)}]
              [:> text* {:as "span" :typography t/body-small :class (stl/css :copy-name)}
               (:label copy)]
              [:> text* {:as "span" :typography t/body-small :class (stl/css :copy-page)}
               (or (get-in data [:pages-index (:page-id copy) :name])
                   (tr "workspace.branches.review.unknown-page"))]]
             (for [[idx line] (map-indexed vector (copy-lines copy))]
               [:> text* {:key idx :as "span" :typography t/body-small
                          :class (stl/css :copy-line)}
                line])])])])))

(mf/defc copies-affected*
  "On a conflict about a component's main: how many copies the choice
  also decides, since they follow the main the merge keeps."
  [{:keys [n]}]
  (when (pos? n)
    [:> brl/notice* {:level :info}
     (tr "workspace.branches.review.copies.affected" (i18n/c n))]))

(mf/defc settled-facts*
  "What the copy rule decided on a copy (`settled`, the entry's
  `:settled`): each property and token slot with the side it takes and
  why. On a conflict (`is-conflict`), a note says the user's choice
  decides only the open ones; on an entry the rule settled entirely,
  that there is nothing to choose. Nothing shows when the rule decided
  nothing: the conflict is then an ordinary one."
  [{:keys [settled is-conflict is-settled]}]
  (let [rows (brc/settled-rows settled)
        open (brc/settled-open settled)]
    (when (seq rows)
      [:div {:class (stl/css :section)}
       [:> section-title* {}
        (tr "workspace.branches.review.copies.settled-title")]
       (when is-settled
         [:> brl/notice* {:level :info}
          (tr "workspace.branches.review.copies.settled-notice")])
       [:> fact-list*
        {:rows (mapv (fn [{:keys [label side reason]}]
                       [label
                        (mf/html
                         [:span {:class (stl/css :settled-value)}
                          [:> tag* {:tone (if (= side :main) "purple" "accent")}
                           (brc/side-label side)]
                          [:span (brc/reason-label reason)]])])
                     rows)}]
       (when (and is-conflict (seq open))
         [:> brl/notice* {:level :info}
          (tr "workspace.branches.review.copies.open-note" (lower-list open))])])))
