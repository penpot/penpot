;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.nudge
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.icons :as deprecated-icon]
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
        update-small (mf/use-fn #(st/emit! (dw/update-nudge {:small %})))]

    (mf/with-effect
      (->> (events/listen js/document EventType.KEYDOWN on-keydown)
           (partial events/unlistenByKey)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.nudge-title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]
      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "nudge-small"} (tr "modals.small-nudge")]
        [:> numeric-input* {:min 0.01
                            :id "nudge-small"
                            :value (:small nudge)
                            :on-change update-small}]]
       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "nudge-big"} (tr "modals.big-nudge")]
        [:> numeric-input* {:min 0.01
                            :id "nudge-big"
                            :value (:big nudge)
                            :on-change update-big}]]]]]))
