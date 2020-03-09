;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

;; NOTE: this namespace is deprecated and will be removed when new
;; dashboard is implemented. Is just maintained as a temporal solution
;; for have the old dashboard code "working".


(ns uxbox.main.data.projects
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; WARN: this file is deprecated.

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id (s/nilable ::us/uuid))
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)

(s/def ::project
  (s/keys ::req-un [::id
                    ::name
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


;; --- Initialize Dashboard

(declare fetch-projects)
(declare fetch-files)
(declare fetch-draft-files)
(declare initialized)

;; NOTE/WARN: this need to be refactored completly when new UI is
;; prototyped.

(defn initialize
  [id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-projects assoc :id id))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (if (nil? id)
         (rx/of fetch-draft-files)
         (rx/of (fetch-files id)))
       (->> stream
            (rx/filter (ptk/type? ::files-fetched))
            (rx/take 1)
            (rx/map #(initialized id (deref %))))))))

(defn initialized
  [id files]
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (let [files (into #{} (map :id) files)]
        (update-in state [:dashboard-projects :files] assoc id files)))))

;; --- Update Opts (Filtering & Ordering)

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (ptk/reify ::update-opts
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-projects merge
              (when order {:order order})
              (when filter {:filter filter})))))

;; --- Fetch Projects

(declare projects-fetched)

(def fetch-projects
  (ptk/reify ::fetch-projects
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :projects)
           (rx/map projects-fetched)))))

;; --- Projects Fetched

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
      (let [params (if (nil? project-id) {} {:project-id project-id})]
        (->> (rp/query :files params)
             (rx/map files-fetched))))))

(def fetch-draft-files
  (ptk/reify ::fetch-draft-files
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :draft-files {})
           (rx/map files-fetched)))))

;; --- Fetch File (by ID)

(defn fetch-file
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :file {:id id})
           (rx/map #(files-fetched [%]))))))

;; --- Create File

(defn create-file
  [{:keys [project-id] :as params}]
  (ptk/reify ::create-file
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New File " (gensym "p"))
            params {:name name :project-id project-id}]
        (->> (rp/mutation! :create-file params)
             (rx/mapcat
              (fn [data]
                (rx/of (files-fetched [data])
                       #(update-in % [:dashboard-projects :files project-id] conj (:id data))))))))))

(declare file-created)

(def create-draft-file
  (ptk/reify ::create-draft-file
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New File " (gensym "p"))
            params {:name name}]
        (->> (rp/mutation! :create-draft-file params)
             (rx/map file-created))))))

(defn file-created
  [data]
  (us/verify ::file data)
  (ptk/reify ::create-draft-file
    ptk/UpdateEvent
    (update [this state]
      (update state :files assoc (:id data) data))))

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

;; --- Go To Project

(defn go-to
  [file-id]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::go-to
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:files file-id :pages])]
        (let [path-params {:file-id file-id}
              query-params {:page-id (first page-ids)}]
          (rx/of (rt/nav :workspace path-params query-params)))))))

(defn go-to-project
  [id]
  (us/verify (s/nilable ::us/uuid) id)
  (ptk/reify ::go-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (if (nil? id)
        (rx/of (rt/nav :dashboard-projects {} {}))
        (rx/of (rt/nav :dashboard-projects {} {:project-id (str id)}))))))
