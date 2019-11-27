;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.projects
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.main.repo.core :as rp]
   [uxbox.main.data.pages :as udp]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.spec :as us]
   [uxbox.util.time :as dt]
   [uxbox.util.router :as rt]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::version integer?)
(s/def ::user-id uuid?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)

(s/def ::project-entity
  (s/keys ::req-un [::id
                    ::name
                    ::version
                    ::user-id
                    ::created-at
                    ::modified-at]))

;; --- Helpers

(defn assoc-project
  "A reduce function for assoc the project to the state map."
  [state {:keys [id] :as project}]
  (s/assert ::project-entity project)
  (update-in state [:projects id] merge project))

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

(defn projects-fetched
  [projects]
  (s/assert (s/every ::project-entity) projects)
  (ptk/reify ::projects-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce assoc-project state projects))))

(defn projects-fetched?
  [v]
  (= ::projects-fetched  (ptk/type v)))

;; --- Fetch Projects

(defn fetch-projects
  []
  (ptk/reify ::fetch-projects
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :projects)
           (rx/map projects-fetched)))))

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
      (->> (rp/mutation :update-project project)
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
      (->> (rp/mutation :delete-project {:id id})
           (rx/map on-success)))))

(defn delete-project
  [id]
  (if (map? id)
    (DeleteProject. (:id id))
    (DeleteProject. id)))

;; --- Create Project

(s/def ::create-project-params
  (s/keys :req-un [::name ::width ::height]))

(defn create-project
  [{:keys [name] :as params}]
  (s/assert ::create-project-params params)
  (reify
    ptk/WatchEvent
    (watch [this state stream]
      (->> (rp/req :create/project {:name name})
           (rx/map :payload)
           (rx/mapcat (fn [{:keys [id] :as project}]
                        (rx/of #(assoc-project % project)
                               (udp/form->create-page (assoc params :project id)))))))))

;; --- Go To Project

(defn go-to
  [id]
  (s/assert ::us/uuid id)
  (ptk/reify ::go-to
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects id :pages])]
        (let [params {:project id :page (first page-ids)}]
          (rx/of (rt/nav :workspace/page params)))))))


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
