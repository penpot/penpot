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
  (:require
   [okulary.core :as l]
   [beicon.core :as rx]
   [uxbox.common.pages :as cp]
   [uxbox.main.constants :as c]
   [uxbox.main.store :as st]
   [uxbox.common.uuid :as uuid]))

;; ---- Global refs

(def route
  (l/derived :route st/state))

(def router
  (l/derived :router st/state))

(def message
  (l/derived :message st/state))

(def profile
  (l/derived :profile st/state))

;; ---- Workspace refs

(def workspace-local
  (l/derived :workspace-local st/state))

(def workspace-layout
  (l/derived :workspace-layout st/state))

(def workspace-page
  (l/derived :workspace-page st/state))

(def workspace-page-id
  (l/derived :id workspace-page))

(def workspace-file
  (l/derived :workspace-file st/state))

(def workspace-project
  (l/derived :workspace-project st/state))

(def workspace-images
  (l/derived :workspace-images st/state))

(def workspace-users
  (l/derived :workspace-users st/state))

(def workspace-presence
  (l/derived :workspace-presence st/state))

(def workspace-snap-data
  (l/derived :workspace-snap-data st/state))

(def workspace-data
  (-> #(let [page-id (get-in % [:workspace-page :id])]
         (get-in % [:workspace-data page-id]))
      (l/derived st/state)))

(def workspace-page-options
  (l/derived :options workspace-data))

(def workspace-saved-grids
  (l/derived :saved-grids workspace-page-options))

(def workspace-objects
  (l/derived :objects workspace-data))

(def workspace-frames
  (l/derived cp/select-frames workspace-objects))

(defn object-by-id
  [id]
  (letfn [(selector [state]
            (let [page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])]
              (get objects id)))]
    (l/derived selector st/state =)))

(defn objects-by-id
  [ids]
  (letfn [(selector [state]
            (let [page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])]
              (->> (set ids)
                   (map #(get objects %))
                   (filter identity)
                   (vec))))]
    (l/derived selector st/state =)))

(defn is-child-selected?
  [id]
  (letfn [(selector [state]
            (let [page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])
                  selected (get-in state [:workspace-local :selected])
                  shape (get objects id)
                  children (cp/get-children id objects)]
              (some selected children)))]
    (l/derived selector st/state)))

(def selected-shapes
  (l/derived :selected workspace-local))

(def selected-shapes-with-children
  (letfn [(selector [state]
            (let [selected (get-in state [:workspace-local :selected])
                  page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])
                  children (mapcat #(cp/get-children % objects) selected)]
              (into selected children)))]
    (l/derived selector st/state)))

(defn make-selected
  [id]
  (l/derived #(contains? % id) selected-shapes))

(def selected-zoom
  (l/derived :zoom workspace-local))

(def selected-drawing-tool
  (l/derived :drawing-tool workspace-local))

(def current-drawing-shape
  (l/derived :drawing workspace-local))

(def selected-edition
  (l/derived :edition workspace-local))

(def current-transform
  (l/derived :transform workspace-local))

(def options-mode
  (l/derived :options-mode workspace-local))

;; ---- Viewer refs

(def viewer-data
  (l/derived :viewer-data st/state))

(def viewer-local
  (l/derived :viewer-local st/state))
