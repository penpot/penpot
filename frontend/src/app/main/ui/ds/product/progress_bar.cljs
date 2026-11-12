;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.progress-bar
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

(def ^:private schema:progress-bar
  [:map
   [:class {:optional true} [:maybe :string]]
   [:value :int]
   [:total :int]])

(mf/defc progress-bar*
  "A thin bar filled by how much of `total` is done (`value`). The caller
  sizes it through `class`."
  {::mf/schema schema:progress-bar}
  [{:keys [class value total] :rest props}]
  (let [percent (if (pos? total) (* 100 (/ value total)) 0)
        props   (mf/spread-props props
                                 {:class [class (stl/css :progress-bar)]
                                  :role "progressbar"
                                  :aria-valuemin 0
                                  :aria-valuemax total
                                  :aria-valuenow value})]
    [:> :span props
     [:span {:class (stl/css :fill)
             :style #js {:width (dm/str percent "%")}}]]))
