;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.detail
  "The right sidebar of the compare mode: the change or the conflict
  chosen in the list. Inside a design review it adds the comments of the
  review on the layer of that change."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.compare :as dwcp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.review.panel :refer [comments-tab*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.detail :as brd]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- on-resolve
  []
  (st/emit! (dwcp/start-resolve)))

(defn- on-attr
  [attr]
  (st/emit! (dwcp/set-attr attr)))

(mf/defc change*
  "The change chosen while comparing, with what can be done about it."
  {::mf/private true}
  [{:keys [comparison]}]
  (let [{:keys [item model data copies incoming excluded]} comparison
        objects     (get-in data [:pages-index (:page-id item) :objects])
        ;; only a layer the open file has can be edited
        editable?   (and (= :shape (:kind item)) (contains? objects (:id item)))
        selectable? (brc/selectable-item? item)
        on-edit     (mf/use-fn (mf/deps item) #(st/emit! (dwcp/edit-layer item)))
        on-toggle   (mf/use-fn (mf/deps comparison) #(cc/toggle-excluded comparison))]
    [:> brd/change-detail* {:item item
                            :items (:items model)
                            :inner (get (:cascades model) (dwcp/item-key item))
                            :copies copies
                            :data data
                            ;; the mode only opens on the branch file
                            :open-side :branch
                            :incoming incoming
                            :excluded (and selectable? (contains? excluded (:key item)))
                            :on-edit (when editable? on-edit)
                            :on-toggle (when selectable? on-toggle)
                            :on-resolve (when (brc/conflict? item) on-resolve)}]))

(mf/defc conflict*
  "The conflict chosen while resolving."
  {::mf/private true}
  [{:keys [comparison is-review]}]
  (let [{:keys [item res attr branch data copies]} comparison
        on-pick   (mf/use-fn (mf/deps comparison) #(cc/pick-attr comparison %1 %2))
        on-choose (mf/use-fn (mf/deps comparison) #(cc/choose-side comparison %))
        on-clear  (mf/use-fn (mf/deps comparison) #(cc/clear-choice comparison))]
    [:> brd/conflict-detail* {:conflict item
                              :res res
                              :attr (brc/pending-attr item res attr)
                              :branch-name (:name branch)
                              :is-review is-review
                              :copies copies
                              :data data
                              :on-pick on-pick
                              :on-choose on-choose
                              :on-clear on-clear
                              :on-attr on-attr}]))

(mf/defc review-tabs*
  "Inside a design review: the change beside the comments the review has
  on its layer."
  {::mf/private true}
  [{:keys [item pr-id children]}]
  (let [threads* (mf/deref refs/threads)
        tab*     (mf/use-state "changes")
        tab      (deref tab*)
        threads  (mf/with-memo [threads* pr-id item]
                   (filterv #(and (= pr-id (:pull-request-id %))
                                  (= (:id item) (:shape-id %)))
                            (vals threads*)))
        tabs     (mf/with-memo [item threads]
                   [{:id "changes"
                     :label (tr "workspace.compare.tab.changes"
                                (count (brc/visible-attrs (:changed-attrs item))))}
                    {:id "comments"
                     :label (tr "workspace.review.tab.comments" (count threads))}])
        on-tab   (mf/use-fn #(reset! tab* %))]
    [:> tab-switcher* {:tabs tabs
                       :selected tab
                       :on-change on-tab
                       :scrollable-panel true
                       :class (stl/css :tabs)}
     [:div {:class (stl/css :tab-content)}
      (if (= tab "comments")
        [:> comments-tab* {:threads threads}]
        children)]]))

(mf/defc compare-detail*
  []
  (let [comparison (cc/use-comparison)
        preview    (mf/deref refs/pull-request-preview)
        review?    (some? preview)
        {:keys [item phase status]} comparison]
    (cond
      (nil? item)
      [:div {:class (stl/css :empty)}
       (when (or (= status :loaded) (= phase :resolve))
         [:> empty-state* {:icon i/git-compare-arrows
                           :text (tr "workspace.branches.compare.select-hint")}])]

      (= phase :resolve)
      [:div {:class (stl/css :compare-detail)}
       [:> conflict* {:comparison comparison :is-review review?}]]

      ;; a layer can carry comments of the review
      (and review? (= :shape (:kind item)))
      [:div {:class (stl/css :compare-detail :has-tabs)}
       [:> review-tabs* {:key (dwcp/item-key item) :item item :pr-id (:pr-id preview)}
        [:> change* {:comparison comparison}]]]

      :else
      [:div {:class (stl/css :compare-detail)}
       [:> change* {:comparison comparison}]])))
