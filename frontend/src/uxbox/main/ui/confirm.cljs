;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.confirm
  (:require
   [uxbox.builtins.icons :as i]
   [rumext.alpha :as mf]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.dom :as dom]))

(mf/defc confirm-dialog
  [{:keys [on-accept on-cancel hint] :as ctx}]
  (letfn [(accept [event]
            (dom/prevent-default event)
            (modal/hide!)
            (on-accept (dissoc ctx :on-accept :on-cancel)))
          (cancel [event]
            (dom/prevent-default event)
            (modal/hide!)
            (when on-cancel
              (on-cancel (dissoc ctx :on-accept :on-cancel))))]
    [:div.lightbox-body.confirm-dialog
     [:h3 (tr "ds.confirm-title")]
     (if hint
       [:span hint])
     [:div.row-flex
      [:input.btn-success.btn-small
       {:type "button"
        :value (tr "ds.confirm-ok")
        :on-click accept}]
      [:input.btn-delete.btn-small
       {:type "button"
        :value (tr "ds.confirm-cancel")
        :on-click cancel}]]
     [:a.close {:href "#"
                :on-click #(do
                             (dom/prevent-default %)
                             (modal/hide!))}
      i/close]]))
