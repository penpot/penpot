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
   [uxbox.common.uuid :as uuid]))

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

(declare search-files)

(defn initialize-search
  [team-id search-term]
  (ptk/reify ::initialize-search
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local assoc
              :search-result nil))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:dashboard-local state)]
        (when-not (empty? search-term)
          (rx/of (search-files team-id search-term)))))))


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


(defn initialize-recent
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
         (rx/of (fetch-projects (:team-id local))
                (fetch-files (:project-id local)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Fetch Projects

(declare projects-fetched)

(defn fetch-projects
  [team-id project-id]
  (us/assert ::us/uuid team-id)
  (us/assert (s/nilable ::us/uuid) project-id)
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
      (assoc state :projects (d/index-by :id projects)))))

;; --- Search Files

(declare files-searched)

(defn search-files
  [team-id search-term]
  (us/assert ::us/uuid team-id)
  (us/assert ::us/string search-term)
  (ptk/reify ::search-files
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :search-files {:team-id team-id :search-term search-term})
           (rx/map files-searched)))))

(defn files-searched
  [files]
  (us/verify (s/every ::file) files)
  (ptk/reify ::files-searched
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local assoc
              :search-result files))))

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
      (let [flatten-files #(reduce (fn [acc [project-id files]]
                                     (merge acc (d/index-by :id files)))
                                   {}
                                   %1)
            extract-ids #(reduce (fn [acc [project-id files]]
                                   (assoc acc project-id (map :id files)))
                                 {}
                                 %1)]
        (assoc state
               :files (flatten-files recent-files)
               :recent-file-ids (extract-ids recent-files))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Create Project

(declare project-created)

(def create-project
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New Project " (gensym "p"))
            team-id (get-in state [:dashboard-local :team-id])]
        (->> (rp/mutation! :create-project {:name name :team-id team-id})
             (rx/map project-created))))))

(defn project-created
  [data]
  (us/verify ::project data)
  (ptk/reify ::project-created
    ptk/UpdateEvent
    (update [this state]
      (update state :projects assoc (:id data) data))))

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
      (let [project-id (get-in state [:files id :project-id])
            recent-project-files (get-in state [:recent-file-ids project-id] [])]
        (-> state
          (update :files dissoc id)
          (assoc-in [:recent-file-ids project-id] (remove #(= % id) recent-project-files)))))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :delete-file {:id id})
           (rx/ignore)))))

;; --- Rename File

(defn rename-file
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:files id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:dashboard-local state)
            params {:id id :name name}]
        (->> (rp/mutation :rename-file params)
             (rx/ignore))))))


;; --- Create File

(declare file-created)

(defn create-file
  [project-id]
  (ptk/reify ::create-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [name (str "New File " (gensym "p"))
            params {:name name :project-id project-id}]
        (->> (rp/mutation! :create-file params)
             (rx/map file-created))))))

(defn file-created
  [data]
  (us/verify ::file data)
  (ptk/reify ::file-created
    ptk/UpdateEvent
    (update [this state]
      (let [project-id (:project-id data)
            file-id (:id data)
            recent-project-files (get-in state [:recent-file-ids project-id] [])]
        (-> state
          (assoc-in [:files file-id] data)
          (assoc-in [:recent-file-ids project-id] (conj recent-project-files file-id)))))))


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

