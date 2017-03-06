;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.projects
  (:require [cljs.spec :as s]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.pages :as udp]
            [uxbox.util.spec :as us]
            [uxbox.util.time :as dt]
            [uxbox.util.router :as rt]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::version integer?)
(s/def ::user uuid?)
(s/def ::created-at dt/inst?)
(s/def ::modified-at dt/inst?)

(s/def ::project-entity
  (s/keys ::req-un [::id
                    ::name
                    ::version
                    ::user
                    ::created-at
                    ::modified-at]))

;; --- Helpers

(defn assoc-project-page
  "Assoc to the state the project's embedded page."
  [state project]
  {:pre [(us/valid? ::project-entity project)]}
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
  {:pre [(us/valid? ::project-entity project)]}
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
  {:pre [(us/valid? (s/every ::project-entity) projects)]}
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
    (let [project-data {:name name}
          page-data {:name "Page 0"
                     :data {}
                     :metadata {:width width
                                :height height
                                :layout layout
                                :order 0}}]
      (->> (rp/req :create/project {:name name})
           (rx/map :payload)
           (rx/mapcat (fn [{:keys [id] :as project}]
                        (rp/req :create/page (assoc page-data :project id))))
           (rx/map #(fetch-projects))))))

(s/def ::create-project-event
  (s/keys :req-un [::name
                   ::udp/width
                   ::udp/height
                   ::udp/layout]))

(defn create-project
  [data]
  {:pre [(us/valid? ::create-project-event data)]}
  (map->CreateProject data))

;; --- Go To & Go To Page

(deftype GoToFirstPage [pages]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [[page & rest] (sort-by #(get-in % [:metadata :order]) pages)
          params {:project (:project page)
                  :page (:id page)}]
      (rx/of (rt/navigate :workspace/page params)))))

(defn go-to-first-page
  [pages]
  (GoToFirstPage. pages))

(defrecord GoTo [project-id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page-id (get-in state [:projects project-id :page-id])
          params {:project project-id
                  :page page-id}]
      (rx/merge
       (rx/of (udp/fetch-pages project-id))
       (->> stream
            (rx/filter udp/pages-fetched?)
            (rx/take 1)
            (rx/map deref)
            (rx/map go-to-first-page))))))

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
