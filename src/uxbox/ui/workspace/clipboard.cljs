;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.clipboard
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.data.workspace :as udw]
            [uxbox.util.dom :as dom]
            [uxbox.util.datetime :as dt]))

;; --- Lenses

(def ^:const ^:private clipboard-l
  (-> (l/key :clipboard)
      (l/focus-atom st/state)))

;; --- Clipboard Dialog Component

(defn- on-paste
  [item]
  (rs/emit! (udw/paste-from-clipboard (:id item)))
  (lightbox/close!))

(defn- clipboard-dialog-render
  [own]
  (let [clipboard (rum/react clipboard-l)]
    (html
     [:div.lightbox-body.clipboard
      [:div.clipboard-list
       (for [item clipboard]
         [:div.clipboard-item
          {:key (str (:id item))
           :on-click (partial on-paste item)}
          [:span.clipboard-icon i/box]
          [:span (str "Copied (" (dt/timeago (:created-at item)) ")")]])]
      [:a.close {:href "#"
                 :on-click #(do (dom/prevent-default %)
                                (lightbox/close!))} i/close]])))

(def clipboard-dialog
  (mx/component
   {:render clipboard-dialog-render
    :name "clipboard-dialog"
    :mixins [mx/static rum/reactive]}))

(defmethod lightbox/render-lightbox :clipboard
  [_]
  (clipboard-dialog))
