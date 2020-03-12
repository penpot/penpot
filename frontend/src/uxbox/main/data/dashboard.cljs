;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.dashboard
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)

(s/def ::team
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at]))

(s/def ::project
  (s/keys ::req-un [::id
                    ::name
                    ::team-id
                    ::version
                    ::profile-id
                    ::created-at
                    ::modified-at]))

(s/def ::file
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::project-id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare fetch-files)
(declare fetch-projects)
(declare fetch-recent-files)

(def initialize-drafts
  (ptk/reify ::initialize-drafts
    ptk/UpdateEvent
    (update [_ state]
      (let [profile (:profile state)]
        (update state :dashboard-local assoc
                :team-id (:default-team-id profile)
                :project-id (:default-project-id profile))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:dashboard-local state)]
        (rx/of (fetch-files (:project-id local))
               (fetch-projects (:team-id local)))))))


(defn initialize-team
  [team-id]
  (us/verify ::us/uuid team-id)
  (ptk/reify ::initialize-team
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local assoc
              :project-id nil
              :team-id team-id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:dashboard-local state)]
        (rx/of (fetch-projects (:team-id local))
               (fetch-recent-files (:team-id local)))))))


(defn initialize-project
  [team-id project-id]
  (us/verify ::us/uuid team-id)
  (us/verify ::us/uuid project-id)
  (ptk/reify ::initialize-project
     ptk/UpdateEvent
     (update [_ state]
       (update state :dashboard-local assoc
               :team-id team-id
               :project-id project-id))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [local (:dashboard-local state)]
         (rx/of (fetch-files (:project-id local))
                (fetch-projects (:team-id local)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Fetch Projects

(declare projects-fetched)

(defn fetch-projects
  [team-id]
  (us/assert ::us/uuid team-id)
  (ptk/reify ::fetch-projects
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :projects-by-team {:team-id team-id})
           (rx/map projects-fetched)))))

(defn projects-fetched
  [projects]
  (us/verify (s/every ::project) projects)
  (ptk/reify ::projects-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-project #(update-in %1 [:projects (:id %2)] merge %2)]
        (reduce assoc-project state projects)))))

;; --- Fetch Files

(declare files-fetched)

(defn fetch-files
  [project-id]
  (ptk/reify ::fetch-files
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:project-id project-id}]
        (->> (rp/query :files params)
             (rx/map files-fetched))))))

(defn files-fetched
  [files]
  (us/verify (s/every ::file) files)
  (ptk/reify ::files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [state (dissoc state :files)
            files (d/index-by :id files)]
        (assoc state :files files)))))

;; --- Fetch recent files

(declare recent-files-fetched)

(defn fetch-recent-files
  [team-id]
  (ptk/reify ::fetch-recent-files
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:team-id team-id}]
        (->> (rp/query :recent-files params)
             (rx/map recent-files-fetched))))))

(defn recent-files-fetched
  [recent-files]
  (ptk/reify ::recent-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :recent-files recent-files))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Create Project

(def create-project
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New Project " (gensym "p"))
            team-id (get-in state [:dashboard-local :team-id])]
        (->> (rp/mutation! :create-project {:name name :team-id team-id})
             (rx/map (fn [data]
                       (projects-fetched [data]))))))))

;; --- Rename Project

(defn rename-project
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-project
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:projects id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-project params)
             (rx/ignore))))))

;; --- Delete Project (by id)

(defn delete-project
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::delete-project
    ptk/UpdateEvent
    (update [_ state]
      (update state :projects dissoc id))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :delete-project {:id id})
           (rx/ignore)))))

;; --- Delete File (by id)

(defn delete-file
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (update state :files dissoc id))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :delete-file {:id id})
           (rx/ignore)))))

;; --- Rename Project

(defn rename-file
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:files id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-file params)
             (rx/ignore))))))


;; --- Create File

(declare file-created)

(defn create-file
  [project-id]
  (ptk/reify ::create-draft-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [name (str "New File " (gensym "p"))
            params {:name name :project-id project-id}]
        (->> (rp/mutation! :create-file params)
             (rx/map file-created))))))

(defn file-created
  [data]
  (us/verify ::file data)
  (ptk/reify ::create-draft-file
    ptk/UpdateEvent
    (update [this state]
      (-> state
          (update :files assoc (:id data) data)
          (update-in [:recent-files (:project-id data)] conj data)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI State Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Update Opts (Filtering & Ordering)

;; (defn update-opts
;;   [& {:keys [order filter] :as opts}]
;;   (ptk/reify ::update-opts
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :dashboard-local merge
;;               (when order {:order order})
;;               (when filter {:filter filter})))))

