;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.canvas
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes :as uus]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]
   [uxbox.util.geom.point :as gpt])
  (:import goog.events.EventType))

;; --- Background

(mf/def background
  :mixins [mf/memo]
  :render
  (fn [own {:keys [background] :as metadata}]
    [:rect
     {:x 0 :y 0
      :width "100%"
      :height "100%"
      :fill (or background "#ffffff")}]))

;; --- Canvas

(mf/defc canvas
  [{:keys [page wst] :as props}]
  (let [{:keys [metadata id]} page
        zoom (:zoom wst 1)  ;; NOTE: maybe forward wst to draw-area
        width (:width metadata)
        height (:height metadata)]
    [:svg.page-canvas {:x c/canvas-start-x
                       :y c/canvas-start-y
                       :width width
                       :height height}
     [:& background metadata]
     [:svg.page-layout
      [:g.main
       (for [id (reverse (:shapes page))]
         [:& uus/shape-component {:id id :key id}])
       [:& selection-handlers {:wst wst}]
       (when-let [dshape (:drawing wst)]
         [:& draw-area {:shape dshape
                        :zoom (:zoom wst)
                        :modifiers (:modifiers wst)}])]]]))
