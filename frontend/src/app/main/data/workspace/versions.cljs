;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.versions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.thumbnails :as th]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn repair-snapshot?
  "True when `snapshot` is the copy the backend keeps of a file before it
  repairs it."
  [{:keys [label]}]
  (and (string? label)
       (str/starts-with? label "internal/repair/")))

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
      (update state :workspace-versions #(merge default-state (select-keys % [:backup :preview :restore]))))

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

         (->> (dwp/wait-persisted-or-error)
              (rx/mapcat #(rp/cmd! :create-file-snapshot {:file-id file-id :label label}))
              (rx/mapcat
               (fn [{:keys [id]}]
                 (rx/of (update-versions-state {:editing id})
                        (fetch-versions))))
              ;; A version of the file without the unsaved changes would
              ;; lose them, so none is made and the user is told why
              (rx/catch (fn [cause]
                          (if (= :persistence (:type (ex-data cause)))
                            (rx/of (ntf/warn (tr "workspace.versions.create-failed-unsaved")))
                            (rx/throw cause))))))))))

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
         (->> (rp/cmd! :update-file-snapshot {:id id :file-id file-id :label label})
              (rx/map fetch-versions)))))))

(defn- get-opening
  [state]
  (select-keys state [:current-file-id :workspace-load-id]))

(defn- get-active-session
  [state kind]
  (let [session (get-in state [:workspace-versions kind])]
    (when (= (get-opening state) (:opening session))
      session)))

(defn get-version-controls
  [state]
  (or (get-active-session state :restore)
      (get-active-session state :preview)))

(defn- active-session?
  [state kind token]
  (and (some? token) (= token (:token (get-active-session state kind)))))

(defn- workspace-replacement
  [stream]
  (rx/filter #(or (ptk/type? ::dw/finalize-workspace %)
                  (ptk/type? ::dw/initialize-workspace %))
             stream))

(defn- get-read-only-before-preview
  [state]
  (cond
    (dm/get-in state [:workspace-global :persistence-lock])
    (dm/get-in state [:workspace-global :persistence-lock :read-only?])

    (dm/get-in state [:render-state :lost])
    (dm/get-in state [:render-state :pre-context-lost-read-only?])

    :else
    (dm/get-in state [:workspace-global :read-only?])))

(defn- clear-preview-state
  []
  (ptk/reify ::clear-preview-state
    ptk/UpdateEvent
    (update [_ state] (dw/clear-version-preview state))))

(defn- initialize-version
  ([] (initialize-version nil))
  ([token]
   (let [current? #(or (nil? token) (active-session? % :restore token))]
     (ptk/reify ::initialize-version
       ptk/UpdateEvent
       (update [_ state]
         (if (and token (current? state))
           (assoc-in state [:workspace-versions :restore :phase] :loading)
           state))

       ptk/WatchEvent
       (watch [_ state stream]
         (when (current? state)
           (let [{:keys [current-file-id current-team-id current-page-id]} state]
             (rx/merge
              (cond->> (->> stream
                            (rx/filter (ptk/type? ::dw/bundle-fetched))
                            (rx/filter #(or (nil? token) (= current-file-id (:file-id @%))))
                            (rx/take 1)
                            ;; Keep historical content read-only until the
                            ;; restored file has loaded, not just until the
                            ;; RPC succeeds.
                            (rx/mapcat #(rx/of (clear-preview-state)
                                               (dwpg/initialize-page current-file-id current-page-id))))
                token (rx/take-until (workspace-replacement stream)))

              (rx/of (dw/initialize-file current-team-id current-file-id))))))

       ptk/EffectEvent
       (effect [_ state _]
         (when (current? state)
           (th/clear-queue!)))))))

(defn delete-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")

  (ptk/reify ::delete-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :delete-file-snapshot {:id id :file-id file-id})
             (rx/map fetch-versions))))))

(defn pin-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::pin-version
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [version (->> (dm/get-in state [:workspace-versions :data])
                              (d/seek #(= (:id %) id)))]
        (let [file-id (:current-file-id state)
              params  {:id      id
                       :file-id file-id
                       :label   (ct/format-inst (:created-at version) :localized-date)}]

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
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :lock-file-snapshot {:id id :file-id file-id})
             (rx/map fetch-versions))))))

(defn unlock-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::unlock-version
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :unlock-file-snapshot {:id id :file-id file-id})
             (rx/map fetch-versions))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RESTORE VERSION EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- exit-preview-cleanup
  "Restore the backed-up live file data and clear the preview flags."
  []
  (ptk/reify ::exit-preview-cleanup
    ptk/UpdateEvent
    (update [_ state]
      (let [backup (dm/get-in state [:workspace-versions :backup])]
        (-> (dw/clear-version-preview state)
            (update :files assoc (:id backup) backup))))))

(defn exit-preview
  "Exit from preview mode and reload the live file data.

  No-op when there is no preview to exit (no backup stored), so it is
  safe to call from the restore dialog dismiss action even when the
  restore was triggered directly without entering preview first."
  ([] (exit-preview nil))
  ([token]
   (ptk/reify ::exit-preview
     ptk/WatchEvent
     (watch [_ state _]
       (let [file-id (:current-file-id state)
             page-id (:current-page-id state)
             backup  (dm/get-in state [:workspace-versions :backup])]
         (when (and backup
                    (= file-id (:id backup))
                    (or (nil? token)
                        (and (active-session? state :preview token)
                             (nil? (get-active-session state :restore)))))
           (rx/concat
            (rx/of (exit-preview-cleanup))
            (when page-id (rx/of (dwpg/initialize-page file-id page-id)))
            (rx/of (dwp/finish-preview-effects file-id)))))))))

(defn- close-restore
  [token phase]
  (ptk/reify ::close-restore
    ptk/WatchEvent
    (watch [_ state _]
      (let [restore (get-active-session state :restore)]
        (when (and (= token (:token restore)) (= phase (:phase restore)))
          (rx/concat
           (rx/of #(update % :workspace-versions dissoc :restore))
           (when-let [preview (get-active-session state :preview)]
             (rx/of (exit-preview (:token preview))))))))))

(defn cancel-restore
  [token]
  (close-restore token :confirm-restore))

(defn- restore-version
  ([id] (restore-version id nil))
  ([id token]
   (assert (uuid? id) "expected valid uuid for `id`")
   (let [accepted (volatile! false)]
     (ptk/reify ::restore-version
       ptk/UpdateEvent
       (update [_ state]
         (let [restore (get-active-session state :restore)]
           (if (and token (= token (:token restore)) (= :confirm-restore (:phase restore)))
             (do
               (vreset! accepted true)
               (assoc-in state [:workspace-versions :restore :phase] :restoring))
             state)))

       ptk/WatchEvent
       (watch [_ state stream]
         (when (or (nil? token) @accepted)
           (let [file-id (:current-file-id state)]
             (rx/concat
              (rx/of ::dwp/force-persist
                     (dw/remove-layout-flag :document-history))

              (cond->> (->> (dwp/wait-persisted-or-error)
                            (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
                            (rx/map #(initialize-version token))
                            (rx/catch (fn [cause]
                                        (rx/concat
                                         (rx/of (if token (close-restore token :restoring) (exit-preview)))
                                         (rx/throw cause)))))
                token (rx/take-until (workspace-replacement stream)))))))))))

(defn confirm-restore
  [token]
  (ptk/reify ::confirm-restore
    ptk/WatchEvent
    (watch [_ state _]
      (let [restore (get-active-session state :restore)]
        (when (and (= token (:token restore)) (= :confirm-restore (:phase restore)))
          (rx/of (restore-version (:snapshot-id restore) token)))))))

(defn enter-restore
  ([id] (enter-restore id nil))
  ([id preview-token]
   (assert (uuid? id) "expected valid uuid for `id`")
   (ptk/reify ::enter-restore
     ptk/UpdateEvent
     (update [_ state]
       (if (and (:current-file-id state)
                (nil? (get-active-session state :restore))
                (or (nil? preview-token) (active-session? state :preview preview-token)))
         (assoc-in state [:workspace-versions :restore]
                   {:opening (get-opening state)
                    :phase :confirm-restore
                    :snapshot-id id
                    :token (uuid/next)})
         state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PREVIEW VERSION EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- apply-snapshot
  "Swap the file data in app state with the provided snapshot-file
  response. Used by the version preview feature to show historical
  file content without modifying the database"
  [token {:keys [id] :as snapshot}]
  (let [current? (fn [state]
                   (and (= id (:current-file-id state))
                        (active-session? state :preview token)
                        (not= :loading (:phase (get-active-session state :restore)))))]
    (ptk/reify ::apply-snapshot-data
      ptk/UpdateEvent
      (update [_ state]
        (if (current? state)
          (update state :files assoc id snapshot)
          state))

      ptk/WatchEvent
      (watch [_ state _]
        (when (current? state)
          (when-let [page-id (:current-page-id state)]
            (rx/of (dwpg/initialize-page id page-id))))))))

(defn enter-preview
  "Load a snapshot into the workspace for read-only preview without
  modifying any database state. Sets a read-only flag so no changes
  are persisted while previewing and enter on the preview mode"
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")

  (let [token (uuid/next)]
    (ptk/reify ::enter-preview
      ptk/UpdateEvent
      (update [_ state]
        (if-let [file (and (nil? (get-active-session state :restore))
                           (dsh/lookup-file state))]
          (let [versions (:workspace-versions state)
                current  (get-active-session state :preview)
                backup   (if current (:backup versions) file)
                read-only (if current (:prior-read-only current) (get-read-only-before-preview state))
                snapshot (d/seek #(= id (:id %)) (:data versions))]
            (-> state
                (update :workspace-versions assoc :backup backup
                        :preview {:opening (get-opening state)
                                  :phase :preview
                                  :prior-read-only read-only
                                  :snapshot-id id
                                  :snapshot snapshot
                                  :token token})
                (update :workspace-global assoc :read-only? true :preview-id id)))
          state))

      ptk/WatchEvent
      (watch [_ state stream]
        (when (active-session? state :preview token)
          (let [file-id (:current-file-id state)
                features (features/get-enabled-features state (:current-team-id state))]
            (->> (rp/cmd! :get-file-snapshot {:file-id file-id :id id :features features})
                 (rx/map #(apply-snapshot token %))
                 (rx/catch (fn [err]
                             (log/error :hint "failed to load snapshot" :cause err :file-id file-id :snapshot-id id)
                             (rx/of (exit-preview token))))
                 (rx/take-until (workspace-replacement stream)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PLUGINS SPECIFIC EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
                     (dwp/wait-persisted-or-error)
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
  [file-id id resolve reject]
  (assert (uuid? id) "expected valid uuid for `id`")

  (ptk/reify ::restore-version-from-plugins
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/concat
            (rx/of (ev/event {::ev/name "restore-version"
                              ::ev/origin "plugins"})
                   ::dwp/force-persist)

            (->> (dwp/wait-persisted-or-error)
                 (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
                 (rx/map #(initialize-version)))

            (->> (rx/of 1)
                 (rx/tap resolve)
                 (rx/ignore)))

           ;; Restore the live file before rejecting the plugin promise.
           (rx/catch (fn [error]
                       (rx/concat
                        (rx/of (exit-preview))
                        (->> (rx/of error)
                             (rx/observe-on :async)
                             (rx/tap reject)
                             (rx/ignore)))))))))
