;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.shapes.grid-cell
  (:require
   [rumext.v2 :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape row column] :as props}]

  [:div.element-set
   [:div.element-set-title
    [:span "Grid Cell"]]

   [:div.element-set-content.layout-item-menu
    [:div.layout-row
     [:div.row-title.sizing "Position"]
     [:div (str row "," column)]]]])
