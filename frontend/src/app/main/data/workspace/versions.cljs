;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.versions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defonce default-state
  {:status :loading
   :data nil
   :editing nil})

(declare fetch-versions)

(defn init-version-state
  [file-id]
  (ptk/reify ::init-version-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-versions default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-versions file-id)))))

(defn update-version-state
  [version-state]
  (ptk/reify ::update-version-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-versions merge version-state))))

(defn fetch-versions
  [file-id]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::fetch-versions
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-snapshots {:file-id file-id})
           (rx/map #(update-version-state {:status :loaded :data %}))))))

(defn create-version
  [file-id]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::create-version
    ptk/WatchEvent
    (watch [_ _ _]
      (let [label (dt/format (dt/now) :date-full)]
        ;; Force persist before creating snapshot, otherwise we could loss changes
        (rx/concat
         (rx/of ::dwp/force-persist)
         (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
              (rx/filter #(or (nil? %) (= :saved %)))
              (rx/take 1)
              (rx/mapcat #(rp/cmd! :take-file-snapshot {:file-id file-id :label label}))
              (rx/mapcat
               (fn [{:keys [id]}]
                 (rx/of
                  (update-version-state {:editing id})
                  (fetch-versions file-id))))))))))

(defn rename-version
  [file-id id label]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))
  (dm/assert! (and (string? label) (d/not-empty? label)))

  (ptk/reify ::rename-version
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       (rx/of (update-version-state {:editing false}))
       (->> (rp/cmd! :update-file-snapshot {:id id :label label})
            (rx/map #(fetch-versions file-id)))))))

(defn restore-version
  [project-id file-id id]
  (dm/assert! (uuid? project-id))
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))

  (ptk/reify ::restore-version
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of ::dwp/force-persist)
       (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
            (rx/filter #(or (nil? %) (= :saved %)))
            (rx/take 1)
            (rx/mapcat #(rp/cmd! :take-file-snapshot {:file-id file-id :created-by "system" :label (dt/format (dt/now) :date-full)}))
            (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
            (rx/map #(dw/initialize-file project-id file-id)))))))

(defn delete-version
  [file-id id]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))

  (ptk/reify ::delete-version
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :remove-file-snapshot {:id id})
           (rx/map #(fetch-versions file-id))))))

(defn pin-version
  [file-id id]
  (dm/assert! (uuid? file-id))
  (dm/assert! (uuid? id))

  (ptk/reify ::pin-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [version (->> (dm/get-in state [:workspace-versions :data])
                         (d/seek #(= (:id %) id)))]
        (->> (rp/cmd! :update-file-snapshot
                      {:id id
                       :created-by "user"
                       :label (dt/format (:created-at version) :date-full)})
             (rx/mapcat #(rx/of (update-version-state {:editing id})
                                (fetch-versions file-id))))))))
