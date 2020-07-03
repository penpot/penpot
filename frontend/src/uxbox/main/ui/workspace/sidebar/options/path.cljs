;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.path
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        stroke-values (select-keys shape stroke-attrs)]
    [:*
     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]]))
