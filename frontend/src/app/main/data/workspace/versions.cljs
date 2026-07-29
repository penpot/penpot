;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.versions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.time :as ct]
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
   [potok.v2.core :as ptk]))

(defonce default-state
  {:status :loading
   :data nil
   :editing nil
   :preview-id nil})

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

         (->> (dwp/wait-persisted)
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
;; RESTORE VERSION EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- exit-preview-cleanup
  "Restore the backed-up live file data and clear the preview flags."
  []
  (ptk/reify ::exit-preview-cleanup
    ptk/UpdateEvent
    (update [_ state]
      (let [backup (dm/get-in state [:workspace-versions :backup])]
        (-> state
            (update :workspace-versions dissoc :backup)
            (update :workspace-global dissoc :read-only? :preview-id)
            (update :files assoc (:id backup) backup))))))

(defn exit-preview
  "Exit from preview mode and reload the live file data.

  No-op when there is no preview to exit (no backup stored), so it is
  safe to call from the restore dialog dismiss action even when the
  restore was triggered directly without entering preview first."
  []
  (ptk/reify ::exit-preview
    ptk/WatchEvent
    (watch [_ state _]
      ;; Ensure we are actually in preview mode. Otherwise there
      ;; is no backup to restore and wasm crashes
      (when (dm/get-in state [:workspace-versions :backup])
        (let [file-id (:current-file-id state)
              page-id (:current-page-id state)]
          (rx/of (exit-preview-cleanup)
                 (dwpg/initialize-page file-id page-id)))))))

(defn- restore-version
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::restore-version
    ptk/UpdateEvent
    (update [_ state]
      ;; Clear preview state if we're restoring from preview mode
      (-> state
          (update :workspace-versions dissoc :backup)
          (update :workspace-global dissoc :read-only? :preview-id)))
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (rx/concat
         (rx/of ::dwp/force-persist
                (dw/remove-layout-flag :document-history))

         (->> (dwp/wait-persisted)
              (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
              (rx/map #(initialize-version))))))))

(defn enter-restore
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::enter-restore
    ptk/WatchEvent
    (watch [_ _ _]
      (let [output-s (rx/subject)]
        (rx/merge
         output-s
         (rx/of (ntf/dialog
                 :content (tr "workspace.versions.restore-warning")
                 :controls :inline-actions
                 :cancel {:label (tr "workspace.updates.dismiss")
                          :callback #(do
                                       (rx/push! output-s (ntf/hide :tag :restore-dialog))
                                       (rx/push! output-s (exit-preview))
                                       (rx/end! output-s))}
                 :accept {:label (tr "labels.restore")
                          :callback #(do
                                       (rx/push! output-s (restore-version id))
                                       (rx/end! output-s))}
                 :tag :restore-dialog)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PREVIEW VERSION EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- apply-snapshot
  "Swap the file data in app state with the provided snapshot-file
  response. Used by the version preview feature to show historical
  file content without modifying the database"
  [{:keys [id] :as snapshot}]
  (ptk/reify ::apply-snapshot-data
    ptk/UpdateEvent
    (update [_ state]
      (update state :files assoc id snapshot))))

(defn enter-preview
  "Load a snapshot into the workspace for read-only preview without
  modifying any database state. Sets a read-only flag so no changes
  are persisted while previewing and enter on the preview mode"
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")

  (ptk/reify ::enter-preview
    ptk/UpdateEvent
    (update [_ state]
      (let [file (dsh/lookup-file state)]
        (-> state
            (update :workspace-versions assoc :backup file)
            (update :workspace-global assoc :read-only? true :preview-id id))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            team-id  (:current-team-id state)
            features (features/get-enabled-features state team-id)
            snapshot (->> (dm/get-in state [:workspace-versions :data])
                          (d/seek #(= id (:id %))))
            ;; Match the History sidebar's identifying text so the
            ;; preview banner and the sidebar entry "speak the same
            ;; language" (#9503):
            ;; - user-created (pinned) versions keep the user's custom
            ;;   label; if absent, fall back to "unnamed"
            ;; - system-created autosaves use the same auto-generated
            ;;   label the sidebar's `snapshot-entry*` already renders
            ;;   via `workspace.versions.autosaved.version` + a
            ;;   localized date, instead of the internal snapshot
            ;;   label (e.g. `internal/snapshot/20`).
            label    (cond
                       (= "system" (:created-by snapshot))
                       (tr "workspace.versions.autosaved.version"
                           (ct/format-inst (:created-at snapshot) :localized-date))

                       :else
                       (or (:label snapshot)
                           (tr "workspace.versions.preview.unnamed")))
            output-s (rx/subject)]
        (rx/merge
         output-s

         (rx/of (ntf/dialog
                 :content (tr "workspace.versions.preview-banner-title" label)
                 :controls :inline-actions
                 :cancel {:label (tr "labels.exit")
                          :callback #(do
                                       (rx/push! output-s (ntf/hide))
                                       (rx/push! output-s (exit-preview))
                                       (rx/end! output-s))}
                 :accept {:label (tr "labels.restore")
                          :callback #(do
                                       (rx/push! output-s (ntf/hide))
                                       (rx/push! output-s (enter-restore id))
                                       (rx/end! output-s))}
                 :tag :preview-dialog))

         (->> (rp/cmd! :get-file-snapshot
                       {:file-id file-id
                        :id id
                        :features features})
              (rx/mapcat
               (fn [snapshot]
                 (rx/of
                  ;; Swap the file data in state with snapshot content.
                  ;; Passing id sets workspace-file-version-id, which
                  ;; causes the WASM viewport to reload its shape buffer.
                  (apply-snapshot snapshot)
                  ;; Re-initialize the page to rebuild its search index
                  ;; and page-local state with the new snapshot
                  ;; objects.
                  (dwpg/initialize-page file-id page-id))))

              (rx/catch (fn [err]
                          ;; On error roll back the read-only flag so the
                          ;; user is not stuck in a broken preview state.
                          (log/error :hint "failed to load snapshot" :cause err :file-id file-id :snapshot-id id)
                          (rx/of (exit-preview))))))))))

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
                     (dwp/wait-persisted)
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

            (->> (dwp/wait-persisted)
                 (rx/mapcat #(rp/cmd! :restore-file-snapshot {:file-id file-id :id id}))
                 (rx/map #(initialize-version)))

            (->> (rx/of 1)
                 (rx/tap resolve)
                 (rx/ignore)))

           ;; On error reject the promise and empty the stream
           (rx/catch (fn [error]
                       (reject error)
                       (rx/empty)))))))



