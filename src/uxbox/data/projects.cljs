;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.projects
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.repo :as rp]
            [uxbox.locales :refer (tr)]
            [uxbox.schema :as sc]
            [uxbox.state.project :as stpr]
            [uxbox.data.pages :as udp]
            [uxbox.ui.messages :as uum]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sort-projects-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created-at projs))
    projs))

(defn contains-term?
  [phrase term]
  (str/contains? (str/lower phrase) (str/trim (str/lower term))))

(defn filter-projects-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Projects Fetched

(defrecord ProjectsFetched [projects]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stpr/assoc-project state projects)))

(defn projects-fetched
  [projects]
  (ProjectsFetched. projects))

;; --- Fetch Projects

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

;; --- Create Project

(defrecord CreateProject [name width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [project]
              (rx/of (rs/swap #(stpr/assoc-project % project))
                     (udp/create-page (assoc (into {} this)
                                             :project (:id project)
                                             :name "Page 1"
                                             :data nil))))
            (on-failure [err]
              (uum/error (tr "errors.create-project")))]
      (->> (rp/do :create/project {:name name})
           (rx/mapcat on-success)
           (rx/catch on-failure)))))

(def ^:static +project-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (map->CreateProject data))

;; --- Delete Project (by id)

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

;; --- Go To & Go To Page

(defrecord GoTo [projectid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(navigate [pages]
              (let [pageid (:id (first pages))
                    params {:project-uuid projectid
                           :page-uuid pageid}]
                (r/navigate :workspace/page params)))]
      (rx/merge
       (rx/of (udp/fetch-pages projectid))
       (->> (rx/filter udp/pages-fetched? s)
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
