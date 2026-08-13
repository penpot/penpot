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
  the three-way merge into \"branch overwrites main\" without conflicts."
  [cfg {:keys [source-file-id base-snapshot-id] :as branch}]
  (when-not base-snapshot-id
    (ex/raise :type :not-found
              :code :base-snapshot-missing
              :hint "the branch has no merge-base snapshot"
              :branch-id (:id branch)))
  (:data (fsnap/get-snapshot cfg source-file-id base-snapshot-id)))

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

  (let [file-row (db/get-by-id cfg :file file-id)
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
                  (let [file (bfc/get-file cfg file-id :realize? true)
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

                    {:id meta-id
                     :branch-file-id branch-id
                     :source-file-id file-id
                     :base-revn (:revn file)
                     :name name
                     :description description
                     :status "open"})))))

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

(defn branch-diff-counts
  "Entity-level `[ahead behind conflicts]` change counts between a branch
  file and its source (main) — the same numbers the compare dialog lists
  (root-frame churn and structural noise filtered out). The revn deltas
  are used only as a cheap gate to skip the (expensive) 3-way diff when a
  side hasn't moved. `main-data` may be pre-realized and shared across
  branches of the same source; otherwise it is loaded on demand.

  When the base snapshot cannot be resolved the counts degrade to zeros
  with a warning: this is a read-only listing, and merge/update DO refuse
  loudly in that situation (`get-base-data`)."
  [cfg main-data {:keys [source-file-id branch-file-id ahead-revn behind-revn] :as branch}]
  (if (and (zero? ahead-revn) (zero? behind-revn))
    [0 0 0]
    (try
      (let [main-data   (or main-data (:data (bfc/get-file cfg source-file-id :realize? true)))
            branch-data (bm/remap-refs
                         (:data (bfc/get-file cfg branch-file-id :realize? true))
                         (branch-id-map cfg branch-file-id source-file-id))
            base-data   (get-base-data cfg branch)
            clean-count (fn [m] (let [s (:stats m)]
                                  (+ (:added s) (:modified s) (:deleted s))))
            fwd (when (pos? ahead-revn)
                  (bm/compute-merge base-data main-data branch-data :branch->main))
            bwd (when (pos? behind-revn)
                  (bm/compute-merge base-data branch-data main-data :branch->main))]
        [(if fwd (clean-count fwd) 0)
         (if bwd (clean-count bwd) 0)
         ;; conflicts are symmetric; only possible when both sides diverged
         (if (and (pos? ahead-revn) (pos? behind-revn)) (count (:conflicts fwd)) 0)])
      (catch Throwable cause
        (l/wrn :hint "unable to compute branch diff counts"
               :branch-file-id (str branch-file-id)
               :source-file-id (str source-file-id)
               :cause cause)
        [0 0 0]))))

(sv/defmethod ::get-file-branches
  "List the branches of a file. `ahead`/`behind` are entity-level change
  counts (matching the compare dialog), gated by the cheap revn deltas so
  in-sync branches skip the diff entirely; `main` is realized once and
  shared across branches."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branches}
  [cfg {:keys [::rpc/profile-id file-id include-archived]}]
  (check-branching-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (files/check-read-permissions! cfg profile-id file-id)
             (let [rows      (db/exec! conn [sql:get-file-branches file-id (boolean include-archived)])
                   ;; only OPEN branches get diff counts: merged/archived ones
                   ;; are not going to be merged as-is, so the expensive diff
                   ;; would be wasted work (and their base may be released)
                   open?     (fn [row] (= "open" (:status row)))
                   ;; realize main once, only if some open branch diverged
                   need?     (some (fn [r] (and (open? r)
                                                (let [[a b] (revn-deltas r)] (or (pos? a) (pos? b)))))
                                   rows)
                   main-data (when need? (:data (bfc/get-file cfg file-id :realize? true)))]
               (mapv (fn [{:keys [base-snapshot-id branch-file-id] :as row}]
                       (let [[ahead-revn behind-revn] (revn-deltas row)
                             [ahead behind conflicts]
                             (if (open? row)
                               (branch-diff-counts cfg main-data
                                                   {:id (:id row)
                                                    :source-file-id file-id
                                                    :branch-file-id branch-file-id
                                                    :base-snapshot-id base-snapshot-id
                                                    :ahead-revn ahead-revn
                                                    :behind-revn behind-revn})
                               [0 0 0])]
                         (-> row
                             (assoc :ahead ahead :behind behind :conflicts conflicts)
                             (dissoc :branch-revn :source-revn :base-snapshot-id :base-branch-revn))))
                     rows)))))

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

    (let [main-file   (bfc/get-file cfg (:source-file-id branch) :realize? true)
          branch-file (bfc/get-file cfg (:branch-file-id branch) :realize? true)
          main-data   (:data main-file)
          branch-data (bm/remap-refs
                       (:data branch-file)
                       (branch-id-map cfg (:branch-file-id branch) (:source-file-id branch)))
          base-data   (get-base-data cfg branch)]
      ;; `:meta` carries the "when" of each side so the resolution UI can show
      ;; how recent main/branch are (base is pinned at branch creation), plus
      ;; `:main-revn` so the client can do optimistic concurrency on merge
      ;; (`expected-main-revn`). The last editor's identity is intentionally
      ;; omitted: files do not store a reliable "modified-by".
      (-> (bm/compute-merge base-data main-data branch-data (or direction :branch->main))
          (assoc :meta {:base-at   (:created-at branch)
                        :main-at   (:modified-at main-file)
                        :branch-at (:modified-at branch-file)
                        :main-revn (:revn main-file)})))))

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
   ::climit/id [[:merge-file-branch/global]]}
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

    (let [main-id        (:source-file-id branch)
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

         (let [main-file   (bfc/get-file cfg main-id :realize? true)
               branch-file (bfc/get-file cfg branch-file-id :realize? true)
               base-data   (get-base-data cfg branch)

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
               {:status :conflicts :conflicts conflicts}

               :else
               (let [{:keys [changes unsupported]}
                     (bm/compute-changes base-data (:data main-file) branch-data
                                         (or resolutions {})
                                         merge-summary)]
                 (cond
                   (seq unsupported)
                   {:status :unsupported :kinds (vec unsupported)}

                   (empty? changes)
                   ;; Nothing to integrate (branch matches main): close the
                   ;; branch without touching main.
                   (let [ts (ct/now)]
                     (finish-branch! ts)
                     {:status :merged :revn (:revn main-file) :source-file-id main-id})

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

                         {:status :merged :revn (:revn merged) :source-file-id main-id})))))))))))))

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
   ::climit/id [[:update-branch-from-main/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id resolutions]}]
  (check-branching-enabled!)
  (let [branch (db/get* cfg :file-branch {:id branch-id})]
    (when (or (nil? branch) (some? (:deleted-at branch)))
      (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
    (when (not= "open" (:status branch))
      (ex/raise :type :validation :code :branch-not-open :branch-id branch-id))

    (let [branch-file-id (:branch-file-id branch)
          main-id        (:source-file-id branch)]
      ;; Editing the branch -> need edition permissions on the branch file.
      (files/check-edition-permissions! cfg profile-id branch-file-id)

      (db/tx-run!
       cfg
       (fn [{:keys [::db/conn] :as cfg}]
         (db/xact-lock! conn branch-file-id)

         (let [main-file   (bfc/get-file cfg main-id :realize? true)
               branch-file (bfc/get-file cfg branch-file-id :realize? true)
               base-raw    (get-base-data cfg branch)

               team  (teams/get-team conn :profile-id profile-id :file-id branch-file-id)
               delay (ldel/get-deletion-delay team)

               ;; update applies main's changes INTO the branch, so the
               ;; canonical local ids are the branch's: re-point main/base
               ;; local refs (file id + paired media) to the branch's ids;
               ;; media ADDED on main gets fresh ids pre-allocated here and
               ;; its rows copied into the branch only if the update applies.
               pairs       (media-pairs cfg main-id branch-file-id)
               new-media   (unpaired-media-rows cfg main-id (:data main-file) pairs)
               fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
               id-map      (-> pairs
                               (merge fresh-map)
                               (assoc main-id branch-file-id))
               base-data   (bm/remap-refs base-raw id-map)
               main-data   (bm/remap-refs (:data main-file) id-map)

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
               (bm/compute-merge base-data main-data
                                 (:data branch-file) :main->branch)

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
             {:status :conflicts :conflicts conflicts}

             :else
             ;; target = branch, source = main -> changes that bring main's
             ;; net changes (and conflicts resolved to main) into the branch.
             ;; The `:main->branch` summary maps to the same (theirs, ours)
             ;; assignment compute-changes uses internally here, so it can
             ;; be reused for the unsupported-kinds gate without re-diffing.
             (let [{:keys [changes unsupported]}
                   (bm/compute-changes base-data (:data branch-file) main-data
                                       (or inverted {})
                                       merge-summary)]
               (cond
                 (seq unsupported)
                 {:status :unsupported :kinds (vec unsupported)}

                 (empty? changes)
                 (let [ts (ct/now)]
                   ;; the branch is in sync with main: the repositioned
                   ;; base already represents the branch, so the op log
                   ;; is emptied (old ops were built against the old base)
                   (db/delete! conn :file-branch-change {:branch-id branch-id})
                   (reposition-base! ts (:revn branch-file))
                   {:status :updated :revn (:revn branch-file)})

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

                     (let [updated (-> branch-file
                                       (update :revn inc)
                                       (update :data #(cpc/process-changes % changes)))
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
                                    :changes (blob/encode (vec changes))}
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

                       {:status :updated :revn (:revn updated)}))))))))))))

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
               (when-let [{:keys [base-snapshot-id source-file-id status] :as row}
                          (db/exec-one! conn [sql:get-file-branch-info file-id])]
                 ;; `ahead`/`behind` count actual entity-level changes (what
                 ;; the compare dialog lists), NOT the raw revn delta — a
                 ;; single edit can span many save-revns and noise is filtered.
                 (let [[ahead-revn behind-revn] (revn-deltas row)
                       [ahead behind conflicts]
                       (if (= "open" status)
                         (branch-diff-counts cfg nil
                                             {:id (:id row)
                                              :source-file-id source-file-id
                                              :branch-file-id file-id
                                              :base-snapshot-id base-snapshot-id
                                              :ahead-revn ahead-revn
                                              :behind-revn behind-revn})
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
