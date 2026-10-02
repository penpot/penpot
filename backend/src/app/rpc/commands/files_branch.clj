;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.files-branch
  "Design branches: identity-preserving copies of a file that merge
  back into it with a three-way merge."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.files.merge :as fmerge]
   [app.common.files.validate :as fval]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.management :as management]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(defn- decode-branch
  [row]
  (some-> row (update :id-index db/decode-transit-pgobject)))

(defn- get-branch-by-file
  [conn file-id]
  (decode-branch (db/get* conn :file-branch {:file-id file-id})))

(def ^:private sql:get-core-branches
  "SELECT fb.id, fb.created_at, fb.modified_at, fb.merged_at, fb.core_file_id,
          fb.file_id, fb.profile_id, fb.name, fb.status, fb.base_snapshot_id,
          fb.base_revn
     FROM file_branch AS fb
     JOIN file AS f ON (f.id = fb.file_id)
    WHERE fb.core_file_id = ?
      AND f.deleted_at IS NULL
    ORDER BY fb.created_at DESC")

(defn- get-core-branches
  [conn core-file-id]
  (db/exec! conn [sql:get-core-branches core-file-id]))

;; --- COMMAND: create-file-branch

(def ^:private schema:create-file-branch
  [:map {:title "create-file-branch"}
   [:file-id ::sm/uuid]
   [:name [:string {:min 1 :max 120}]]])

(sv/defmethod ::create-file-branch
  "Create a branch of a file. The branch keeps every shape, component
  and asset id of the core file."
  {::doc/added "2.19"
   ::sm/params schema:create-file-branch
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id name]}]
  (files/check-read-permissions! conn profile-id file-id)

  (let [core (bfc/get-file cfg file-id :realize? true)]
    (when (get-branch-by-file conn file-id)
      (ex/raise :type :validation
                :code :file-is-branch
                :hint "a branch can not be branched"))

    (let [snapshot  (fsnap/create! cfg core
                                   {:label (str "branch/" name)
                                    :profile-id profile-id
                                    :created-by "user"})
          branch-id (uuid/next)
          state     (volatile! {:index {file-id branch-id}})]

      (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
      (binding [bfc/*state* state]
        (management/duplicate-file (assoc cfg ::bfc/timestamp (ct/now))
                                   {:profile-id profile-id
                                    :file-id file-id
                                    :name (str (:name core) " / " name)
                                    :reset-shared-flag true}))

      (let [id-index (into {} (map (fn [[old new]] [new old])) (:index @state))]
        (-> (db/insert! conn :file-branch
                        {:id (uuid/next)
                         :core-file-id file-id
                         :file-id branch-id
                         :profile-id profile-id
                         :name name
                         :base-snapshot-id (:id snapshot)
                         :base-revn (:revn core)
                         :id-index (db/tjson id-index)})
            (dissoc :id-index)
            (assoc :project-id (:project-id core)))))))

;; --- COMMAND QUERY: get-file-branches

(def ^:private schema:get-file-branches
  [:map {:title "get-file-branches"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-branches
  "Return the core file of `file-id` (itself when it is not a branch),
  every branch of that core file, and the branch row when `file-id` is
  a branch."
  {::doc/added "2.19"
   ::sm/params schema:get-file-branches}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-read-permissions! conn profile-id file-id)
                 (let [branch  (get-branch-by-file conn file-id)
                       core-id (or (:core-file-id branch) file-id)]
                   {:branch   (some-> branch (dissoc :id-index))
                    :core     (-> (db/get conn :file {:id core-id})
                                  (select-keys [:id :name :project-id :is-shared]))
                    :branches (get-core-branches conn core-id)}))))

;; --- Merge

(defn- relink-to-core
  "Map the ids the branch copy remapped back to the core ids."
  [cfg branch-file {:keys [core-file-id id-index]}]
  (binding [bfc/*state* (volatile! {:index id-index})]
    (:data (bfc/process-file cfg (assoc branch-file :id core-file-id)))))

(defn- compute-merge
  [cfg {:keys [core-file-id base-snapshot-id] :as branch} opts]
  (let [core     (bfc/get-file cfg core-file-id :realize? true)
        snapshot (fsnap/get-snapshot cfg core-file-id base-snapshot-id)
        base     (:data (fdata/realize cfg (assoc snapshot :id core-file-id)))
        theirs   (relink-to-core cfg (bfc/get-file cfg (:file-id branch) :realize? true) branch)
        result   (fmerge/merge-file-data base (:data core) theirs opts)]
    (assoc result :core core)))

(defn- get-branch-for-merge
  [conn file-id]
  (let [branch (get-branch-by-file conn file-id)]
    (when-not branch
      (ex/raise :type :not-found
                :code :branch-not-found
                :hint "the file is not a branch"))
    branch))

(def ^:private schema:get-file-branch-diff
  [:map {:title "get-file-branch-diff"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-branch-diff
  "Compute what the branch changed since it was created and the
  conflicts with the current core file. Changes nothing."
  {::doc/added "2.19"
   ::sm/params schema:get-file-branch-diff}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (files/check-read-permissions! conn profile-id file-id)
                 (let [branch (get-branch-for-merge conn file-id)
                       _      (files/check-read-permissions! conn profile-id (:core-file-id branch))
                       result (compute-merge cfg branch nil)]
                   {:branch    (dissoc branch :id-index)
                    :core-revn (-> result :core :revn)
                    :changes   (:changes result)
                    :conflicts (:conflicts result)}))))

;; --- COMMAND: merge-file-branch

(def ^:private schema:merge-file-branch
  [:map {:title "merge-file-branch"}
   [:file-id ::sm/uuid]
   [:resolutions {:optional true} [:map-of :string [:enum "ours" "theirs"]]]
   [:excluded {:optional true} [::sm/set :string]]])

(sv/defmethod ::merge-file-branch
  "Merge a branch into its core file. The core file is snapshotted
  first, so the merge can be reverted from the version history."
  {::doc/added "2.19"
   ::sm/params schema:merge-file-branch}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id ::rpc/session-id file-id resolutions excluded]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [branch      (get-branch-for-merge conn file-id)
                      core-id     (:core-file-id branch)
                      _           (files/check-edition-permissions! conn profile-id core-id)
                      _           (bfc/get-minimal-file conn core-id {::db/for-update true})
                      resolutions (update-vals resolutions keyword)
                      {:keys [core data conflicts changes]}
                      (compute-merge cfg branch {:resolutions resolutions :excluded excluded})
                      unresolved  (remove #(contains? resolutions (:id %)) conflicts)]

                  (when (not= "open" (:status branch))
                    (ex/raise :type :validation
                              :code :branch-not-open
                              :hint "the branch is already merged"))

                  (when (every? #(contains? excluded (:key %)) changes)
                    (ex/raise :type :validation
                              :code :nothing-to-merge
                              :hint "select at least one change to merge"))

                  (when (seq unresolved)
                    (ex/raise :type :validation
                              :code :unresolved-conflicts
                              :hint "every conflict needs a resolution"
                              :conflicts (mapv :id unresolved)))

                  (let [file (-> core
                                 (assoc :data data)
                                 (update :revn inc)
                                 (assoc :vern (rand-int Integer/MAX_VALUE))
                                 (assoc :modified-at (ct/now)))]

                    (fval/validate-file-schema! file)
                    (fval/validate-file! file (bfc/get-resolved-file-libraries cfg file))

                    (fsnap/create! cfg core
                                   {:label (str "before-merge/" (:name branch))
                                    :profile-id profile-id
                                    :created-by "user"})

                    (bfc/update-file! cfg file)

                    (db/update! conn :file-branch
                                {:status "merged"
                                 :merged-at (ct/now)
                                 :modified-at (ct/now)}
                                {:id (:id branch)})

                    (mbus/pub! msgbus
                               :topic core-id
                               :message {:type :file-restored
                                         :session-id session-id
                                         :file-id core-id
                                         :vern (:vern file)})

                    {:file-id core-id
                     :project-id (:project-id core)
                     :revn (:revn file)})))))

;; --- COMMAND: delete-file-branch

(def ^:private schema:delete-file-branch
  [:map {:title "delete-file-branch"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::delete-file-branch
  "Delete a branch, open or merged: the branch file goes through the
  normal file deletion and its base snapshot is removed from the core
  file history."
  {::doc/added "2.19"
   ::sm/params schema:delete-file-branch
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (let [branch (get-branch-for-merge conn file-id)]
    (files/delete-file cfg {:profile-id profile-id :id file-id})
    (fsnap/delete! cfg
                   :id (:base-snapshot-id branch)
                   :file-id (:core-file-id branch)
                   :deleted-at (ct/now))
    (db/delete! conn :file-branch {:id (:id branch)})
    {:core-file-id (:core-file-id branch)}))
