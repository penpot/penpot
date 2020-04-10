;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.refs
  "A collection of derived refs."
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [uxbox.main.store :as st]
            [uxbox.main.data.helpers :as helpers]))

(def profile
  (-> (l/key :profile)
      (l/derive st/state)))

(def workspace
  (-> (l/key :workspace-local)
      (l/derive st/state)))

(def workspace-local
  (-> (l/key :workspace-local)
      (l/derive st/state)))

(def workspace-layout
  (-> (l/key :workspace-layout)
      (l/derive st/state)))

(def workspace-page
  (-> (l/key :workspace-page)
      (l/derive st/state)))

(def workspace-file
  (-> (l/key :workspace-file)
      (l/derive st/state)))

(def workspace-project
  (-> (l/key :workspace-project)
      (l/derive st/state)))

(def workspace-images
  (-> (l/key :workspace-images)
      (l/derive st/state)))

(def workspace-users
  (-> (l/key :workspace-users)
      (l/derive st/state)))

(def workspace-data
  (-> (l/lens #(let [page-id (get-in % [:workspace-page :id])]
                 (get-in % [:workspace-data page-id])))
      (l/derive st/state)))

(def objects
  (-> (l/lens #(let [page-id (get-in % [:workspace-page :id])]
                 (get-in % [:workspace-data page-id :objects])))
      (l/derive st/state)))

(defn objects-by-id
  [ids]
  (-> (l/lens (fn [state]
                (let [page-id (get-in state [:workspace-page :id])
                      objects (get-in state [:workspace-data page-id :objects])]
                  (->> (set ids)
                       (map #(get objects %))
                       (filter identity)
                       (vec)))))
      (l/derive st/state)))

(defn is-child-selected?
  [id]
  (letfn [(selector [state]
            (let [page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])
                  selected (get-in state [:workspace-local :selected])
                  shape (get objects id)
                  children (helpers/get-children id objects)]
              (some selected children)))]
    (-> (l/lens selector)
        (l/derive st/state))))

(def selected-shapes
  (-> (l/key :selected)
      (l/derive workspace-local)))

(defn make-selected
  [id]
  (-> (l/lens #(contains? % id))
      (l/derive selected-shapes)))

(def selected-frame
  (-> (l/key :selected-frame)
      (l/derive workspace-local)))

(def toolboxes
  (-> (l/key :toolboxes)
      (l/derive workspace-local)))

;; DEPRECATED
(def selected-zoom
  (-> (l/key :zoom)
      (l/derive workspace-local)))

(def selected-tooltip
  (-> (l/key :tooltip)
      (l/derive workspace-local)))

(def selected-drawing-shape
  (-> (l/key :drawing)
      (l/derive workspace-local)))

(def selected-drawing-tool
  (-> (l/key :drawing-tool)
      (l/derive workspace)))

(def selected-edition
  (-> (l/key :edition)
      (l/derive workspace)))

(def history
  (-> (l/key :history)
      (l/derive workspace)))

(defn selected-modifiers
  [id]
  {:pre [(uuid? id)]}
  (-> (l/in [:modifiers id])
      (l/derive workspace)))

(defn alignment-activated?
  [flags]
  (and (contains? flags :grid-indexed)
       (contains? flags :grid-snap)))

(def selected-alignment
  (-> (comp (l/key :flags)
            (l/lens alignment-activated?))
      (l/derive workspace)))

(def shapes-by-id
  (-> (l/key :shapes)
      (l/derive st/state)))




