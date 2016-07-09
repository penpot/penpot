;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.canvas
  (:require [sablono.core :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.icons :as i]))

(defn canvas-render
  [own]
  (html
   [:div.view-canvas "VIEW CONTENT"]))

(def canvas
  (mx/component
   {:render canvas-render
    :name "canvas"
    :mixins [mx/static]}))
