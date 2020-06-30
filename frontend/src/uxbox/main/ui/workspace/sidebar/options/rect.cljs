;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measures-menu]]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]))

(mf/defc options
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        fill-values (select-keys shape fill-attrs)
        stroke-values (select-keys shape stroke-attrs)]
    [:div
     [:& measures-menu {:shape shape}]
     [:& fill-menu {:ids ids :values fill-values}]
     [:& stroke-menu {:ids ids :values stroke-values}]]))

