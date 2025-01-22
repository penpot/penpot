;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.actionable
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [rumext.v2 :as mf]))

(def ^:private schema:actionable
  [:map
   [:class {:optional true} :string]
   [:variant {:optional true}
    [:maybe [:enum "default" "error"]]]
   [:actionLabel {:optional true} :string]
   [:cancelLabel {:optional true} :string]])

(mf/defc actionable*
  {::mf/props :obj
   ::mf/schema schema:actionable}
  [{:keys [class variant actionLabel cancelLabel children] :rest props}]

  (let [class (d/append-class class (stl/css :notification))
        props (mf/spread-props props {:class class :data-testid "actionable"})]
    [:> "aside" props
     [:div {:class (stl/css :notification-message)} children]
     [:> button* {:variant "secondary"} cancelLabel]
     [:> button* {:variant (if (= variant "default") "primary" "destructive")} actionLabel]]))
