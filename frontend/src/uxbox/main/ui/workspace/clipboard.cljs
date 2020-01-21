;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.clipboard
  (:require
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.util.dom :as dom]
   [uxbox.util.time :as dt]))

(mf/def clipboard-dialog
  :mixins [mf/reactive]
  :init
  (fn [own props]
    (assoc own ::clipboard (-> (l/key :clipboard)
                               (l/derive st/state))))

  :render
  (fn [own props]
    []
    (letfn [(on-paste [item]
              #_(st/emit! (udw/paste-from-clipboard (:id item)))
              (udl/close!))
            (on-close [event]
              (dom/prevent-default event)
              (udl/close!))]
      [:div.lightbox-body.clipboard
       [:div.clipboard-list
        (for [item (mf/react (::clipboard own))]
          [:div.clipboard-item {:key (str (:id item))
                                :on-click (partial on-paste item)}
           [:span.clipboard-icon i/box]
           [:span (str "Copied (" (dt/timeago (:created-at item)) ")")]])]
       [:a.close {:href "#" :on-click on-close} i/close]])))

(defmethod lbx/render-lightbox :clipboard
  [_]
  (clipboard-dialog))
