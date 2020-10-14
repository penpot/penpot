;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.confirm
  (:require
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.data :refer [classnames]]
   [rumext.alpha :as mf]))

(mf/defc confirm-dialog
  {::mf/register modal/components
   ::mf/register-as :confirm}
  [{:keys [message
           title
           on-accept
           on-cancel
           hint
           cancel-label
           accept-label
           accept-style] :as props}]
  (let [locale       (mf/deref i18n/locale)

        on-accept    (or on-accept identity)
        on-cancel    (or on-cancel identity)
        message      (or message (t locale "ds.confirm-title"))
        cancel-label (or cancel-label (tr "ds.confirm-cancel"))
        accept-label (or accept-label (tr "ds.confirm-ok"))
        accept-style (or accept-style :danger)
        title        (or title (t locale "ds.confirm-title"))

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

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 title]]
       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      [:div.modal-content
       [:h3 message]
       (when (string? hint)
         [:p hint])]

      [:div.modal-footer
       [:div.action-buttons
        (when-not (empty? cancel-label)
          [:input.cancel-button
           {:type "button"
            :value cancel-label
            :on-click cancel-fn}])

        [:input.accept-button
         {:class (classnames :danger (= accept-style :danger)
                             :primary (= accept-style :primary))
          :type "button"
          :value accept-label
          :on-click accept-fn}]]]]]))

