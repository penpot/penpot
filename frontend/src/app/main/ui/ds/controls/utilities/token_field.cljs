;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.utilities.token-field
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))


(def ^:private schema:token-field
  [:map
   [:id {:optional true} [:maybe :string]]
   [:label {:optional true} [:maybe :string]]
   [:value :any]
   [:disabled {:optional true} :boolean]
   [:slot-start {:optional true} [:maybe some?]]
   [:on-click {:optional true} fn?]
   [:on-token-key-down fn?]
   [:on-blur {:optional true} fn?]
   [:detach-token fn?]])

(mf/defc token-field*
  {::mf/schema schema:token-field}
  [{:keys [id label value slot-start disabled
           on-click on-token-key-down on-blur detach-token
           token-wrapper-ref token-detach-btn-ref]}]
  (let [set-active? (some? id)
        content     (if set-active?
                      label
                      (tr "ds.inputs.token-field.no-active-token-option"))
        default-id  (mf/use-id)
        id          (d/nilv id default-id)

        focus-wrapper
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (when-not ^boolean disabled
             (dom/prevent-default event)
             (dom/focus! (mf/ref-val token-wrapper-ref)))))

        class
        (stl/css-case :token-field true
                      :with-icon (some? slot-start)
                      :token-field-disabled disabled)]

    [:div {:class class
           :on-click focus-wrapper
           :disabled disabled
           :on-key-down on-token-key-down
           :ref token-wrapper-ref
           :on-blur on-blur
           :tab-index (if disabled -1 0)}

     (when (some? slot-start) slot-start)

     [:> tooltip* {:content content
                   :id (dm/str id "-pill")}
      [:button {:on-click on-click
                :class (stl/css-case :pill true
                                     :no-set-pill (not set-active?)
                                     :pill-disabled disabled)
                :disabled disabled
                :aria-labelledby (dm/str id "-pill")
                :on-key-down on-token-key-down}
       value
       (when-not set-active?
         [:div {:class (stl/css :pill-dot)}])]]

     (when-not ^boolean disabled
       [:> icon-button* {:variant "action"
                         :class (stl/css :invisible-button)
                         :icon i/broken-link
                         :ref token-detach-btn-ref
                         :aria-label (tr "ds.inputs.token-field.detach-token")
                         :on-click detach-token}])]))
