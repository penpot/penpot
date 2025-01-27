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
   [:acceptLabel {:optional true} :string]
   [:cancelLabel {:optional true} :string]
   [:onAccept {:optional true} [:fn fn?]]
   [:onCancel {:optional true} [:fn fn?]]])

(mf/defc actionable*
  {::mf/props :obj
   ::mf/schema schema:actionable}
  [{:keys [class variant acceptLabel cancelLabel children onAccept onCancel] :rest props}]

  (let [variant (or variant "default")
        class (d/append-class class (stl/css :notification))
        props (mf/spread-props props {:class class :data-testid "actionable"})

        handle-accept
        (mf/use-fn
         (fn [e]
           (when onAccept (onAccept e))))

        handle-cancel
        (mf/use-fn
         (fn [e]
           (when onCancel (onCancel e))))]

    [:> "aside" props
     [:div {:class (stl/css :notification-message)}
      children]
     [:> button* {:variant "secondary"
                  :on-click handle-cancel} cancelLabel]
     [:> button* {:variant (if (= variant "default") "primary" "destructive")
                  :on-click handle-accept} acceptLabel]]))
