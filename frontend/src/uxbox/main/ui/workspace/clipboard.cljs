;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.clipboard
  (:require [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.workspace :as udw]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [potok.core :as ptk]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.time :as dt]))

(def ^:private clipboard-ref
  (-> (l/key :clipboard)
      (l/derive st/state)))

(mx/defc clipboard-dialog
  {:mixins [mx/static mx/reactive]}
  []
  (letfn [(on-paste [item]
            (st/emit! (udw/paste-from-clipboard (:id item)))
            (udl/close!))
          (on-close [event]
            (dom/prevent-default event)
            (udl/close!))]
    [:div.lightbox-body.clipboard
     [:div.clipboard-list
      (for [item (mx/react clipboard-ref)]
        [:div.clipboard-item
         {:key (str (:id item))
          :on-click (partial on-paste item)}
         [:span.clipboard-icon i/box]
         [:span (str "Copied (" (dt/timeago (:created-at item)) ")")]])]
     [:a.close {:href "#" :on-click on-close} i/close]]))

(defmethod lbx/render-lightbox :clipboard
  [_]
  (clipboard-dialog))
