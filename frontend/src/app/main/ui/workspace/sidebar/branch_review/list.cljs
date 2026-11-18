;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-review.list
  "The rows of a comparison: the changes of a branch, grouped by page and
  board, each with the checkbox that keeps it in or leaves it out; and
  the conflicts of a merge or an update, with the ones that still wait
  for a choice first."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.change-row :refer [change-row*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Changes

(mf/defc row-end*
  {::mf/private true}
  [{:keys [item excluded reviewed]}]
  (let [status (:status item)
        edits  (count (brc/visible-attrs (:changed-attrs item)))]
    (cond
      (= :conflict status)
      [:> tag* {:tone "warning" :variant "pill"}
       (tr "workspace.branches.review.row.choose")]

      ;; information, not a change: nothing to choose or leave out
      (= :settled status)
      [:> tag* {:variant "pill"} (tr "workspace.branches.review.copies.settled-tag")]

      excluded
      [:> tag* {:tone "info" :variant "pill"} (tr "workspace.branches.review.row.left-out")]

      ;; reviewing is the user's progress, not what happened to the
      ;; entry: a quiet tag in the place of the coloured status
      reviewed
      [:> tag* {:variant "pill"} (tr "workspace.branches.review.row.reviewed")]

      (= :added status)
      [:> tag* {:tone "success" :variant "pill"} (tr "workspace.branches.status.added")]

      (= :deleted status)
      [:> tag* {:tone "error" :variant "pill"} (tr "workspace.branches.status.deleted")]

      (pos? edits)
      [:> tag* {:tone "info" :variant "pill"}
       (tr "workspace.branches.review.row.edits" (i18n/c edits))]

      :else nil)))

(defn- follows-of
  "How many copies follow the component `item` is, or the main instance
  of (`brc/copy-follow-counts`), or nil."
  [follow-counts item]
  (when (or (= :component (:kind item)) (:component? item))
    (get follow-counts (:id item))))

(def pointed-row
  "The key of the entry a list shows as pointed at from somewhere else
  (its layer on the canvas), for its row to say so."
  (mf/create-context nil))

(mf/defc item-row*
  "One change. `inner` are the layers added or deleted with it, folded
  under it: the row counts them and its checkbox takes them along.
  `follows` is how many copies follow the component the row changes.
  Without `on-toggle` the row has no checkbox."
  [{:keys [item inner follows selected excluded reviewed on-select on-toggle]}]
  (let [item-key    (dwcp/item-key item)
        selectable? (and (some? on-toggle) (brc/selectable-item? item))
        toggle-keys (brc/row-keys item inner)
        subtitle    (cond-> (brc/change-summary item)
                      (seq inner)
                      (dm/str " - " (tr "workspace.branches.review.facts.layers"
                                        (i18n/c (count inner))))

                      (and follows (pos? follows))
                      (dm/str " - " (tr "workspace.branches.review.copies.row-follow"
                                        (i18n/c follows))))
        pointed?    (= item-key (mf/use-ctx pointed-row))
        on-click    (mf/use-fn (mf/deps item-key on-select) #(on-select item-key))
        on-check    (mf/use-fn (mf/deps toggle-keys on-toggle) #(on-toggle toggle-keys))
        start       (when selectable?
                      (mf/html
                       [:span {:class (stl/css :row-check)
                               ;; the click must not also select the row
                               :on-click dom/stop-propagation}
                        [:> checkbox* {:id (dm/str "change-" item-key)
                                       :checked (not excluded)
                                       :on-change on-check}]]))]
    [:> change-row* {:title (or (:label item) "")
                     :subtitle subtitle
                     :icon (brc/entry-icon item)
                     :tone (if (and excluded selectable?) "neutral" (brc/status-tone (:status item)))
                     :is-selected (= selected item-key)
                     :is-muted (boolean (and excluded selectable?))
                     :class (stl/css-case :is-pointed pointed?)
                     ;; for whoever follows the pointer over the list
                     :data-item-key item-key
                     :start start
                     :end (mf/html [:> row-end* {:item item :excluded excluded :reviewed reviewed}])
                     :on-click on-click}]))

(mf/defc rows*
  "The rows of `items`, each with what the list knows about it."
  {::mf/private true}
  [{:keys [items cascades follow-counts selected excluded reviewed on-select on-toggle]}]
  (for [item items
        :let [item-key (dwcp/item-key item)]]
    [:> item-row* {:key item-key
                   :item item
                   :inner (get cascades item-key)
                   :follows (follows-of follow-counts item)
                   :selected selected
                   :excluded (contains? excluded (:key item))
                   :reviewed (contains? reviewed item-key)
                   :on-select on-select
                   :on-toggle on-toggle}]))

(mf/defc board-group*
  "A top-level board that holds two or more changes: they fold under it."
  {::mf/private true}
  [{:keys [group board collapsed on-fold children]}]
  (let [fold-id  (dm/str (:id group) "/" (:id board))
        open?    (not (contains? collapsed fold-id))
        on-click (mf/use-fn (mf/deps fold-id on-fold) #(on-fold fold-id))]
    [:div {:class (stl/css :board)}
     [:> brl/group-head* {:label (dm/str (:name board) " (" (count (remove bm/settled? (:rows board))) ")")
                          :icon i/board
                          :is-open open?
                          :on-toggle on-click}]
     (when open? children)]))

(mf/defc change-group*
  "The changes of one page: the group folds, and its checkbox takes all of
  them."
  {::mf/private true}
  [{:keys [group cascades follow-counts selected excluded reviewed collapsed
           on-select on-toggle on-fold]}]
  (let [{:keys [id name rows boards count]} group
        open?     (not (contains? collapsed id))
        toggles   (into #{}
                        (comp (filter brc/selectable-item?)
                              (mapcat #(brc/row-keys % (get cascades (dwcp/item-key %)))))
                        (concat rows (mapcat :rows boards)))
        included? (not-any? excluded toggles)
        on-header (mf/use-fn (mf/deps id on-fold) #(on-fold id))
        on-check  (mf/use-fn (mf/deps toggles included? on-toggle) #(on-toggle toggles (not included?)))]
    [:div {:class (stl/css :group)}
     [:> brl/group-head*
      {:label (dm/str name " (" count ")")
       :is-open open?
       :on-toggle on-header
       :start (when (seq toggles)
                (mf/html
                 [:span {:class (stl/css :row-check)}
                  [:> checkbox* {:id (dm/str "group-" id)
                                 :checked included?
                                 :on-change on-check}]]))}]
     (when open?
       [:*
        [:> rows* {:items rows
                   :cascades cascades
                   :follow-counts follow-counts
                   :selected selected
                   :excluded excluded
                   :reviewed reviewed
                   :on-select on-select
                   :on-toggle on-toggle}]
        (for [board boards]
          [:> board-group* {:key (str (:id board))
                            :group group
                            :board board
                            :collapsed collapsed
                            :on-fold on-fold}
           [:> rows* {:items (:rows board)
                      :cascades cascades
                      :follow-counts follow-counts
                      :selected selected
                      :excluded excluded
                      :reviewed reviewed
                      :on-select on-select
                      :on-toggle on-toggle}]])])]))

(mf/defc change-list*
  "The list of a comparison (`model`, a `brc/change-model`): the changes
  both sides made, which wait for a decision, and then the clean ones by
  page and board."
  [{:keys [model follow-counts selected excluded reviewed collapsed
           on-select on-toggle on-fold]}]
  (let [{:keys [conflicts groups cascades]} model
        open?     (not (contains? collapsed "decisions"))
        on-header (mf/use-fn (mf/deps on-fold) #(on-fold "decisions"))]
    [:*
     (when (seq conflicts)
       [:div {:class (stl/css :group)}
        [:> brl/group-head* {:label (tr "workspace.branches.review.group.decisions"
                                        (str (count conflicts)))
                             :is-open open?
                             :on-toggle on-header}]
        (when open?
          [:> rows* {:items conflicts
                     :follow-counts follow-counts
                     :selected selected
                     :reviewed reviewed
                     :on-select on-select}])])

     (for [group groups]
       [:> change-group* {:key (:id group)
                          :group group
                          :cascades cascades
                          :follow-counts follow-counts
                          :selected selected
                          :excluded excluded
                          :reviewed reviewed
                          :collapsed collapsed
                          :on-select on-select
                          :on-toggle on-toggle
                          :on-fold on-fold}])]))

;; --- Conflicts

(defn- row-subtitle
  [conflict res]
  (let [rows        (brc/conflict-attr-rows conflict)
        conflicting (brc/conflicting-attrs rows)
        drops       (brc/dropped-values conflict res)]
    (cond
      (bm/conflict-resolved? conflict res)
      (cond
        (= res :main)   (tr "workspace.branches.resolve.row.kept-main")
        (= res :branch) (tr "workspace.branches.resolve.row.kept-branch")
        (seq drops)     (tr "workspace.branches.resolve.row.drops"
                            (str/lower (str/join ", " (map (comp brc/attr-label :attr) drops))))
        :else           (tr "workspace.branches.resolve.row.kept-both"))

      (seq conflicting)
      (tr "workspace.branches.resolve.row.both-changed"
          (str/join ", " (map brc/attr-label conflicting)))

      :else
      (brc/conflict-reason-label (:reason conflict)))))

(mf/defc conflict-row*
  {::mf/private true}
  [{:keys [conflict selected res on-select]}]
  (let [item-key  (dwcp/item-key conflict)
        resolved? (bm/conflict-resolved? conflict res)
        pointed?  (= item-key (mf/use-ctx pointed-row))
        on-click  (mf/use-fn (mf/deps item-key on-select) #(on-select item-key))]
    [:> change-row* {:title (or (:label conflict) "")
                     :subtitle (row-subtitle conflict res)
                     :icon (brc/entry-icon conflict)
                     :tone (if resolved? "success" "warning")
                     :is-selected (= selected item-key)
                     :class (stl/css-case :is-pointed pointed?)
                     :data-item-key item-key
                     :end (mf/html
                           (cond
                             resolved?
                             [:> tag* {:tone "success" :variant "pill"}
                              (tr "workspace.branches.resolve.decided")]
                             (brc/loses-work? conflict)
                             [:> tag* {:tone "error" :variant "pill"}
                              (tr "workspace.branches.resolve.row.loses-work")]
                             :else
                             [:> tag* {:tone "warning" :variant "pill"}
                              (tr "workspace.branches.review.row.choose")]))
                     :on-click on-click}]))

(mf/defc conflict-group*
  {::mf/private true}
  [{:keys [id label conflicts resolutions selected collapsed on-select on-fold]}]
  (let [open?     (not (contains? collapsed id))
        on-header (mf/use-fn (mf/deps id on-fold) #(on-fold id))]
    (when (seq conflicts)
      [:div {:class (stl/css :group)}
       [:> brl/group-head* {:label label :is-open open? :on-toggle on-header}]
       (when open?
         (for [conflict conflicts]
           [:> conflict-row* {:key (dwcp/item-key conflict)
                              :conflict conflict
                              :selected selected
                              :res (get resolutions (:id conflict))
                              :on-select on-select}]))])))

(mf/defc conflict-list*
  "The conflicts of a merge or an update (`model`, a
  `brc/conflict-model`): the ones that wait for a choice, then the ones
  decided."
  [{:keys [model resolutions selected collapsed on-select on-fold]}]
  (let [{:keys [pending decided]} model]
    [:*
     [:> conflict-group* {:id "pending"
                          :label (tr "workspace.branches.resolve.group.pending" (str (count pending)))
                          :conflicts pending
                          :resolutions resolutions
                          :selected selected
                          :collapsed collapsed
                          :on-select on-select
                          :on-fold on-fold}]
     [:> conflict-group* {:id "decided"
                          :label (tr "workspace.branches.resolve.group.decided" (str (count decided)))
                          :conflicts decided
                          :resolutions resolutions
                          :selected selected
                          :collapsed collapsed
                          :on-select on-select
                          :on-fold on-fold}]]))
