;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.scroll
  "Workspace scroll events handling."
  (:require [beicon.core :as rx]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.geom.point :as gpt]))

(defn watch-scroll-interactions
  [own]
  (letfn [(is-space-up? [[type {:keys [key]}]]
            (and (= 32 key) (= :key/up type)))

          (on-start []
            (let [stoper (->> wb/events-s
                              (rx/filter is-space-up?)
                              (rx/take 1))
                  local (:rum/local own)
                  initial @wb/mouse-viewport-a
                  stream (rx/take-until stoper wb/mouse-viewport-s)]
              (swap! local assoc :scrolling true)
              (rx/subscribe stream #(on-scroll % initial) nil on-scroll-end)))

          (on-scroll-end []
            (rlocks/release! :workspace/scroll)
            (let [local (:rum/local own)]
              (swap! local assoc :scrolling false)))

          (on-scroll [pt initial]
            (let [{:keys [x y]} (gpt/subtract pt initial)
                  el (mx/ref-node own "workspace-canvas")
                  cx (.-scrollLeft el)
                  cy (.-scrollTop el)]
              (set! (.-scrollLeft el) (- cx x))
              (set! (.-scrollTop el) (- cy y))))]

    (let [stream (->> (rx/map first rlocks/stream)
                      (rx/filter #(= % :workspace/scroll)))]
      (rx/subscribe stream on-start))))

(defn set-scroll-position
  [dom position]
  (set! (.-scrollLeft dom) (:x position))
  (set! (.-scrollTop dom) (:y position)))

(defn set-scroll-center
  [dom center]
  (let [viewport-width (.-offsetWidth dom)
        viewport-height (.-offsetHeight dom)
        position-x (- (* (:x center) @wb/zoom-ref) (/ viewport-width 2))
        position-y (- (* (:y center) @wb/zoom-ref) (/ viewport-height 2))
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
  (gpt/divide (get-current-center dom) @wb/zoom-ref))

(defn get-current-position
  [dom]
  (let [scroll-left (.-scrollLeft dom)
        scroll-top (.-scrollTop dom)]
    (gpt/point scroll-left scroll-top)))

(defn get-current-position-absolute
  [dom]
    (gpt/divide (get-current-position dom) @wb/zoom-ref))

(defn scroll-to-point
  [dom point position]
  (let [viewport-offset (gpt/subtract point position)
        new-scroll-position (gpt/subtract (gpt/multiply point @wb/zoom-ref) (gpt/multiply viewport-offset @wb/zoom-ref))]
    (set-scroll-position dom new-scroll-position)))
