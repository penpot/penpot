;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.detail
  "The right sidebar of the compare mode: the change or the conflict
  chosen in the list, and under it the comments and the tasks on its
  layer."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.review.panel :refer [thread-card*]]
   [app.main.ui.workspace.review.tasks :refer [task-list*]]
   [app.main.ui.workspace.sidebar.branch-compare.common :as brc]
   [app.main.ui.workspace.sidebar.branch-compare.detail :as brd]
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

(defn layer-threads
  "The comment threads of `threads` (by id) on the layer `layer-id` that
  wait for an answer, in the order they were opened."
  [threads layer-id]
  (->> (vals threads)
       (filter #(and (= layer-id (:shape-id %)) (not (:is-resolved %))))
       (sort-by :seqn)
       (vec)))

(defn layer-tasks
  "The tasks of `tasks` (by id) on the layer `layer-id` still to be done
  or verified."
  [tasks layer-id]
  (->> (vals tasks)
       (filter #(and (= layer-id (:shape-id %)) (not (dwtk/closed? %))))
       (vec)))

(mf/defc context-head*
  {::mf/private true}
  [{:keys [icon label]}]
  [:> heading* {:level 3 :typography t/body-small :class (stl/css :context-head)}
   [:> i/icon* {:icon-id icon :size "s" :class (stl/css :context-icon)}]
   label])

(mf/defc layer-context*
  "What else the layer of `item` carries: the comments that wait for an
  answer and the tasks still open on it, for the context of its change.
  Nothing for an entry that is no layer, or a layer without them."
  {::mf/private true}
  [{:keys [item data]}]
  (let [threads  (mf/deref refs/threads)
        tasks    (mf/deref refs/tasks)
        clocal   (mf/deref refs/comments-local)
        layer-id (when (= :shape (:kind item)) (:id item))
        on-layer (mf/with-memo [threads layer-id]
                   (if layer-id (layer-threads threads layer-id) []))
        todo     (mf/with-memo [tasks layer-id]
                   (if layer-id (layer-tasks tasks layer-id) []))
        objects  (dm/get-in data [:pages-index (:page-id item) :objects])]
    (when (or (seq on-layer) (seq todo))
      [:div {:class (stl/css :context)}
       (when (seq on-layer)
         [:section {:class (stl/css :context-group)}
          [:> context-head* {:icon i/comments
                             :label (tr "workspace.compare.layer.comments" (count on-layer))}]
          (for [thread on-layer]
            [:> thread-card* {:key (dm/str (:id thread))
                              :thread thread
                              :objects objects
                              :show-review true
                              :is-open (= (:id thread) (:open clocal))}])])
       (when (seq todo)
         [:section {:class (stl/css :context-group)}
          [:> context-head* {:icon i/view-as-list
                             :label (tr "workspace.compare.layer.tasks" (count todo))}]
          [:> task-list* {:tasks todo :show-review true}]])])))

(mf/defc compare-detail*
  []
  (let [comparison (cc/use-comparison)
        preview    (mf/deref refs/branch-review-preview)
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
       [:> conflict* {:comparison comparison :is-review review?}]
       [:> layer-context* {:item item :data (:data comparison)}]]

      :else
      [:div {:class (stl/css :compare-detail)}
       [:> change* {:comparison comparison}]
       [:> layer-context* {:item item :data (:data comparison)}]])))
