;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.colorpicker
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.ui.colorpicker :as cp]))

;; --- Color Picker Modal

(mf/defc colorpicker-modal
  [{:keys [x y default value opacity page on-change disable-opacity] :as props}]
  [:div.colorpicker-tooltip
   {:style {:left (str (- x 270) "px")
            :top (str (- y 50) "px")}}
   [:& cp/colorpicker {:value (or value default)
                       :opacity (or opacity 1)
                       :colors (into-array @cp/most-used-colors)
                       :on-change on-change
                       :disable-opacity disable-opacity}]])


