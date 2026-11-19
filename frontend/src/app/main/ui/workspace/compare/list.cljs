;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.list
  "The left sidebar of the compare mode. Comparing, it lists the changes
  of the branch or the ones main has, by page and board, with the
  checkbox that leaves each one out. Resolving, it lists the conflicts
  with the ones that wait for a choice first."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.filter-chip :refer [filter-chip*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.read-only-badge :refer [read-only-badge*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.list :as brli]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- on-select
  [item-key]
  (st/emit! (dwcp/toggle-selected item-key)))

(defn- on-hover
  "The row under the pointer, from wherever in it the pointer is; none
  between rows."
  [event]
  (let [row (.closest ^js (dom/get-target event) "[data-item-key]")]
    (st/emit! (dwcp/set-hovered (some-> row (dom/get-data "item-key")) :list))))

(defn- on-leave
  [_]
  (st/emit! (dwcp/set-hovered nil :list)))

(defn- on-fold
  [id]
  (st/emit! (dwcp/toggle-group id)))

(defn- on-toggle
  [change-keys include?]
  (st/emit! (dwb/toggle-diff-excluded change-keys include?)))

(defn- on-direction
  [id]
  (st/emit! (dwcp/set-direction (keyword id))))

(defn- on-filter
  [event]
  (st/emit! (dwcp/set-filter (-> (dom/get-current-target event)
                                 (dom/get-data "filter")
                                 (keyword)))))

(mf/defc header*
  "The title of the list, the tag that says the canvas takes no edits
  here and, with `on-export`, the button that downloads the diff."
  {::mf/private true}
  [{:keys [title on-export]}]
  [:div {:class (stl/css :header)}
   [:> heading* {:level 2 :typography t/headline-small :class (stl/css :title)} title]
   (when on-export
     [:> icon-button* {:variant "ghost"
                       :icon i/download
                       :aria-label (tr "workspace.branches.compare.export")
                       :on-click on-export}])
   ;; the same badge the pages panel shows on a file that takes no edits
   [:> read-only-badge* {}]])

(mf/defc filters*
  {::mf/private true}
  [{:keys [counts selected]}]
  [:div {:class (stl/css :filters)}
   [:> filter-chip* {:label (dm/str (tr "labels.all") " (" (:all counts) ")")
                     :is-selected (= selected :all)
                     :data-filter "all"
                     :on-click on-filter}]
   (when (pos? (:layers counts))
     [:> filter-chip* {:label (dm/str (tr "workspace.compare.filter.layers") " (" (:layers counts) ")")
                       :is-selected (= selected :layers)
                       :data-filter "layers"
                       :on-click on-filter}])
   (when (pos? (:library counts))
     [:> filter-chip* {:label (dm/str (tr "workspace.branches.review.group.library")
                                      " (" (:library counts) ")")
                       :is-selected (= selected :library)
                       :data-filter "library"
                       :on-click on-filter}])
   (when (pos? (:left-out counts))
     [:> filter-chip* {:label (dm/str (tr "workspace.branches.review.row.left-out")
                                      " (" (:left-out counts) ")")
                       :is-selected (= selected :left-out)
                       :data-filter "left-out"
                       :on-click on-filter}])])

(defn- tab-label
  "The label of a direction tab, with how many changes it lists once that
  is known."
  [label total]
  (if (some? total)
    (tr "workspace.compare.tab-count" label total)
    label))

(mf/defc changes*
  "The changes of the direction shown, under the tabs that switch it."
  {::mf/private true}
  [{:keys [comparison]}]
  (let [{:keys [mode status incoming model selected excluded reviewed copies]} comparison
        counts    (:counts mode)
        direction (if incoming :main->branch :branch->main)
        tabs      (mf/with-memo [counts]
                    [{:id "branch->main"
                      :label (tab-label (tr "workspace.compare.direction.branch")
                                        (get counts :branch->main))}
                     {:id "main->branch"
                      :label (tab-label (tr "workspace.compare.direction.main")
                                        (get counts :main->branch))}])
        follows   (mf/with-memo [copies]
                    (brc/copy-follow-counts copies))]
    [:> tab-switcher* {:tabs tabs
                       :selected (name direction)
                       :on-change on-direction
                       :scrollable-panel true
                       :class (stl/css :tabs)}
     (cond
       (= status :error)
       [:div {:class (stl/css :empty)}
        [:> empty-state* {:icon i/triangle-alert :text (tr "workspace.branches.compare.error")}]]

       (not= status :loaded)
       [:div {:class (stl/css :empty)}
        [:> empty-state* {:icon i/git-compare-arrows :text (tr "workspace.branches.compare.loading")}]]

       (empty? (:items model))
       [:div {:class (stl/css :empty)}
        [:> empty-state* {:icon i/git-branch
                          :text (if incoming
                                  (tr "workspace.compare.empty-main")
                                  (tr "workspace.branches.compare.empty"))}]]

       :else
       [:div {:class (stl/css :content)}
        [:> filters* {:counts (:counts model) :selected (:filter mode)}]
        [:div {:class (stl/css :rows)}
         [:> brli/change-list* {:model model
                                :follow-counts follows
                                :selected selected
                                :excluded excluded
                                :reviewed reviewed
                                :collapsed (:collapsed mode)
                                :on-select on-select
                                :on-toggle on-toggle
                                :on-fold on-fold}]]])]))

(mf/defc decisions*
  "The conflicts to resolve, under the buttons that decide them all at
  once."
  {::mf/private true}
  [{:keys [comparison]}]
  (let [{:keys [mode conflicts selected resolutions]} comparison
        total       (count (:conflicts conflicts))
        on-all-main   (mf/use-fn (mf/deps comparison) #(cc/keep-all comparison :main))
        on-all-branch (mf/use-fn (mf/deps comparison) #(cc/keep-all comparison :branch))]
    (if (zero? total)
      [:div {:class (stl/css :empty)}
       [:> empty-state* {:icon i/git-merge :text (tr "workspace.branches.conflicts.empty")}]]
      [:div {:class (stl/css :content :is-scrollable)}
       ;; deciding them all at once only means something with more than
       ;; one decision
       (when (> total 1)
         [:div {:class (stl/css :bulk)}
          [:> button* {:variant "secondary" :on-click on-all-main}
           (tr "workspace.branches.resolve.keep-all-main")]
          [:> button* {:variant "secondary" :on-click on-all-branch}
           (tr "workspace.branches.resolve.keep-all-branch")]])
       [:div {:class (stl/css :rows)}
        [:> brli/conflict-list* {:model conflicts
                                 :resolutions resolutions
                                 :selected selected
                                 :collapsed (:collapsed mode)
                                 :on-select on-select
                                 :on-fold on-fold}]]])))

(mf/defc compare-list*
  [{:keys [class]}]
  (let [comparison (cc/use-comparison)
        resolving? (= :resolve (:phase comparison))
        on-export  (mf/use-fn (mf/deps comparison) #(cc/export-diff comparison))
        node-ref   (mf/use-ref nil)
        hovered    (:hovered comparison)
        ;; the row of the layer under the pointer, on the canvas
        pointed    (when (= :canvas (:hovered-from comparison)) hovered)]

    ;; that row comes into sight
    (mf/with-effect [pointed]
      (when (some? pointed)
        (some-> (mf/ref-val node-ref)
                (.querySelector (dm/str "[data-item-key=\"" pointed "\"]"))
                (.scrollIntoView #js {:block "nearest"}))))

    [:div {:class [class (stl/css :compare-list)]
           :ref node-ref
           :on-pointer-over on-hover
           :on-pointer-leave on-leave}
     [:> (mf/provider brli/pointed-row) {:value pointed}
      [:> header* {:title (if resolving?
                            (tr "workspace.compare.decisions"
                                (count (get-in comparison [:conflicts :conflicts])))
                            (tr "workspace.compare.changes"))
                   :on-export (when (= :loaded (:status comparison)) on-export)}]
      (if resolving?
        [:> decisions* {:comparison comparison}]
        [:> changes* {:comparison comparison}])]]))
