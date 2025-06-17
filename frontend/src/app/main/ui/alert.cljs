;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.alert
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.link :as lk]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(mf/defc alert-dialog
  {::mf/register modal/components
   ::mf/register-as :alert}
  [{:keys [message
           scd-message
           link-message
           title
           on-accept
           hint
           accept-label
           accept-style] :as props}]

  (let [on-accept    (or on-accept identity)
        message      (or message (tr "ds.alert-title"))
        accept-label (or accept-label (tr "ds.alert-ok"))
        accept-style (or accept-style :danger)
        title        (or title (tr "ds.alert-title"))

        accept-fn
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept props)))]

    (mf/with-effect []
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (st/emit! (modal/hide))
                  (on-accept props)))]
        (->> (events/listen js/document "keydown" on-keydown)
             (partial events/unlistenByKey))))
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} title]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click accept-fn} i/close]]

      [:div {:class (stl/css :modal-content)}
       (when (and (string? message) (not= message ""))
         [:h3 {:class (stl/css :modal-msg)} message])
       (when (seq link-message)
         [:h3 {:class (stl/css :modal-msg)}
          [:span (:before link-message)]
          [:& lk/link {:action (:on-click link-message)
                       :class (stl/css :link)}
           (:text link-message)]
          [:span (:after link-message)]])
       (when (and (string? scd-message) (not= scd-message ""))
         [:h3 {:class (stl/css :modal-scd-msg)} scd-message])

       (when (string? hint)
         [:> context-notification* {:level :info
                                    :appearance :ghost}
          hint])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css-case :accept-btn true
                                      :danger (= accept-style :danger)
                                      :primary (= accept-style :primary))
                 :type "button"
                 :value accept-label
                 :on-click accept-fn}]]]]]))
