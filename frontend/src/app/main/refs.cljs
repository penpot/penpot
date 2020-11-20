;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.refs
  "A collection of derived refs."
  (:require
   [beicon.core :as rx]
   [okulary.core :as l]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.constants :as c]
   [app.main.store :as st]))

;; ---- Global refs

(def route
  (l/derived :route st/state))

(def router
  (l/derived :router st/state))

(def message
  (l/derived :message st/state))

(def profile
  (l/derived :profile st/state))

;; ---- Dashboard refs

(def dashboard-local
  (l/derived :dashboard-local st/state))

;; ---- Workspace refs

(def workspace-local
  (l/derived :workspace-local st/state))

(def workspace-drawing
  (l/derived :workspace-drawing st/state))

(def selected-shapes
  (l/derived :selected workspace-local))

(defn make-selected-ref
  [id]
  (l/derived #(contains? % id) selected-shapes))

(def selected-zoom
  (l/derived :zoom workspace-local))

(def selected-drawing-tool
  (l/derived :tool workspace-drawing))

(def current-drawing-shape
  (l/derived :object workspace-drawing))

(def selected-edition
  (l/derived :edition workspace-local))

(def current-transform
  (l/derived :transform workspace-local))

(def options-mode
  (l/derived :options-mode workspace-local))

(def vbox
  (l/derived :vbox workspace-local))

(def current-hover
  (l/derived :hover workspace-local))

(def workspace-layout
  (l/derived :workspace-layout st/state))

(def workspace-file
  (l/derived (fn [state]
               (when-let [file (:workspace-file state)]
                 (-> file
                     (dissoc :data)
                     (assoc :pages (get-in file [:data :pages])))))
             st/state =))

(def workspace-file-colors
  (l/derived (fn [state]
               (when-let [file (:workspace-file state)]
                 (get-in file [:data :colors])))
             st/state))

(def workspace-recent-colors
  (l/derived (fn [state]
               (get-in state [:workspace-data :recent-colors] []))
             st/state))

(def workspace-file-typography
  (l/derived (fn [state]
               (when-let [file (:workspace-file state)]
                 (get-in file [:data :typographies])))
             st/state))

(def workspace-project
  (l/derived :workspace-project st/state))

(def workspace-shared-files
  (l/derived :workspace-shared-files st/state))

(def workspace-local-library
  (l/derived (fn [state]
               (select-keys (get state :workspace-data)
                            [:colors
                             :media
                             :typographies
                             :components]))
             st/state))

(def workspace-libraries
  (l/derived :workspace-libraries st/state))

(def workspace-users
  (l/derived :workspace-users st/state))

(def workspace-presence
  (l/derived :workspace-presence st/state))

(def workspace-snap-data
  (l/derived :workspace-snap-data st/state))

(def workspace-page
  (l/derived (fn [state]
               (let [page-id (:current-page-id state)
                     data    (:workspace-data state)]
                 (get-in data [:pages-index page-id])))
             st/state))

(def workspace-page-objects
  (l/derived :objects workspace-page))

(def workspace-page-options
  (l/derived :options workspace-page))

(def workspace-frames
  (l/derived cph/select-frames workspace-page-objects))

(defn object-by-id
  [id]
  (l/derived #(get % id) workspace-page-objects))

(defn objects-by-id
  [ids]
  (l/derived (fn [objects]
               (into [] (comp (map #(get objects %))
                              (filter identity))
                     (set ids)))
             workspace-page-objects =))

(defn is-child-selected?
  [id]
  (letfn [(selector [state]
            (let [page-id  (:current-page-id state)
                  objects  (get-in state [:workspace-data :pages-index page-id :objects])
                  selected (get-in state [:workspace-local :selected])
                  children (cph/get-children id objects)]
              (some selected children)))]
    (l/derived selector st/state)))


;; TODO: can be replaced by objects-by-id
(def selected-objects
  (letfn [(selector [state]
            (let [selected (get-in state [:workspace-local :selected])
                  page-id  (:current-page-id state)
                  objects  (get-in state [:workspace-data :pages-index page-id :objects])]
              (mapv #(get objects %) selected)))]
    (l/derived selector st/state =)))

(def selected-shapes-with-children
  (letfn [(selector [state]
            (let [selected (get-in state [:workspace-local :selected])
                  page-id  (:current-page-id state)
                  objects  (get-in state [:workspace-data :pages-index page-id :objects])
                  children (mapcat #(cph/get-children % objects) selected)]
              (into selected children)))]
    (l/derived selector st/state =)))


;; TODO: looks very inneficient access method, revisit the usage of this ref
(def selected-objects-with-children
  (letfn [(selector [state]
            (let [selected (get-in state [:workspace-local :selected])
                  page-id  (:current-page-id state)
                  objects  (get-in state [:workspace-data :pages-index page-id :objects])
                  children (mapcat #(cph/get-children % objects) selected)
                  shapes   (into selected children)]
              (mapv #(get objects %) shapes)))]
    (l/derived selector st/state =)))

;; ---- Viewer refs

(def viewer-data
  (l/derived :viewer-data st/state))

(def viewer-local
  (l/derived :viewer-local st/state))

(def comment-threads
  (l/derived :comment-threads st/state))

(def comments-local
  (l/derived :comments-local st/state))

