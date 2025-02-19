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
   [:accept-label {:optional true} :string]
   [:cancel-label {:optional true} :string]
   [:on-accept {:optional true} [:fn fn?]]
   [:on-cancel {:optional true} [:fn fn?]]])

(mf/defc actionable*
  {::mf/schema schema:actionable}
  [{:keys [class variant accept-label cancel-label children on-accept on-cancel] :rest props}]

  (let [variant (d/nilv variant "default")
        class   (d/append-class class (stl/css :notification))
        props   (mf/spread-props props
                                 {:class class
                                  :data-testid "actionable"})

        on-accept
        (mf/use-fn
         (fn [e]
           (when (fn? on-accept)
             (on-accept e))))

        on-cancel
        (mf/use-fn
         (fn [e]
           (when on-cancel (on-cancel e))))]

    [:> :aside props
     [:div {:class (stl/css :notification-message)} children]
     [:> button* {:variant "secondary"
                  :on-click on-cancel}
      cancel-label]
     [:> button* {:variant (if (= variant "default") "primary" "destructive")
                  :on-click on-accept}
      accept-label]]))
