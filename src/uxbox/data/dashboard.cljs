;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.dashboard
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.util.time :as time]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +color-replace-schema+
  {:id [v/required sc/uuid]
   :from [sc/color]
   :to [v/required sc/color]})

(def ^:static +remove-color-schema+
  {:id [v/required sc/uuid]
   :color [v/required sc/color]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-page
  "A reduce function for assoc the page
  to the state map."
  [state page]
  (let [uuid (:id page)]
    (update-in state [:pages-by-id] assoc uuid page)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn merge-if-not-exists
  [map & maps]
  (let [result (transient map)]
    (loop [maps maps]
      (if-let [nextval (first maps)]
        (do
          (run! (fn [[key value]]
                  (when-not (contains? result key)
                    (assoc! result key value)))
                nextval)
          (recur (rest maps)))
        (persistent! result)))))

(defn initialize
  [section]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (as-> state $
        (assoc-in $ [:dashboard :section] section)
        (update $ :dashboard merge-if-not-exists
                {:collection-type :builtin
                 :collection-id 1})))))

(defn set-project-ordering
  [order]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :project-order] order))))

(defn set-project-filtering
  [term]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :project-filter] term))))

(defn clear-project-filtering
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :project-filter] ""))))

(defn set-collection-type
  [type]
  {:pre [(contains? #{:builtin :own} type)]}
  (letfn [(select-first [state]
            (if (= type :builtin)
              (assoc-in state [:dashboard :collection-id] 1)
              (let [colls (sort-by :id (vals (:colors-by-id state)))]
                (assoc-in state [:dashboard :collection-id] (:id (first colls))))))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (as-> state $
          (assoc-in $ [:dashboard :collection-type] type)
          (select-first $))))))

(defn set-collection
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :collection-id] id))))

(defn mk-color-collection
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [id (random-uuid)
            coll {:name "Unnamed collection"
                  :id id :colors #{}}]
        (-> state
            (assoc-in [:colors-by-id id] coll)
            (assoc-in [:dashboard :collection-id] id)
            (assoc-in [:dashboard :collection-type] :own))))))

(defn rename-color-collection
  [id name]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:colors-by-id id :name] name))))

(defn delete-color-collection
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [state (update state :colors-by-id dissoc id)
            colls (sort-by :id (vals (:colors-by-id state)))]
        (assoc-in state [:dashboard :collection-id] (:id (first colls)))))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [id from to] :as params}]
  (sc/validate! +color-replace-schema+ params)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if-let [colors (get-in state [:colors-by-id id :colors])]
        (as-> colors $
          (disj $ from)
          (conj $ to)
          (assoc-in state [:colors-by-id id :colors] $))
        state))))

(defn remove-color
  "Remove color in a collection."
  [{:keys [id color] :as params}]
  (sc/validate! +remove-color-schema+ params)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if-let [colors (get-in state [:colors-by-id id :colors])]
        (as-> colors $
          (disj $ color)
          (assoc-in state [:colors-by-id id :colors] $))
        state))))

