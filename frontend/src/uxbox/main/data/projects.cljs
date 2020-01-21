;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

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

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::user ::us/uuid)
(s/def ::type keyword?)
(s/def ::file-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/number)
(s/def ::ordering ::us/number)
(s/def ::metadata (s/nilable ::cp/metadata))
(s/def ::data ::cp/data)

(s/def ::project
  (s/keys ::req-un [::id
                    ::name
                    ::version
                    ::user-id
                    ::created-at
                    ::modified-at]))

(s/def ::file
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::project-id]))

(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::file-id
                   ::version
                   ::created-at
                   ::modified-at
                   ::user-id
                   ::ordering
                   ::data]))

;; --- Helpers

(defn unpack-page
  [state {:keys [id data metadata] :as page}]
  (-> state
      (update :pages assoc id (dissoc page :data))
      (update :pages-data assoc id data)))

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (if-let [file-id (get-in state [:pages id :file-id])]
    (-> state
        (update-in [:files file-id :pages] #(filterv (partial not= id) %))
        (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
        (update :pages dissoc id)
        (update :pages-data dissoc id))
    state))

;; --- Initialize Dashboard

(declare fetch-projects)

(declare fetch-files)
(declare initialized)

(defn initialize
  [id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-projects assoc :id id))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (fetch-files id))
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
  (us/assert (s/every ::project) projects)
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
        (->> (rp/query :project-files params)
             (rx/map files-fetched))))))

;; --- Fetch File (by ID)

(defn fetch-file
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :project-file {:id id})
           (rx/map #(files-fetched [%]))))))

;; --- Files Fetched

(defn files-fetched
  [files]
  (us/assert (s/every ::file) files)
  (ptk/reify ::files-fetched
    cljs.core/IDeref
    (-deref [_] files)

    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-file #(assoc-in %1 [:files (:id %2)] %2)]
        (reduce assoc-file state files)))))

;; --- Create Project

(declare project-created)

(def create-project
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New Project " (gensym "p"))]
        (->> (rp/mutation! :create-project {:name name})
             (rx/map (fn [data]
                       (projects-fetched [data]))))))))

;; --- Create File

(defn create-file
  [{:keys [project-id] :as params}]
  (ptk/reify ::create-file
    ptk/WatchEvent
    (watch [this state stream]
      (let [name (str "New File " (gensym "p"))
            params {:name name :project-id project-id}]
        (->> (rp/mutation! :create-project-file params)
             (rx/mapcat
              (fn [data]
                (rx/of (files-fetched [data])
                       #(update-in % [:dashboard-projects :files project-id] conj (:id data))))))))))

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
  (us/assert ::us/uuid id)
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
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (update state :files dissoc id))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :delete-project-file {:id id})
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
        (->> (rp/mutation :rename-project-file params)
             (rx/ignore))))))

;; --- Go To Project

(defn go-to
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::go-to
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:files file-id :pages])]
        (let [path-params {:file-id file-id}
              query-params {:page-id (first page-ids)}]
          (rx/of (rt/nav :workspace path-params query-params)))))))

(defn go-to-project
  [id]
  (us/assert (s/nilable ::us/uuid) id)
  (ptk/reify ::go-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (if (nil? id)
        (rx/of (rt/nav :dashboard-projects {} {}))
        (rx/of (rt/nav :dashboard-projects {} {:project-id (str id)}))))))


;; --- Fetch Pages (by File ID)

(declare pages-fetched)

(defn fetch-pages
  [file-id]
  (us/assert ::us/uuid file-id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :project-pages {:file-id file-id})
           (rx/map pages-fetched)))))

;; --- Pages Fetched

(defn pages-fetched
  [pages]
  (us/assert (s/every ::page) pages)
  (ptk/reify ::pages-fetched
    IDeref
    (-deref [_] pages)

    ptk/UpdateEvent
    (update [_ state]
      (reduce unpack-page state pages))))

;; --- Fetch Page (By ID)

(declare page-fetched)

(defn fetch-page
  "Fetch page by id."
  [id]
  (us/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :project-page {:id id})
           (rx/map page-fetched)))))

;; --- Page Fetched

(defn page-fetched
  [data]
  (us/assert ::page data)
  (ptk/reify ::page-fetched
    IDeref
    (-deref [_] data)

    ptk/UpdateEvent
    (update [_ state]
      (unpack-page state data))))

;; --- Create Page

(declare page-created)

(def create-empty-page
  (ptk/reify ::create-empty-page
    ptk/WatchEvent
    (watch [this state stream]
      (let [file-id (get-in state [:workspace-page :file-id])
            name (str "Page " (gensym "p"))
            ordering (count (get-in state [:files file-id :pages]))
            params {:name name
                    :file-id file-id
                    :ordering ordering
                    :data cp/default-page-data}]
        (->> (rp/mutation :create-project-page params)
             (rx/map page-created))))))

;; --- Page Created

(defn page-created
  [{:keys [id file-id] :as page}]
  (us/assert ::page page)
  (ptk/reify ::page-created
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (let [data (:data page)
            page (dissoc page :data)]
        (-> state
            (update-in [:workspace-file :pages] (fnil conj []) id)
            (update :pages assoc id page)
            (update :pages-data assoc id data))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (fetch-file file-id)))))

;; --- Rename Page

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/assert ::us/uuid id)
  (us/assert string? name)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-page :id])
            state (assoc-in state [:pages id :name] name)]
        (cond-> state
          (= pid id) (assoc-in [:workspace-page :name] name))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-project-page params)
             (rx/map #(ptk/data-event ::page-renamed params)))))))

;; --- Delete Page (by ID)

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (purge-page state id))

    ptk/WatchEvent
    (watch [_ state s]
      (let [page (:workspace-page state)]
        (rx/merge
         (->> (rp/mutation :delete-project-page  {:id id})
              (rx/flat-map (fn [_]
                             (if (= id (:id page))
                               (rx/of (go-to (:file-id page)))
                               (rx/empty))))))))))

;; --- Persist Page

(declare page-persisted)

(def persist-current-page
  (ptk/reify ::persist-page
    ptk/WatchEvent
    (watch [this state s]
      (let [local (:workspace-local state)
            page (:workspace-page state)
            data (:workspace-data state)]
        (if (:history local)
          (rx/empty)
          (let [page (assoc page :data data)]
            (->> (rp/mutation :update-project-page-data page)
                 (rx/map (fn [res] (merge page res)))
                 (rx/map page-persisted)
                 (rx/catch (fn [err] (rx/of ::page-persist-error))))))))))

;; --- Page Persisted

(defn page-persisted
  [{:keys [id] :as page}]
  (us/assert ::page page)
  (ptk/reify ::page-persisted
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (let [data (:data page)
            page (dissoc page :data)]
        (-> state
            (assoc :workspace-data data)
            (assoc :workspace-page page)
            (update :pages assoc id page)
            (update :pages-data assoc id data))))))
