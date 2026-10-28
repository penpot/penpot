;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.facts
  "What the detail pane says about an entry that has no before and after:
  a new or deleted page or layer, and the library entries other layers
  depend on. It answers what the entry is, where it lands and what it
  affects, from the open file and, for a layer it lacks, from the diff."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.preview-card :refer [preview-card*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private max-names
  "How many board names a page lists before it counts the rest."
  6)

(defn- side-label
  [side]
  (tr (if (= side :main)
        "workspace.branches.review.side.main"
        "workspace.branches.review.side.branch")))

(mf/defc fact-list*
  "Label and value rows, one per fact."
  {::mf/private true}
  [{:keys [rows]}]
  [:div {:class (stl/css :facts)}
   (for [[label value] (remove nil? rows)]
     [:div {:class (stl/css :fact) :key label}
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
        [:> preview-card* {:label (side-label open-side)
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
                    (dm/str (str/join ", " (take max-names names))
                            (when (> (count names) max-names)
                              (dm/str " " (tr "workspace.branches.review.summary.more"
                                              (i18n/c (- (count names) max-names))))))])]}]
        (when (and deleted? (pos? layers))
          [:> brl/notice* {:level :warning
                           :title (tr "workspace.branches.review.facts.page-goes")}
           (tr (if incoming
                 "workspace.branches.review.facts.page-goes-update"
                 "workspace.branches.review.facts.page-goes-merge")
               (tr "workspace.branches.review.facts.boards" (i18n/c (count boards)))
               (tr "workspace.branches.review.facts.layers" (i18n/c layers)))])]

       [:> preview-card* {:label (side-label (if (= open-side :main) :branch :main))
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

(mf/defc shape-facts*
  "A new or deleted layer: where it lands (or was), what it is and, for a
  deleted main component, the copies it leaves behind."
  [{:keys [item items data open-side incoming]}]
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
              (i18n/c copies))])])))

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
