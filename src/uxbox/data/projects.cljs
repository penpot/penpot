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
            [uxbox.repo :as rp]
            [uxbox.locales :refer (tr)]
            [uxbox.schema :as sc]
            [uxbox.state :as st]
            [uxbox.state.project :as stpr]
            [uxbox.ui.messages :as uum]
            [uxbox.util.datetime :as dt]
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
   :data [sc/required]
   :project [sc/required sc/uuid]})

(def ^:static +update-page-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :data [sc/required]
   :layout [sc/required sc/string]})

(def ^:static +update-page-metadata-schema+
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

(defrecord LoadProjects [projects]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stpr/assoc-project state projects)))

(defn load-projects
  [projects]
  (LoadProjects. projects))

(defrecord FetchProjects []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-loaded [{projects :payload}]
              #(reduce stpr/assoc-project % projects))
            (on-error [err]
              (println err)
              (rx/empty))]
      (->> (rp/do :fetch/projects)
           (rx/map on-loaded)
           (rx/catch on-error)))))

(defn fetch-projects
  []
  (FetchProjects.))

(defrecord LoadPages [pages]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stpr/assoc-page state pages)))

(defn load-pages
  [pages]
  (LoadPages. pages))

(defrecord FetchPages [projectid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-loaded [{pages :payload}]
              (load-pages pages))
            (on-error [err]
              (js/console.error err)
              (rx/empty))]
      (->> (rp/do :fetch/pages-by-project {:project projectid})
           (rx/map on-loaded)
           (rx/catch on-error)))))

(defn fetch-pages
  [projectid]
  (FetchPages. projectid))

(defrecord CreatePage [name width height project layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-created [{page :payload}]
              #(stpr/assoc-page % page))
            (on-failed [page]
              (uum/error (tr "errors.auth"))
              (rx/empty))]
      (let [params (-> (into {} this)
                       (assoc :data {}))]
        (->> (rp/do :create/page params)
             (rx/map on-created)
             (rx/catch on-failed))))))

(defn create-page
  [data]
  (sc/validate! +create-page-schema+ data)
  (map->CreatePage data))

(defrecord UpdatePage [id name width height layout]
  rs/UpdateEvent
  (-apply-update [_ state]
    (letfn [(updater [page]
              (merge page
                     (when width {:width width})
                     (when height {:height height})
                     (when name {:name name})))]
      (update-in state [:pages-by-id id] updater)))

  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{page :payload}]
              #(assoc-in % [:pages-by-id id :version] (:version page)))
            (on-failure [e]
              (uum/error (tr "errors.page-update"))
              (rx/empty))]
      (->> (rp/do :update/page (into {} this))
           (rx/map on-success)
           (rx/catch on-failure)))))

(defn update-page
  [data]
  (sc/validate! +update-page-schema+ data)
  (map->UpdatePage data))

(defrecord DeletePage [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stpr/dissoc-page % id)))
            (on-failure [e]
              (println "ERROR" e)
              (uum/error (tr "errors.delete-page"))
              (rx/empty))]
      (->> (rp/do :delete/page id)
           (rx/map on-success)
           (rx/catch on-failure)))))

(defn delete-page
  [id]
  (DeletePage. id))

(defrecord CreateProject [name width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [project]
              (rx/of (rs/swap #(stpr/assoc-project % project))
                     (create-page (assoc (into {} this)
                                         :project (:id project)
                                         :name "Page 1"
                                         :data []))))
            (on-failure [err]
              (uum/error (tr "errors.create-project")))]
      (->> (rp/do :create/project {:name name})
           (rx/mapcat on-success)
           (rx/catch on-failure)))))

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (map->CreateProject data))

(defrecord DeleteProject [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stpr/dissoc-project % id)))
            (on-failure [e]
              (uum/error (tr "errors.delete-project")))]
      (->> (rp/do :delete/project id)
           (rx/map on-success)
           (rx/catch on-failure)))))

(defn delete-project
  [id]
  (if (map? id)
    (DeleteProject. (:id id))
    (DeleteProject. id)))

(defrecord GoTo [projectid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(navigate [pages]
              (let [pageid (:id (first pages))
                    params {:project-uuid projectid
                           :page-uuid pageid}]
                (r/navigate :workspace/page params)))]
      (rx/merge
       (rx/of (fetch-pages projectid))
       (->> (rx/filter #(instance? LoadPages %) s)
            (rx/take 1)
            (rx/map :pages)
            (rx/map navigate))))))

(defrecord GoToPage [projectid pageid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [params {:project-uuid projectid
                  :page-uuid pageid}]
      (rx/of (r/navigate :workspace/page params)))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid] (GoTo. projectid))
  ([projectid pageid] (GoToPage. projectid pageid)))
