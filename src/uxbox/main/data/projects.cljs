;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.projects
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.schema :as sc]
            [uxbox.main.data.pages :as udp]))

;; --- Initialize

(declare fetch-projects)
(declare projects-fetched?)

(defrecord Initialize []
  rs/EffectEvent
  (-apply-effect [_ state]
    (when-not (seq (:projects-by-id state))
      (reset! st/loader true)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [projects (seq (:projects-by-id state))]
      (if projects
        (rx/empty)
        (rx/merge
         (rx/of (fetch-projects))
         (->> (rx/filter projects-fetched? s)
              (rx/take 1)
              (rx/do #(reset! st/loader false))
              (rx/ignore)))))))

(defn initialize
  []
  (Initialize.))

;; --- Projects Fetched

(declare assoc-project)

(defrecord ProjectsFetched [projects]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce assoc-project state projects)))

(defn projects-fetched
  [projects]
  (ProjectsFetched. projects))

(defn projects-fetched?
  [v]
  (instance? ProjectsFetched v))

;; --- Fetch Projects

(defrecord FetchProjects []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/projects)
         (rx/map :payload)
         (rx/map projects-fetched))))

(defn fetch-projects
  []
  (FetchProjects.))

;; --- Project Created

(defrecord ProjectCreated [project]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-project state project)))

(defn project-created
  [data]
  (ProjectCreated. data))

;; --- Create Project

(defrecord CreateProject [name width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{project :payload}]
              (rx/of
               (project-created project)
               (udp/create-page {:width width
                                 :height height
                                 :layout layout
                                 :project (:id project)
                                 :name "Page 1"
                                 :data nil})))]
      (->> (rp/req :create/project {:name name})
           (rx/mapcat on-success)))))

(def ^:private create-project-schema
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn create-project
  [params]
  (-> (sc/validate! params create-project-schema)
      (map->CreateProject)))

;; --- Delete Project (by id)

(declare dissoc-project)

(defrecord DeleteProject [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              #(dissoc-project % id))]
      (->> (rp/req :delete/project id)
           (rx/map on-success)))))

(defn delete-project
  [id]
  (if (map? id)
    (DeleteProject. (:id id))
    (DeleteProject. id)))

;; --- Go To & Go To Page

(defrecord GoTo [projectid]
  rs/EffectEvent
  (-apply-effect [_ state]
    (reset! st/loader true))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(navigate [pages]
              (let [pageid (:id (first pages))
                    params {:project-uuid projectid
                            :page-uuid pageid}]
                (r/navigate :workspace/page params)))]
      (rx/merge
       (rx/of #(assoc % :loader true)
              (udp/fetch-pages projectid))
       (->> (rx/filter udp/pages-fetched? s)
            (rx/take 1)
            (rx/map :pages)
            (rx/do #(reset! st/loader false))
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

;; --- UI related events

(defn set-project-ordering
  [order]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :project-order] order))))

(defn set-project-filtering
  [term]
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (assoc-in state [:dashboard :project-filter] term))))

(defn clear-project-filtering
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :project-filter] ""))))

;; --- Helpers

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

(defn assoc-project
  "A reduce function for assoc the project
  to the state map."
  [state proj]
  (let [id (:id proj)]
    (update-in state [:projects-by-id id] merge proj)))

(defn dissoc-project
  "A reduce function for dissoc the project
  from the state map."
  [state id]
  (update-in state [:projects-by-id] dissoc id))

