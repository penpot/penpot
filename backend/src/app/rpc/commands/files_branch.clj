;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-branch
  "RPC commands for file branching: create an isolated copy of a file
  (a \"branch\") linked to its source file (\"main\"), list branches,
  diff a branch against main, merge a branch into main (with conflict
  resolutions), and update a branch from main. See
  `app.rpc.commands.files-snapshot` for the patterns this namespace
  mirrors."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.files.changes :as cpc]
   [app.common.files.helpers :as cfh]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as fupd]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.util.blob :as blob]
   [app.util.cache :as ucache]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [clojure.set :as set]
   [clojure.string :as str]))

(defn- check-branching-enabled!
  "Guard the branching commands behind the `:branching` product flag."
  []
  (when-not (contains? cf/flags :branching)
    (ex/raise :type :restriction
              :code :branching-disabled
              :hint "the branching feature is not enabled on this instance")))

;; --- Size gates
;;
;; Nothing in this feature limited size before, so the failure mode at
;; enterprise scale was a timeout: an operation that never returns and a
;; user who cannot tell whether it is working. Every limit below is a
;; measured cost divided into a 30 s budget and then halved, taken from
;; the numbers in `pp:vcs:tp-measurement-harness`:
;;
;;  * a merge on the 21,169-shape design system costs about 6 s, which is
;;    0.28 ms per shape, so 30 s is about 105,000 shapes -> 50,000.
;;  * a whole-file comparison of 48 pages costs 402 ms, which is 8.4 ms
;;    per page, so the page limit is far above any real design system and
;;    exists to bound the presence pass -> 500.
;;  * one value-derived squash already emits about 19,000 changes for a
;;    one-page delta (`pp:vcs:tp-squash-rewrites-most-of-the-file`), so a
;;    depth limit has to clear that with room -> 100,000 changes.
;;
;; A refusal names the limit, the actual value, and what to do instead.
;; None of them truncates anything.

(defn- branching-limit
  [key default]
  (or (cf/get key) default))

(defn- check-file-size-limits!
  "Refuse an operation on a file whose realized state is beyond what the
  feature was measured to survive. `file` must already be realized."
  [{:keys [data] :as file} operation]
  (let [max-shapes (branching-limit :branching-max-shapes 50000)
        max-pages  (branching-limit :branching-max-pages 500)
        pages      (count (:pages-index data))
        shapes     (reduce-kv (fn [total _ page] (+ total (count (:objects page))))
                              0
                              (or (:pages-index data) {}))]

    (when (> pages max-pages)
      (ex/raise :type :restriction
                :code :branching-page-limit-exceeded
                :hint (str "this file has " pages " pages and branching is limited to "
                           max-pages "; split the file, or materialise the branch to keep "
                           "working on it as an ordinary file")
                :operation operation
                :limit max-pages
                :actual pages))

    (when (> shapes max-shapes)
      (ex/raise :type :restriction
                :code :branching-shape-limit-exceeded
                :hint (str "this file has " shapes " shapes and branching is limited to "
                           max-shapes "; split the file, or materialise the branch to keep "
                           "working on it as an ordinary file")
                :operation operation
                :limit max-shapes
                :actual shapes))
    file))

(defn- check-oplog-depth-limit!
  "Refuse an operation on a branch whose op log is deeper than the derive
  was measured to survive. `changes` is the flattened log."
  [changes branch-file-id operation]
  (let [limit (branching-limit :branching-max-oplog-changes 100000)
        depth (count changes)]
    (when (> depth limit)
      (ex/raise :type :restriction
                :code :branching-oplog-limit-exceeded
                :hint (str "this branch has " depth " changes in its op log and the limit is "
                           limit "; merge it, or materialise it into an ordinary file")
                :operation operation
                :branch-file-id branch-file-id
                :limit limit
                :actual depth))
    changes))

(def ^:private schema:get-branching-limits
  [:map {:title "get-branching-limits"}])

(sv/defmethod ::get-branching-limits
  "The size limits this instance refuses beyond, so the UI can publish them
  before a user meets one. Read from config on every call: the limits have
  exactly one home, and a page that states them must not keep its own copy."
  {::doc/added "2.16"
   ::sm/params schema:get-branching-limits}
  [_cfg _params]
  (check-branching-enabled!)
  {:max-shapes (branching-limit :branching-max-shapes 50000)
   :max-pages (branching-limit :branching-max-pages 500)
   :max-oplog-changes (branching-limit :branching-max-oplog-changes 100000)})

;; --- Audit: what an operation cost and how it ended
;;
;; The generic RPC audit event records who called what with which params.
;; It does not record how long the call took, and duration is the number
;; the first enterprise trial will be asked about, so every branch
;; operation attaches it to its own event.

(defn- audited
  "Attach the `:duration-ms` and `:outcome` props to the audit event of a
  branch operation, and print the matching log line. `result` is returned
  unchanged to the caller, and `extra` lets an operation add numbers of
  its own to the same record.

  A listing returns a vector rather than a map, and a vector carries
  metadata exactly like the maps the other commands return, so the props
  reach the audit middleware (`app/loggers/audit.clj::prepare-rpc-event`
  reads `(meta result)`) the way they always have. Nothing between the
  handler and that middleware rebuilds the value."
  ([result tpoint operation]
   (audited result tpoint operation nil))
  ([result tpoint operation extra]
   (let [duration (inst-ms (tpoint))
         outcome  (name (or (:status result) :ok))]
     (l/inf :hint "branch operation" :operation (name operation)
            :outcome outcome
            :duration duration)
     (vary-meta result update ::audit/props merge
                (cond-> {:branch-operation (name operation)
                         :branch-outcome outcome
                         :branch-duration-ms duration}
                  (seq extra) (merge extra))))))

;; --- Helpers: media pairing
;;
;; `duplicate-file` (branch creation) gives the branch's file_media_object
;; rows FRESH ids and relinks the branch `:data` to them, so the same image
;; carries a different id on each side. Before diffing or merging, the
;; branch-side media ids must be normalized back to the target's ids
;; (together with the file id, via `bm/remap-refs`); otherwise every image
;; looks changed, and merged shapes would reference media rows owned by the
;; branch file — which break when the branch is deleted and GC'd.

(def ^:private sql:file-media-identity
  "SELECT id, media_id, name, width, height, mtype
     FROM file_media_object
    WHERE file_id = ?
      AND deleted_at IS NULL")

(defn- media-pairs
  "{from-media-id -> to-media-id} for the file_media_object rows present on
  both files with the same content identity (storage object + name +
  dimensions + mtype) — i.e. the rows `duplicate-file` copied at branch
  creation. Rows with several identical copies are paired positionally
  (they are interchangeable). Media added on one side stays unpaired."
  [cfg from-file-id to-file-id]
  (let [ident   (juxt :media-id :name :width :height :mtype)
        from-gs (group-by ident (db/exec! cfg [sql:file-media-identity from-file-id]))
        to-gs   (group-by ident (db/exec! cfg [sql:file-media-identity to-file-id]))]
    (reduce-kv (fn [acc k from-group]
                 (if-let [to-group (get to-gs k)]
                   (into acc (map (fn [f t] [(:id f) (:id t)])
                                  (sort-by :id from-group)
                                  (sort-by :id to-group)))
                   acc))
               {}
               from-gs)))

(def ^:private sql:media-rows-by-id
  "SELECT * FROM file_media_object WHERE id = ANY(?) AND deleted_at IS NULL")

(defn- unpaired-media-rows
  "Full file_media_object rows of `file-id` that are used by `data` but have
  no counterpart in `pairs` — media added on this side since branching.
  They must be copied into the target file when the merge/update applies."
  [cfg file-id data pairs]
  (let [used (into #{} (remove pairs) (cfh/collect-used-media data))]
    (if (seq used)
      (->> (db/exec! cfg [sql:media-rows-by-id
                          (db/create-array (db/get-connection cfg) "uuid" used)])
           (filterv #(= file-id (:file-id %))))
      [])))

(defn- copy-media-rows!
  "Insert copies of media `rows` into `file-id`, with the ids assigned in
  `fresh-map` ({old-id -> new-id})."
  [conn file-id fresh-map rows]
  (doseq [row rows]
    (db/insert! conn :file-media-object
                (-> row
                    (assoc :id (get fresh-map (:id row)))
                    (assoc :file-id file-id)
                    (dissoc :created-at :deleted-at))
                {::db/return-keys false})))

;; --- Helpers: merge base snapshot

(defn- get-base-data
  "The merge-base `:data` from the branch's base snapshot. Raises when the
  snapshot cannot be resolved: silently falling back to main would degrade
  the three-way merge into \"branch overwrites main\" without conflicts.

  Prefer `branch-base-data` where the branch file was read with
  `:include-base-data? true`: the derive decoded the merge base to replay
  the op log over it, so this function would decode the same snapshot a
  second time."
  [cfg {:keys [source-file-id base-snapshot-id] :as branch}]
  (when-not base-snapshot-id
    (ex/raise :type :not-found
              :code :base-snapshot-missing
              :hint "the branch has no merge-base snapshot"
              :branch-id (:id branch)))
  (:data (fsnap/get-snapshot cfg source-file-id base-snapshot-id)))

(defn- branch-base-data
  "The comparison's `base`: the merge-base document that a branch file
  read with `:include-base-data? true` carries under `::bfc/base-data`.
  That document is the state the branch's op log was replayed over, that
  is the merge base before the replay. The file's `:data` holds the
  branch's own state, so a comparison against `:data` would compare the
  branch with itself. Raises when the key is absent, so a read that
  forgot the flag fails loudly instead of comparing against nil."
  [branch-file]
  (or (::bfc/base-data branch-file)
      (ex/raise :type :assertion
                :code :branch-base-data-missing
                :hint "the branch file was read without `:include-base-data? true`")))

(defn- release-base-snapshot!
  "Reschedule a branch's base snapshot for normal deletion: it is pinned
  ~10 years while the branch is open, and must be released when the branch
  is merged, deleted or its base is repositioned."
  [cfg {:keys [base-snapshot-id source-file-id]} deleted-at]
  (when base-snapshot-id
    (fsnap/delete! cfg
                   :id base-snapshot-id
                   :file-id source-file-id
                   :deleted-at deleted-at)))

;; --- Helpers: pull requests attached to a branch
;;
;; Pull requests live in `app.rpc.commands.files-pull-request`, which
;; requires THIS namespace (it reuses the diff-count helpers), so the
;; branch lifecycle hooks below work over the table directly to avoid a
;; circular dependency.

(def ^:private sql:get-open-branch-pull-requests
  "SELECT id, source_file_id, review_snapshot_id
     FROM file_pull_request
    WHERE file_branch_id = ?
      AND status = 'open'
      AND deleted_at IS NULL")

(defn- close-branch-pull-requests!
  "Close (or mark merged) any open pull request attached to a branch and
  release its pinned review snapshot: the review sandbox must not
  outlive the branch. The pull request row itself is kept as history.
  Must run inside a transaction."
  [{:keys [::db/conn] :as cfg} branch-id {:keys [profile-id status deleted-at]}]
  (doseq [pr (db/exec! conn [sql:get-open-branch-pull-requests branch-id])]
    (let [ts (ct/now)]
      (db/update! conn :file-pull-request
                  {:status (or status "closed")
                   :closed-at ts
                   :closed-by profile-id
                   :updated-at ts}
                  {:id (:id pr)}
                  {::db/return-keys false})
      (when-let [snapshot-id (:review-snapshot-id pr)]
        (fsnap/delete! cfg
                       :id snapshot-id
                       :file-id (:source-file-id pr)
                       :deleted-at deleted-at)))))

;; --- Helpers: branch deletion (shared by delete-file-branch and merge)

(defn- delete-branch!
  "Logically delete a branch and its branch file (with the team's deletion
  delay), release the base snapshot, schedule the file object GC and notify
  the branch file's open clients. Must run inside a transaction."
  [{:keys [::mbus/msgbus ::db/conn] :as cfg}
   {:keys [id branch-file-id] :as branch}
   {:keys [profile-id session-id status]}]
  (let [team (teams/get-team conn :profile-id profile-id :file-id branch-file-id)
        dt   (ct/in-future (ldel/get-deletion-delay team))]
    (db/update! conn :file {:deleted-at dt} {:id branch-file-id} {::db/return-keys false})
    (db/delete! conn :file-library-rel {:library-file-id branch-file-id})
    (db/update! conn :file-branch
                (cond-> {:deleted-at dt :updated-at (ct/now)}
                  (some? status) (assoc :status status))
                {:id id}
                {::db/return-keys false})
    (release-base-snapshot! cfg branch dt)
    ;; the review sandbox must not outlive the branch (`status` above is
    ;; the BRANCH status; a pull request closed this way is just "closed")
    (close-branch-pull-requests! cfg id {:profile-id profile-id
                                         :status "closed"
                                         :deleted-at dt})
    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :file :deleted-at dt :id branch-file-id}})
    (mbus/pub! msgbus
               :topic branch-file-id
               :message {:type :file-deleted
                         :file-id branch-file-id
                         :profile-id profile-id
                         :session-id session-id})
    nil))

;; --- COMMAND: create-file-branch

(def ^:private schema:create-file-branch
  [:map {:title "create-file-branch"}
   [:file-id ::sm/uuid]
   [:name [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::create-file-branch
  "Create a branch from a file. The branch file stores NO data payload:
  its `:data` is derived on every read by replaying the branch's op log
  over the merge base — a system snapshot of main taken at creation.
  The branch file row still exists (permissions, libraries, msgbus
  topics and media ownership key on it), but nothing is duplicated."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-file-branch
   ::climit/id [[:create-file-branch/by-profile ::rpc/profile-id]
                [:create-file-branch/global]]}
  [cfg {:keys [::rpc/profile-id file-id name description]}]
  (check-branching-enabled!)
  (files/check-edition-permissions! cfg profile-id file-id)

  (let [tpoint   (ct/tpoint)
        file-row (db/get-by-id cfg :file file-id)
        project  (db/get-by-id cfg :project (:project-id file-row))]

    (when (:is-branch file-row)
      (ex/raise :type :validation
                :code :cannot-branch-a-branch
                :hint "branches of branches are not supported"
                :file-id file-id))

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/project-id (:project-id file-row))
        (assoc ::quotes/team-id (:team-id project))
        (assoc ::quotes/file-id file-id)
        (quotes/check! {::quotes/id ::quotes/branches-per-file}
                       {::quotes/id ::quotes/branches-per-team}))

    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

                  ;; Serialize against concurrent update-file on main
                  ;; (same advisory lock) so the base snapshot and the
                  ;; branch's initial state (the empty op log over that
                  ;; base) are taken from the SAME state of main.
                  (db/xact-lock! conn file-id)

                  ;; 1. Materialize the merge base: a system snapshot of
                  ;; main, kept long-lived so it is not pruned by the
                  ;; snapshot GC while the branch is open (it is
                  ;; released when the branch is deleted or its base
                  ;; moves).
                  (let [file (-> (bfc/get-file cfg file-id :realize? true)
                                 (check-file-size-limits! :create-branch))
                        base (fsnap/create! cfg file
                                            {:label (str "branch-base/" name)
                                             :profile-id profile-id
                                             :created-by "system"
                                             :deleted-at (ct/in-future {:days 3650})})

                        ts         (ct/now)
                        branch-id  (uuid/next)

                        ;; 2. Create the branch file: an empty file row
                        ;; carrying main's project, features and data
                        ;; version. No data, no media, no library copy:
                        ;; the read path derives the state.
                        branch-file
                        (-> (ctf/make-file {:id branch-id
                                            :name name
                                            :project-id (:project-id file)}
                                           {:create-page false})
                            (assoc :features (set/difference (:features file)
                                                             #{"fdata/objects-map" "fdata/pointer-map"}))
                            (assoc :version (:version file)))

                        meta-id (uuid/next)]

                    (bfc/insert-file! cfg branch-file {::db/return-keys false})

                    ;; 3. Mark the new file row as a branch so it is
                    ;; hidden from the project/team file listings.
                    (db/update! conn :file
                                {:is-branch true}
                                {:id branch-id}
                                {::db/return-keys false})

                    ;; 4. Grant the creator ownership (same grant
                    ;; duplicate-file used to make).
                    (when (uuid? profile-id)
                      (db/insert! conn :file-profile-rel
                                  {:file-id branch-id
                                   :profile-id profile-id
                                   :is-owner true
                                   :is-admin true
                                   :can-edit true}
                                  {::db/return-keys false}))

                    ;; 5. The branch resolves libraries exactly like
                    ;; main (component instances from shared libraries
                    ;; must keep resolving on the derived state).
                    (doseq [rel (bfc/get-files-rels cfg #{file-id})]
                      (let [rel-params (-> rel
                                           (assoc :file-id branch-id)
                                           (assoc :created-at ts)
                                           (dissoc :synced-at))]
                        (db/insert! conn :file-library-rel rel-params ::db/return-keys false)
                        (bfc/upsert-file-library-sync! conn
                                                       {:file-id branch-id
                                                        :library-file-id (:library-file-id rel)
                                                        :synced-at (or (:synced-at rel) ts)})))

                    ;; 6. Persist the branch metadata. `base-revn`
                    ;; tracks MAIN's revision counter and
                    ;; `base-branch-revn` the BRANCH file's one (they
                    ;; coincide at creation but drift apart: they are
                    ;; independent counters).
                    (db/insert! conn :file-branch
                                {:id meta-id
                                 :branch-file-id branch-id
                                 :source-file-id file-id
                                 :base-snapshot-id (:id base)
                                 :base-revn (:revn file)
                                 :base-branch-revn (:revn branch-file)
                                 :created-by profile-id
                                 :name name
                                 :description description
                                 :status "open"}
                                {::db/return-keys false})

                    (audited {:id meta-id
                              :branch-file-id branch-id
                              :source-file-id file-id
                              :base-revn (:revn file)
                              :name name
                              :description description
                              :status "open"}
                             tpoint :create-branch))))))

;; --- COMMAND QUERY: get-file-branches

(def ^:private schema:get-file-branches
  [:map {:title "get-file-branches"}
   [:file-id ::sm/uuid]
   [:include-archived {:optional true} ::sm/boolean]])

(def ^:private sql:get-file-branches
  "SELECT fb.id,
          fb.branch_file_id,
          fb.source_file_id,
          fb.name,
          fb.description,
          fb.status,
          fb.created_by,
          fb.created_at,
          fb.updated_at,
          fb.merged_at,
          fb.merged_by,
          fb.base_revn,
          fb.base_branch_revn,
          fb.base_snapshot_id,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS source_name
     FROM file_branch AS fb
     JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE fb.source_file_id = ?
      AND fb.deleted_at IS NULL
      AND (?::boolean OR fb.status = 'open')
    ORDER BY fb.created_at DESC")

(defn revn-deltas
  "Cheap `[ahead-revn behind-revn]` deltas gating the expensive diff. Each
  side is compared against ITS OWN counter captured when the base was
  (re)positioned: `branch-revn` vs `base-branch-revn` and `source-revn` vs
  `base-revn` — the two revns are independent counters and must never be
  mixed."
  [{:keys [branch-revn source-revn base-revn base-branch-revn]}]
  [(max 0 (- branch-revn (or base-branch-revn base-revn)))
   (max 0 (- source-revn base-revn))])

(defn- branch-id-map
  "Reference-normalization map for diffing/merging a branch against its
  source: the branch file id plus the branch->source media pairs (see
  `media-pairs`)."
  [cfg branch-file-id source-file-id]
  (-> (media-pairs cfg branch-file-id source-file-id)
      (assoc branch-file-id source-file-id)))

(def ^:private scoped-change-types
  "The change types `validate::extract-affected-ids` maps to a page or a
  component id. Every other type (a page or component deletion, page
  order, colors, typographies, tokens, file-level plugin data) is real in
  a diff and invisible to that reducer, so a log containing one has no
  complete affected set and cannot bound a comparison."
  #{:add-obj :mod-obj :del-obj :fix-obj :mov-objects :reorder-children :reg-objects
    :add-page :mod-page :add-component :mod-component :restore-component})

(defn- scoped-change?
  [{:keys [type page-id component-id id]}]
  (and (contains? scoped-change-types type)
       (case type
         (:add-page :mod-page :add-component :mod-component)
         (some? id)

         ;; restores touch the component definition AND the page its main
         ;; instance lands on, so both ids have to be there for the set to
         ;; be complete
         :restore-component
         (and (some? id) (some? page-id))

         ;; a shape op carries either a page or a component; one without
         ;; both scopes to nothing the reducer can name
         (or (some? page-id) (some? component-id)))))

(defn- branch-log
  "The branch's op log flattened into one change vector, oldest first."
  [cfg branch-file-id]
  (into [] (mapcat identity) (bfc/get-branch-changes cfg branch-file-id)))

(defn- affected-pages
  "The set of page ids a log touched, or nil when it holds a change that is
  not scoped to a page or a component.

  It is exact rather than approximate because of the storage model: a
  branch's `:data` IS the base snapshot plus this log, so a log that only
  touches these pages cannot differ from base anywhere else. `nil` is the
  honest answer for everything else, and it leaves the whole-file
  comparison in place."
  [changes]
  (when (every? scoped-change? changes)
    (:page-ids (cfv/extract-affected-ids changes))))

(defn- branch-affected-pages
  "`affected-pages` of the branch's whole log. Used by the listing, which
  never refuses, so it reads the log without checking its depth."
  [cfg branch-file-id]
  (affected-pages (branch-log cfg branch-file-id)))

(defn branch-diff-counts!
  "Entity-level `[ahead behind conflicts]` change counts between a branch
  file and its source (main) — the same numbers the compare dialog lists
  (root-frame churn and structural noise filtered out). The revn deltas
  are used only as a cheap gate to skip the (expensive) 3-way diff when a
  side hasn't moved. `main-data` may be pre-realized and shared across
  branches of the same source; otherwise it is loaded on demand.

  The forward pass is bounded to the pages the branch's op log touched
  when that set is complete (`branch-affected-pages`); the reverse pass
  reports MAIN's changes and stays whole-file, because nothing here knows
  what main touched.

  Raises on failure instead of degrading: the caller decides whether the
  result is worth caching, and a value computed from an error must never
  enter the summary cache."
  [cfg main-data {:keys [source-file-id branch-file-id ahead-revn behind-revn]}]
  (if (and (zero? ahead-revn) (zero? behind-revn))
    [0 0 0]
    (let [main-data   (or main-data (:data (bfc/get-file cfg source-file-id :realize? true)))
          ;; the derive hands back the merge base it replayed the log over,
          ;; so the comparison's `base` costs one decode of the snapshot
          ;; rather than two
          branch-file (bfc/get-file cfg branch-file-id :realize? true
                                    :include-base-data? true)
          branch-data (bm/remap-refs
                       (:data branch-file)
                       (branch-id-map cfg branch-file-id source-file-id))
          base-data   (branch-base-data branch-file)
          clean-count (fn [m] (let [s (:stats m)]
                                (+ (:added s) (:modified s) (:deleted s))))
          fwd (when (pos? ahead-revn)
                (bm/compute-merge base-data main-data branch-data :branch->main
                                  (when-let [pages (branch-affected-pages cfg branch-file-id)]
                                    {:only-pages pages})))
          bwd (when (pos? behind-revn)
                (bm/compute-merge base-data branch-data main-data :branch->main))]
      [(if fwd (clean-count fwd) 0)
       (if bwd (clean-count bwd) 0)
       ;; conflicts are symmetric; only possible when both sides diverged
       (if (and (pos? ahead-revn) (pos? behind-revn)) (count (:conflicts fwd)) 0)])))

(def ^:private branch-summary-cache
  "The per-branch `[ahead behind conflicts]` summary, keyed by
  `(base-snapshot-id, source-revn, branch-revn)` — exactly the inputs the
  computation reads. A stale key is a cache miss, and a cache miss is the
  old behaviour, so the cache needs no invalidation logic; `keepalive`
  and `max-size` bound memory, they never decide correctness.

  The window is a working day rather than minutes because the fetch this
  exists for is a file open: an entry is a uuid and five numbers, so
  keeping it costs nothing, and a shorter window pays the whole cold cost
  again for a designer who comes back after lunch.

  Both surfaces reporting these numbers share it: the branches listing and
  the pull-request listing ask the same question about the same pair, so
  whichever runs first warms the other."
  (ucache/create :max-size 8192 :keepalive "8h"))

(defn- summary-cache-key
  [{:keys [base-snapshot-id source-revn branch-revn]}]
  [base-snapshot-id source-revn branch-revn])

(defn cached-summary
  "The cached summary of a branch row, or nil when it would have to be
  computed. Public because a listing decides whether to realize main
  before it pays for it, and a listing whose rows are all cached needs no
  main at all."
  [row]
  (ucache/get branch-summary-cache (summary-cache-key row)))

(defn cached-diff-counts
  "`branch-diff-counts!` behind the summary cache, degrading to zeros with
  a warning when the base snapshot cannot be resolved: a listing is
  read-only, and merge/update DO refuse loudly in that situation (reading
  the branch file raises `:base-snapshot-missing`). A value computed from
  an error never enters the cache.

  `branch` carries the key tuple and the pair being compared, with
  `source-file-id` naming main and `branch-file-id` the branch, whichever
  listing is asking."
  [cfg main-data branch]
  (let [[ahead-revn behind-revn] (revn-deltas branch)]
    (try
      (ucache/get branch-summary-cache
                  (summary-cache-key branch)
                  (fn [_]
                    (branch-diff-counts! cfg main-data
                                         (assoc branch
                                                :ahead-revn ahead-revn
                                                :behind-revn behind-revn))))
      (catch Throwable cause
        (l/wrn :hint "unable to compute branch diff counts"
               :branch-file-id (str (:branch-file-id branch))
               :source-file-id (str (:source-file-id branch))
               :cause cause)
        [0 0 0]))))

(sv/defmethod ::get-file-branches
  "List the branches of a file. `ahead`/`behind` are entity-level change
  counts (matching the compare dialog), gated by the cheap revn deltas so
  in-sync branches skip the diff entirely; `main` is realized once and
  shared across branches.

  A cold listing is one of the slowest things a user meets, so the listing
  reports its own duration to `audit_log` and to the log, together with
  the two numbers that explain that duration: how many open diverged
  branches it had to look a comparison up for, and how many of those the
  summary cache answered without computing anything.

  The per-branch summary is cached by `(base-snapshot-id, source-revn,
  branch-revn)`: a repeated listing with no intervening save performs no
  comparison work, and the tuple names exactly the inputs the computation
  reads, so a stale key cannot exist and the cache needs no invalidation.
  The pull-request listing reads the same cache, so either surface can
  arrive warm."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branches
   ::climit/id [[:get-file-branches/global]]}
  [cfg {:keys [::rpc/profile-id file-id include-archived]}]
  (check-branching-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (files/check-read-permissions! cfg profile-id file-id)
             (let [tpoint    (ct/tpoint)
                   rows      (db/exec! conn [sql:get-file-branches file-id (boolean include-archived)])
                   ;; only OPEN branches get diff counts: merged/archived ones
                   ;; are not going to be merged as-is, so the expensive diff
                   ;; would be wasted work (and their base may be released)
                   open?     (fn [row] (= "open" (:status row)))
                   ;; an open branch whose cheap revn deltas say it diverged is
                   ;; the only one the listing looks a comparison up for
                   diverged? (fn [row] (let [[a b] (revn-deltas row)] (or (pos? a) (pos? b))))
                   compared  (filterv (fn [row] (and (open? row) (diverged? row))) rows)
                   ;; the rows the summary cache answers without computing
                   cached    (filterv (comp some? cached-summary) compared)
                   ;; realize main once, only if some compared branch is not
                   ;; already cached
                   need?     (< (count cached) (count compared))
                   main-data (when need? (:data (bfc/get-file cfg file-id :realize? true)))
                   branches  (mapv (fn [row]
                                     (let [[ahead behind conflicts] (if (open? row)
                                                                      (cached-diff-counts cfg main-data row)
                                                                      [0 0 0])]
                                       (-> row
                                           (assoc :ahead ahead :behind behind :conflicts conflicts)
                                           (dissoc :branch-revn :source-revn :base-snapshot-id :base-branch-revn))))
                                   rows)]
               (audited branches tpoint :list-branches
                        {:branches-compared (count compared)
                         :branches-cached (count cached)})))))

;; --- COMMAND QUERY: get-branch-diff

(def ^:private schema:get-branch-diff
  [:map {:title "get-branch-diff"}
   [:branch-id ::sm/uuid]
   ;; `:branch->main` (default) = the branch's outgoing changes to merge;
   ;; `:main->branch` = main's incoming changes the branch is missing.
   [:direction {:optional true} [:enum :branch->main :main->branch]]])

(sv/defmethod ::get-branch-diff
  "Read-only three-way diff between a branch and its source (main),
  using the merge base captured at branch creation. Returns the summary
  produced by `branch-merge/compute-merge` (stats, changes, conflicts).

  NOTE (Phase 2): base/main/branch are assumed to share the same file
  data version; explicit migration normalization before diffing is a
  later refinement."
  {::doc/added "2.16"
   ::sm/params schema:get-branch-diff
   ::climit/id [[:get-branch-diff/global]]
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id branch-id direction]}]
  (check-branching-enabled!)
  (let [branch (db/get* conn :file-branch {:id branch-id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found
                :code :branch-not-found
                :hint "unable to find branch with the provided id"
                :branch-id branch-id))

    (files/check-read-permissions! cfg profile-id (:source-file-id branch))

    (let [tpoint      (ct/tpoint)
          dir         (or direction :branch->main)
          main-file   (-> (bfc/get-file cfg (:source-file-id branch) :realize? true)
                          (check-file-size-limits! :compare))
          branch-file (bfc/get-file cfg (:branch-file-id branch)
                                    :realize? true :include-base-data? true)
          log         (-> (branch-log cfg (:branch-file-id branch))
                          (check-oplog-depth-limit! (:branch-file-id branch) :compare))
          main-data   (:data main-file)
          branch-data (bm/remap-refs
                       (:data branch-file)
                       (branch-id-map cfg (:branch-file-id branch) (:source-file-id branch)))
          base-data   (branch-base-data branch-file)
          ;; only the branch->main direction may be bounded to the pages
          ;; the branch touched: the other direction is main's changes and
          ;; nothing here knows which pages those are
          opts        (when (= dir :branch->main)
                        (when-let [pages (affected-pages log)]
                          {:only-pages pages}))]
      ;; `:meta` carries the "when" of each side so the resolution UI can show
      ;; how recent main/branch are (base is pinned at branch creation), plus
      ;; `:main-revn` so the client can do optimistic concurrency on merge
      ;; (`expected-main-revn`). The last editor's identity is intentionally
      ;; omitted: files do not store a reliable "modified-by".
      (-> (bm/compute-merge base-data main-data branch-data dir opts)
          (assoc :meta {:base-at   (:created-at branch)
                        :main-at   (:modified-at main-file)
                        :branch-at (:modified-at branch-file)
                        :main-revn (:revn main-file)})
          (audited tpoint :compare)))))

;; --- COMMAND: merge-file-branch

(def ^:private schema:merge-file-branch
  [:map {:title "merge-file-branch"}
   [:branch-id ::sm/uuid]
   ;; conflict resolutions keyed by entity id (uuid) or, for structural
   ;; conflicts, a keyword id like :active-themes. A resolution is either a
   ;; whole-entity choice (:main/:branch) or a per-attr map {attr -> side}.
   [:resolutions {:optional true} [:map-of :any [:or :keyword [:map-of :keyword :keyword]]]]
   [:expected-main-revn {:optional true} ::sm/int]
   ;; when false (the default) the branch and its file are logically
   ;; deleted right after a successful merge, in the SAME transaction (so
   ;; merged copies do not pile up); when true the branch is kept as
   ;; "merged" and can be inspected/restored from the archived list.
   [:keep-branch {:optional true} ::sm/boolean]])

(sv/defmethod ::merge-file-branch
  "Merge a branch into its source file (main).

  Phase 3 scope: clean merges only. If the three-way diff has conflicts
  the command returns `{:status :conflicts}` (resolution UI lands in a
  later phase); if it contains change kinds not yet translatable
  (components, pages, tokens) it returns `{:status :unsupported}` so no
  change is silently dropped. Otherwise it applies the merge to main
  through the production change pipeline, takes a safety snapshot, marks
  the branch merged and notifies open clients via msgbus."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:merge-file-branch
   ::climit/id [[:merge-file-branch/by-profile ::rpc/profile-id]
                [:merge-file-branch/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id resolutions expected-main-revn keep-branch]}]
  (check-branching-enabled!)
  (let [branch (db/get* cfg :file-branch {:id branch-id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found
                :code :branch-not-found
                :branch-id branch-id))
    (when (not= "open" (:status branch))
      (ex/raise :type :validation
                :code :branch-not-open
                :branch-id branch-id))

    (let [tpoint         (ct/tpoint)
          main-id        (:source-file-id branch)
          branch-file-id (:branch-file-id branch)]
      ;; Only editors of main can integrate (same rule as Figma).
      (files/check-edition-permissions! cfg profile-id main-id)

      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         ;; Serialize against concurrent edits/merges on BOTH files (same
         ;; advisory lock the normal update-file path takes; stable order
         ;; to avoid deadlocks): without the branch lock, edits saved to
         ;; the branch while the merge runs would be silently lost when
         ;; the branch is marked merged (and possibly deleted) below.
         (run! (partial db/xact-lock! conn) (sort [main-id branch-file-id]))

         (let [main-file   (-> (bfc/get-file cfg main-id :realize? true)
                               (check-file-size-limits! :merge))
               _           (-> (branch-log cfg branch-file-id)
                               (check-oplog-depth-limit! branch-file-id :merge))
               branch-file (bfc/get-file cfg branch-file-id
                                         :realize? true :include-base-data? true)
               base-data   (branch-base-data branch-file)

               ;; canonicalize the branch's local refs to main's ids: the
               ;; branch file id (components, library color/typography
               ;; refs) and the paired media ids; media ADDED on the
               ;; branch gets fresh ids pre-allocated here — the rows are
               ;; copied into main only if the merge actually applies.
               pairs       (media-pairs cfg branch-file-id main-id)
               new-media   (unpaired-media-rows cfg branch-file-id (:data branch-file) pairs)
               fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
               id-map      (-> pairs
                               (merge fresh-map)
                               (assoc branch-file-id main-id))
               branch-data (bm/remap-refs (:data branch-file) id-map)]

           (when (and (some? expected-main-revn)
                      (not= expected-main-revn (:revn main-file)))
             (ex/raise :type :conflict
                       :code :file-modified
                       :hint "main was modified, recompute the diff and retry"))

           (let [merge-summary (bm/compute-merge base-data (:data main-file)
                                                 branch-data :branch->main)
                 conflicts  (:conflicts merge-summary)
                 resolved?  (fn [c] (bm/conflict-resolved? c (get resolutions (:id c))))
                 unresolved (remove resolved? conflicts)

                 finish-branch!
                 (fn [ts]
                   ;; mark merged. The pinned base snapshot stays for a
                   ;; KEPT branch (its state is derived from that
                   ;; snapshot and the op log, so releasing the pin
                   ;; would leave it unreadable once the snapshot GC
                   ;; runs); delete-branch! releases it on the delete
                   ;; path. Unless the user chose to keep it the branch
                   ;; and its file are deleted in this same transaction
                   ;; (no client-driven second call, no window where a
                   ;; crash leaves a stale copy).
                   (db/update! conn :file-branch
                               {:status "merged"
                                :merged-at ts
                                :merged-by profile-id
                                :updated-at ts}
                               {:id branch-id}
                               {::db/return-keys false})
                   (let [team  (teams/get-team conn :profile-id profile-id :file-id main-id)
                         delay (ldel/get-deletion-delay team)]
                     ;; an open pull request over this branch has served
                     ;; its purpose: mark it merged and close its sandbox
                     (close-branch-pull-requests! cfg branch-id
                                                  {:profile-id profile-id
                                                   :status "merged"
                                                   :deleted-at (ct/in-future delay)})
                     (when-not keep-branch
                       (delete-branch! cfg branch {:profile-id profile-id
                                                   :session-id session-id}))))]
             (cond
               (seq unresolved)
               (audited {:status :conflicts :conflicts conflicts} tpoint :merge)

               :else
               (let [{:keys [changes unsupported]}
                     (bm/compute-changes base-data (:data main-file) branch-data
                                         (or resolutions {})
                                         merge-summary)]
                 (cond
                   (seq unsupported)
                   (audited {:status :unsupported :kinds (vec unsupported)} tpoint :merge)

                   (empty? changes)
                   ;; Nothing to integrate (branch matches main): close the
                   ;; branch without touching main.
                   (let [ts (ct/now)]
                     (finish-branch! ts)
                     (audited {:status :merged :revn (:revn main-file) :source-file-id main-id}
                              tpoint :merge))

                   :else
                   (let [team  (teams/get-team conn :profile-id profile-id :file-id main-id)
                         delay (ldel/get-deletion-delay team)
                         ts    (ct/now)]
                     (binding [pmap/*tracked* (pmap/create-tracked)
                               pmap/*load-fn*  (partial fdata/load-pointer cfg main-id)
                               cfeat/*current*  (:features main-file)
                               cfeat/*previous* (:features main-file)]

                       ;; Safety snapshot of pre-merge main (rollback via versions).
                       (fsnap/create! cfg main-file
                                      {:label (str "pre-merge/" (:name branch))
                                       :created-by "system"
                                       :deleted-at (ct/in-future delay)
                                       :profile-id profile-id})

                       ;; media added on the branch: copy its rows into main
                       ;; under the pre-allocated ids the changes reference
                       (copy-media-rows! conn main-id fresh-map new-media)

                       (let [merged (-> main-file
                                        (update :revn inc)
                                        (update :data #(cpc/process-changes % changes)))
                             libs   (bfc/get-resolved-file-libraries cfg merged)
                             errors (not-empty (cfv/validate-file merged libs))
                             merged (if errors
                                      (update merged :data cpc/process-changes
                                              (cfr/repair-file merged libs errors))
                                      merged)]

                         ;; Change log (xlog), GC-eligible after the delay.
                         (db/insert! conn :file-change
                                     {:id (uuid/next)
                                      :session-id session-id
                                      :profile-id profile-id
                                      :created-at ts
                                      :updated-at ts
                                      :deleted-at (ct/in-future {:hours 1})
                                      :file-id main-id
                                      :revn (:revn merged)
                                      :version (:version merged)
                                      :features (into-array (:features merged))
                                      :changes (blob/encode (vec changes))}
                                     {::db/return-keys false})

                         (fupd/persist-file! (assoc cfg ::fupd/timestamp ts) merged)

                         (finish-branch! ts)

                         (mbus/pub! msgbus
                                    :topic main-id
                                    :message {:type :file-merged
                                              :file-id main-id
                                              :session-id session-id
                                              :revn (:revn merged)})

                         (audited {:status :merged :revn (:revn merged) :source-file-id main-id}
                                  tpoint :merge))))))))))))))

;; --- COMMAND: update-branch-from-main

(def ^:private schema:update-branch-from-main
  [:map {:title "update-branch-from-main"}
   [:branch-id ::sm/uuid]
   ;; resolutions in UI terms: id -> :main (take main) | :branch (keep
   ;; branch) | {attr -> side} (per-attr)
   [:resolutions {:optional true} [:map-of :any [:or :keyword [:map-of :keyword :keyword]]]]])

(defn- persist-branch-update!
  "Persist the result of an update-from-main integration on a branch
  file: update the `file` row and the project modified-at without any
  data payload, and REPLACE the branch's op log with the squashed
  branch-only changes. The repositioned merge base already carries
  everything main contributed, so replaying the squash over it
  reproduces the updated branch exactly, and the log never accumulates
  main-side ops (which would otherwise be double-applied on read)."
  [{:keys [::db/conn] :as cfg} file ts branch-id changes]
  (let [file (-> file
                 (dissoc ::snapshot)
                 (assoc :modified-at ts)
                 (assoc :has-media-trimmed false))]
    (db/update! conn :project
                {:modified-at ts}
                {:id (:project-id file)}
                {::db/return-keys false})
    (db/delete! conn :file-branch-change {:branch-id branch-id})
    (when (seq changes)
      (db/insert! conn :file-branch-change
                  {:id (uuid/next)
                   :branch-id branch-id
                   :file-id (:id file)
                   :revn (:revn file)
                   :changes (blob/encode (vec changes))
                   :created-at ts
                   :updated-at ts}
                  {::db/return-keys false}))
    (bfc/update-file-row! cfg file)
    nil))


(sv/defmethod ::update-branch-from-main
  "Bring the changes main received since the merge base into the branch
  (the reverse direction of a merge). Returns `{:status :conflicts}` when
  main and the branch diverged on the same entity and it is not resolved
  in `resolutions`, or `{:status :unsupported}` for change kinds not yet
  translatable. On success it applies main's changes (and any conflicts
  resolved to main) to the branch, takes a safety snapshot, and
  repositions the merge base to the current state of main."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:update-branch-from-main
   ::climit/id [[:update-branch-from-main/by-profile ::rpc/profile-id]
                [:update-branch-from-main/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id resolutions]}]
  (check-branching-enabled!)
  (let [branch (db/get* cfg :file-branch {:id branch-id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
    (when (not= "open" (:status branch))
      (ex/raise :type :validation :code :branch-not-open :branch-id branch-id))

    (let [tpoint         (ct/tpoint)
          branch-file-id (:branch-file-id branch)
          main-id        (:source-file-id branch)]
      ;; Editing the branch -> need edition permissions on the branch file.
      (files/check-edition-permissions! cfg profile-id branch-file-id)

      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         (db/xact-lock! conn branch-file-id)

         (let [main-file   (-> (bfc/get-file cfg main-id :realize? true)
                               (check-file-size-limits! :update-from-main))
               _           (-> (branch-log cfg branch-file-id)
                               (check-oplog-depth-limit! branch-file-id :update-from-main))
               branch-file (bfc/get-file cfg branch-file-id :realize? true)
               base-raw    (get-base-data cfg branch)

               team  (teams/get-team conn :profile-id profile-id :file-id branch-file-id)
               delay (ldel/get-deletion-delay team)

               ;; Two id maps, one per direction of the crossing.
               ;;
               ;; The COMPARISON runs in main's frame, the frame the merge
               ;; base was captured in, the frame the compare view uses
               ;; (`files_branch.clj::branch-id-map` in
               ;; `files_branch.clj::get-branch-diff`), and the only frame
               ;; the branch's inherited content agrees with. A branch stores
               ;; no data, so `binfile.clj::branch-file-data` replays its op
               ;; log over that base and everything it inherited carries the
               ;; base's ids. Comparing in the branch's frame instead
               ;; rewrites main's and the base's local references to the
               ;; branch file id, and a self reference in main is then
               ;; unequal to the branch's own copy of it, so every shape
               ;; main touched that carries one reads as an edit on both
               ;; sides.
               base-data   base-raw
               main-raw    (:data main-file)
               branch-cmp  (bm/remap-refs (:data branch-file)
                                          (branch-id-map cfg branch-file-id main-id))

               ;; The WRITE runs in the branch's frame: a reference is only a
               ;; reference in the file it names, so main's file id and
               ;; main's media rows are re-pointed at the branch's ids before
               ;; the change reaches the branch. Media ADDED on main has no
               ;; branch row yet, so it gets a fresh id here and its row is
               ;; copied below only if the update applies.
               pairs       (media-pairs cfg main-id branch-file-id)
               new-media   (unpaired-media-rows cfg main-id main-raw pairs)
               fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
               apply-map   (-> pairs
                               (merge fresh-map)
                               (assoc main-id branch-file-id))
               main-data   (bm/remap-refs main-raw apply-map)

               ;; did the branch carry its OWN changes before this update?
               ;; (revn moved since the base was last positioned)
               branch-diverged?
               (pos? (- (:revn branch-file)
                        (or (:base-branch-revn branch) (:base-revn branch))))

               reposition-base!
               (fn [ts branch-revn]
                 (let [new-base (fsnap/create! cfg main-file
                                               {:label (str "branch-base/" (:name branch))
                                                :created-by "system"
                                                :deleted-at (ct/in-future {:days 3650})
                                                :profile-id profile-id})
                       ;; the ahead/behind revn gate compares each side's revn
                       ;; against this stored counter to SKIP the diff. After
                       ;; an update the new base equals MAIN, so a branch that
                       ;; had its own changes still differs from it even if
                       ;; its revn never moves again — keep the gate open by
                       ;; storing one revn less; the diff then reports the
                       ;; real counts.
                       branch-revn (if branch-diverged? (dec branch-revn) branch-revn)]
                   ;; the previous base is superseded: release its pin
                   (release-base-snapshot! cfg branch (ct/in-future delay))
                   (db/update! conn :file-branch
                               {:base-snapshot-id (:id new-base)
                                :base-revn (:revn main-file)
                                :base-branch-revn branch-revn
                                :updated-at ts}
                               {:id branch-id}
                               {::db/return-keys false})))

               merge-summary
               (bm/compute-merge base-data main-raw branch-cmp :main->branch)

               conflicts  (:conflicts merge-summary)
               resolved?  (fn [c] (bm/conflict-resolved? c (get resolutions (:id c))))
               unresolved (remove resolved? conflicts)

               ;; UI resolutions are in main/branch terms; the update applies
               ;; main->branch (compute-changes target=branch, source=main),
               ;; where :branch means "take the source (main)". So invert each
               ;; side — including the per-attr maps, value by value.
               flip     (fn [v] (case v :main :branch, :branch :main, v))
               inverted (into {} (map (fn [[k v]]
                                        [k (if (map? v)
                                             (update-vals v flip)
                                             (flip v))]))
                              resolutions)]

           (cond
             (seq unresolved)
             (audited {:status :conflicts :conflicts conflicts} tpoint :update-from-main)

             :else
             ;; target = branch, source = main -> changes that bring main's
             ;; net changes (and conflicts resolved to main) into the branch.
             ;; The `:main->branch` summary maps to the same (theirs, ours)
             ;; assignment compute-changes uses internally here, so it can
             ;; be reused for the unsupported-kinds gate without re-diffing.
             (let [{:keys [changes unsupported]}
                   (bm/compute-changes base-data branch-cmp main-raw
                                       (or inverted {})
                                       merge-summary)]
               (cond
                 (seq unsupported)
                 (audited {:status :unsupported :kinds (vec unsupported)} tpoint :update-from-main)

                 (empty? changes)
                 (let [ts (ct/now)]
                   ;; the branch is in sync with main: the repositioned
                   ;; base already represents the branch, so the op log
                   ;; is emptied (old ops were built against the old base)
                   (db/delete! conn :file-branch-change {:branch-id branch-id})
                   (reposition-base! ts (:revn branch-file))
                   (audited {:status :updated :revn (:revn branch-file)} tpoint :update-from-main))

                 :else
                 (let [ts (ct/now)]
                   (binding [pmap/*tracked* (pmap/create-tracked)
                             pmap/*load-fn*  (partial fdata/load-pointer cfg branch-file-id)
                             cfeat/*current*  (:features branch-file)
                             cfeat/*previous* (:features branch-file)]

                     (fsnap/create! cfg branch-file
                                    {:label (str "pre-update/" (:name branch))
                                     :created-by "system"
                                     :deleted-at (ct/in-future delay)
                                     :profile-id profile-id})

                     ;; media added on main: copy its rows into the branch
                     ;; under the pre-allocated ids the changes reference
                     (copy-media-rows! conn branch-file-id fresh-map new-media)

                     (let [applied (bm/remap-changes changes apply-map)
                           updated (-> branch-file
                                       (update :revn inc)
                                       (update :data #(cpc/process-changes % applied)))
                           libs    (bfc/get-resolved-file-libraries cfg updated)
                           errors  (not-empty (cfv/validate-file updated libs))
                           updated (if errors
                                     (update updated :data cpc/process-changes
                                             (cfr/repair-file updated libs errors))
                                     updated)]

                       (db/insert! conn :file-change
                                   {:id (uuid/next)
                                    :session-id session-id
                                    :profile-id profile-id
                                    :created-at ts
                                    :updated-at ts
                                    :deleted-at (ct/in-future {:hours 1})
                                    :file-id branch-file-id
                                    :revn (:revn updated)
                                    :version (:version updated)
                                    :features (into-array (:features updated))
                                    :changes (blob/encode (vec applied))}
                                   {::db/return-keys false})

                       ;; SQUASH: the new base (main's current state)
                       ;; already carries everything main contributed,
                       ;; so the op log is replaced with the net
                       ;; branch-only changes computed against the new
                       ;; base. Refuse if that net cannot be translated
                       ;; into replayable ops; nothing has been
                       ;; persisted yet, so the transaction rollback
                       ;; undoes the snapshot and media copies above.
                       (let [{net-changes :changes
                              unsupported :unsupported}
                             (bm/compute-changes main-data main-data (:data updated) {})]
                         (when (seq unsupported)
                           (ex/raise :type :validation
                                     :code :unsupported-update-squash
                                     :hint "the update produces branch-only changes that cannot be replayed"
                                     :kinds (vec unsupported)))
                         (persist-branch-update! cfg updated ts branch-id net-changes))
                       (reposition-base! ts (:revn updated))

                       (mbus/pub! msgbus
                                  :topic branch-file-id
                                  :message {:type :file-merged
                                            :file-id branch-file-id
                                            :session-id session-id
                                            :revn (:revn updated)})

                       (audited {:status :updated :revn (:revn updated)}
                                tpoint :update-from-main)))))))))))))

;; --- COMMAND: materialize-file-branch

(def ^:private schema:materialize-file-branch
  [:map {:title "materialize-file-branch"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::materialize-file-branch
  "Turn a branch file into an ordinary file: persist its derived state as
  the file's own data payload, drop the op log, release the pinned base
  snapshot, close any pull request over it, and clear the branch marker so
  every read and write path takes the ordinary route from then on
  (`binfile::get-file*` and `files-update::update-file*` both switch on
  that flag).

  This is the preview's exit door: with it the `:branching` flag can go
  off and the engine can be replaced without stranding anybody with a file
  nobody can open.

  It keys on the branch FILE rather than on the branch metadata, which is
  what makes it idempotent: a file carrying no live branch row is already
  materialised, and the command reports that without touching anything."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:materialize-file-branch
   ::climit/id [[:materialize-file-branch/by-profile ::rpc/profile-id]
                [:materialize-file-branch/global]]}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (check-branching-enabled!)
  (files/check-edition-permissions! cfg profile-id file-id)
  (let [tpoint (ct/tpoint)]
    (db/tx-run!
     cfg
     (fn [{:keys [::db/conn] :as cfg}]
       ;; the advisory lock every save on this file takes: a concurrent
       ;; branch save must not land between the derive and the persist, or
       ;; its op would be dropped together with the log
       (db/xact-lock! conn file-id)

       (let [branch (db/get* conn :file-branch {:branch-file-id file-id})]
         (if (nil? branch)
           (audited {:status :materialized :file-id file-id :changed false}
                    tpoint :materialize)
           (let [file  (bfc/get-file cfg file-id :realize? true)
                 team  (teams/get-team conn :profile-id profile-id :file-id file-id)
                 delay (ldel/get-deletion-delay team)
                 ts    (ct/now)]

             (binding [pmap/*tracked* (pmap/create-tracked)
                       pmap/*load-fn*  (partial fdata/load-pointer cfg file-id)
                       cfeat/*current*  (:features file)
                       cfeat/*previous* (:features file)]

               (let [libs   (bfc/get-resolved-file-libraries cfg file)
                     errors (not-empty (cfv/validate-file file libs))
                     file   (if errors
                              (update file :data cpc/process-changes
                                      (cfr/repair-file file libs errors))
                              file)]

                 ;; the derived state becomes the file's own payload
                 (fupd/persist-file! (assoc cfg ::fupd/timestamp ts) file)

                 ;; from here the ordinary paths apply
                 (db/update! conn :file
                             {:is-branch false}
                             {:id file-id}
                             {::db/return-keys false})

                 ;; the payload replaced the log
                 (db/delete! conn :file-branch-change {:branch-id (:id branch)})

                 ;; a review sandbox over a file that is no longer a branch
                 ;; has nothing left to review
                 (close-branch-pull-requests! cfg (:id branch)
                                              {:profile-id profile-id
                                               :status "closed"
                                               :deleted-at (ct/in-future delay)})

                 ;; nothing derives from the base any more, so the pin goes
                 (release-base-snapshot! cfg branch (ct/in-future delay))

                 ;; The branch row is archived rather than deleted: its pull
                 ;; requests reference it with ON DELETE CASCADE, so deleting
                 ;; it would take the review history along, and `archived` is
                 ;; a status the schema already allows.
                 (db/update! conn :file-branch
                             {:status "archived"
                              :deleted-at (ct/in-future delay)
                              :updated-at ts}
                             {:id (:id branch)}
                             {::db/return-keys false})

                 (audited
                  {:status :materialized
                   :file-id file-id
                   :changed true
                   :revn (:revn file)}
                  tpoint :materialize))))))))))

;; --- COMMAND QUERY: get-file-branch-info

(def ^:private schema:get-file-branch-info
  [:map {:title "get-file-branch-info"}
   [:file-id ::sm/uuid]])

(def ^:private sql:get-file-branch-info
  "SELECT fb.id,
          fb.branch_file_id,
          fb.source_file_id,
          fb.name,
          fb.description,
          fb.status,
          fb.created_by,
          fb.created_at,
          fb.updated_at,
          fb.base_revn,
          fb.base_branch_revn,
          fb.base_snapshot_id,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS source_name
     FROM file_branch AS fb
     JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE fb.branch_file_id = ?
      AND fb.deleted_at IS NULL")

(sv/defmethod ::get-file-branch-info
  "If the given file is a branch, return its branch metadata (with cheap
  revn-based ahead/behind); otherwise nil. Returns nil when branching is
  disabled, so it is safe to call on every file open."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-info}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (when (contains? cf/flags :branching)
    (db/run! cfg
             (fn [{:keys [::db/conn] :as cfg}]
               (files/check-read-permissions! cfg profile-id file-id)
               (when-let [{:keys [source-file-id status] :as row}
                          (db/exec-one! conn [sql:get-file-branch-info file-id])]
                 ;; `ahead`/`behind` count actual entity-level changes (what
                 ;; the compare dialog lists), NOT the raw revn delta — a
                 ;; single edit can span many save-revns and noise is filtered.
                 ;; The frontend asks this on every file open, so it reads the
                 ;; same summary cache as the two listings.
                 (let [[ahead behind conflicts]
                       (if (= "open" status)
                         (cached-diff-counts cfg nil
                                             (assoc row
                                                    :source-file-id source-file-id
                                                    :branch-file-id file-id))
                         [0 0 0])]
                   (-> row
                       (assoc :ahead ahead :behind behind :conflicts conflicts)
                       (dissoc :branch-revn :source-revn :base-snapshot-id :base-branch-revn))))))))

;; --- COMMAND: update-file-branch (rename / description)

(def ^:private schema:update-file-branch
  [:map {:title "update-file-branch"}
   [:id ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::update-file-branch
  "Rename a branch or update its description."
  {::doc/added "2.16"
   ::sm/params schema:update-file-branch
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id name description]}]
  (check-branching-enabled!)
  (when (and (some? name) (str/blank? name))
    (ex/raise :type :validation
              :code :invalid-branch-name
              :hint "branch name cannot be blank"))
  (let [branch (db/get* conn :file-branch {:id id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found :code :branch-not-found :branch-id id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    (db/update! conn :file-branch
                (cond-> {:updated-at (ct/now)}
                  (some? name)        (assoc :name name)
                  (some? description) (assoc :description description))
                {:id id}
                {::db/return-keys false})
    {:id id
     :name (or name (:name branch))
     :description (or description (:description branch))}))

;; --- COMMAND: archive-file-branch

(def ^:private schema:archive-file-branch
  [:map {:title "archive-file-branch"}
   [:id ::sm/uuid]
   [:archived {:optional true} ::sm/boolean]])

(sv/defmethod ::archive-file-branch
  "Archive (hide from the default list) or restore a branch."
  {::doc/added "2.16"
   ::sm/params schema:archive-file-branch
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id archived]}]
  (check-branching-enabled!)
  (let [branch (db/get* conn :file-branch {:id id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found :code :branch-not-found :branch-id id))
    (when (= "merged" (:status branch))
      (ex/raise :type :validation :code :branch-merged :branch-id id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    (let [status (if (false? archived) "open" "archived")]
      (db/update! conn :file-branch
                  {:status status :updated-at (ct/now)}
                  {:id id}
                  {::db/return-keys false})
      ;; archiving hides the branch from the default flow, so its review
      ;; sandbox has no reason to stay open either
      (when (= "archived" status)
        (let [team (teams/get-team conn :profile-id profile-id
                                   :file-id (:branch-file-id branch))
              dt   (ct/in-future (ldel/get-deletion-delay team))]
          (close-branch-pull-requests! cfg id {:profile-id profile-id
                                               :status "closed"
                                               :deleted-at dt})))
      {:id id :status status})))

;; --- COMMAND: delete-file-branch

(def ^:private schema:delete-file-branch
  [:map {:title "delete-file-branch"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-file-branch
  "Logically delete a branch: marks the branch metadata and the branch
  file as deleted (with the team's deletion delay) and schedules the
  file object for GC, mirroring the normal file deletion path."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:delete-file-branch}
  [cfg {:keys [::rpc/profile-id ::rpc/session-id id]}]
  (check-branching-enabled!)
  (let [branch (db/get* cfg :file-branch {:id id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found :code :branch-not-found :branch-id id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    (db/tx-run!
     cfg
     (fn [cfg]
       (delete-branch! cfg branch {:profile-id profile-id
                                   :session-id session-id
                                   :status "archived"})
       {:status :deleted}))))
