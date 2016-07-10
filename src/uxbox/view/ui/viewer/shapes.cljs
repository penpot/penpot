;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.shapes
  (:require [sablono.core :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.main.state :as st]
            [uxbox.main.ui.shapes :as uus]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.shapes.rect :refer (rect-shape)]
            [uxbox.main.ui.shapes.icon :refer (icon-shape)]
            [uxbox.main.ui.shapes.text :refer (text-shape)]
            [uxbox.main.ui.shapes.group :refer (group-shape)]
            [uxbox.main.ui.shapes.line :refer (line-shape)]
            [uxbox.main.ui.shapes.circle :refer (circle-shape)]))

;; --- Interactions Wrapper

(mx/defc interactions-wrapper
  [shape factory]
  (let [interactions (:interactions shape)]
    [:g (factory shape)]))

;; --- Shapes

(mx/defc shape*
  [{:keys [type] :as shape}]
  (case type
    :group (group-shape shape #(interactions-wrapper % shape*))
    :text (text-shape shape)
    :line (line-shape shape)
    :icon (icon-shape shape)
    :rect (rect-shape shape)
    :circle (circle-shape shape)))

(mx/defc shape
  [sid]
  (let [item (get-in @st/state [:shapes-by-id sid])]
    (interactions-wrapper item shape*)))

