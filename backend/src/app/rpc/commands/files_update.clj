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
   [app.common.files.migrations :as pmg]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.pages.changes :as cpc]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :refer [enable-pointer-map enable-objects-map]]
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
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]))

;; --- SCHEMA

(def ^:private schema:changes
  [:vector ::cpc/change])

(def ^:private schema:change-with-metadata
  [:map {:title "ChangeWithMetadata"}
   [:changes schema:changes]
   [:hint-origin {:optional true} :keyword]
   [:hint-events {:optional true} [:vector :string]]])

(def ^:private schema:update-file
  [:map {:title "update-file"}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} :int]
   [:features {:optional true} ::cfeat/features]
   [:changes {:optional true} schema:changes]
   [:changes-with-metadata {:optional true}
    [:vector schema:change-with-metadata]]
   [:skip-validate {:optional true} :boolean]])

(def ^:private schema:update-file-result
  [:vector {:title "update-file-result"}
   [:map
    [:changes schema:changes]
    [:file-id ::sm/uuid]
    [:id ::sm/uuid]
    [:revn {:min 0} :int]
    [:session-id ::sm/uuid]]])

;; --- HELPERS

;; File changes that affect to the library, and must be notified
;; to all clients using it.

(def ^:private library-change-types
  #{:add-color :mod-color :del-color
    :add-media :mod-media :del-media
    :add-component :mod-component :del-component
    :add-typography :mod-typography :del-typography})

(def ^:private file-change-types
  #{:add-obj :mod-obj :del-obj
    :reg-objects :mov-objects})

(defn- library-change?
  [{:keys [type] :as change}]
  (or (contains? library-change-types type)
      (contains? file-change-types type)))

(def ^:private sql:get-file
  "SELECT f.*, p.team_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?
      AND (f.deleted_at IS NULL OR
           f.deleted_at > now())
      FOR KEY SHARE")

(defn get-file
  [conn id]
  (let [file (db/exec-one! conn [sql:get-file id])]
    (when-not file
      (ex/raise :type :not-found
                :code :object-not-found
                :hint (format "file with id '%s' does not exists" id)))
    (update file :features db/decode-pgarray #{})))

(defn- wrap-with-pointer-map-context
  [f]
  (fn [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (partial files/load-pointer conn id)
              cfeat/*wrap-with-pointer-map-fn* pmap/wrap]
      (let [result (f cfg file)]
        (files/persist-pointers! conn id)
        result))))

(defn- wrap-with-objects-map-context
  [f]
  (fn [cfg file]
    (binding [cfeat/*wrap-with-objects-map-fn* omap/wrap]
      (f cfg file))))

(declare get-lagged-changes)
(declare send-notifications!)
(declare update-file)
(declare update-file*)
(declare update-file-data)
(declare take-snapshot?)

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(sv/defmethod ::update-file
  {::climit/id :update-file-by-id
   ::climit/key-fn :id
   ::webhooks/event? true
   ::webhooks/batch-timeout (dt/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file
   ::sm/result schema:update-file-result
   ::doc/module :files
   ::doc/added "1.17"}
  [cfg {:keys [::rpc/profile-id id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (files/check-edition-permissions! conn profile-id id)
                    (db/xact-lock! conn id)

                    (let [file     (get-file conn id)
                          team     (teams/get-team cfg
                                                   :profile-id profile-id
                                                   :team-id (:team-id file))

                          features (-> (cfeat/get-team-enabled-features cf/flags team)
                                       (cfeat/check-client-features! (:features params))
                                       (cfeat/check-file-features! (:features file) (:features params)))

                          params   (assoc params
                                          :profile-id profile-id
                                          :features features
                                          :team team
                                          :file file)

                          tpoint   (dt/tpoint)]

                      ;; When newly computed features does not match exactly with
                      ;; the features defined on team row, we update it.
                      (when (not= features (:features team))
                        (let [features (db/create-array conn "text" features)]
                          (db/update! conn :team
                                      {:features features}
                                      {:id (:id team)})))

                      (-> (update-file cfg params)
                          (rph/with-defer #(let [elapsed (tpoint)]
                                             (l/trace :hint "update-file" :time (dt/format-duration elapsed)))))))))

(defn update-file
  [{:keys [::db/conn ::mtx/metrics] :as cfg} {:keys [id file features changes changes-with-metadata skip-validate] :as params}]
  (binding [cfeat/*current*  features
            cfeat/*previous* (:features file)]
    (let [update-fn (cond-> update-file*
                      (contains? features "fdata/pointer-map")
                      (wrap-with-pointer-map-context)

                      (contains? features "fdata/objects-map")
                      (wrap-with-objects-map-context))

          ;; TODO: this ruins performance.
          ;;       We must find some other way to do general validation.
          libraries (when (and (contains? cf/flags :file-validation)
                               (not skip-validate))
                      (let [libs (->> (files/get-file-libraries conn (:id file))
                                      (map #(get-file conn (:id %)))
                                      (map #(update % :data blob/decode))
                                      (d/index-by :id))]
                        (assoc libs (:id file) file)))

          changes   (if changes-with-metadata
                      (->> changes-with-metadata (mapcat :changes) vec)
                      (vec changes))

          features  (-> features
                        (set/difference cfeat/frontend-only-features)
                        (set/union (:features file)))]

      (when (> (:revn params)
               (:revn file))
        (ex/raise :type :validation
                  :code :revn-conflict
                  :hint "The incoming revision number is greater that stored version."
                  :context {:incoming-revn (:revn params)
                            :stored-revn (:revn file)}))

      (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

      (when (not= features (:features file))
        (let [features (db/create-array conn "text" features)]
          (db/update! conn :file
                      {:features features}
                      {:id id})))

      (let [file   (assoc file :features features)
            params (-> params
                       (assoc :file file)
                       (assoc :libraries libraries)
                       (assoc :changes changes)
                       (assoc ::created-at (dt/now)))]
        (-> (update-fn cfg params)
            (vary-meta assoc ::audit/replace-props
                       {:id         (:id file)
                        :name       (:name file)
                        :features   (:features file)
                        :project-id (:project-id file)
                        :team-id    (:team-id file)}))))))

(defn- update-file*
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file libraries changes session-id ::created-at skip-validate] :as params}]
  (let [;; Process the file data in the CLIMIT context; scheduling it
        ;; to be executed on a separated executor for avoid to do the
        ;; CPU intensive operation on vthread.
        file (-> (climit/configure cfg :update-file)
                 (climit/submit! (partial update-file-data file libraries changes skip-validate)))]

    (db/insert! conn :file-change
                {:id (uuid/next)
                 :session-id session-id
                 :profile-id profile-id
                 :created-at created-at
                 :file-id (:id file)
                 :revn (:revn file)
                 :features (db/create-array conn "text" (:features file))
                 :data (when (take-snapshot? file)
                         (:data file))
                 :changes (blob/encode changes)})

    (db/update! conn :file
                {:revn (:revn file)
                 :data (:data file)
                 :data-backend nil
                 :modified-at created-at
                 :has-media-trimmed false}
                {:id (:id file)})

    (db/update! conn :project
                {:modified-at created-at}
                {:id (:project-id file)})

    (let [params (assoc params :file file)]
      ;; Send asynchronous notifications
      (send-notifications! cfg params)

      ;; Retrieve and return lagged data
      (get-lagged-changes conn params))))

(defn- update-file-data
  [file libraries changes skip-validate]
  (let [validate (fn [file]
                   (when (and (cf/flags :file-validation)
                              (not skip-validate))
                     (val/validate-file file libraries :throw? true)))
        file     (-> file
                     (update :revn inc)
                     (update :data (fn [data]
                                     (cond-> data
                                       :always
                                       (-> (blob/decode)
                                           (assoc :id (:id file))
                                           (pmg/migrate-data))

                                       :always
                                       (cp/process-changes changes))))
                     (d/tap-r validate))

        file     (if (and (contains? cfeat/*current* "fdata/objects-map")
                          (not (contains? cfeat/*previous* "fdata/objects-map")))
                   (enable-objects-map file)
                   file)

        file     (if (and (contains? cfeat/*current* "fdata/pointer-map")
                          (not (contains? cfeat/*previous* "fdata/pointer-map")))
                   (enable-pointer-map file)
                   file)
        ]

    (update file :data blob/encode)))

(defn- take-snapshot?
  "Defines the rule when file `data` snapshot should be saved."
  [{:keys [revn modified-at] :as file}]
  (let [freq    (or (cf/get :file-change-snapshot-every) 20)
        timeout (or (cf/get :file-change-snapshot-timeout)
                    (dt/duration {:hours 1}))]
    (or (= 1 freq)
        (zero? (mod revn freq))
        (> (inst-ms (dt/diff modified-at (dt/now)))
           (inst-ms timeout)))))

(def ^:private
  sql:lagged-changes
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
