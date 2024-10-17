;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-update
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.http.errors :as errors]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.set :as set]
   [promesa.exec :as px]))

(declare ^:private get-lagged-changes)
(declare ^:private send-notifications!)
(declare ^:private update-file)
(declare ^:private update-file*)
(declare ^:private process-changes-and-validate)
(declare ^:private take-snapshot?)
(declare ^:private delete-old-snapshots!)

;; PUBLIC API; intended to be used outside of this module
(declare update-file!)
(declare update-file-data!)
(declare persist-file!)
(declare get-file)

;; --- SCHEMA

(def ^:private
  schema:update-file
  [:map {:title "update-file"}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:changes {:optional true} [:vector ::cpc/change]]
   [:changes-with-metadata {:optional true}
    [:vector [:map
              [:changes [:vector ::cpc/change]]
              [:hint-origin {:optional true} :keyword]
              [:hint-events {:optional true} [:vector [:string {:max 250}]]]]]]
   [:skip-validate {:optional true} ::sm/boolean]])

(def ^:private
  schema:update-file-result
  [:vector {:title "update-file-result"}
   [:map
    [:changes [:vector ::cpc/change]]
    [:file-id ::sm/uuid]
    [:id ::sm/uuid]
    [:revn {:min 0} ::sm/int]
    [:session-id ::sm/uuid]]])

;; --- HELPERS

;; File changes that affect to the library, and must be notified
;; to all clients using it.

(def ^:private library-change-types
  #{:add-color
    :mod-color
    :del-color
    :add-media
    :mod-media
    :del-media
    :add-component
    :mod-component
    :del-component
    :restore-component
    :add-typography
    :mod-typography
    :del-typography})

(def ^:private file-change-types
  #{:add-obj
    :mod-obj
    :del-obj
    :reg-objects
    :mov-objects})

(defn- library-change?
  [{:keys [type] :as change}]
  (or (contains? library-change-types type)
      (contains? file-change-types type)))

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(sv/defmethod ::update-file
  {::climit/id [[:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]
   ::webhooks/event? true
   ::webhooks/batch-timeout (dt/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file
   ::sm/result schema:update-file-result
   ::doc/module :files
   ::doc/added "1.17"}
  [{:keys [::mtx/metrics] :as cfg}
   {:keys [::rpc/profile-id id changes changes-with-metadata] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (files/check-edition-permissions! conn profile-id id)
                    (db/xact-lock! conn id)

                    (let [file     (get-file conn id)
                          team     (teams/get-team conn
                                                   :profile-id profile-id
                                                   :team-id (:team-id file))

                          features (-> (cfeat/get-team-enabled-features cf/flags team)
                                       (cfeat/check-client-features! (:features params))
                                       (cfeat/check-file-features! (:features file) (:features params)))

                          changes  (if changes-with-metadata
                                     (->> changes-with-metadata (mapcat :changes) vec)
                                     (vec changes))

                          params   (-> params
                                       (assoc :profile-id profile-id)
                                       (assoc :features features)
                                       (assoc :team team)
                                       (assoc :file file)
                                       (assoc :changes changes))

                          cfg      (assoc cfg ::timestamp (dt/now))

                          tpoint   (dt/tpoint)]


                      (when (> (:revn params)
                               (:revn file))
                        (ex/raise :type :validation
                                  :code :revn-conflict
                                  :hint "The incoming revision number is greater that stored version."
                                  :context {:incoming-revn (:revn params)
                                            :stored-revn (:revn file)}))

                      ;; When newly computed features does not match exactly with
                      ;; the features defined on team row, we update it.
                      (when (not= features (:features team))
                        (let [features (db/create-array conn "text" features)]
                          (db/update! conn :team
                                      {:features features}
                                      {:id (:id team)})))

                      (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

                      (binding [l/*context* (some-> (meta params)
                                                    (get :app.http/request)
                                                    (errors/request->context))]
                        (-> (update-file* cfg params)
                            (rph/with-defer #(let [elapsed (tpoint)]
                                               (l/trace :hint "update-file" :time (dt/format-duration elapsed))))))))))

(defn- update-file*
  "Internal function, part of the update-file process, that encapsulates
  the changes application offload to a separated thread and emit all
  corresponding notifications.

  Follow the inner implementation to `update-file-data!` function.

  Only intended for internal use on this module."
  [{:keys [::db/conn ::wrk/executor ::timestamp] :as cfg}
   {:keys [profile-id file features changes session-id skip-validate] :as params}]

  (let [;; Retrieve the file data
        file (feat.fdata/resolve-file-data cfg file)

        file (assoc file :features
                    (-> features
                        (set/difference cfeat/frontend-only-features)
                        (set/union (:features file))))

        ;; Process the file data on separated thread for avoid to do
        ;; the CPU intensive operation on vthread.
        file  (px/invoke! executor
                          (fn []
                            (binding [cfeat/*current*  features
                                      cfeat/*previous* (:features file)]
                              (update-file-data! cfg file
                                                 process-changes-and-validate
                                                 changes skip-validate))))]

    (when (feat.fdata/offloaded? file)
      (let [storage (sto/resolve cfg ::db/reuse-conn true)]
        (some->> (:data-ref-id file) (sto/touch-object! storage))))

    ;; TODO: move this to asynchronous task
    (when (::snapshot-data file)
      (delete-old-snapshots! cfg file))

    (persist-file! cfg file)

    (let [params   (assoc params :file file)
          response {:revn (:revn file)
                    :lagged (get-lagged-changes conn params)}
          features (db/create-array conn "text" (:features file))]

      ;; Insert change (xlog)
      (db/insert! conn :file-change
                  {:id (uuid/next)
                   :session-id session-id
                   :profile-id profile-id
                   :created-at timestamp
                   :file-id (:id file)
                   :revn (:revn file)
                   :version (:version file)
                   :features features
                   :label (::snapshot-label file)
                   :data (::snapshot-data file)
                   :changes (blob/encode changes)}
                  {::db/return-keys false})

      ;; Send asynchronous notifications
      (send-notifications! cfg params)

      (vary-meta response assoc ::audit/replace-props
                 {:id         (:id file)
                  :name       (:name file)
                  :features   (:features file)
                  :project-id (:project-id file)
                  :team-id    (:team-id file)}))))

(defn update-file!
  "A public api that allows apply a transformation to a file with all context setup."
  [cfg file-id update-fn & args]
  (let [file (get-file cfg file-id)
        file (apply update-file-data! cfg file update-fn args)]
    (persist-file! cfg file)))

(def ^:private sql:get-file
  "SELECT f.*, p.team_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?
      AND (f.deleted_at IS NULL OR
           f.deleted_at > now())
      FOR KEY SHARE")

(defn get-file
  "Get not-decoded file, only decodes the features set."
  [conn id]
  (let [file (db/exec-one! conn [sql:get-file id])]
    (when-not file
      (ex/raise :type :not-found
                :code :object-not-found
                :hint (format "file with id '%s' does not exists" id)))
    (update file :features db/decode-pgarray #{})))

(defn persist-file!
  "Function responsible of persisting already encoded file. Should be
  used together with `get-file` and `update-file-data!`.

  It also updates the project modified-at attr."
  [{:keys [::db/conn ::timestamp]} file]
  (let [features (db/create-array conn "text" (:features file))
        ;; The timestamp can be nil because this function is also
        ;; intended to be used outside of this module
        modified-at (or timestamp (dt/now))]

    (db/update! conn :project
                {:modified-at modified-at}
                {:id (:project-id file)}
                {::db/return-keys false})

    (db/update! conn :file
                {:revn (:revn file)
                 :data (:data file)
                 :version (:version file)
                 :features features
                 :data-backend nil
                 :data-ref-id nil
                 :modified-at modified-at
                 :has-media-trimmed false}
                {:id (:id file)}
                {::db/return-keys false})))

(defn- update-file-data!
  "Perform a file data transformation in with all update context setup.

  This function expected not-decoded file and transformation function. Returns
  an encoded file.

  This function is not responsible of saving the file. It only saves
  fdata/pointer-map modified fragments."

  [cfg {:keys [id] :as file} update-fn & args]
  (binding [pmap/*tracked* (pmap/create-tracked)
            pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
    (let [file (update file :data (fn [data]
                                    (-> data
                                        (blob/decode)
                                        (assoc :id (:id file)))))

          ;; For avoid unnecesary overhead of creating multiple pointers
          ;; and handly internally with objects map in their worst
          ;; case (when probably all shapes and all pointers will be
          ;; readed in any case), we just realize/resolve them before
          ;; applying the migration to the file
          file (if (fmg/need-migration? file)
                 (-> file
                     (update :data feat.fdata/process-pointers deref)
                     (update :data feat.fdata/process-objects (partial into {}))
                     (fmg/migrate-file))
                 file)

          file (apply update-fn cfg file args)

          ;; TODO: reuse operations if file is migrated
          ;; TODO: move encoding to a separated thread
          file (if (take-snapshot? file)
                 (let [tpoint   (dt/tpoint)
                       snapshot (-> (:data file)
                                    (feat.fdata/process-pointers deref)
                                    (feat.fdata/process-objects (partial into {}))
                                    (blob/encode))
                       elapsed (tpoint)
                       label   (str "internal/snapshot/" (:revn file))]

                   (l/trc :hint "take snapshot"
                          :file-id (str (:id file))
                          :revn (:revn file)
                          :label label
                          :elapsed (dt/format-duration elapsed))

                   (-> file
                       (assoc ::snapshot-data snapshot)
                       (assoc ::snapshot-label label)))
                 file)

          file (cond-> file
                 (contains? cfeat/*current* "fdata/objects-map")
                 (feat.fdata/enable-objects-map)

                 (contains? cfeat/*current* "fdata/pointer-map")
                 (feat.fdata/enable-pointer-map)

                 :always
                 (update :data blob/encode))]

      (feat.fdata/persist-pointers! cfg id)

      file)))

(defn- get-file-libraries
  "A helper for preload file libraries, mainly used for perform file
  semantical and structural validation"
  [{:keys [::db/conn] :as cfg} file]
  (->> (files/get-file-libraries conn (:id file))
       (into [file] (map (fn [{:keys [id]}]
                           (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)
                                     pmap/*tracked* nil]
                             ;; We do not resolve the objects maps here
                             ;; because there is a lower probability that all
                             ;; shapes needed to be loded into memory, so we
                             ;; leeave it on lazy status
                             (-> (files/get-file cfg id :migrate? false)
                                 (update :data feat.fdata/process-pointers deref) ; ensure all pointers resolved
                                 (update :data feat.fdata/process-objects (partial into {}))
                                 (fmg/migrate-file))))))
       (d/index-by :id)))

(defn- soft-validate-file-schema!
  [file]
  (try
    (val/validate-file-schema! file)
    (catch Throwable cause
      (l/error :hint "file schema validation error" :cause cause))))

(defn- soft-validate-file!
  [file libs]
  (try
    (val/validate-file! file libs)
    (catch Throwable cause
      (l/error :hint "file validation error"
               :cause cause))))

(defn- process-changes-and-validate
  [cfg file changes skip-validate]
  (let [;; WARNING: this ruins performance; maybe we need to find
        ;; some other way to do general validation
        libs (when (and (or (contains? cf/flags :file-validation)
                            (contains? cf/flags :soft-file-validation))
                        (not skip-validate))
               (get-file-libraries cfg file))

        file (-> (files/check-version! file)
                 (update :revn inc)
                 (update :data cpc/process-changes changes)
                 (update :data d/without-nils))]

    (binding [pmap/*tracked* nil]
      (when (contains? cf/flags :soft-file-validation)
        (soft-validate-file! file libs))

      (when (contains? cf/flags :soft-file-schema-validation)
        (soft-validate-file-schema! file))

      (when (and (contains? cf/flags :file-validation)
                 (not skip-validate))
        (val/validate-file! file libs))

      (when (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate))
        (val/validate-file-schema! file)))

    file))

(defn- take-snapshot?
  "Defines the rule when file `data` snapshot should be saved."
  [{:keys [revn modified-at] :as file}]
  (when (contains? cf/flags :auto-file-snapshot)
    (let [freq    (or (cf/get :auto-file-snapshot-every) 20)
          timeout (or (cf/get :auto-file-snapshot-timeout)
                      (dt/duration {:hours 1}))]

      (or (= 1 freq)
          (zero? (mod revn freq))
          (> (inst-ms (dt/diff modified-at (dt/now)))
             (inst-ms timeout))))))

;; Get the latest available snapshots without exceeding the total
;; snapshot limit.
(def ^:private sql:get-latest-snapshots
  "SELECT fch.id, fch.created_at
     FROM file_change AS fch
    WHERE fch.file_id = ?
      AND fch.label LIKE 'internal/%'
    ORDER BY fch.created_at DESC
    LIMIT ?")

;; Mark all snapshots that are outside the allowed total threshold
;; available for the GC.
(def ^:private sql:delete-snapshots
  "UPDATE file_change
      SET label = NULL
    WHERE file_id = ?
      AND label LIKE 'internal/%'
      AND created_at < ?")

(defn- delete-old-snapshots!
  [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
  (when-let [snapshots (not-empty (db/exec! conn [sql:get-latest-snapshots id
                                                  (cf/get :auto-file-snapshot-total 10)]))]
    (let [last-date (-> snapshots peek :created-at)
          result    (db/exec-one! conn [sql:delete-snapshots id last-date])]
      (l/trc :hint "delete old snapshots" :file-id (str id) :total (db/get-update-count result)))))

(def ^:private sql:lagged-changes
  "select s.id, s.revn, s.file_id,
          s.session_id, s.changes
     from file_change as s
    where s.file_id = ?
      and s.revn > ?
    order by s.created_at asc")

(defn- get-lagged-changes
  [conn {:keys [id revn] :as params}]
  (->> (db/exec! conn [sql:lagged-changes id revn])
       (map files/decode-row)
       (vec)))

(defn- send-notifications!
  [cfg {:keys [file team changes session-id] :as params}]
  (let [lchanges (filter library-change? changes)
        msgbus   (::mbus/msgbus cfg)]

    (mbus/pub! msgbus
               :topic (:id file)
               :message {:type :file-change
                         :profile-id (:profile-id params)
                         :file-id (:id file)
                         :session-id (:session-id params)
                         :revn (:revn file)
                         :changes changes})

    (when (and (:is-shared file) (seq lchanges))
      (mbus/pub! msgbus
                 :topic (:id team)
                 :message {:type :library-change
                           :profile-id (:profile-id params)
                           :file-id (:id file)
                           :session-id session-id
                           :revn (:revn file)
                           :modified-at (dt/now)
                           :changes lchanges}))))
