;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.project
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(log/set-level! :warn)

(defn- project-fetched
  [{:keys [id] :as project}]
  (ptk/reify ::project-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:projects id] merge project))))

(defn fetch-project
  "Fetch or refresh a single project"
  ([] (fetch-project))
  ([project-id]
   (assert (uuid? project-id) "expected a valid uuid for `project-id`")

   (ptk/reify ::fetch-project
     ptk/WatchEvent
     (watch [_ state _]
       (let [project-id (or project-id (:current-project-id state))]
         (->> (rp/cmd! :get-project {:id project-id})
              (rx/map project-fetched)))))))

(defn initialize-project
  [project-id]
  (ptk/reify ::initialize-project
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-project-id project-id))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-project project-id)))))

(defn finalize-project
  [project-id]
  (ptk/reify ::finalize-project
    ptk/UpdateEvent
    (update [_ state]
      (let [project-id' (get state :current-project-id)]
        (if (= project-id' project-id)
          (dissoc state :current-project-id)
          state)))))

(defn- files-fetched
  [project-id files]
  (ptk/reify ::files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :files merge (d/index-by :id files))
          (d/update-in-when [:projects project-id] (fn [project]
                                                     (assoc project :count (count files))))))))

(defn fetch-files
  ([] (fetch-files nil))
  ([project-id]
   (ptk/reify ::fetch-files
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [project-id (or project-id (:current-project-id state))]
         (->> (rp/cmd! :get-project-files {:project-id project-id})
              (rx/map (partial files-fetched project-id))))))))



