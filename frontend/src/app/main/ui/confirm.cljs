;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.confirm
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc confirm-dialog
  {::mf/register modal/components
   ::mf/register-as :confirm}
  [{:keys [message
           scd-message
           title
           on-accept
           on-cancel
           hint
           items
           cancel-label
           accept-label
           accept-style] :as props}]
  (let [on-accept    (or on-accept identity)
        on-cancel    (or on-cancel identity)
        message      (or message (tr "ds.confirm-title"))
        cancel-label (or cancel-label (tr "ds.confirm-cancel"))
        accept-label (or accept-label (tr "ds.confirm-ok"))
        accept-style (or accept-style :danger)
        title        (or title (tr "ds.confirm-title"))

        accept-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept props)))

        cancel-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-cancel props)))]

    (mf/with-effect
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (st/emit! (modal/hide))
                  (on-accept props)))]
        (->> (events/listen js/document EventType.KEYDOWN on-keydown)
             (partial events/unlistenByKey))))


    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} title]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click cancel-fn} i/close]]

      [:div {:class (stl/css :modal-content)}
       (when (and (string? message) (not= message ""))
         [:h3 {:class (stl/css :modal-msg)} message])
       (when (and (string? scd-message) (not= scd-message ""))
         [:h3 {:class (stl/css :modal-scd-msg)} scd-message])
       (when (string? hint)
         [:> context-notification* {:level :info
                                    :appearance :ghost}
          hint])
       (when (> (count items) 0)
         [:*
          [:p {:class (stl/css :modal-subtitle)}
           (tr "ds.component-subtitle")]
          [:ul {:class (stl/css :component-list)}
           (for [item items]
             [:li {:class (stl/css :modal-item-element)}
              [:span {:class (stl/css :modal-component-icon)}
               i/component]
              [:span {:class (stl/css :modal-component-name)}
               (:name item)]])]])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        (when-not (= cancel-label :omit)
          [:input
           {:class (stl/css :cancel-button)
            :type "button"
            :value cancel-label
            :on-click cancel-fn}])

        [:input
         {:class (stl/css-case :accept-btn true
                               :danger (= accept-style :danger)
                               :primary (= accept-style :primary))
          :type "button"
          :value accept-label
          :on-click accept-fn}]]]]]))
