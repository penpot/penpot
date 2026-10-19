;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.files-update
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.http.errors :as errors]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.redis :as rds]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]
   [cuerdas.core :as str]))

(declare ^:private get-lagged-changes)
(declare ^:private send-notifications!)
(declare ^:private update-file)
(declare ^:private update-file*)
(declare ^:private process-changes-and-validate)
(declare ^:private take-snapshot?)
(declare ^:private invalidate-caches!)

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
   [:vern {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:changes {:optional true} [:vector cpc/schema:change]]
   [:changes-with-metadata {:optional true}
    [:vector [:map
              [:changes [:vector cpc/schema:change]]
              [:commit-id {:optional true} ::sm/uuid]
              [:hint-origin {:optional true} :keyword]
              [:hint-events {:optional true} [:vector [:string {:max 250}]]]]]]
   [:skip-validate {:optional true} ::sm/boolean]])

(def ^:private
  schema:update-file-result
  [:map {:title "update-file-result"}
   ;; The revision the file had before this update
   [:revn {:min 0} ::sm/int]
   ;; Every change stored after the incoming revision, this one included
   [:lagged
    [:vector
     [:map
      [:changes [:vector cpc/schema:change]]
      [:file-id ::sm/uuid]
      [:id ::sm/uuid]
      [:revn {:min 0} ::sm/int]
      [:session-id ::sm/uuid]]]]
   ;; Commits not saved: the first one that failed and every later one
   [:rejected {:optional true}
    [:map
     [:commit-ids [:vector ::sm/uuid]]
     [:failed-commit-id ::sm/uuid]
     [:error
      [:map
       [:type :keyword]
       [:code {:optional true} [:maybe :keyword]]
       [:hint {:optional true} [:maybe :string]]
       [:details {:optional true} [:vector :any]]]]]]])

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

(def ^:private token-change-types
  #{:set-tokens-lib
    :set-token
    :set-token-set
    :set-token-theme
    :rename-token-set-group
    :move-token-set
    :move-token-set-group
    :set-base-font-size})

(def ^:private file-change-types
  #{:add-obj
    :mod-obj
    :del-obj
    :reg-objects
    :mov-objects})

(defn- library-change?
  [{:keys [type] :as change}]
  (or (contains? library-change-types type)
      (contains? token-change-types type)
      (contains? file-change-types type)))

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(sv/defmethod ::update-file
  {::rpc/id-type :file
   ::climit/id [[:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]

   ::webhooks/event? true
   ::webhooks/batch-timeout (ct/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file
   ::sm/result schema:update-file-result
   ::doc/module :files
   ::doc/added "1.17"
   ::db/transaction true}
  [{:keys [::mtx/metrics ::db/conn] :as cfg}
   {:keys [::rpc/profile-id id changes changes-with-metadata] :as params}]

  (files/check-edition-permissions! conn profile-id id)
  (db/xact-lock! conn id)

  (let [file     (get-file cfg id)
        team     (teams/get-team conn
                                 :profile-id profile-id
                                 :team-id (:team-id file))

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params))
                     (cfeat/check-file-features! (:features file)))

        changes  (if changes-with-metadata
                   (->> changes-with-metadata (mapcat :changes) vec)
                   (vec changes))

        ;; Saved commit by commit only when every group names its commit
        commits  (when (and (seq changes-with-metadata)
                            (every? :commit-id changes-with-metadata))
                   changes-with-metadata)

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features (set/difference features cfeat/frontend-only-features))
                     (assoc :team team)
                     (assoc :file file)
                     (assoc :changes changes)
                     (assoc :commits commits))

        cfg      (assoc cfg ::timestamp (ct/now)
                        ::validation-timings (atom {}))

        tpoint   (ct/tpoint)]

    (when (not= (:vern params)
                (:vern file))
      (ex/raise :type :validation
                :code :vern-conflict
                :hint "A different version has been restored for the file."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    (when (> (:revn params)
             (:revn file))
      (ex/raise :type :validation
                :code :revn-conflict
                :hint "The incoming revision number is greater that stored version."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    ;; When newly computed features does not match exactly with the
    ;; features defined on team row, we update it
    (when-let [features (-> features
                            (set/difference (:features team))
                            (set/difference cfeat/no-team-inheritable-features)
                            (not-empty))]
      (let [features (-> features
                         (set/union (:features team))
                         (set/difference cfeat/no-team-inheritable-features)
                         (into-array))]
        (db/update! conn :team
                    {:features features}
                    {:id (:id team)}
                    {::db/return-keys false})))


    (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

    (binding [l/*context* (some-> (meta params)
                                  (get :app.http/request)
                                  (errors/request->context))]
      (try
        (-> (update-file* cfg params)
            (rph/with-defer #(let [elapsed (tpoint)
                                   timings @(::validation-timings cfg)]
                               (l/dbg :hint "update-file"
                                      :file-id (str (:id file))
                                      :project-id (str (:project-id file))
                                      :team-id (str (:team-id file))
                                      :status "success"
                                      :time (ct/format-duration elapsed)
                                      :resolve-libs-ms (get timings :resolve-libs-ms)
                                      :process-changes-ms (get timings :process-changes-ms)
                                      :soft-validate-affected-ms (get timings :soft-validate-affected-ms)
                                      :soft-validate-schema-ms (get timings :soft-validate-schema-ms)
                                      :validate-affected-ms (get timings :validate-affected-ms)
                                      :validate-schema-ms (get timings :validate-schema-ms)))))
        (catch Throwable cause
          ;; NOTE: `with-defer` hooks only run on the success path
          ;; (`handle-response`), so timings collected before a hard
          ;; validation failure would be lost without this inline log.
          (let [timings @(::validation-timings cfg)]
            (l/dbg :hint "update-file"
                   :file-id (str (:id file))
                   :project-id (str (:project-id file))
                   :team-id (str (:team-id file))
                   :status "error"
                   :resolve-libs-ms (get timings :resolve-libs-ms)
                   :process-changes-ms (get timings :process-changes-ms)
                   :soft-validate-affected-ms (get timings :soft-validate-affected-ms)
                   :soft-validate-schema-ms (get timings :soft-validate-schema-ms)
                   :validate-affected-ms (get timings :validate-affected-ms)
                   :validate-schema-ms (get timings :validate-schema-ms)))
          (throw cause))))))

(defn- update-file*
  "Internal function, part of the update-file process, that encapsulates
  the changes application offload to a separated thread and emit all
  corresponding notifications.

  Follow the inner implementation to `update-file-data!` function.

  Only intended for internal use on this module."
  [{:keys [::db/conn ::timestamp] :as cfg}
   {:keys [profile-id file team features changes commits session-id skip-validate] :as params}]

  (binding [pmap/*tracked* (pmap/create-tracked)
            pmap/*load-fn* (partial fdata/load-pointer cfg (:id file))]

    (let [file (assoc file :features
                      (-> features
                          (set/difference cfeat/frontend-only-features)
                          (set/union (:features file))))

          ;; We need to preserve the original revn for the response
          revn
          (get file :revn)

          file
          (binding [cfeat/*current*  features
                    cfeat/*previous* (:features file)]
            (update-file-data! cfg file
                               process-changes-and-validate
                               changes commits skip-validate))

          ;; Only the accepted changes are stored and broadcast
          changes  (::changes file)
          rejected (::rejected file)
          params   (assoc params :changes changes)
          file     (dissoc file ::changes ::rejected)

          ;; A request whose first commit is rejected leaves the file as is
          saved?   (or (nil? rejected) (seq changes))
          snapshot (when saved? (::snapshot file))

          deleted-at
          (ct/plus timestamp (ct/duration {:hours 1}))]

      (when snapshot
        (fsnap/create! cfg snapshot
                       {:objects-map (boolean (::objects-map snapshot))
                        :label (str "internal/snapshot/" revn)
                        :created-by "system"
                        :deleted-at (ct/plus timestamp (ldel/get-deletion-delay team))
                        :profile-id profile-id
                        :session-id session-id}))

      (when saved?
        ;; Insert change (xlog) with deleted_at in a future data for
        ;; make them automatically eleggible for GC once they expires
        (db/insert! conn :file-change
                    {:id (uuid/next)
                     :session-id session-id
                     :profile-id profile-id
                     :created-at timestamp
                     :updated-at timestamp
                     :deleted-at deleted-at
                     :file-id (:id file)
                     :revn (:revn file)
                     :version (:version file)
                     :features (into-array (:features file))
                     :changes (blob/encode changes)}
                    {::db/return-keys false})

        (persist-file! cfg file)

        (when (contains? cf/flags :redis-cache)
          (invalidate-caches! cfg file))

        ;; Send asynchronous notifications
        (send-notifications! cfg params file))

      (with-meta (cond-> {:revn revn :lagged (get-lagged-changes conn params)}
                   (some? rejected)
                   (assoc :rejected rejected))
        {::audit/replace-props
         {:id          (:id file)
          :name        (:name file)
          :features    (:features file)
          :project-id  (:project-id file)
          :team-id     (:team-id file)
          ;; NOTE: `file` carries the post-update revn here (`revn`
          ;; above is the pre-update one kept for the response), and
          ;; `modified-at` mirrors `persist-file!`: same `::timestamp`
          ;; fallback, so both agree to the millisecond when the
          ;; request timestamp exists.
          :revn        (if saved? (:revn file) revn)
          :modified-at (or timestamp (ct/now))}}))))

(defn get-file
  "Get not-decoded file, only decodes the features set."
  [cfg id]
  (bfc/get-file cfg id :decode? false :lock-for-share? true))

(defn persist-file!
  "Function responsible of persisting already encoded file. Should be
  used together with `get-file` and `update-file-data!`.

  It also updates the project modified-at attr."
  [{:keys [::db/conn ::timestamp] :as cfg} file]
  (let [;; The timestamp can be nil because this function is also
        ;; intended to be used outside of this module
        modified-at
        (or timestamp (ct/now))

        file
        (-> file
            (dissoc ::snapshot)
            (assoc :modified-at modified-at)
            (assoc :has-media-trimmed false))]

    (db/update! conn :project
                {:modified-at modified-at}
                {:id (:project-id file)}
                {::db/return-keys false})

    (bfc/update-file! cfg file)))

(defn- invalidate-caches!
  [cfg {:keys [id] :as file}]
  (rds/run! cfg (fn [{:keys [::rds/conn]}]
                  (let [key (files/file-summary-cache-key id)]
                    (rds/del conn key)))))

(defn- attach-snapshot
  "Attaches to `file` the snapshot of its state before the changes. The
  snapshot keeps `data`, the data as stored, except for a migrated file
  (the migrated data) or one with pointers (the data with its pointers
  realized). Stored data comes with `::objects-map`."
  [cfg migrated? data file]
  (let [snapshot (cond
                   migrated?
                   file

                   (seq (fdata/get-used-pointer-ids (:data file)))
                   (fdata/realize-pointers cfg file)

                   :else
                   (assoc file
                          :data data
                          ::objects-map (boolean (fdata/has-objects-map? (:data file)))))]
    (assoc file ::snapshot snapshot)))

(defn- update-file-data!
  "Perform a file data transformation in with all update context setup.

  This function expected not-decoded file and transformation function. Returns
  an encoded file.

  This function is not responsible of saving the file. It only saves
  fdata/pointer-map modified fragments."

  [cfg {:keys [id data] :as file} update-fn & args]
  (let [file (update file :data (fn [data]
                                  (-> data
                                      (blob/decode)
                                      (assoc :id id))))
        libs (delay (bfc/get-resolved-file-libraries cfg file))

        need-migration?
        (fmg/need-migration? file)

        take-snapshot?
        (take-snapshot? file)

        ;; For avoid unnecesary overhead of creating multiple
        ;; pointers and handly internally with objects map in their
        ;; worst case (when probably all shapes and all pointers
        ;; will be readed in any case), we just realize/resolve them
        ;; before applying the migration to the file
        file
        (cond-> file
          ;; need-migration?
          ;; (->> (fdata/realize cfg))

          need-migration?
          (fmg/migrate-file libs)

          take-snapshot?
          (->> (attach-snapshot cfg need-migration? data)))]

    (apply update-fn cfg file args)))

(defn- soft-validate-file-schema!
  [file changes]
  (try
    (val/validate-file-schema-affected! file changes)
    (catch Throwable cause
      (l/error :hint "file schema validation error" :cause cause))))

(defn- record-validation-timing
  [cfg step-key step-label elapsed-ms]
  (when-let [metrics (::mtx/metrics cfg)]
    (when (mtx/metrics? metrics)
      (mtx/run! metrics {:id :update-file-validation-timing
                         :labels [step-label]
                         :val (double elapsed-ms)})))
  (when-let [timings (::validation-timings cfg)]
    (swap! timings assoc step-key elapsed-ms)))

(defn- soft-validate-file!
  [file libs changes]
  (try
    (val/validate-file-affected! file libs changes)
    (catch Throwable cause
      (l/error :hint "file validation error"
               :cause cause))))

(defmacro ^:private with-validation-timing
  "Runs `body` and records its time as the validation step `step-key`
  (`step-label` in the metric), also when it throws."
  [cfg step-key step-label & body]
  `(let [t# (ct/tpoint)]
     (try
       ~@body
       (finally
         (record-validation-timing ~cfg ~step-key ~step-label (inst-ms (t#)))))))

(defn- validate-file
  "Validates the entities `changes` affect and the schema of the data they
  modify."
  [cfg file libs changes skip-validate]
  (binding [pmap/*tracked* nil]
    (when (contains? cf/flags :soft-file-validation)
      (with-validation-timing cfg :soft-validate-affected-ms "soft-validate-affected"
        (soft-validate-file! file libs changes)))

    (when (contains? cf/flags :soft-file-schema-validation)
      (with-validation-timing cfg :soft-validate-schema-ms "soft-validate-schema"
        (soft-validate-file-schema! file changes)))

    (when (and (contains? cf/flags :file-validation)
               (not skip-validate))
      (with-validation-timing cfg :validate-affected-ms "validate-affected"
        (val/validate-file-affected! file libs changes)))

    (when (and (contains? cf/flags :file-schema-validation)
               (not skip-validate))
      (with-validation-timing cfg :validate-schema-ms "validate-schema"
        (val/validate-file-schema-affected! file changes)))))

(def ^:private debug-fault-prefix
  "Start of the plugin data key that asks for a debug save fault."
  "save-fault:")

(defn- get-debug-fault
  "Name of the fault a debug marker in `changes` asks for, or nil. A marker
  is a `:set-plugin-data` of the file in the `:penpot-debug` namespace with
  no value, so applying it changes nothing."
  [changes]
  (some (fn [{:keys [type namespace key]}]
          (when (and (= :set-plugin-data type)
                     (= :penpot-debug namespace)
                     (str/starts-with? key debug-fault-prefix))
            (subs key (count debug-fault-prefix))))
        changes))

(defn- raise-debug-fault
  "Throws the error named `fault`, standing in for a real failure while the
  changes are applied."
  [fault]
  (let [message "debug save fault"]
    (case fault
      "validation"     (ex/raise :type :validation :code :debug-fault :hint message)
      "runtime"        (throw (RuntimeException. message))
      "ex-info"        (throw (ex-info message {}))
      "assertion"      (throw (AssertionError. message))
      "sql"            (throw (java.sql.SQLException. message))
      "io"             (throw (java.io.IOException. message))
      "interrupted"    (throw (InterruptedException. message))
      "not-found"      (ex/raise :type :not-found :code :object-not-found :hint message)
      "stack-overflow" (throw (StackOverflowError. message))
      (ex/raise :type :validation :code :unknown-debug-fault :hint fault))))

(defn- apply-changes
  "Applies and validates `changes`. With the `debug-save-faults` flag, a
  debug marker in them makes this fail with the error it names."
  [cfg file libs changes skip-validate]
  (when (contains? cf/flags :debug-save-faults)
    (some-> (get-debug-fault changes) (raise-debug-fault)))
  (let [file (with-validation-timing cfg :process-changes-ms "process-changes"
               (update file :data (fn [data]
                                    (-> data
                                        (cpc/process-changes changes)
                                        (d/without-nils)))))]
    (validate-file cfg file libs changes skip-validate)
    file))

(defn- decode-objects
  "Decodes the objects of the pages `changes` touch. Every file derived from
  `file` shares them, so each attempt does not decode them again."
  [file changes]
  (doseq [page-id (into #{} (keep :page-id) changes)]
    (let [objects (get-in file [:data :pages-index page-id :objects])]
      (doseq [id (keys objects)]
        (get objects id)))))

(defn- infrastructure-error?
  [cause]
  (some (fn [cause]
          (or (instance? java.sql.SQLException cause)
              (instance? java.io.IOException cause)
              (instance? InterruptedException cause)
              ;; A file fragment that cannot be loaded
              (= :not-found (:type (ex-data cause)))))
        (take-while some? (iterate ex-cause cause))))

(defn- change-failure?
  "True when `cause` comes from applying or validating changes. Only these
  reject a commit; any other error fails the whole request."
  [cause]
  (and (or (instance? Exception cause)
           (instance? AssertionError cause))
       (not (infrastructure-error? cause))))

(defn- rejection
  "Describes `commits` as rejected, the first one failing with `cause`."
  [commits cause]
  (let [data (ex-data cause)]
    {:commit-ids       (mapv :commit-id commits)
     :failed-commit-id (:commit-id (first commits))
     ;; An ex-info without a type reports like any unexpected exception
     :error            (if (:type data)
                         (cond-> {:type (:type data)
                                  :code (:code data)
                                  :hint (or (:hint data) (ex-message cause))}
                           (:details data)
                           (assoc :details (mapv #(dissoc % :shape) (:details data))))
                         {:type :internal
                          :code :unexpected-error
                          :hint (or (ex-message cause) (.getName (class cause)))})}))

(defn- apply-prefix
  "Applies and validates the first `n` commits of `attempt`, starting from
  its `:file` and its `:tracked` pointers. Returns the resulting `:file`,
  with the pointers to persist in `:tracked` and the media refs in `:refs`,
  or the cause of the failure."
  [{:keys [cfg file libs commits skip-validate state tracked]} n]
  (reset! pmap/*tracked* tracked)
  (reset! state {})
  (let [changes (into [] (comp (take n) (mapcat :changes)) commits)]
    (try
      (let [file (apply-changes cfg file libs changes skip-validate)]
        {:file    (assoc file ::changes changes)
         :tracked @pmap/*tracked*
         :refs    @state})
      (catch Throwable cause
        (when-not (change-failure? cause)
          (throw cause))
        cause))))

(defn- apply-commits
  "Keeps the longest prefix of the `:commits` of `attempt` that applies and
  validates, found by bisection, and rejects the rest. `cause` is the
  failure of all of them together. Takes about log2 of the commits
  attempts; a commit that repairs an earlier one can keep both. Leaves the
  pointers and media refs of the kept prefix in place and returns its file,
  with the accepted changes in `::changes` and the rejection in
  `::rejected`. The whole search is timed as the `retry` step of `cfg`."
  [cfg {:keys [file commits state tracked] :as attempt} cause]
  (let [tpoint (ct/tpoint)]
    (with-validation-timing cfg :retry-ms "retry"
      (loop [lo       0
             hi       (count commits)
             cause    cause
             attempts 0
             accepted nil]
        (if (> (- hi lo) 1)
          (let [mid    (quot (+ lo hi) 2)
                result (apply-prefix attempt mid)]
            (if (instance? Throwable result)
              (recur lo mid result (inc attempts) accepted)
              (recur mid hi cause (inc attempts) result)))
          (let [rejected (subvec commits lo)]
            (reset! pmap/*tracked* (:tracked accepted tracked))
            (reset! state (:refs accepted {}))
            (l/error :hint "update-file commit rejected"
                     :file-id (str (:id file))
                     :commit-id (str (:commit-id (first rejected)))
                     :rejected (count rejected)
                     :commits (count commits)
                     :attempts attempts
                     :elapsed (ct/format-duration (tpoint))
                     :cause cause)
            (assoc (:file accepted (assoc file ::changes []))
                   ::rejected (rejection rejected cause))))))))

(defn- process-changes-and-validate
  "Applies and validates all `changes` at once. If that fails and there are
  `commits`, keeps the longest prefix of them that validates."
  [cfg file changes commits skip-validate]
  (let [should-resolve-libs?
        (and (or (contains? cf/flags :file-validation)
                 (contains? cf/flags :soft-file-validation))
             (not skip-validate))

        libs
        (when should-resolve-libs?
          (with-validation-timing cfg :resolve-libs-ms "resolve-libs"
            (bfc/get-resolved-file-libraries cfg file)))

        ;; The main purpose of this atom is provide a contextual state
        ;; for the changes subsystem where optionally some hints can
        ;; be provided for the changes processing. Right now we are
        ;; using it for notify about the existence of media refs when
        ;; a new shape is added.
        state
        (atom {})

        ;; True when validation reads every object of the touched pages
        schema-check?
        (or (contains? cf/flags :soft-file-schema-validation)
            (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate)))

        file
        (-> (files/check-version! file)
            (update :revn inc))

        tracked
        @pmap/*tracked*

        file
        (binding [cpc/*state* state]
          ;; Decoded on `file`, the objects the schema check reads are
          ;; shared by every retry
          (when (and (next commits) schema-check?)
            (decode-objects file changes))
          (try
            (-> (apply-changes cfg file libs changes skip-validate)
                (assoc ::changes changes))
            (catch Throwable cause
              (when-not (and (some? commits)
                             (change-failure? cause))
                (throw cause))
              (apply-commits cfg
                             {;; The retries record no step timings
                              :cfg           (dissoc cfg ::mtx/metrics ::validation-timings)
                              :file          file
                              :libs          libs
                              :commits       (vec commits)
                              :skip-validate skip-validate
                              :state         state
                              :tracked       tracked}
                             cause))))]

    (if-let [media-refs (-> @state :media-refs not-empty)]
      (bfc/update-media-references! cfg file media-refs)
      file)))

(defn- take-snapshot?
  "Defines the rule when file `data` snapshot should be saved."
  [{:keys [revn modified-at] :as file}]
  (when (contains? cf/flags :auto-file-snapshot)
    (let [freq    (or (cf/get :auto-file-snapshot-every) 20)
          timeout (or (cf/get :auto-file-snapshot-timeout)
                      (ct/duration {:hours 1}))]

      (or (= 1 freq)
          (zero? (mod revn freq))
          (> (inst-ms (ct/diff modified-at (ct/now)))
             (inst-ms timeout))))))

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
       (filter :changes)
       (mapv (fn [row]
               (update row :changes blob/decode)))))

(defn- send-notifications!
  [cfg {:keys [team changes session-id] :as params} file]
  (let [lchanges (filter library-change? changes)
        msgbus   (::mbus/msgbus cfg)]

    (mbus/pub! msgbus
               :topic (:id file)
               :message {:type :file-change
                         :profile-id (:profile-id params)
                         :file-id (:id file)
                         :session-id (:session-id params)
                         :revn (:revn file)
                         :vern (:vern file)
                         :changes changes})

    (when (and (:is-shared file) (seq lchanges))
      (mbus/pub! msgbus
                 :topic (:id team)
                 :message {:type :library-change
                           :profile-id (:profile-id params)
                           :file-id (:id file)
                           :session-id session-id
                           :revn (:revn file)
                           :modified-at (ct/now)
                           :changes lchanges}))))
