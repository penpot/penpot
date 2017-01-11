;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.base
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.lenses :as ul]
            [uxbox.main.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]
            [goog.events :as events])
  (:import goog.events.EventType))

;; FIXME: split this namespace in two:
;; uxbox.main.ui.streams and uxbox.main.ui.workspace.refs

;; --- Helpers

(defn resolve-project
  "Retrieve the current project."
  [state]
  (let [id (l/focus ul/selected-project state)]
    (get-in state [:projects id])))

(defn resolve-page
  [state]
  (let [id (l/focus ul/selected-page state)]
    (get-in state [:pages id])))

;; --- Refs

(def workspace-ref (l/derive ul/workspace st/state))

(def project-ref
  "Ref to the current selected project."
  (-> (l/lens resolve-project)
      (l/derive st/state)))

(def page-ref
  "Ref to the current selected page."
  (-> (l/lens resolve-page)
      (l/derive st/state)))

(def page-id-ref
  "Ref to the current selected page id."
  (-> (l/key :id)
      (l/derive page-ref)))

(def page-id-ref-s (rx/from-atom page-id-ref))

(def selected-shapes-ref
  (-> (l/key :selected)
      (l/derive workspace-ref)))

(def toolboxes-ref
  (-> (l/key :toolboxes)
      (l/derive workspace-ref)))

(def flags-ref
  (-> (l/key :flags)
      (l/derive workspace-ref)))

(def shapes-by-id-ref
  (-> (l/key :shapes)
      (l/derive st/state)))

(def zoom-ref
  (-> (l/key :zoom)
      (l/derive workspace-ref)))

(def zoom-ref-s (rx/from-atom zoom-ref))

(def alignment-ref
  (-> (l/lens uds/alignment-activated?)
      (l/derive flags-ref)))

;; --- Scroll Stream

(defonce scroll-b (rx/subject))

(defonce scroll-s
  (as-> scroll-b $
    (rx/sample 10 $)
    (rx/merge $ (rx/of (gpt/point)))
    (rx/dedupe $)))

(defonce scroll-a
  (rx/to-atom scroll-s))

;; --- Events

(defonce events-b (rx/subject))
(defonce events-s (rx/dedupe events-b))

;; --- Mouse Position Stream

(defonce mouse-b (rx/subject))
(defonce mouse-s (rx/dedupe mouse-b))

(defonce mouse-canvas-s
  (->> mouse-s
       (rx/map :canvas-coords)
       (rx/share)))

(defonce mouse-canvas-a
  (rx/to-atom mouse-canvas-s))

(defonce mouse-viewport-s
  (->> mouse-s
       (rx/map :viewport-coords)
       (rx/share)))

(defonce mouse-viewport-a
  (rx/to-atom mouse-viewport-s))

(defonce mouse-absolute-s
  (->> mouse-s
       (rx/map :window-coords)
       (rx/share)))

(defonce mouse-absolute-a
  (rx/to-atom mouse-absolute-s))

(defonce mouse-ctrl-s
  (->> mouse-s
       (rx/map :ctrl)
       (rx/share)))

(defn- coords-delta
  [[old new]]
  (gpt/subtract new old))

(defonce mouse-delta-s
  (->> mouse-viewport-s
       (rx/sample 10)
       (rx/map #(gpt/divide % @zoom-ref))
       (rx/mapcat (fn [point]
                    (if @alignment-ref
                      (uds/align-point point)
                      (rx/of point))))
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))
