;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.canvas
  (:require
   [rumext.alpha :as mf]
   [uxbox.view.ui.viewer.shapes :as shapes]))

;; --- Background (Component)

(mf/defc background
  {:wrap [mf/wrap-memo]}
  [{:keys [background] :as metadata}]
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill (or background "#ffffff")}])

;; --- Canvas (Component)

(declare shape)

(mf/defc canvas
  {:wrap [mf/wrap-memo]}
  [{:keys [page] :as props}]
  (let [{:keys [metadata id]} page
        {:keys [width height]} metadata]
    [:div.view-canvas
     [:svg.page-layout {:width width
                        :height height}
      [:& background metadata]
      (for [id (reverse (:shapes page))]
        (-> (shapes/shape id)
            (mf/with-key (str id))))]]))

