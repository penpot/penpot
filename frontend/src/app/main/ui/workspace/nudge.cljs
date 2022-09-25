;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.nudge
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn- on-keydown
  [event]
  (when (k/enter? event)
    (dom/prevent-default event)
    (dom/stop-propagation event)
    (modal/hide!)))

(mf/defc nudge-modal
  {::mf/register modal/components
   ::mf/register-as :nudge-option}
  []
  (let [profile      (mf/deref refs/profile)
        nudge        (or (get-in profile [:props :nudge]) {:big 10 :small 1})
        update-big   (mf/use-fn #(st/emit! (dw/update-nudge {:big %})))
        update-small (mf/use-fn #(st/emit! (dw/update-nudge {:small %})))
        on-close     (mf/use-fn #(modal/hide!))]

    (mf/with-effect
      (->> (events/listen js/document EventType.KEYDOWN on-keydown)
           (partial events/unlistenByKey)))

    [:div.nudge-modal-overlay
     [:div.nudge-modal-container
      [:div.nudge-modal-header
       [:p.nudge-modal-title (tr "modals.nudge-title")]
       [:button.modal-close-button {:on-click on-close} i/close]]
      [:div.nudge-modal-body
       [:div.input-wrapper
        [:span
         [:p.nudge-subtitle (tr "modals.small-nudge")]
         [:> numeric-input {:min 0.01
                            :value (:small nudge)
                            :on-change update-small}]]]
       [:div.input-wrapper
        [:span
         [:p.nudge-subtitle (tr "modals.big-nudge")]
         [:> numeric-input {:min 0.01
                            :value (:big nudge)
                            :on-change update-big}]]]]]]))
