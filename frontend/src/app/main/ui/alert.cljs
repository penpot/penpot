;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.alert
  (:require
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc alert-dialog
  {::mf/register modal/components
   ::mf/register-as :alert}
  [{:keys [message
           scd-message
           title
           on-accept
           hint
           accept-label
           accept-style] :as props}]
  (let [locale       (mf/deref i18n/locale)

        on-accept    (or on-accept identity)
        message      (or message (t locale "ds.alert-title"))
        accept-label (or accept-label (tr "ds.alert-ok"))
        accept-style (or accept-style :danger)
        title        (or title (t locale "ds.alert-title"))

        accept-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept props)))]

    (mf/with-effect
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (st/emit! (modal/hide))
                  (on-accept props)))]
        (->> (events/listen js/document EventType.KEYDOWN on-keydown)
             (partial events/unlistenByKey))))

    [:div.modal-overlay
     [:div.modal-container.alert-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 title]]
       [:div.modal-close-button
        {:on-click accept-fn} i/close]]

      [:div.modal-content
       (when (and (string? message) (not= message ""))
         [:h3 message])
       (when (and (string? scd-message) (not= scd-message ""))
         [:h3 scd-message])
       (when (string? hint)
         [:p hint])]

      [:div.modal-footer
       [:div.action-buttons
        [:input.accept-button
         {:class (dom/classnames
                  :danger (= accept-style :danger)
                  :primary (= accept-style :primary))
          :type "button"
          :value accept-label
          :on-click accept-fn}]]]]]))
