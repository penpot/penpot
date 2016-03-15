;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.projects
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.repo :as rp]
            [uxbox.util.datetime :as dt]
            [uxbox.state.project :as stpr]
            [uxbox.util.data :refer (without-keys)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME use only one ns for validators

(def ^:static +project-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(def ^:static +create-page-schema+
  {:name [sc/required sc/string]
   :layout [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :project [sc/required sc/uuid]})

(def ^:static +update-page-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sort-projects-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created projs))
    projs))

(defn contains-term?
  [phrase term]
  (str/contains? (str/lower phrase) (str/trim (str/lower term))))

(defn filter-projects-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-projects
  []
  (letfn [(transform [state projects]
            (as-> state $
              (reduce stpr/assoc-project $ projects)
              (assoc $ ::projects-loaded true)))
          (on-loaded [projects]
            #(transform % projects))]
    (reify
      rs/WatchEvent
      (-apply-watch [_ state]
        (if (::projects-loaded state)
          (rx/empty)
          (-> (rp/do :fetch/projects)
              (p/then on-loaded)))))))

(defn load-pages
  []
  (letfn [(transform [state pages]
            (as-> state $
              (reduce stpr/assoc-page $ pages)
              (assoc $ ::pages-loaded true)))
          (on-loaded [pages]
            #(transform % pages))]
    (reify
      rs/WatchEvent
      (-apply-watch [_ state]
        (if (::pages-loaded state)
          (rx/empty)
          (-> (rp/do :fetch/pages)
              (p/then on-loaded)))))))

(defn create-page
  [{:keys [name width height project layout] :as data}]
  (sc/validate! +create-page-schema+ data)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page {:id (random-uuid)
                  :project project
                  :created (dt/now)
                  :layout layout
                  :shapes []
                  :name name
                  :width width
                  :height height}]
        (stpr/assoc-page state page)))))

(defn update-page
  [{:keys [id name width height layout] :as data}]
  (sc/validate! +create-page-schema+ data)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [page (merge (get-in state [:pages-by-id id])
                        (when width {:width width})
                        (when height {:height height})
                        (when name {:name name}))]
        (assoc-in state [:pages-by-id id] page)))))

(defn delete-page
  [pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shapeids (get-in state [:pages-by-id pageid :shapes])]
        (as-> state $
          (update $ :shapes-by-id without-keys shapeids)
          (update $ :pages-by-id dissoc pageid))))))

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (let [uuid (random-uuid)]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [proj {:id uuid
                    :name name
                    :created (dt/now)}]
          (stpr/assoc-project state proj)))

      rs/EffectEvent
      (-apply-effect [_ state]
        (rs/emit! (create-page {:name "Page 1"
                                :layout layout
                                :width width
                                :height height
                                :project uuid}))))))
(defn delete-project
  [proj]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (stpr/dissoc-project state proj))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid]
   (reify
     rs/WatchEvent
     (-apply-watch [_ state]
       (println "go-to" projectid)
       (let [pages (stpr/project-pages state projectid)
             pageid (:id (first pages))
             params {:project-uuid projectid
                     :page-uuid pageid}]
         (rx/of (r/navigate :workspace/page params))))))

  ([projectid pageid]
   (reify
     rs/WatchEvent
     (-apply-watch [_ state]
       (let [params {:project-uuid projectid
                     :page-uuid pageid}]
         (rx/of (r/navigate :workspace/page params)))))))
