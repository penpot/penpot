;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.scroll
  "Workspace scroll events handling."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.refs :as refs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]))

(defn set-scroll-position
  [dom position]
  (set! (.-scrollLeft dom) (:x position))
  (set! (.-scrollTop dom) (:y position)))

(defn set-scroll-center
  [dom center]
  (let [viewport-width (.-offsetWidth dom)
        viewport-height (.-offsetHeight dom)
        position-x (- (* (:x center) @refs/selected-zoom) (/ viewport-width 2))
        position-y (- (* (:y center) @refs/selected-zoom) (/ viewport-height 2))
        position (gpt/point position-x position-y)]
    (set-scroll-position dom position)))

(defn scroll-to-page-center
  [dom page]
  (let [page-width (get-in page [:metadata :width])
        page-height (get-in page [:metadata :height])
        center (gpt/point (+ 1200 (/ page-width 2)) (+ 1200 (/ page-height 2)))]
    (set-scroll-center dom center)))

(defn get-current-center
  [dom]
  (let [viewport-width (.-offsetWidth dom)
        viewport-height (.-offsetHeight dom)
        scroll-left (.-scrollLeft dom)
        scroll-top (.-scrollTop dom)]
    (gpt/point
     (+ (/ viewport-width 2) scroll-left)
     (+ (/ viewport-height 2) scroll-top))))

(defn get-current-center-absolute
  [dom]
  (gpt/divide (get-current-center dom) @refs/selected-zoom))

(defn get-current-position
  [dom]
  (let [scroll-left (.-scrollLeft dom)
        scroll-top (.-scrollTop dom)]
    (gpt/point scroll-left scroll-top)))

(defn get-current-position-absolute
  [dom]
    (gpt/divide (get-current-position dom) @refs/selected-zoom))

(defn scroll-to-point
  [dom point position]
  (let [viewport-offset (gpt/subtract point position)
        new-scroll-position (gpt/subtract (gpt/multiply point @refs/selected-zoom) (gpt/multiply viewport-offset @refs/selected-zoom))]
    (set-scroll-position dom new-scroll-position)))
