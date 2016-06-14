;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.confirm
  (:require [sablono.core :as html :refer-macros [html]]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.lightbox :as lbx]))

(defn- confirm-dialog-render
  [own {:keys [on-accept on-cancel] :as ctx}]
  (letfn [(accept [event]
            (dom/prevent-default event)
            (udl/close!)
            (on-accept (dissoc ctx :on-accept :on-cancel)))
          (cancel [event]
            (dom/prevent-default event)
            (udl/close!)
            (when on-cancel
              (on-cancel (dissoc ctx :on-accept :on-cancel))))]
    (html
     [:div.lightbox-body.confirm-dialog
      [:h3 "Are you sure?"]
      [:span "You're going to delete __PAGENAME__"]
      [:div.row-flex
       [:input.btn-success.btn-small
        {:type "button"
         :value "Ok"
         :on-click accept}]
       [:input.btn-delete.btn-small
        {:type "button"
         :value "Cancel"
         :on-click cancel}]]
         [:a.close {:href "#"
                :on-click #(do (dom/prevent-default %)
                               (udl/close!))} i/close]])))

(def confirm-dialog
  (mx/component
   {:render confirm-dialog-render
    :name "confirm-dialog"
    :mixins []}))

(defmethod lbx/render-lightbox :confirm
  [context]
  (confirm-dialog context))
