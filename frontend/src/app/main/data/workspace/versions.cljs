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
   [app.common.time :as ct]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.thumbnails :as th]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defonce default-state
  {:status :loading
   :data nil
   :editing nil})

(declare fetch-versions)

(defn init-versions-state
  []
  (ptk/reify ::init-versions-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-versions default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-versions)))))

(defn update-versions-state
  [version-state]
  (ptk/reify ::update-versions-state
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
             (rx/map #(update-versions-state {:status :loaded :data %})))))))

(defn create-version
  []
  (ptk/reify ::create-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [label   (ct/format-inst (ct/now) :localized-date)
            file-id (:current-file-id state)]

        ;; Force persist before creating snapshot, otherwise we could loss changes
        (rx/concat
         (rx/of ::dwp/force-persist
                (ev/event {::ev/name "create-version"}))

         (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
              (rx/filter #(or (nil? %) (= :saved %)))
              (rx/take 1)
              (rx/mapcat #(rp/cmd! :create-file-snapshot {:file-id file-id :label label}))
              (rx/mapcat
               (fn [{:keys [id]}]
                 (rx/of (update-versions-state {:editing id})
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
         (rx/of (update-versions-state {:editing nil})
                (ev/event {::ev/name "rename-version"
                           :file-id file-id}))
         (->> (rp/cmd! :update-file-snapshot {:id id :label label})
              (rx/map fetch-versions)))))))

(defn- initialize-version
  []
  (ptk/reify ::initialize-version
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            file-id (:current-file-id state)
            team-id (:current-team-id state)]

        (rx/merge
         (->> stream
              (rx/filter (ptk/type? ::dw/bundle-fetched))
              (rx/take 1)
              (rx/map #(dwpg/initialize-page file-id page-id)))

         (rx/of (ntf/hide :tag :restore-dialog)
                (dw/initialize-file team-id file-id)))))

    ptk/EffectEvent
    (effect [_ _ _]
      (th/clear-queue!))))

(defn- wait-for-persistence
  [file-id snapshot-id]
  (->> (rx/from-atom refs/persistence-state {:emit-current-value? true})
       (rx/filter #(or (nil? %) (= :saved %)))
       (rx/take 1)
       (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id snapshot-id}))))

(defn restore-version
  [id origin]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::restore-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id    (:current-file-id state)
            team-id    (:current-team-id state)
            event-name (case origin
                         :version "restore-pin-version"
                         :snapshot "restore-autosave"
                         :plugin "restore-version-plugin")]

        (rx/concat
         (rx/of ::dwp/force-persist
                (dw/remove-layout-flag :document-history))

         (->> (wait-for-persistence file-id id)
              (rx/map #(initialize-version)))

         (if event-name
           (rx/of (ev/event {::ev/name event-name
                             :file-id file-id
                             :team-id team-id}))
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
      (when-let [version (->> (dm/get-in state [:workspace-versions :data])
                              (d/seek #(= (:id %) id)))]
        (let [params {:id id
                      :label (ct/format-inst (:created-at version) :localized-date)}]

          (->> (rp/cmd! :update-file-snapshot params)
               (rx/mapcat (fn [_]
                            (rx/of (update-versions-state {:editing id})
                                   (fetch-versions)
                                   (ev/event {::ev/name "pin-version"}))))))))))

(defn lock-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::lock-version
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :lock-file-snapshot {:id id})
           (rx/map fetch-versions)))))

(defn unlock-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::unlock-version
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :unlock-file-snapshot {:id id})
           (rx/map fetch-versions)))))

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
       (rx/of ::dwp/force-persist)
       (->> (wait-for-persistence file-id id)
            (rx/map #(initialize-version)))

       (->> (rx/of 1)
            (rx/tap resolve)
            (rx/ignore))))))




