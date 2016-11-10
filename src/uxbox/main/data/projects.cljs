;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.projects
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.pages :as udp]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.schema :as sc]
            [uxbox.main.data.pages :as udp]))

;; --- Helpers

(defn assoc-project-page
  "Assoc to the state the project's embedded page."
  [state project]
  (let [page {:id (:page-id project)
              :name (:page-name project)
              :version (:page-version project)
              :data (:page-data project)
              :created-at (:page-created-at project)
              :modified-at (:page-modified-at project)
              :metadata (:page-metadata project)}]
    (-> state
        (udp/assoc-page page)
        (udp/assoc-packed-page page))))

(defn assoc-project
  "A reduce function for assoc the project to the state map."
  [state {:keys [id] :as project}]
  (let [project (dissoc project
                        :page-name :page-version
                        :page-data :page-metadata
                        :page-created-at :page-modified-at)]
    (update-in state [:projects id] merge project)))

(defn dissoc-project
  "A reduce function for dissoc the project from the state map."
  [state id]
  (update state :projects dissoc id))

;; --- Initialize

(declare fetch-projects)
(declare projects-fetched?)

(defrecord Initialize []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:dashboard :section] :dashboard/projects))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (fetch-projects))))

(defn initialize
  []
  (Initialize.))

;; --- Projects Fetched

(defrecord ProjectsFetched [projects]
  rs/UpdateEvent
  (-apply-update [_ state]
    (as-> state $
      (reduce assoc-project-page $ projects)
      (reduce assoc-project $ projects))))

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

;; --- Project Persisted

(defrecord ProjectPersisted [data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-project state data)))

(defn project-persisted
  [data]
  {:pre [(map? data)]}
  (ProjectPersisted. data))

;; --- Persist Project

(defrecord PersistProject [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [project (get-in state [:projects id])]
      (->> (rp/req :update/project project)
           (rx/map :payload)
           (rx/map project-persisted)))))

(defn persist-project
  [id]
  {:pre [(uuid? id)]}
  (PersistProject. id))

;; --- Rename Project

(defrecord RenameProject [id name]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:projects id :name] name))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (persist-project id))))

(defn rename-project
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameProject. id name))

;; --- Delete Project (by id)

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

;; --- Create Project

(defrecord CreateProject [name width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{project :payload}]
              (rx/of
               (project-persisted project)
               (udp/create-page {:metadata {:width width
                                            :height height
                                            :layout layout}
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

;; --- Go To & Go To Page

(defrecord GoTo [projectid]
  rs/EffectEvent
  (-apply-effect [_ state]
    (reset! st/loader true))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(navigate [pages]
              (let [pageid (:id (first pages))
                    params {:project (str projectid)
                            :page (str pageid)}]
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
    (let [params {:project (str projectid)
                  :page (str pageid)}]
      (rx/of (r/navigate :workspace/page params)))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid] (GoTo. projectid))
  ([projectid pageid] (GoToPage. projectid pageid)))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :projects] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))
