;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.workspace
  (:require [bouncer.validators :as v]
            [beicon.core :as rx]
            [uxbox.shapes :as sh]
            [uxbox.rstore :as rs]
            [uxbox.state.shapes :as stsh]
            [uxbox.schema :as sc]
            [uxbox.data.pages :as udp]
            [uxbox.data.shapes :as uds]
            ;; [uxbox.data.worker :as wrk]
            [uxbox.util.datetime :as dt]
            [uxbox.util.geom.point :as gpt]))

;; --- Workspace Initialization

(defn initialize
  "Initialize the workspace state."
  [project page]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if (:workspace state)
        (update state :workspace merge
                {:project project
                 :page page
                 :selected #{}
                 :drawing nil})
        (assoc state :workspace
               {:project project
                :zoom 1
                :page page
                :flags #{:layers :element-options}
                :selected #{}
                :drawing nil})))))

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [flags (get-in state [:workspace :flags])]
        (if (contains? flags key)
          (assoc-in state [:workspace :flags] (disj flags key))
          (assoc-in state [:workspace :flags] (conj flags key)))))))

(defn select-for-drawing
  "Mark a shape selected for drawing in the canvas."
  [shape]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if shape
        (assoc-in state [:workspace :drawing] shape)
        (update-in state [:workspace] dissoc :drawing)))))

;; --- Copy to Clipboard

(defrecord CopyToClipboard []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [selected (get-in state [:workspace :selected])
          item {:id (random-uuid)
                :created-at (dt/now)
                :items selected}
          clipboard (-> (:clipboard state)
                        (conj item))]
      (assoc state :clipboard
             (if (> (count clipboard) 5)
               (pop clipboard)
               clipboard)))))

(defn copy-to-clipboard
  "Copy selected shapes to clipboard."
  []
  (CopyToClipboard.))

;; --- Paste from Clipboard

(defrecord PasteFromClipboard [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [page (get-in state [:workspace :page])
          selected (if (nil? id)
                     (first (:clipboard state))
                     (->> (:clipboard state)
                          (filter #(= id (:id %)))
                          (first)))]
      (stsh/duplicate-shapes state (:items selected) page))))

(defn paste-from-clipboard
  "Copy selected shapes to clipboard."
  ([] (PasteFromClipboard. nil))
  ([id] (PasteFromClipboard. id)))

;; --- Increase Zoom

(defrecord IncreaseZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [increase #(* % 1.05)]
      (update-in state [:workspace :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

;; --- Decrease Zoom

(defrecord DecreaseZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [decrease #(* % 0.95)]
      (update-in state [:workspace :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

;; --- Reset Zoom

(defrecord ResetZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:workspace :zoom] 1)))

(defn reset-zoom
  []
  (ResetZoom.))

