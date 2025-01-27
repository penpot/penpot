;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.versions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.data.event :as ev]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.thumbnails :as th]
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
  []
  (ptk/reify ::init-version-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-versions default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-versions)))))

(defn update-version-state
  [version-state]
  (ptk/reify ::update-version-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-versions merge version-state))))

(defn fetch-versions
  []
  (ptk/reify ::fetch-versions
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-file-snapshots {:file-id file-id})
             (rx/map #(update-version-state {:status :loaded :data %})))))))

(defn create-version
  []
  (ptk/reify ::create-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [label   (dt/format (dt/now) :date-full)
            file-id (:current-file-id state)]

        ;; Force persist before creating snapshot, otherwise we could loss changes
        (rx/concat
         (rx/of ::dwp/force-persist
                (ptk/event ::ev/event {::ev/name "create-version"}))

         (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
              (rx/filter #(or (nil? %) (= :saved %)))
              (rx/take 1)
              (rx/mapcat #(rp/cmd! :create-file-snapshot {:file-id file-id :label label}))
              (rx/mapcat
               (fn [{:keys [id]}]
                 (rx/of (update-version-state {:editing id})
                        (fetch-versions))))))))))

(defn rename-version
  [id label]
  (assert (uuid? id) "expected valid uuid for `id`")
  (assert (sm/valid-text? label) "expected not empty string for `label`")

  (ptk/reify ::rename-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (rx/merge
         (rx/of (update-version-state {:editing false})
                (ptk/event ::ev/event {::ev/name "rename-version"
                                       :file-id file-id}))
         (->> (rp/cmd! :update-file-snapshot {:id id :label label})
              (rx/map fetch-versions)))))))

(defn restore-version
  [id origin]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::restore-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (rx/concat
         (rx/of ::dwp/force-persist
                (dw/remove-layout-flag :document-history))
         (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
              (rx/filter #(or (nil? %) (= :saved %)))
              (rx/take 1)
              (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
              (rx/tap #(th/clear-queue!))
              (rx/map #(dw/initialize-workspace file-id)))
         (case origin
           :version
           (rx/of (ptk/event ::ev/event {::ev/name "restore-pin-version"}))

           :snapshot
           (rx/of (ptk/event ::ev/event {::ev/name "restore-autosave"}))

           :plugin
           (rx/of (ptk/event ::ev/event {::ev/name "restore-version-plugin"}))

           (rx/empty)))))))

(defn delete-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")

  (ptk/reify ::delete-version
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-file-snapshot {:id id})
           (rx/map fetch-versions)))))

(defn pin-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::pin-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [version (->> (dm/get-in state [:workspace-versions :data])
                         (d/seek #(= (:id %) id)))
            params  {:id id
                     :label (dt/format (:created-at version) :date-full)}]

        (->> (rp/cmd! :update-file-snapshot params)
             (rx/mapcat (fn [_]
                          (rx/of (update-version-state {:editing id})
                                 (fetch-versions)
                                 (ptk/event ::ev/event {::ev/name "pin-version"})))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PLUGINS SPECIFIC EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wait-persisted-status
  []
  (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
       (rx/filter #(or (nil? %) (= :saved %)))
       (rx/take 1)))

(defn create-version-from-plugins
  [file-id label resolve reject]

  (assert (uuid? file-id) "expected valid uuid for `file-id`")
  (assert (sm/valid-text? label) "expected not empty string for `label`")

  (ptk/reify ::create-version-from-plugins
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-file-id (:current-file-id state)]
        ;; Force persist before creating snapshot, otherwise we could loss changes
        (->> (rx/concat
              (rx/of (ptk/event ::ev/event {::ev/origin "plugins"
                                            ::ev/name "create-version"}))

              (when (= file-id current-file-id)
                (rx/of ::dwp/force-persist))

              (->> (if (= file-id current-file-id)
                     (wait-persisted-status)
                     (rx/of :nothing))
                   (rx/mapcat
                    (fn [_]
                      (rp/cmd! :create-file-snapshot {:file-id file-id :label label})))
                   (rx/mapcat
                    (fn [{:keys [id]}]
                      (->> (rp/cmd! :get-file-snapshots {:file-id file-id})
                           (rx/take 1)
                           (rx/map (fn [versions] (d/seek #(= id (:id %)) versions))))))
                   (rx/tap resolve)
                   (rx/ignore)))

             ;; On error reject the promise and empty the stream
             (rx/catch (fn [error]
                         (reject error)
                         (rx/empty))))))))

(defn restore-version-from-plugin
  [file-id id resolve _reject]
  (assert (uuid? id) "expected valid uuid for `id`")

  (ptk/reify ::restore-version-from-plugins
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (ptk/event ::ev/event {::ev/name "restore-version-plugin"})
              ::dwp/force-persist)

       ;; FIXME: we should abstract this
       (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
            (rx/filter #(or (nil? %) (= :saved %)))
            (rx/take 1)
            (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
            (rx/map #(dw/initialize-workspace file-id)))

       (->> (rx/of 1)
            (rx/tap resolve)
            (rx/ignore))))))




