;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.confirm
  (:require
   [uxbox.main.ui.icons :as i]
   [rumext.alpha :as mf]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.dom :as dom]))

(mf/defc confirm-dialog
  [{:keys [message on-accept on-cancel hint cancel-text accept-text] :as ctx}]
  (let [message (or message (tr "ds.confirm-title"))
        cancel-text (or cancel-text (tr "ds.confirm-cancel"))
        accept-text (or accept-text (tr "ds.confirm-ok"))

        accept
        (fn [event]
          (dom/prevent-default event)
          (modal/hide!)
          (on-accept (dissoc ctx :on-accept :on-cancel)))

        cancel
        (fn [event]
          (dom/prevent-default event)
          (modal/hide!)
          (when on-cancel
            (on-cancel (dissoc ctx :on-accept :on-cancel))))]
    [:div.modal-overlay
     [:div.modal.confirm-dialog
      [:a.close {:on-click cancel} i/close]
      [:div.modal-content
       [:h3.dialog-title message]
       (if hint [:span hint])
       [:div.dialog-buttons
        [:input.dialog-cancel-button
         {:type "button"
          :value cancel-text
          :on-click cancel}]

        [:input.dialog-accept-button
         {:type "button"
          :value accept-text
          :on-click accept}]]]]]))
