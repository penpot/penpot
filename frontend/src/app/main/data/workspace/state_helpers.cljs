;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.state-helpers
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]))

(defn lookup-page-objects
  ([state]
   (lookup-page-objects state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :objects])))

(defn lookup-page-options
  ([state]
   (lookup-page-options state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :options])))

(defn lookup-component-objects
  ([state component-id]
   (get-in state [:workspace-data :components component-id :objects])))

(defn lookup-local-components
  ([state]
   (get-in state [:workspace-data :components])))

(defn lookup-selected
  ([state]
   (lookup-selected state nil))

  ([state {:keys [omit-blocked?]
           :or   {omit-blocked? false}}]
   (let [objects (lookup-page-objects state)
         selected (->> (get-in state [:workspace-local :selected])
                       (cp/clean-loops objects))
         selectable? (fn [id]
                       (and (contains? objects id)
                            (or (not omit-blocked?)
                                (not (get-in objects [id :blocked] false)))))]
     (into (d/ordered-set)
           (filter selectable?)
           selected))))
