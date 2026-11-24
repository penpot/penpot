;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-branch-review
  "RPC commands for branch reviews on top of file branching: a review
  request over the live state of a branch. A branch review does NOT
  create a new file and does not freeze the branch. Reviewers open the
  live branch file, which stays editable, and the interactions viewer
  (`viewer/get-view-only-bundle` with `:review-id`) plays the live branch
  read-only while the review is open. A verdict records the branch revn
  it was issued over, so every later save of the branch file makes it
  stale. Merging stays on `app.rpc.commands.files-branch`; a verdict is
  informative and never a gate for the merge.

  The review's two sides are derived, never stored: the branch file is
  the `file_branch` edge's `branch_file_id` and the target its
  `source_file_id`. A review of a stacked branch therefore follows its
  edge when a parent merges and re-parents it. Every review belongs to
  the `file_family` of the file its target lives on, which is what
  outlives the branch and the file rows."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.review-activity :as ra]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-branch :as fbranch]
   [app.rpc.commands.files-task :as ftask]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.string :as str]))

(defn check-branch-reviews-enabled!
  "Guard the branch-review commands behind the `:branching` product flag
  (reviews are part of the branching feature). Public: the viewer bundle
  command guards its review-id variant with it too."
  []
  (when-not (contains? cf/flags :branching)
    (ex/raise :type :restriction
              :code :branching-disabled
              :hint "the branching feature is not enabled on this instance")))

;; --- Helpers

(def ^:private sql:get-branch-review
  "SELECT r.id,
          r.file_branch_id,
          r.title,
          r.description,
          r.created_by,
          r.created_at,
          r.updated_at,
          r.status,
          r.closed_at,
          r.closed_by,
          fb.name AS branch_name,
          fb.base_snapshot_id,
          fb.base_source_revn,
          fb.base_branch_revn,
          fb.branch_file_id AS source_file_id,
          fb.source_file_id AS target_file_id,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS target_name,
          (sf.id = sf.family_id) AS target_is_main
     FROM file_branch_review AS r
     JOIN file_branch AS fb ON (fb.id = r.file_branch_id)
     LEFT JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE r.id = ?
      AND r.deleted_at IS NULL")

(defn- get-branch-review*
  "Fetch a branch review row, with the branch file and the target derived
  from the branch edge, or raise :not-found. The two sides are never
  stored on the review: a review of a stacked branch follows its edge
  when a parent merge re-parents it."
  [cfg id]
  (let [review (db/exec-one! cfg [sql:get-branch-review id])]
    (when (nil? review)
      (ex/raise :type :not-found
                :code :branch-review-not-found
                :hint "unable to find the branch review with the provided id"
                :review-id id))
    review))

(defn- check-author-or-admin!
  "Managing a branch review (edit metadata, reviewers, close, reopen) is
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
  "SELECT r.review_id,
          r.profile_id,
          r.verdict,
          r.verdict_revn,
          r.comment,
          r.updated_at
     FROM file_branch_reviewer AS r
     JOIN profile AS p ON (p.id = r.profile_id)
    WHERE r.review_id = ANY(?)
    ORDER BY r.created_at ASC")

(defn- get-reviews-map
  "{review-id -> [reviewer ...]} for the given branch reviews, each
  reviewer annotated with `:stale` (verdict issued over an earlier revn
  of the branch file: the branch was saved since). A NULL verdict is a
  pending reviewer."
  [cfg reviews]
  (if (empty? reviews)
    {}
    (let [ids  (db/create-array (db/get-connection cfg) "uuid" (mapv :id reviews))
          revn (into {} (map (juxt :id :branch-revn)) reviews)]
      (->> (db/exec! cfg [sql:get-reviews ids])
           (mapv (fn [{:keys [review-id verdict verdict-revn] :as row}]
                   (let [branch-revn (get revn review-id)]
                     (assoc row :stale (boolean (and (some? verdict)
                                                     (some? branch-revn)
                                                     (> branch-revn verdict-revn)))))))
           (group-by :review-id)))))

(defn- overall-verdict
  "Aggregate verdict of a branch review: `changes-requested` beats
  `approved`; stale verdicts (issued over an earlier revn of the branch
  file) only count as `in-review`; no verdict at all is `pending`."
  [reviews]
  (let [submitted (remove #(nil? (:verdict %)) reviews)
        current   (remove :stale submitted)]
    (cond
      (some #(= "changes-requested" (:verdict %)) current) "changes-requested"
      (some #(= "approved" (:verdict %)) current)          "approved"
      (seq submitted)                                      "in-review"
      :else                                                "pending")))

(defn- decorate-branch-review
  "Attach the derived attributes the UI needs: the reviewers (+ aggregate
  `overall-verdict`) and entity-level ahead/behind/conflicts counts
  against the target (only for open reviews, gated like the branches
  listing).

  The row carries the review's two sides as the SQL of the edge aliases
  them (`source-file-id` the branch file, `target-file-id` the target),
  while the diff-count helper reads the branch-row naming, so the two
  sides swap here.

  The counts come from the branch summary cache, keyed on the same tuple
  the branches listing uses, so ten open reviews on one file cost one
  computation per branch and nothing at all once that panel has been
  opened."
  [cfg target-data reviews-map counts-map {:keys [id status file-branch-id source-file-id target-file-id]
                                           :as row}]
  (let [reviews (get reviews-map id [])
        open?   (= "open" status)
        [ahead behind conflicts]
        (if open?
          (fbranch/cached-diff-counts cfg target-data
                                      (assoc row
                                             :id file-branch-id
                                             :source-file-id target-file-id
                                             :branch-file-id source-file-id))
          [0 0 0])]
    (-> row
        (assoc :reviews (mapv #(dissoc % :review-id) reviews))
        (assoc :overall-verdict (overall-verdict reviews))
        (assoc :ahead ahead :behind behind :conflicts conflicts)
        ;; the comments and tasks of the review, for its overview
        (assoc :counts (get counts-map id {:tasks {} :comments-open 0 :comments-resolved 0}))
        (dissoc :branch-revn :source-revn :base-snapshot-id
                :base-source-revn :base-branch-revn))))

(def ^:private sql:get-open-branch-review-for-branch
  "SELECT id FROM file_branch_review
    WHERE file_branch_id = ?
      AND status = 'open'
      AND deleted_at IS NULL")

;; --- Helpers: reviewer notification emails

(defn- format-branch-review-url
  "Deep link into the review sandbox: the branch file's workspace with the
  review-id param."
  [{:keys [id source-file-id]} team-id]
  (str/join [(cf/get :public-uri)
             "/#/workspace?"
             (uri/map->query-string {:file-id source-file-id
                                     :team-id team-id
                                     :review-id id})]))

(defn- send-review-request-emails!
  "Notify `reviewers` (profile ids) that `actor-id` requested their review
  on the branch review. Follows the same in-transaction outbox pattern as
  the comment emails."
  [{:keys [::db/conn] :as cfg} {:keys [title description] :as review} {:keys [team-id actor-id branch-name target-name reviewers]}]
  (when (seq reviewers)
    (let [users (->> (teams/get-users+props conn team-id)
                     (map profile/decode-row)
                     (d/index-by :id))
          actor (get users actor-id)
          route (str/join [branch-name " → " target-name])
          url   (format-branch-review-url review team-id)]
      (doseq [reviewer-id reviewers]
        (when-let [{:keys [fullname email]} (get users reviewer-id)]
          (eml/send-template
           cfg
           {::eml/reuse-conn true
            ::eml/template "review-request"
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (get actor :fullname "")
            :review-title title
            :review-route route
            :review-description (or description "")
            :review-url url}))))))

;; --- COMMAND: create-branch-review

(def ^:private schema:create-branch-review
  [:map {:title "create-branch-review"}
   [:branch-id ::sm/uuid]
   [:title [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]
   [:reviewers {:optional true} [:vector ::sm/uuid]]])

(sv/defmethod ::create-branch-review
  "Create a branch review from an open branch towards the file the branch
  was cut from (its source). Registers the requested reviewers, who must
  be members of the team; they review the live branch. At most one open
  review may exist per branch. The review joins the family of the target
  file, which is what outlives the branch."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-branch-review
   ::climit/id [[:create-branch-review/by-profile ::rpc/profile-id]
                [:create-branch-review/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id branch-id title description reviewers]}]
  (check-branch-reviews-enabled!)
  (when (str/blank? title)
    (ex/raise :type :validation
              :code :invalid-branch-review-title
              :hint "the branch review title cannot be blank"))
  (let [branch (db/get* cfg :file-branch {:id branch-id})]
    (when (nil? branch)
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
      ;; permissions on the target, unchanged). Checked BEFORE validating
      ;; the reviewers so team membership cannot be probed without access.
      (files/check-edition-permissions! cfg profile-id branch-file-id)

      (let [target-row (db/get-by-id cfg :file target-id)
            project    (db/get-by-id cfg :project (:project-id target-row))
            team-id    (:team-id project)
            reviewers  (validate-reviewers! cfg team-id profile-id (or reviewers []))
            messages   (volatile! [])]

        (-> cfg
            (assoc ::quotes/profile-id profile-id)
            (assoc ::quotes/team-id team-id)
            (quotes/check! {::quotes/id ::quotes/pull-requests-per-team}))

        (fbranch/tx-run-notify!
         cfg
         msgbus
         messages
         (fn [{:keys [::db/conn] :as cfg}]
           ;; Serialize against a concurrent create-branch-review on the
           ;; same branch (the advisory lock the save path takes on the
           ;; branch file).
           (db/xact-lock! conn branch-file-id)

           ;; Serialize against a merge of the same branch (which locks
           ;; the branch file) and re-read the row under the lock: the
           ;; branch may be merged and gone by now, so the stale row read
           ;; before the transaction is dropped and everything below
           ;; derives from the fresh one.
           (let [branch         (db/get* conn :file-branch {:id branch-id})
                 branch-file-id (:branch-file-id branch)
                 target-id      (:source-file-id branch)]
             (when (nil? branch)
               (ex/raise :type :not-found
                         :code :branch-not-found
                         :branch-id branch-id))
             (when (not= "open" (:status branch))
               (ex/raise :type :validation
                         :code :branch-not-open
                         :branch-id branch-id))

             (when (db/exec-one! conn [sql:get-open-branch-review-for-branch branch-id])
               (ex/raise :type :validation
                         :code :branch-review-already-exists
                         :hint "the branch already has an open review"
                         :branch-id branch-id))

             (let [review-id (uuid/next)]

               (db/insert! conn :file-branch-review
                           {:id review-id
                            :file-branch-id branch-id
                            :family-id (:family-id target-row)
                            :title title
                            :description description
                            :created-by profile-id
                            :status "open"}
                           {::db/return-keys false})

               (doseq [reviewer-id reviewers]
                 (db/insert! conn :file-branch-reviewer
                             {:review-id review-id
                              :profile-id reviewer-id}
                             {::db/return-keys false}))

               (send-review-request-emails! cfg
                                            {:id review-id
                                             :source-file-id branch-file-id
                                             :title title
                                             :description description}
                                            {:team-id team-id
                                             :actor-id profile-id
                                             :branch-name (:name branch)
                                             :target-name (:name target-row)
                                             :reviewers reviewers})

               (vswap! messages conj
                       {:topic target-id
                        :message {:type :branch-review-created
                                  :file-id target-id
                                  :review-id review-id
                                  :profile-id profile-id}})

               {:id review-id
                :file-branch-id branch-id
                :source-file-id branch-file-id
                :target-file-id target-id
                :title title
                :description description
                :status "open"
                :reviewers reviewers}))))))))

;; --- COMMAND QUERY: get-file-branch-reviews

(def ^:private schema:get-file-branch-reviews
  [:map {:title "get-file-branch-reviews"}
   [:file-id ::sm/uuid]
   [:include-closed {:optional true} ::sm/boolean]])

(def ^:private sql:resolve-target-file
  "SELECT source_file_id FROM file_branch
    WHERE branch_file_id = ?
      AND deleted_at IS NULL")

(def ^:private sql:get-file-branch-reviews
  "SELECT r.id,
          r.file_branch_id,
          r.title,
          r.description,
          r.created_by,
          r.created_at,
          r.updated_at,
          r.status,
          r.closed_at,
          r.closed_by,
          fb.name AS branch_name,
          fb.base_snapshot_id,
          fb.base_source_revn,
          fb.base_branch_revn,
          fb.branch_file_id AS source_file_id,
          fb.source_file_id AS target_file_id,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS target_name,
          (sf.id = sf.family_id) AS target_is_main
     FROM file_branch_review AS r
     JOIN file_branch AS fb ON (fb.id = r.file_branch_id)
     LEFT JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE fb.source_file_id = ?
      AND r.deleted_at IS NULL
      AND (?::boolean OR r.status = 'open')
    ORDER BY r.created_at DESC")

(sv/defmethod ::get-file-branch-reviews
  "List the branch reviews of a file. Accepts either the target file or a
  branch file id (it resolves to its source through the branch edge).
  Every open review is annotated with its reviewers (each with its
  `stale` flag), the aggregate `overall-verdict` and the entity-level
  ahead/behind/conflicts counts (gated by the cheap revn deltas, with the
  target realized once and shared, like the branches listing).

  Those counts come from the branch summary cache that the branches
  listing fills, keyed on the same tuple: a repeated listing with no
  intervening save performs no comparison work and realizes no file, and
  ten open reviews on one branch pay for one computation.

  The listing declares the branches listing's limit, because a cold one
  holds the same documents: the target realized once and reused across
  rows, and each diverged review compared against it."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-reviews
   ::climit/id [[:get-file-branch-reviews/global]]}
  [cfg {:keys [::rpc/profile-id file-id include-closed]}]
  (check-branch-reviews-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [target-id (or (:source-file-id (db/exec-one! conn [sql:resolve-target-file file-id]))
                                 file-id)]
               (files/check-read-permissions! cfg profile-id target-id)
               (let [rows        (db/exec! conn [sql:get-file-branch-reviews target-id
                                                 (boolean include-closed)])
                     reviews-map (get-reviews-map cfg rows)
                     open?       (fn [row] (= "open" (:status row)))
                     ;; realize the target once, and only for a diverged
                     ;; review whose summary is not already cached
                     need?       (some (fn [r] (and (open? r)
                                                    (nil? (fbranch/cached-summary r))
                                                    (let [[a b] (fbranch/revn-deltas r)]
                                                      (or (pos? a) (pos? b)))))
                                       rows)
                     target-data (when need? (:data (bfc/get-file cfg target-id :realize? true)))
                     counts-map  (ftask/get-review-counts conn (mapv :id rows))]
                 (mapv (partial decorate-branch-review cfg target-data reviews-map counts-map) rows))))))

;; --- COMMAND QUERY: get-branch-review

(def ^:private schema:get-branch-review
  [:map {:title "get-branch-review"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-branch-review
  "Fetch a single branch review with its reviewers, aggregate verdict and
  diff counts."
  {::doc/added "2.16"
   ::sm/params schema:get-branch-review}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-branch-reviews-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [row (db/exec-one! conn [sql:get-branch-review id])]
               (when-not row
                 (ex/raise :type :not-found
                           :code :branch-review-not-found
                           :review-id id))
               (files/check-read-permissions! cfg profile-id (:target-file-id row))
               (decorate-branch-review cfg nil
                                       (get-reviews-map cfg [row])
                                       (ftask/get-review-counts conn [id])
                                       row)))))

;; --- COMMAND QUERY: get-branch-review-threads

(def ^:private schema:get-branch-review-threads
  [:map {:title "get-branch-review-threads"}
   [:review-id ::sm/uuid]])

(sv/defmethod ::get-branch-review-threads
  "List the threads of a branch review by number, each with its comments
  and its tasks, wherever the thread lives: on the target when the merge
  moved it, or behind with the branch, whether or not the branch file
  still exists. Needs read rights on the target of the review."
  {::doc/added "2.16"
   ::sm/params schema:get-branch-review-threads}
  [cfg {:keys [::rpc/profile-id review-id]}]
  (check-branch-reviews-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [review (get-branch-review* cfg review-id)]
               (files/check-read-permissions! cfg profile-id (:target-file-id review))
               (ftask/get-review-threads conn review-id)))))

;; --- COMMAND QUERY: get-branch-review-activity

(def ^:private schema:get-branch-review-activity
  [:map {:title "get-branch-review-activity"}
   [:id ::sm/uuid]])

(def ^:private activity-fetch-size
  "Rows the server-side cursor of the activity holds at once."
  500)

(def ^:private sql:get-branch-review-activity
  "SELECT a.id, a.profile_id, a.revn, a.entries, a.created_at
     FROM file_branch_review_activity AS a
    WHERE a.review_id = ?
    ORDER BY a.created_at ASC, a.id ASC")

(defn- starts-sitting?
  "Whether `row` is too late to join the sitting of the row before it,
  so the rows read so far hold whole sittings."
  [prev row]
  (and (some? prev)
       (> (- (inst-ms (:created-at row))
             (inst-ms (:created-at prev)))
          ra/default-session-gap-ms)))

(def ^:private empty-activity
  {:sittings [] :buffer [] :people #{} :last-save nil})

(defn- fold-sitting
  "Fold the sitting in course into the sittings read so far, oldest
  first."
  [{:keys [buffer sittings] :as acc}]
  (if (empty? buffer)
    acc
    (-> acc
        (assoc :sittings (into sittings (reverse (ra/sessions buffer))))
        (assoc :buffer []))))

(defn- add-activity-row
  "Fold one activity row, read oldest first, into the sittings, the
  people who saved and the newest save."
  ([acc] acc)
  ([acc row]
   (-> (if (starts-sitting? (:last-save acc) row)
         (fold-sitting acc)
         acc)
       (update :buffer conj row)
       (assoc :last-save (select-keys row [:created-at :profile-id]))
       (update :people conj (:profile-id row)))))

(defn- sitting-rows
  "The net entries of `sittings` as activity rows, the shape
  `net-entries` reads. A sitting is the fold of its own rows, so folding
  the sittings folds every row of the review: the fold sorts the rows by
  the moment of the change each one carries. Each wrapped row keeps that
  moment and the person who made the change, which is what the fold
  reports as `:profile-id` and `:changed-at`."
  [sittings]
  (into []
        (mapcat (fn [{:keys [profile-id entries]}]
                  (map (fn [{:keys [changed-at] :as entry}]
                         {:profile-id profile-id
                          :created-at changed-at
                          :entries [entry]})
                       entries)))
        sittings))

(defn- get-activity
  "The activity of a review, folded from every row of it: what each
  layer ends up as, the sittings of saves, how many people saved and
  the newest save. Rows are read oldest first and folded one sitting at
  a time, so a long history is never held in memory at once."
  [conn review-id]
  (let [acc      (transduce (map (fn [row] (update row :entries blob/decode)))
                            add-activity-row
                            empty-activity
                            (db/plan conn [sql:get-branch-review-activity review-id]
                                     {:fetch-size activity-fetch-size}))
        folded   (fold-sitting acc)
        sittings (vec (reverse (:sittings folded)))]
    {:entries   (ra/net-entries (sitting-rows sittings))
     :sessions  sittings
     :people    (count (disj (:people folded) nil))
     :last-save (:last-save folded)}))

(sv/defmethod ::get-branch-review-activity
  "What the saves of the branch did to its layers since the branch review
  opened, folded on the server: `:entries` what each layer ends up as
  (`app.common.files.review-activity/net-entries`), `:sessions` the
  sittings of saves the activity lists, each with the branch `:revn` its
  last save produced (what `get-branch-layer-at-revision` takes),
  `:people` how many people saved, and `:last-save` the newest save.
  Every row of the review is read, so the oldest change still counts in
  what a layer ends up as."
  {::doc/added "2.16"
   ::sm/params schema:get-branch-review-activity
   ::climit/id [[:get-branch-review-activity/global]]}
  [cfg {:keys [::rpc/profile-id id]}]
  (check-branch-reviews-enabled!)
  (db/tx-run! (assoc cfg ::db/read-only true)
              (fn [{:keys [::db/conn] :as cfg}]
                (let [review (get-branch-review* conn id)]
                  (files/check-read-permissions! cfg profile-id (:target-file-id review))
                  (get-activity conn id)))))

;; --- COMMAND: update-branch-review-info

(def ^:private schema:update-branch-review-info
  [:map {:title "update-branch-review-info"}
   [:id ::sm/uuid]
   [:title {:optional true} [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]
   ;; full replacement of the reviewer set; added reviewers start as
   ;; pending, removed ones lose their row (their comments remain)
   [:reviewers {:optional true} [:vector ::sm/uuid]]])

(sv/defmethod ::update-branch-review-info
  "Edit a branch review's title, description or reviewer list. Allowed to
  the author and to team admins/owners."
  {::doc/added "2.16"
   ::sm/params schema:update-branch-review-info}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id id title description reviewers]}]
  (check-branch-reviews-enabled!)
  (when (and (some? title) (str/blank? title))
    (ex/raise :type :validation
              :code :invalid-branch-review-title
              :hint "the branch review title cannot be blank"))
  (let [review (get-branch-review* cfg id)]
    (when (not= "open" (:status review))
      (ex/raise :type :validation
                :code :branch-review-not-open
                :review-id id))
    (check-author-or-admin! cfg profile-id review)

    (let [team-id   (perms/get-team-id-for-file cfg (:target-file-id review))
          reviewers (some->> reviewers
                             (validate-reviewers! cfg team-id (:created-by review)))
          messages  (volatile! [])]
      (fbranch/tx-run-notify!
       cfg
       msgbus
       messages
       (fn [{:keys [::db/conn] :as cfg}]
         (db/update! conn :file-branch-review
                     (cond-> {:updated-at (ct/now)}
                       (some? title)       (assoc :title title)
                       (some? description) (assoc :description description))
                     {:id id}
                     {::db/return-keys false})

         (when (some? reviewers)
           (let [current (->> (db/query conn :file-branch-reviewer
                                        {:review-id id})
                              (into #{} (map :profile-id)))
                 wanted  (set reviewers)
                 added   (vec (remove current wanted))]
             (doseq [reviewer-id added]
               (db/insert! conn :file-branch-reviewer
                           {:review-id id
                            :profile-id reviewer-id}
                           {::db/return-keys false}))
             (doseq [reviewer-id (remove wanted current)]
               (db/delete! conn :file-branch-reviewer
                           {:review-id id
                            :profile-id reviewer-id}))
             (when (seq added)
               (let [row (get-branch-review* conn id)]
                 (send-review-request-emails! cfg row
                                              {:team-id team-id
                                               :actor-id profile-id
                                               :branch-name (:branch-name row)
                                               :target-name (:target-name row)
                                               :reviewers added})))))

         (vswap! messages conj
                 {:topic (:target-file-id review)
                  :message {:type :branch-review-updated
                            :file-id (:target-file-id review)
                            :review-id id
                            :profile-id profile-id}})

         {:id id
          :title (or title (:title review))
          :description (or description (:description review))})))))

;; --- COMMAND: submit-branch-review-verdict

(def ^:private schema:submit-branch-review-verdict
  [:map {:title "submit-branch-review-verdict"}
   [:id ::sm/uuid]
   ;; a NULL verdict is a pending reviewer: absent (or null) retracts the
   ;; verdict the caller had submitted
   [:verdict {:optional true} [:maybe [:enum "approved" "changes-requested"]]]
   [:comment {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::submit-branch-review-verdict
  "Submit (or retract, with a null `:verdict`) a verdict on an open branch
  review. Only assigned reviewers may submit; the verdict records the
  revn of the branch file it was issued over, so it goes stale as soon
  as the branch file is saved again. Approval is informative: it never
  gates the merge, which keeps requiring edition permissions on the
  target."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:submit-branch-review-verdict}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id id verdict comment]}]
  (check-branch-reviews-enabled!)
  (let [review   (get-branch-review* cfg id)
        messages (volatile! [])]
    (when (not= "open" (:status review))
      (ex/raise :type :validation
                :code :branch-review-not-open
                :review-id id))
    (files/check-read-permissions! cfg profile-id (:target-file-id review))

    (fbranch/tx-run-notify!
     cfg
     msgbus
     messages
     (fn [{:keys [::db/conn]}]
       (let [reviewer     (db/get* conn :file-branch-reviewer
                                   {:review-id id :profile-id profile-id})
             verdict-revn (when (some? verdict)
                            (:revn (db/get conn :file {:id (:source-file-id review)}
                                           {:columns [:revn]})))]
         (when-not reviewer
           (ex/raise :type :validation
                     :code :not-a-reviewer
                     :hint "only assigned reviewers can submit a verdict"
                     :review-id id))

         (db/update! conn :file-branch-reviewer
                     {:verdict verdict
                      :verdict-revn verdict-revn
                      :comment comment
                      :updated-at (ct/now)}
                     {:review-id id :profile-id profile-id}
                     {::db/return-keys false})

         (vswap! messages conj
                 {:topic (:target-file-id review)
                  :message {:type :branch-review-verdict-submitted
                            :file-id (:target-file-id review)
                            :review-id id
                            :profile-id profile-id
                            :verdict verdict}})

         {:id id
          :profile-id profile-id
          :verdict verdict
          :verdict-revn verdict-revn})))))

;; --- COMMAND: close-branch-review

(def ^:private schema:close-branch-review
  [:map {:title "close-branch-review"}
   [:id ::sm/uuid]])

(sv/defmethod ::close-branch-review
  "Close (discard) an open branch review: its read-only viewer stops
  resolving and reviewers can no longer submit verdicts. The branch
  itself is not touched. Allowed to the author and to team admins/owners."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:close-branch-review}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-branch-reviews-enabled!)
  (let [review   (get-branch-review* cfg id)
        messages (volatile! [])]
    (when (not= "open" (:status review))
      (ex/raise :type :validation
                :code :branch-review-not-open
                :review-id id))
    (check-author-or-admin! cfg profile-id review)

    (fbranch/tx-run-notify!
     cfg
     msgbus
     messages
     (fn [{:keys [::db/conn]}]
       (let [ts (ct/now)]
         (db/update! conn :file-branch-review
                     {:status "closed"
                      :closed-at ts
                      :closed-by profile-id
                      :updated-at ts}
                     {:id id}
                     {::db/return-keys false})

         (vswap! messages conj
                 {:topic (:target-file-id review)
                  :message {:type :branch-review-closed
                            :file-id (:target-file-id review)
                            :review-id id
                            :profile-id profile-id}})

         {:id id :status "closed"})))))

;; --- COMMAND: reopen-branch-review

(def ^:private schema:reopen-branch-review
  [:map {:title "reopen-branch-review"}
   [:id ::sm/uuid]])

(sv/defmethod ::reopen-branch-review
  "Reopen a closed branch review, as long as its branch is still open and
  the branch has no other open review. Reviewers find the live branch,
  so a verdict issued before the close is stale if the branch was saved
  since. Allowed to the author and to team admins/owners."
  {::doc/added "2.16"
   ::sm/params schema:reopen-branch-review}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-branch-reviews-enabled!)
  (let [review   (get-branch-review* cfg id)
        messages (volatile! [])]
    (when (not= "closed" (:status review))
      (ex/raise :type :validation
                :code :branch-review-not-closed
                :hint "only closed branch reviews can be reopened"
                :review-id id))
    (check-author-or-admin! cfg profile-id review)

    (let [branch (db/get* cfg :file-branch {:id (:file-branch-id review)})]
      (when (or (nil? branch)
                (not= "open" (:status branch)))
        (ex/raise :type :validation
                  :code :branch-not-open
                  :hint "the branch review's branch is no longer open"
                  :review-id id))

      (fbranch/tx-run-notify!
       cfg
       msgbus
       messages
       (fn [{:keys [::db/conn]}]
         (db/xact-lock! conn (:source-file-id review))

         ;; Serialize against a merge of the same branch (which locks the
         ;; branch file) and re-read the row under the lock: the branch
         ;; may be merged and gone by now.
         (let [branch (db/get* conn :file-branch {:id (:file-branch-id review)})]
           (when (or (nil? branch)
                     (not= "open" (:status branch)))
             (ex/raise :type :validation
                       :code :branch-not-open
                       :hint "the branch review's branch is no longer open"
                       :review-id id)))

         (when (db/exec-one! conn [sql:get-open-branch-review-for-branch (:file-branch-id review)])
           (ex/raise :type :validation
                     :code :branch-review-already-exists
                     :hint "the branch already has an open review"
                     :branch-id (:file-branch-id review)))

         (let [ts (ct/now)]
           (db/update! conn :file-branch-review
                       {:status "open"
                        :closed-at nil
                        :closed-by nil
                        :updated-at ts}
                       {:id id}
                       {::db/return-keys false})

           (vswap! messages conj
                   {:topic (:target-file-id review)
                    :message {:type :branch-review-updated
                              :file-id (:target-file-id review)
                              :review-id id
                              :profile-id profile-id}})

           {:id id :status "open"}))))))

;; --- COMMAND QUERY: get-profile-review-requests

(def ^:private schema:get-profile-review-requests
  [:map {:title "get-profile-review-requests"}
   [:team-id ::sm/uuid]])

(def ^:private sql:get-profile-review-requests
  "SELECT r.id,
          r.title,
          r.created_by,
          r.created_at,
          fb.branch_file_id AS source_file_id,
          fb.source_file_id AS target_file_id,
          fb.name AS branch_name,
          sf.name AS target_name,
          (sf.id = sf.family_id) AS target_is_main
     FROM file_branch_reviewer AS v
     JOIN file_branch_review AS r ON (r.id = v.review_id)
     JOIN file_branch AS fb ON (fb.id = r.file_branch_id)
     JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
     JOIN project AS p ON (p.id = sf.project_id)
    WHERE v.profile_id = ?
      AND p.team_id = ?
      AND r.status = 'open'
      AND r.deleted_at IS NULL
      AND sf.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND (v.verdict IS NULL
           OR v.verdict_revn < bf.revn)
    ORDER BY r.created_at DESC")

(sv/defmethod ::get-profile-review-requests
  "The open branch reviews of a team where the calling profile is a
  reviewer without a CURRENT verdict (never reviewed, or the branch file
  was saved since their last verdict). Feeds the dashboard
  notifications area."
  {::doc/added "2.16"
   ::sm/params schema:get-profile-review-requests}
  [cfg {:keys [::rpc/profile-id team-id]}]
  (if-not (contains? cf/flags :branching)
    []
    (db/run! cfg
             (fn [{:keys [::db/conn]}]
               (teams/check-read-permissions! conn profile-id team-id)
               (db/exec! conn [sql:get-profile-review-requests profile-id team-id])))))
