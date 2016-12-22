;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.projects
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.pages :as udp]
            [potok.core :as ptk]
            [uxbox.util.router :as rt]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.forms :as sc]
            [uxbox.main.data.pages :as udp]))

;; --- Helpers

(defn assoc-project-page
  "Assoc to the state the project's embedded page."
  [state project]
  (let [page {:id (:page-id project)
              :name (:page-name project)
              :version (:page-version project)
              :project (:id project)
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
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:dashboard :section] :dashboard/projects))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (fetch-projects))))

(defn initialize
  []
  (Initialize.))

;; --- Projects Fetched

(defrecord ProjectsFetched [projects]
  ptk/UpdateEvent
  (update [_ state]
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
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/projects)
         (rx/map :payload)
         (rx/map projects-fetched))))

(defn fetch-projects
  []
  (FetchProjects.))

;; --- Project Persisted

(defrecord ProjectPersisted [data]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-project state data)))

(defn project-persisted
  [data]
  {:pre [(map? data)]}
  (ProjectPersisted. data))

;; --- Persist Project

(defrecord PersistProject [id]
  ptk/WatchEvent
  (watch [_ state stream]
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
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:projects id :name] name))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-project id))))

(defn rename-project
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameProject. id name))

;; --- Delete Project (by id)

(defrecord DeleteProject [id]
  ptk/WatchEvent
  (watch [_ state s]
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
  ptk/WatchEvent
  (watch [this state s]
    (letfn [(on-finish [{project :payload}]
              (rx/of (fetch-projects)))
            (on-success [{project :payload}]
              (->> (rp/req :create/page
                    {:name name
                     :project (:id project)
                     :data {}
                     :metadata {:width width
                                :height height
                                :layout layout
                                :order 0}})
                   (rx/mapcat on-finish)))]
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

(defrecord GoTo [project-id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:projects project-id :page-id])
          params {:project project-id
                  :page page-id}]
      (rx/of (udp/fetch-pages project-id)
             (rt/navigate :workspace/page params)))))

(defrecord GoToPage [project-id page-id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params {:project project-id
                  :page page-id}]
      (rx/of (rt/navigate :workspace/page params)))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([project-id]
   {:pre [(uuid? project-id)]}
   (GoTo. project-id))
  ([project-id page-id]
   {:pre [(uuid? project-id)
          (uuid? page-id)]}
   (GoToPage. project-id page-id)))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :projects] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))
