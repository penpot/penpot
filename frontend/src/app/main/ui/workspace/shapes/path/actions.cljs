;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path.actions
  (:require
   [app.main.data.workspace.drawing.path :as drp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.shapes.path.common :as pc]
   [rumext.alpha :as mf]))

(mf/defc path-actions [{:keys [shape]}]
  (let [id (mf/deref refs/selected-edition)
        {:keys [edit-mode selected-points snap-toggled] :as all} (mf/deref pc/current-edit-path-ref)]
    [:div.path-actions
     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class (when (= edit-mode :draw) "is-toggled")
                                    :on-click #(st/emit! (drp/change-edit-mode :draw))} i/pen]
      [:div.viewport-actions-entry {:class (when (= edit-mode :move) "is-toggled")
                                    :on-click #(st/emit! (drp/change-edit-mode :move))} i/pointer-inner]]
     
     #_[:div.viewport-actions-group
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-add]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-remove]]

     #_[:div.viewport-actions-group
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-merge]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-join]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-separate]]

     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class (when (empty? selected-points) "is-disabled")
                                    :on-click #(when-not (empty? selected-points)
                                                 (st/emit! (drp/make-corner)))} i/nodes-corner]
      [:div.viewport-actions-entry {:class (when (empty? selected-points) "is-disabled")
                                    :on-click #(when-not (empty? selected-points)
                                                 (st/emit! (drp/make-curve)))} i/nodes-curve]]

     #_[:div.viewport-actions-group
      [:div.viewport-actions-entry {:class (when snap-toggled "is-toggled")} i/nodes-snap]]]))
