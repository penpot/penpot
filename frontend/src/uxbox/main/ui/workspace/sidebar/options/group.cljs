;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.group
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measures-menu]]
   [uxbox.main.ui.workspace.sidebar.options.multiple :refer [get-multi]]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [child-ids (:shapes shape)
        children (mf/deref (refs/objects-by-id child-ids))

        type (:type shape)
        fill-values (get-multi children fill-attrs)
        stroke-values (get-multi children stroke-attrs)]
  [:*
   [:& measures-menu {:options #{:position :rotation}
                      :shape shape}]
   [:& fill-menu {:ids child-ids
                  :type type
                  :values fill-values}]
   [:& stroke-menu {:ids child-ids
                    :type type
                    :values stroke-values}]]))

