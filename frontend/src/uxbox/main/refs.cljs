;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.refs
  "A collection of derived refs."
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [uxbox.main.store :as st]))

;; TODO: move inside workspaces because this is workspace only refs

;; --- Helpers

(defn resolve-project
  "Retrieve the current project."
  [state]
  (let [project-id (get-in state [:workspace :project])]
    (get-in state [:projects project-id])))

(defn resolve-page
  [state]
  (let [page-id (get-in state [:workspace :page])]
    (get-in state [:pages page-id])))

(defn- resolve-project-pages
  [state]
  (let [project (get-in state [:workspace :project])
        get-order #(get-in % [:metadata :order])]
    (->> (vals (:pages state))
         (filter #(= project (:project %)))
         (sort-by get-order))))

(def ^:deprecated selected-page
  "Ref to the current selected page."
  (-> (l/lens resolve-page)
      (l/derive st/state)))

;; --- NOT DEPRECATED

(def workspace
  (letfn [(selector [state]
            (let [id (get-in state [:workspace :current])]
              (get-in state [:workspace id])))]
    (-> (l/lens selector)
        (l/derive st/state))))

(def selected-shapes
  (-> (l/key :selected)
      (l/derive workspace)))

(def toolboxes
  (-> (l/key :toolboxes)
      (l/derive workspace)))

(def flags
  (-> (l/key :flags)
      (l/derive workspace)))

(def selected-zoom
  (-> (l/key :zoom)
      (l/derive workspace)))

(def selected-tooltip
  (-> (l/key :tooltip)
      (l/derive workspace)))

(def selected-drawing-shape
  (-> (l/key :drawing)
      (l/derive workspace)))

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

;; ...

(def mouse-position
  (-> (l/in [:workspace :pointer])
      (l/derive st/state)))

(def canvas-mouse-position
  (-> (l/key :canvas)
      (l/derive mouse-position)))

(def viewport-mouse-position
  (-> (l/key :viewport)
      (l/derive mouse-position)))

(def window-mouse-position
  (-> (l/key :window)
      (l/derive mouse-position)))

(def workspace-scroll
  (-> (l/in [:workspace :scroll])
      (l/derive st/state)))

(def shapes-by-id
  (-> (l/key :shapes)
      (l/derive st/state)))




