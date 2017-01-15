;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.refs
  "A collection of derived refs."
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.main.store :as st]
            [uxbox.main.lenses :as ul]))

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

(def workspace
  (l/derive ul/workspace st/state))

(def selected-project
  "Ref to the current selected project."
  (-> (l/lens resolve-project)
      (l/derive st/state)))

(def selected-page
  "Ref to the current selected page."
  (-> (l/lens resolve-page)
      (l/derive st/state)))

(def selected-page-id
  "Ref to the current selected page id."
  (-> (l/key :id)
      (l/derive selected-page)))

(def selected-shapes
  (-> (l/key :selected)
      (l/derive workspace)))

(def toolboxes
  (-> (l/key :toolboxes)
      (l/derive workspace)))

(def flags
  (-> (l/key :flags)
      (l/derive workspace)))

(def shapes-by-id
  (-> (l/key :shapes)
      (l/derive st/state)))

(def selected-zoom
  (-> (l/key :zoom)
      (l/derive workspace)))

(defn alignment-activated?
  [state]
  (let [flags (l/focus ul/workspace-flags state)]
    (and (contains? flags :grid-indexed)
         (contains? flags :grid-alignment)
         (contains? flags :grid))))

(def selected-alignment
  (-> (l/lens alignment-activated?)
      (l/derive flags)))

(def canvas-mouse-position
  (-> (l/in [:pointer :canvas])
      (l/derive workspace)))

(def viewport-mouse-position
  (-> (l/in [:pointer :viewport])
      (l/derive workspace)))

(def window-mouse-position
  (-> (l/in [:pointer :window])
      (l/derive workspace)))





