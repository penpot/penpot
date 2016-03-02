;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.settings
  (:require [sablono.core :as html :refer-macros [html]]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.ui.lightbox :as lightbox]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- settings-dialog-render
  [own]
  (html
   [:div.lightbox-body.settings
    [:h3 "Grid settings"]
    [:form {:on-submit (constantly nil)}
      [:span.lightbox-label "Grid size"]
      [:div.project-size
       [:input#grid-x.input-text
        {:placeholder "X px"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         }]
       [:input#grid-y.input-text
        {:placeholder "Y px"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         }]]
      [:span.lightbox-label "Grid color"]
       [:span "COLOR PICKER HERE!"]
      [:span.lightbox-label "Grid magnet option"]
      [:div.input-checkbox.check-primary
       [:input {:type "checkbox" :id "magnet" :value "Yes"}]
       [:label {:for "magnet"} "Activate magnet"]]
      [:input.btn-primary {:type "submit" :value "Save"}]
        ]
    [:a.close {:href "#"
              :on-click #(do (dom/prevent-default %)
                             (lightbox/close!))} i/close]]))

(def settings-dialog
  (mx/component
   {:render settings-dialog-render
    :name "settings-dialog"
    :mixins []}))

(defmethod lightbox/render-lightbox :settings
  [_]
  (settings-dialog))
