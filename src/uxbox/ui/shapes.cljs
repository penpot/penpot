;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.shapes
  (:require [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.state :as st]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.shapes.group :as group]))

(def render-component group/render-component)

(defn- focus-shape
  [id]
  (-> (l/in [:shapes-by-id id])
      (l/focus-atom st/state)))

(defn- shape-render
  [own id]
  (let [shape (rum/react (focus-shape id))]
    (when-not (:hidden shape)
      (render-component shape))))

(def shape
  (mx/component
   {:render shape-render
    :name "shape"
    :mixins [mx/static rum/reactive]}))
