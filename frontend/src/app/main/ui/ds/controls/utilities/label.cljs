;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.utilities.label
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

(def ^:private schema::label
  [:map
   [:for :string]
   [:is-optional {:optional true} :boolean]
   [:class {:optional true} :string]])

(mf/defc label*
  {::mf/schema schema::label}
  [{:keys [class for is-optional children] :rest props}]
  (let [is-optional (or is-optional false)
        props (mf/spread-props props {:class (dm/str class " " (stl/css :label))
                                      :for for})]
    [:> "label" props
     [:*
      (when (some? children)
        [:span {:class (stl/css :label-text)} children])
      (when is-optional
        [:span {:class (stl/css :label-optional)} "(Optional)"])]]))
