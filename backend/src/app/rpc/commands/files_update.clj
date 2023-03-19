;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-update
  (:require
   [app.common.exceptions :as ex]
   [app.common.files.features :as ffeat]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- SPECS

(s/def ::changes
  (s/coll-of map? :kind vector?))

(s/def ::hint-origin ::us/keyword)
(s/def ::hint-events
  (s/every ::us/keyword :kind vector?))

(s/def ::change-with-metadata
  (s/keys :req-un [::changes]
          :opt-un [::hint-origin
                   ::hint-events]))

(s/def ::changes-with-metadata
  (s/every ::change-with-metadata :kind vector?))

(s/def ::session-id ::us/uuid)
(s/def ::revn ::us/integer)
(s/def ::update-file
  (s/and
   (s/keys :req [::rpc/profile-id]
           :req-un [::files/id ::session-id ::revn]
           :opt-un [::changes ::changes-with-metadata ::features])
   (fn [o]
     (or (contains? o :changes)
         (contains? o :changes-with-metadata)))))

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
      (and (contains? file-change-types type)
           (some? (:component-id change)))))

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
              ffeat/*wrap-with-pointer-map-fn* pmap/wrap]
      (let [result (f cfg file)]
        (files/persist-pointers! conn id)
        result))))

(defn- wrap-with-objects-map-context
  [f]
  (fn [cfg file]
    (binding [ffeat/*wrap-with-objects-map-fn* omap/wrap]
      (f cfg file))))

(declare get-lagged-changes)
(declare send-notifications!)
(declare update-file)
(declare update-file*)
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
   ::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)
    (db/xact-lock! conn id)
    (let [cfg    (assoc cfg ::db/conn conn)
          params (assoc params :profile-id profile-id)
          tpoint (dt/tpoint)]
      (-> (update-file cfg params)
          (rph/with-defer #(let [elapsed (tpoint)]
                             (l/trace :hint "update-file" :time (dt/format-duration elapsed))))))))

(defn update-file
  [{:keys [::db/conn ::mtx/metrics] :as cfg} {:keys [profile-id id changes changes-with-metadata] :as params}]
  (let [file     (get-file conn id)
        features (->> (concat (:features file)
                              (:features params))
                      (into files/default-features)
                      (files/check-features-compatibility!))]

    (files/check-edition-permissions! conn profile-id (:id file))

    (binding [ffeat/*current*  features
              ffeat/*previous* (:features file)]

      (let [update-fn (cond-> update-file*
                        (contains? features "storage/pointer-map")
                        (wrap-with-pointer-map-context)

                        (contains? features "storage/objects-map")
                        (wrap-with-objects-map-context))

            file      (assoc file :features features)
            changes   (if changes-with-metadata
                        (->> changes-with-metadata (mapcat :changes) vec)
                        (vec changes))

            params    (-> params
                          (assoc :file file)
                          (assoc :changes changes)
                          (assoc ::created-at (dt/now)))]

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

        (-> (update-fn cfg params)
            (vary-meta assoc ::audit/replace-props
                       {:id         (:id file)
                        :name       (:name file)
                        :features   (:features file)
                        :project-id (:project-id file)
                        :team-id    (:team-id file)}))))))

(defn- update-file-data
  [file changes]
  (-> file
      (update :revn inc)
      (update :data (fn [data]
                      (cond-> data
                        :always
                        (-> (blob/decode)
                            (assoc :id (:id file))
                            (pmg/migrate-data))

                        (and (contains? ffeat/*current* "components/v2")
                             (not (contains? ffeat/*previous* "components/v2")))
                        (ctf/migrate-to-components-v2)

                        :always
                        (-> (cp/process-changes changes)
                            (blob/encode)))))))


(defn- update-file*
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file changes session-id ::created-at] :as params}]
  (let [;; Process the file data in the CLIMIT context; scheduling it
        ;; to be executed on a separated executor for avoid to do the
        ;; CPU intensive operation on vthread.
        file (-> (climit/configure cfg :update-file)
                 (climit/submit! (partial update-file-data file changes)))]

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
  [{:keys [::db/conn] :as cfg} {:keys [file changes session-id] :as params}]
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
      (let [team-id (or (:team-id file)
                        (files/get-team-id conn (:project-id file)))]
        (mbus/pub! msgbus
                   :topic team-id
                   :message {:type :library-change
                             :profile-id (:profile-id params)
                             :file-id (:id file)
                             :session-id session-id
                             :revn (:revn file)
                             :modified-at (dt/now)
                             :changes lchanges})))))
