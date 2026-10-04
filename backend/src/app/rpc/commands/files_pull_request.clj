;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-pull-request
  "RPC commands for pull requests on top of file branching: a review
  request over a pinned snapshot of a branch. A pull request does NOT
  create a new file; the review sandbox is a virtual read-only view of
  the branch served from the pinned snapshot (`get-pull-request-bundle`),
  so it disappears when the pull request is closed and the snapshot pin
  is released. Merging stays on `app.rpc.commands.files-branch`; approving
  a pull request is informative and never a gate for the merge."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as-alias cfeat]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-branch :as fbranch]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [clojure.string :as str]))

(defn check-pull-requests-enabled!
  "Guard the pull request commands behind the `:branching` product flag
  (pull requests are part of the branching feature). Public: the viewer
  bundle command guards its pr-id variant with it too."
  []
  (when-not (contains? cf/flags :branching)
    (ex/raise :type :restriction
              :code :branching-disabled
              :hint "the branching feature is not enabled on this instance")))

;; --- Helpers

(defn- get-pull-request*
  "Fetch a pull request row or raise :not-found."
  [cfg id]
  (let [pr (db/get* cfg :file-pull-request {:id id})]
    (when (or (nil? pr) (some? (:deleted-at pr)))
      (ex/raise :type :not-found
                :code :pull-request-not-found
                :hint "unable to find pull request with the provided id"
                :pull-request-id id))
    pr))

(defn- check-author-or-admin!
  "Managing a pull request (edit metadata, reviewers, close, reopen) is
  allowed to its author and to team admins/owners. Raises the standard
  not-found on failure so object existence is not leaked."
  [cfg profile-id {:keys [created-by target-file-id]}]
  (let [perms (perms/get-file-read-permissions cfg profile-id target-file-id)]
    (files/check-read-permissions! perms)
    (when-not (or (= created-by profile-id)
                  (:is-admin perms))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))))

(defn- release-review-snapshot!
  "Reschedule a pull request's pinned review snapshot for normal deletion:
  it is pinned ~10 years while the pull request is open and must be
  released when the pull request is closed, merged or its snapshot is
  repositioned. The snapshot belongs to the BRANCH file (the pull
  request's source)."
  [cfg {:keys [review-snapshot-id source-file-id]} deleted-at]
  (when review-snapshot-id
    (fsnap/delete! cfg
                   :id review-snapshot-id
                   :file-id source-file-id
                   :deleted-at deleted-at)))

(defn- pin-review-snapshot!
  "Materialize the review snapshot of the branch file: what reviewers see
  in the sandbox, pinned so the snapshot GC does not prune it while the
  pull request is open."
  [cfg branch-file title profile-id]
  (fsnap/create! cfg branch-file
                 {:label (str "pr-review/" title)
                  :profile-id profile-id
                  :created-by "system"
                  :deleted-at (ct/in-future {:days 3650})}))

(defn- validate-reviewers!
  "Check every reviewer id is a member of `team-id`; the author is
  silently dropped (requesting a review from yourself is meaningless).
  Returns the cleaned vector."
  [cfg team-id author-id reviewers]
  (let [reviewers (into [] (comp (distinct) (remove #(= author-id %))) reviewers)
        members   (into #{} (map :id) (teams/get-team-members cfg team-id))
        invalid   (remove members reviewers)]
    (when (seq invalid)
      (ex/raise :type :validation
                :code :reviewer-not-team-member
                :hint "all reviewers must be members of the team"
                :profile-ids (vec invalid)))
    reviewers))

(def ^:private sql:get-reviews
  "SELECT r.pull_request_id,
          r.profile_id,
          r.state,
          r.reviewed_revn,
          r.comment,
          r.updated_at
     FROM file_pull_request_review AS r
     JOIN profile AS p ON (p.id = r.profile_id)
    WHERE r.pull_request_id = ANY(?)
    ORDER BY r.created_at ASC")

(defn- get-reviews-map
  "{pull-request-id -> [review ...]} for the given pull request ids, each
  review annotated with `:stale` (verdict submitted over a previous
  review snapshot)."
  [cfg prs]
  (if (empty? prs)
    {}
    (let [ids  (db/create-array (db/get-connection cfg) "uuid" (mapv :id prs))
          revn (into {} (map (juxt :id :review-revn)) prs)]
      (->> (db/exec! cfg [sql:get-reviews ids])
           (mapv (fn [{:keys [pull-request-id state reviewed-revn] :as row}]
                   (assoc row :stale (and (not= "pending" state)
                                          (not= reviewed-revn (get revn pull-request-id))))))
           (group-by :pull-request-id)))))

(defn- review-state
  "Aggregate verdict of a pull request: `changes-requested` beats
  `approved`; stale verdicts (submitted over a previous snapshot) only
  count as `in-review`; no verdict at all is `pending`."
  [reviews]
  (let [submitted (remove #(= "pending" (:state %)) reviews)
        current   (remove :stale submitted)]
    (cond
      (some #(= "changes-requested" (:state %)) current) "changes-requested"
      (some #(= "approved" (:state %)) current)          "approved"
      (seq submitted)                                    "in-review"
      :else                                              "pending")))

(defn- decorate-pull-request
  "Attach the derived attributes the UI needs: reviews (+ aggregate
  `review-state`), `outdated` (the branch moved past the review snapshot,
  cheap revn gate) and entity-level ahead/behind/conflicts counts against
  main (only for open pull requests, gated like the branches listing)."
  [cfg main-data reviews-map {:keys [id status source-file-id target-file-id
                                     base-snapshot-id review-revn branch-revn]
                              :as row}]
  (let [reviews (get reviews-map id [])
        open?   (= "open" status)
        [ahead-revn behind-revn] (fbranch/revn-deltas row)
        [ahead behind conflicts]
        (if open?
          (fbranch/branch-diff-counts cfg main-data
                                      {:id id
                                       :source-file-id target-file-id
                                       :branch-file-id source-file-id
                                       :base-snapshot-id base-snapshot-id
                                       :ahead-revn ahead-revn
                                       :behind-revn behind-revn})
          [0 0 0])]
    (-> row
        (assoc :reviews (mapv #(dissoc % :pull-request-id) reviews))
        (assoc :review-state (review-state reviews))
        (assoc :outdated (and open? (> branch-revn review-revn)))
        (assoc :ahead ahead :behind behind :conflicts conflicts)
        (dissoc :branch-revn :source-revn :base-snapshot-id
                :base-revn :base-branch-revn :review-snapshot-id))))

(def ^:private sql:get-open-pull-request-for-branch
  "SELECT id FROM file_pull_request
    WHERE file_branch_id = ?
      AND status = 'open'
      AND deleted_at IS NULL")

;; --- Helpers: reviewer notification emails

(defn- format-pull-request-url
  "Deep link into the review sandbox: the branch file's workspace with the
  pr-id param."
  [{:keys [id source-file-id]} team-id]
  (str/join [(cf/get :public-uri)
             "/#/workspace?"
             (uri/map->query-string {:file-id source-file-id
                                     :team-id team-id
                                     :pr-id id})]))

(defn- send-review-request-emails!
  "Notify `reviewers` (profile ids) that `actor-id` requested their review
  on the pull request. Follows the same in-transaction outbox pattern as
  the comment emails."
  [conn {:keys [title description] :as pr} {:keys [team-id actor-id branch-name target-name reviewers]}]
  (when (seq reviewers)
    (let [users (->> (teams/get-users+props conn team-id)
                     (map profile/decode-row)
                     (d/index-by :id))
          actor (get users actor-id)
          route (str/join [branch-name " → " target-name])
          url   (format-pull-request-url pr team-id)]
      (doseq [reviewer-id reviewers]
        (when-let [{:keys [fullname email]} (get users reviewer-id)]
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/review-request
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (get actor :fullname "")
            :pull-request-title title
            :pull-request-route route
            :pull-request-description (or description "")
            :pull-request-url url}))))))

;; --- COMMAND: create-pull-request

(def ^:private schema:create-pull-request
  [:map {:title "create-pull-request"}
   [:branch-id ::sm/uuid]
   [:title [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]
   [:reviewers {:optional true} [:vector ::sm/uuid]]])

(sv/defmethod ::create-pull-request
  "Create a pull request from an open branch towards its source file
  (main). Pins a review snapshot of the branch (the state reviewers see)
  and registers the requested reviewers, who must be members of the
  team. At most one open pull request may exist per branch."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-pull-request
   ::climit/id [[:create-pull-request/by-profile ::rpc/profile-id]
                [:create-pull-request/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id branch-id title description reviewers]}]
  (check-pull-requests-enabled!)
  (when (str/blank? title)
    (ex/raise :type :validation
              :code :invalid-pull-request-title
              :hint "pull request title cannot be blank"))
  (let [branch (db/get* cfg :file-branch {:id branch-id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found
                :code :branch-not-found
                :branch-id branch-id))
    (when (not= "open" (:status branch))
      (ex/raise :type :validation
                :code :branch-not-open
                :branch-id branch-id))

    (let [branch-file-id (:branch-file-id branch)
          target-id      (:source-file-id branch)]

      ;; Requesting a review is an author-side action: it needs edition
      ;; permissions on the BRANCH file (merging keeps requiring edition
      ;; permissions on main, unchanged). Checked BEFORE validating the
      ;; reviewers so team membership cannot be probed without access.
      (files/check-edition-permissions! cfg profile-id branch-file-id)

      (let [target-row (db/get-by-id cfg :file target-id)
            project    (db/get-by-id cfg :project (:project-id target-row))
            team-id    (:team-id project)
            reviewers  (validate-reviewers! cfg team-id profile-id (or reviewers []))]

        (-> cfg
            (assoc ::quotes/profile-id profile-id)
            (assoc ::quotes/team-id team-id)
            (quotes/check! {::quotes/id ::quotes/pull-requests-per-team}))

        (db/tx-run!
         cfg
         (fn [{:keys [::db/conn] :as cfg}]
           ;; Serialize against concurrent update-file on the branch so
           ;; the review snapshot captures a consistent state (same
           ;; advisory lock the save path takes), and against a
           ;; concurrent create-pull-request on the same branch.
           (db/xact-lock! conn branch-file-id)

           (when (db/exec-one! conn [sql:get-open-pull-request-for-branch branch-id])
             (ex/raise :type :validation
                       :code :pull-request-already-exists
                       :hint "the branch already has an open pull request"
                       :branch-id branch-id))

           (let [branch-file (bfc/get-file cfg branch-file-id :realize? true)
                 snapshot    (pin-review-snapshot! cfg branch-file title profile-id)
                 pr-id       (uuid/next)
                 ts          (ct/now)]

             (db/insert! conn :file-pull-request
                         {:id pr-id
                          :file-branch-id branch-id
                          :source-file-id branch-file-id
                          :target-file-id target-id
                          :title title
                          :description description
                          :created-by profile-id
                          :status "open"
                          :review-snapshot-id (:id snapshot)
                          :review-revn (:revn branch-file)
                          :review-updated-at ts}
                         {::db/return-keys false})

             (doseq [reviewer-id reviewers]
               (db/insert! conn :file-pull-request-review
                           {:pull-request-id pr-id
                            :profile-id reviewer-id}
                           {::db/return-keys false}))

             (send-review-request-emails! conn
                                          {:id pr-id
                                           :source-file-id branch-file-id
                                           :title title
                                           :description description}
                                          {:team-id team-id
                                           :actor-id profile-id
                                           :branch-name (:name branch)
                                           :target-name (:name target-row)
                                           :reviewers reviewers})

             (mbus/pub! msgbus
                        :topic target-id
                        :message {:type :pull-request-created
                                  :file-id target-id
                                  :pull-request-id pr-id
                                  :profile-id profile-id})

             {:id pr-id
              :file-branch-id branch-id
              :source-file-id branch-file-id
              :target-file-id target-id
              :title title
              :description description
              :status "open"
              :review-revn (:revn branch-file)
              :reviewers reviewers})))))))

;; --- COMMAND QUERY: get-file-pull-requests

(def ^:private schema:get-file-pull-requests
  [:map {:title "get-file-pull-requests"}
   [:file-id ::sm/uuid]
   [:include-closed {:optional true} ::sm/boolean]])

(def ^:private sql:resolve-target-file
  "SELECT source_file_id FROM file_branch
    WHERE branch_file_id = ?
      AND deleted_at IS NULL")

(def ^:private sql:get-file-pull-requests
  "SELECT fpr.id,
          fpr.file_branch_id,
          fpr.source_file_id,
          fpr.target_file_id,
          fpr.title,
          fpr.description,
          fpr.created_by,
          fpr.created_at,
          fpr.updated_at,
          fpr.status,
          fpr.review_snapshot_id,
          fpr.review_revn,
          fpr.review_updated_at,
          fpr.closed_at,
          fpr.closed_by,
          fb.name AS branch_name,
          fb.base_snapshot_id,
          fb.base_revn,
          fb.base_branch_revn,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS target_name
     FROM file_pull_request AS fpr
     JOIN file_branch AS fb ON (fb.id = fpr.file_branch_id)
     JOIN file AS bf ON (bf.id = fpr.source_file_id)
     JOIN file AS sf ON (sf.id = fpr.target_file_id)
    WHERE fpr.target_file_id = ?
      AND fpr.deleted_at IS NULL
      AND (?::boolean OR fpr.status = 'open')
    ORDER BY fpr.created_at DESC")

(sv/defmethod ::get-file-pull-requests
  "List the pull requests of a file. Accepts either the target file
  (main) or a branch file id (it resolves to its source). Every open
  pull request is annotated with its reviews, the aggregate
  `review-state`, the `outdated` flag and the entity-level
  ahead/behind/conflicts counts (gated by the cheap revn deltas, with
  main realized once and shared, like the branches listing)."
  {::doc/added "2.16"
   ::sm/params schema:get-file-pull-requests}
  [cfg {:keys [::rpc/profile-id file-id include-closed]}]
  (check-pull-requests-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [target-id (or (:source-file-id (db/exec-one! conn [sql:resolve-target-file file-id]))
                                 file-id)]
               (files/check-read-permissions! cfg profile-id target-id)
               (let [rows        (db/exec! conn [sql:get-file-pull-requests target-id
                                                 (boolean include-closed)])
                     reviews-map (get-reviews-map cfg rows)
                     open?       (fn [row] (= "open" (:status row)))
                     need?       (some (fn [r] (and (open? r)
                                                    (let [[a b] (fbranch/revn-deltas r)]
                                                      (or (pos? a) (pos? b)))))
                                       rows)
                     main-data   (when need? (:data (bfc/get-file cfg target-id :realize? true)))]
                 (mapv (partial decorate-pull-request cfg main-data reviews-map) rows))))))

;; --- COMMAND QUERY: get-pull-request

(def ^:private schema:get-pull-request
  [:map {:title "get-pull-request"}
   [:id ::sm/uuid]])

(def ^:private sql:get-pull-request
  "SELECT fpr.id,
          fpr.file_branch_id,
          fpr.source_file_id,
          fpr.target_file_id,
          fpr.title,
          fpr.description,
          fpr.created_by,
          fpr.created_at,
          fpr.updated_at,
          fpr.status,
          fpr.review_snapshot_id,
          fpr.review_revn,
          fpr.review_updated_at,
          fpr.closed_at,
          fpr.closed_by,
          fb.name AS branch_name,
          fb.base_snapshot_id,
          fb.base_revn,
          fb.base_branch_revn,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS target_name
     FROM file_pull_request AS fpr
     JOIN file_branch AS fb ON (fb.id = fpr.file_branch_id)
     JOIN file AS bf ON (bf.id = fpr.source_file_id)
     JOIN file AS sf ON (sf.id = fpr.target_file_id)
    WHERE fpr.id = ?
      AND fpr.deleted_at IS NULL")

(sv/defmethod ::get-pull-request
  "Fetch a single pull request with its reviews, aggregate review state
  and diff counts."
  {::doc/added "2.16"
   ::sm/params schema:get-pull-request}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-pull-requests-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [row (db/exec-one! conn [sql:get-pull-request id])]
               (when-not row
                 (ex/raise :type :not-found
                           :code :pull-request-not-found
                           :pull-request-id id))
               (files/check-read-permissions! cfg profile-id (:target-file-id row))
               (decorate-pull-request cfg nil (get-reviews-map cfg [row]) row)))))

;; --- COMMAND QUERY: get-pull-request-bundle

(def ^:private schema:get-pull-request-bundle
  [:map {:title "get-pull-request-bundle"}
   [:id ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-pull-request-bundle
  "Retrieve the review sandbox of an OPEN pull request: the branch file
  bundle with the data of the pinned review snapshot overlaid, served
  with edition permissions stripped so the client always opens it
  read-only. Does not modify any database state. Refuses when the
  snapshot cannot be resolved — silently serving the live branch would
  show reviewers a state nobody asked them to review."
  {::doc/added "2.16"
   ::sm/params schema:get-pull-request-bundle
   ::sm/result files/schema:file-with-permissions
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-pull-requests-enabled!)
  (let [pr (get-pull-request* cfg id)]
    (when (not= "open" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-open
                :hint "the review sandbox only exists while the pull request is open"
                :pull-request-id id))
    (let [perms (perms/get-file-read-permissions cfg profile-id (:target-file-id pr))]
      (files/check-read-permissions! perms)
      (let [snapshot (when (:review-snapshot-id pr)
                       (fsnap/get-snapshot cfg (:source-file-id pr) (:review-snapshot-id pr)))]
        (when-not snapshot
          (ex/raise :type :not-found
                    :code :review-snapshot-missing
                    :hint "the pull request review snapshot cannot be resolved"
                    :pull-request-id id))
        (let [base-file (bfc/get-file cfg (:source-file-id pr) :load-data? false)]
          (-> base-file
              (assoc :data (:data snapshot))
              (assoc :version (:version snapshot))
              (assoc :features (:features snapshot))
              (assoc :revn (:revn snapshot))
              (assoc :vern (rand-int 100000))
              ;; the sandbox is read-only by construction, whatever the
              ;; caller's role on the team is
              (assoc :permissions (assoc perms
                                         :can-edit false
                                         :is-admin false
                                         :is-owner false))))))))

;; --- COMMAND: update-pull-request

(def ^:private schema:update-pull-request
  [:map {:title "update-pull-request"}
   [:id ::sm/uuid]
   [:title {:optional true} [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]
   ;; full replacement of the reviewer set; added reviewers start as
   ;; pending, removed ones lose their row (their comments remain)
   [:reviewers {:optional true} [:vector ::sm/uuid]]])

(sv/defmethod ::update-pull-request
  "Edit a pull request's title, description or reviewer list. Allowed to
  the author and to team admins/owners."
  {::doc/added "2.16"
   ::sm/params schema:update-pull-request}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id id title description reviewers]}]
  (check-pull-requests-enabled!)
  (when (and (some? title) (str/blank? title))
    (ex/raise :type :validation
              :code :invalid-pull-request-title
              :hint "pull request title cannot be blank"))
  (let [pr (get-pull-request* cfg id)]
    (when (not= "open" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-open
                :pull-request-id id))
    (check-author-or-admin! cfg profile-id pr)

    (let [team-id   (perms/get-team-id-for-file cfg (:target-file-id pr))
          reviewers (some->> reviewers
                             (validate-reviewers! cfg team-id (:created-by pr)))]
      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn]}]
         (db/update! conn :file-pull-request
                     (cond-> {:updated-at (ct/now)}
                       (some? title)       (assoc :title title)
                       (some? description) (assoc :description description))
                     {:id id}
                     {::db/return-keys false})

         (when (some? reviewers)
           (let [current (->> (db/query conn :file-pull-request-review
                                        {:pull-request-id id})
                              (into #{} (map :profile-id)))
                 wanted  (set reviewers)
                 added   (vec (remove current wanted))]
             (doseq [reviewer-id added]
               (db/insert! conn :file-pull-request-review
                           {:pull-request-id id
                            :profile-id reviewer-id}
                           {::db/return-keys false}))
             (doseq [reviewer-id (remove wanted current)]
               (db/delete! conn :file-pull-request-review
                           {:pull-request-id id
                            :profile-id reviewer-id}))
             (when (seq added)
               (let [row (db/exec-one! conn [sql:get-pull-request id])]
                 (send-review-request-emails! conn row
                                              {:team-id team-id
                                               :actor-id profile-id
                                               :branch-name (:branch-name row)
                                               :target-name (:target-name row)
                                               :reviewers added})))))

         (mbus/pub! msgbus
                    :topic (:target-file-id pr)
                    :message {:type :pull-request-updated
                              :file-id (:target-file-id pr)
                              :pull-request-id id
                              :profile-id profile-id})

         {:id id
          :title (or title (:title pr))
          :description (or description (:description pr))})))))

;; --- COMMAND: update-pull-request-snapshot

(def ^:private schema:update-pull-request-snapshot
  [:map {:title "update-pull-request-snapshot"}
   [:id ::sm/uuid]])

(sv/defmethod ::update-pull-request-snapshot
  "Publish the branch's current state to the reviewers: reposition the
  review snapshot to the head of the branch (releasing the previous
  pin). Verdicts submitted over the previous snapshot become stale
  (`reviewed-revn` no longer matches) but are kept for the history. It
  is an explicit author action — the sandbox never moves on its own."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:update-pull-request-snapshot
   ::climit/id [[:update-pull-request-snapshot/global]]}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-pull-requests-enabled!)
  (let [pr (get-pull-request* cfg id)]
    (when (not= "open" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-open
                :pull-request-id id))

    ;; publishing the branch state is an author-side action, like
    ;; creating the pull request
    (files/check-edition-permissions! cfg profile-id (:source-file-id pr))

    (db/tx-run!
     cfg
     (fn [{:keys [::db/conn] :as cfg}]
       ;; serialize against concurrent update-file on the branch so the
       ;; new snapshot captures a consistent state
       (db/xact-lock! conn (:source-file-id pr))

       (let [branch-file (bfc/get-file cfg (:source-file-id pr) :realize? true)]
         (if (= (:revn branch-file) (:review-revn pr))
           ;; nothing new to publish: the sandbox already shows this state
           {:id id :review-revn (:review-revn pr) :updated false}

           (let [team     (teams/get-team conn :profile-id profile-id
                                          :file-id (:target-file-id pr))
                 delay    (ldel/get-deletion-delay team)
                 snapshot (pin-review-snapshot! cfg branch-file (:title pr) profile-id)
                 ts       (ct/now)]
             ;; the previous review snapshot is superseded: release its pin
             (release-review-snapshot! cfg pr (ct/in-future delay))
             (db/update! conn :file-pull-request
                         {:review-snapshot-id (:id snapshot)
                          :review-revn (:revn branch-file)
                          :review-updated-at ts
                          :updated-at ts}
                         {:id id}
                         {::db/return-keys false})

             (mbus/pub! msgbus
                        :topic (:target-file-id pr)
                        :message {:type :pull-request-updated
                                  :file-id (:target-file-id pr)
                                  :pull-request-id id
                                  :profile-id profile-id})

             {:id id :review-revn (:revn branch-file) :updated true})))))))

;; --- COMMAND: submit-pull-request-review

(def ^:private schema:submit-pull-request-review
  [:map {:title "submit-pull-request-review"}
   [:id ::sm/uuid]
   [:state [:enum "approved" "changes-requested" "pending"]]
   [:comment {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::submit-pull-request-review
  "Submit (or retract, with `pending`) a verdict on an open pull
  request. Only assigned reviewers may submit; the verdict records the
  review revision it was issued over, so it goes stale when the author
  publishes newer changes. Approval is informative: it never gates the
  merge, which keeps requiring edition permissions on main."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:submit-pull-request-review}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id id state comment]}]
  (check-pull-requests-enabled!)
  (let [pr (get-pull-request* cfg id)]
    (when (not= "open" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-open
                :pull-request-id id))
    (files/check-read-permissions! cfg profile-id (:target-file-id pr))

    (db/tx-run!
     cfg
     (fn [{:keys [::db/conn]}]
       (let [review (db/get* conn :file-pull-request-review
                             {:pull-request-id id :profile-id profile-id})]
         (when-not review
           (ex/raise :type :validation
                     :code :not-a-reviewer
                     :hint "only assigned reviewers can submit a review"
                     :pull-request-id id))

         (db/update! conn :file-pull-request-review
                     {:state state
                      :reviewed-revn (when (not= "pending" state) (:review-revn pr))
                      :comment comment
                      :updated-at (ct/now)}
                     {:pull-request-id id :profile-id profile-id}
                     {::db/return-keys false})

         (mbus/pub! msgbus
                    :topic (:target-file-id pr)
                    :message {:type :pull-request-review-submitted
                              :file-id (:target-file-id pr)
                              :pull-request-id id
                              :profile-id profile-id
                              :state state})

         {:id id
          :profile-id profile-id
          :state state
          :reviewed-revn (:review-revn pr)})))))

;; --- COMMAND: close-pull-request

(def ^:private schema:close-pull-request
  [:map {:title "close-pull-request"}
   [:id ::sm/uuid]])

(sv/defmethod ::close-pull-request
  "Close (discard) an open pull request: the review sandbox stops
  resolving and the pinned review snapshot is released to the normal
  snapshot GC. The branch itself is not touched. Allowed to the author
  and to team admins/owners."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:close-pull-request}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-pull-requests-enabled!)
  (let [pr (get-pull-request* cfg id)]
    (when (not= "open" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-open
                :pull-request-id id))
    (check-author-or-admin! cfg profile-id pr)

    (db/tx-run!
     cfg
     (fn [{:keys [::db/conn] :as cfg}]
       (let [team  (teams/get-team conn :profile-id profile-id
                                   :file-id (:target-file-id pr))
             delay (ldel/get-deletion-delay team)
             ts    (ct/now)]
         (db/update! conn :file-pull-request
                     {:status "closed"
                      :closed-at ts
                      :closed-by profile-id
                      :updated-at ts}
                     {:id id}
                     {::db/return-keys false})
         (release-review-snapshot! cfg pr (ct/in-future delay))

         (mbus/pub! msgbus
                    :topic (:target-file-id pr)
                    :message {:type :pull-request-closed
                              :file-id (:target-file-id pr)
                              :pull-request-id id
                              :profile-id profile-id})

         {:id id :status "closed"})))))

;; --- COMMAND: reopen-pull-request

(def ^:private schema:reopen-pull-request
  [:map {:title "reopen-pull-request"}
   [:id ::sm/uuid]])

(sv/defmethod ::reopen-pull-request
  "Reopen a closed pull request, as long as its branch is still open and
  the branch has no other open pull request. The review snapshot is
  re-pinned at the CURRENT head of the branch (the previous one may
  already be garbage-collected). Allowed to the author and to team
  admins/owners."
  {::doc/added "2.16"
   ::sm/params schema:reopen-pull-request}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-pull-requests-enabled!)
  (let [pr (get-pull-request* cfg id)]
    (when (not= "closed" (:status pr))
      (ex/raise :type :validation
                :code :pull-request-not-closed
                :hint "only closed pull requests can be reopened"
                :pull-request-id id))
    (check-author-or-admin! cfg profile-id pr)

    (let [branch (db/get* cfg :file-branch {:id (:file-branch-id pr)})]
      (when (or (nil? branch)
                (some? (:deleted-at branch))
                (not= "open" (:status branch)))
        (ex/raise :type :validation
                  :code :branch-not-open
                  :hint "the pull request's branch is no longer open"
                  :pull-request-id id))

      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         (db/xact-lock! conn (:source-file-id pr))

         (when (db/exec-one! conn [sql:get-open-pull-request-for-branch (:file-branch-id pr)])
           (ex/raise :type :validation
                     :code :pull-request-already-exists
                     :hint "the branch already has an open pull request"
                     :branch-id (:file-branch-id pr)))

         (let [branch-file (bfc/get-file cfg (:source-file-id pr) :realize? true)
               snapshot    (pin-review-snapshot! cfg branch-file (:title pr) profile-id)
               ts          (ct/now)]
           (db/update! conn :file-pull-request
                       {:status "open"
                        :closed-at nil
                        :closed-by nil
                        :review-snapshot-id (:id snapshot)
                        :review-revn (:revn branch-file)
                        :review-updated-at ts
                        :updated-at ts}
                       {:id id}
                       {::db/return-keys false})

           (mbus/pub! msgbus
                      :topic (:target-file-id pr)
                      :message {:type :pull-request-updated
                                :file-id (:target-file-id pr)
                                :pull-request-id id
                                :profile-id profile-id})

           {:id id :status "open" :review-revn (:revn branch-file)}))))))

;; --- COMMAND QUERY: get-profile-pending-reviews

(def ^:private schema:get-profile-pending-reviews
  [:map {:title "get-profile-pending-reviews"}
   [:team-id ::sm/uuid]])

(def ^:private sql:get-profile-pending-reviews
  "SELECT fpr.id,
          fpr.title,
          fpr.created_by,
          fpr.created_at,
          fpr.review_updated_at,
          fpr.source_file_id,
          fpr.target_file_id,
          fb.name AS branch_name,
          sf.name AS target_name
     FROM file_pull_request_review AS r
     JOIN file_pull_request AS fpr ON (fpr.id = r.pull_request_id)
     JOIN file_branch AS fb ON (fb.id = fpr.file_branch_id)
     JOIN file AS sf ON (sf.id = fpr.target_file_id)
     JOIN project AS p ON (p.id = sf.project_id)
    WHERE r.profile_id = ?
      AND p.team_id = ?
      AND fpr.status = 'open'
      AND fpr.deleted_at IS NULL
      AND sf.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND (r.state = 'pending'
           OR r.reviewed_revn IS DISTINCT FROM fpr.review_revn)
    ORDER BY fpr.review_updated_at DESC")

(sv/defmethod ::get-profile-pending-reviews
  "The open pull requests of a team where the calling profile is a
  reviewer without a CURRENT verdict (never reviewed, or the author
  published newer changes since their last verdict). Feeds the dashboard
  notifications area."
  {::doc/added "2.16"
   ::sm/params schema:get-profile-pending-reviews}
  [cfg {:keys [::rpc/profile-id team-id]}]
  (if-not (contains? cf/flags :branching)
    []
    (db/run! cfg
             (fn [{:keys [::db/conn]}]
               (teams/check-read-permissions! conn profile-id team-id)
               (db/exec! conn [sql:get-profile-pending-reviews profile-id team-id])))))
