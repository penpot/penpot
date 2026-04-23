;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.nitrate.nitrate-code-activation-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.profile :as dprof]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc nitrate-code-activation-modal*
  {::mf/register modal/components
   ::mf/register-as :nitrate-code-activation}
  [_props]
  (let [value*  (mf/use-state "")
        error*  (mf/use-state nil)

        on-change
        (mf/use-fn
         (fn [event]
           (reset! error* nil)
           (reset! value* (dom/get-target-val event))))

        on-accept
        (mf/use-fn
         (mf/deps value*)
         (fn [_]
           (let [code (str/trim @value*)]
             (when (seq code)
               (->> (rp/cmd! ::redeem-nitrate-activation-code {:activation-code code})
                    (rx/subs!
                     (fn [result]
                       (modal/hide!)
                       (st/emit!
                        (modal/show {:type :nitrate-activation-success :subscription result})
                        (dprof/refresh-profile)))
                     (fn [error]
                       ;; TODO: "Already used" is not yet detectable (CC upserts on reuse).
                       (let [code (-> error ex-data :code)]
                         (reset! error* (case code
                                          :expired-activation-code (tr "nitrate.activation-code.expired-error")
                                          (tr "nitrate.activation-code.invalid-error")))))))))))

        on-key-down
        (mf/use-fn
         (mf/deps on-accept)
         (fn [event]
           (when (and (= "Enter" (.-key event)) (.-ctrlKey event))
             (on-accept event))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :close-btn)
                        :aria-label (tr "labels.close")
                        :on-click modal/hide!
                        :icon i/close}]

      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "nitrate.code-activation.title")]]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css-case :code-field true :invalid (some? @error*))}
        [:label {:class (stl/css :code-label)}
         (tr "nitrate.code-activation.input-label")]
        [:textarea {:class (stl/css :code-textarea)
                    :auto-focus true
                    :value @value*
                    :placeholder (tr "nitrate.code-activation.placeholder")
                    :on-change on-change
                    :on-key-down on-key-down}]
        (when @error*
          [:span {:class (stl/css :error-msg)} @error*])]

       [:input
        {:type "button"
         :class (stl/css-case :accept-btn true
                              :global/disabled (empty? (str/trim @value*)))
         :disabled (empty? (str/trim @value*))
         :value (tr "nitrate.code-activation.submit")
         :on-click on-accept}]
       [:div {:class (stl/css :footer-text)}
        (tr "nitrate.code-activation.footer") " "
        [:a {:class (stl/css :link)
             :href "mailto:sales@nitrate.com"}
         "sales@nitrate.com"]]]]]))
