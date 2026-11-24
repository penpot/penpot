;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.files-branch
  "RPC commands for file branching: create a branch of a file linked to
  its source file, list branches, diff a branch against its source,
  merge a branch into its source (with conflict resolutions), apply a
  branch to its source and keep it open, and update a branch from its
  source. A branch is a file row with no data of its own: its state is
  derived from a pinned base snapshot of the source plus the branch's op
  log (`app.binfile.common/branch-file-data`). See
  `app.rpc.commands.files-snapshot` for the patterns this namespace
  mirrors."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.files.changes :as cpc]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as fdata]
   [app.features.file-changes :as fchanges]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.jobs :as jobs]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-branch-policies :as bp]
   [app.rpc.commands.files-create :as fcreate]
   [app.rpc.commands.files-task :as ftask]
   [app.rpc.commands.files-update :as fupd]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.util.blob :as blob]
   [app.util.cache :as ucache]
   [app.util.services :as sv]
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
;; For large documents, we are currently slow. To avoid user frustration, we
;; set limits based on waits of at most 30s, and then halve them:

;;  * a merge on the 21,169-shape design system costs about 6 s, which is
;;    0.28 ms per shape, so 30 s is about 105,000 shapes -> 50,000.
;;  * a whole-file comparison of 48 pages costs 402 ms, which is 8.4 ms
;;    per page, so the page limit is far above any real design system and
;;    exists to bound the presence pass -> 500.
;;  * one value-derived squash already emits about 19,000 changes for a
;;    one-page delta, so a depth limit has to clear that with room ->
;;    100,000 changes.
;; (numbers measured on a devenv running on a laptop)
;;
;; A refusal names the limit, the actual value, and what to do instead.


(defn- branching-limit
  [key default]
  (or (cf/get key) default))

(defn- check-file-size-limits!
  "Refuse an operation on a file whose realized state is beyond what the
  feature was measured to survive. A `file` that still holds fragment
  pointers needs the loader of its own id bound (`fdata/with-pointers`)."
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
                           max-pages "; split the file, or detach the branch to keep "
                           "working on it as an ordinary file")
                :operation operation
                :limit max-pages
                :actual pages))

    (when (> shapes max-shapes)
      (ex/raise :type :restriction
                :code :branching-shape-limit-exceeded
                :hint (str "this file has " shapes " shapes and branching is limited to "
                           max-shapes "; split the file, or detach the branch to keep "
                           "working on it as an ordinary file")
                :operation operation
                :limit max-shapes
                :actual shapes))
    file))

(defn- check-oplog-depth-limit!
  "Refuse an operation on a branch whose op log is deeper than the derive
  was measured to survive. `depth` is the number of changes the whole log
  holds, as `bfc/get-branch-oplog-depth` reads it from the count each op
  log row stamps: gating on that instead of the flattened log keeps the
  gate from decoding rows the derive decodes a moment later."
  [depth branch-file-id operation]
  (let [limit (branching-limit :branching-max-oplog-changes 100000)]
    (when (> depth limit)
      (ex/raise :type :restriction
                :code :branching-oplog-limit-exceeded
                :hint (str "this branch has " depth " changes in its op log and the limit is "
                           limit "; detach it into an ordinary file, or delete it")
                :operation operation
                :branch-file-id branch-file-id
                :limit limit
                :actual depth))))

(def ^:private schema:get-branching-limits
  [:map {:title "get-branching-limits"}])

(sv/defmethod ::get-branching-limits
  "Return the maximum number of shapes, pages and changes this
  instance supports. They are advertised on the UI to prevent
  frustration. Read from config on every call to prevent drift."
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
  "Attach the `:branch-operation`, `:branch-outcome` and
  `:branch-duration-ms` props to the audit event of a branch operation,
  and print the matching log line. `result` is returned unchanged to the
  caller, and `extra` lets an operation add numbers of its own to the
  same record.

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
;; A branch copies no media at creation: the content it inherits from its
;; base references the source's file_media_object rows. Rows cross between
;; the two files only as copies under FRESH ids (`copy-media-rows!`): an
;; update from the source copies the media the source added into the
;; branch, and a merge copies the media the branch added into the source.
;; So the same image can carry a different id on each side. Before
;; diffing or merging, the branch-side media ids must be normalized back
;; to the target's ids
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
  dimensions + mtype), such as the rows one file received as a copy of
  the other's (`copy-media-rows!`, run by an update from the source or a
  merge). Rows with several identical copies are paired positionally (they are
  interchangeable). Media added on one side stays unpaired."
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

(defn- branch-media-pairs
  "The branch->source media pairs the comparison and the merge run on,
  from the branch's own record: every op log row stamps the media id map
  the write that produced it applied (`bfc/get-branch-change-media-maps`),
  in the as-applied direction {old-id -> new-id}, so the union of the
  inverted stamps IS the pairing the branch established with its source.
  A row written before the stamps existed comes back without one, and a
  log with any such row cannot certify the full pairing: the
  content-identity heuristic (`media-pairs`) replaces it for the whole
  log. It describes the same pairings from the rows present on both
  files."
  [cfg branch-file-id source-file-id]
  (let [maps (bfc/get-branch-change-media-maps cfg branch-file-id)]
    (if (or (nil? (seq maps)) (some nil? maps))
      (media-pairs cfg branch-file-id source-file-id)
      (reduce (fn [acc m] (merge acc (set/map-invert m))) {} maps))))

(def ^:private sql:media-rows-by-id
  "SELECT * FROM file_media_object WHERE id = ANY(?) AND deleted_at IS NULL")

(defn- used-media-ids
  "The media ids `data` references: `cfh/collect-used-media` plus the
  library-color `:image` ids it does not see but `bm/remap-refs` rewrites."
  [data]
  (into (cfh/collect-used-media data)
        (keep #(get-in % [:image :id]))
        (vals (:colors data))))

(defn- get-media-rows
  "The live file_media_object rows with the given ids."
  [cfg ids]
  (if (seq ids)
    (db/exec! cfg [sql:media-rows-by-id
                   (db/create-array (db/get-connection cfg) "uuid" ids)])
    []))

(defn- unpaired-media-rows
  "Full file_media_object rows of `file-id` that are used by `data` but have
  no counterpart in `pairs` — media added on this side since branching.
  They must be copied into the target file when the merge/update applies."
  [cfg file-id data pairs]
  (->> (get-media-rows cfg (into #{} (remove pairs) (used-media-ids data)))
       (filterv #(= file-id (:file-id %)))))

(defn- foreign-media-rows
  "Full file_media_object rows used by `data` that another file owns. A
  branch reads the images it inherited through its source's rows, and a
  file that leaves the branch model must own copies: the source's
  file-gc and the source's deletion take the source's rows along."
  [cfg file-id data]
  (->> (get-media-rows cfg (used-media-ids data))
       (filterv #(not= file-id (:file-id %)))))

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

(defn- read-source-for-write
  "Read the source for a command that writes a snapshot naming the
  source's fragments or persists the source itself. Returns `[stored
  file]`: `stored` keeps the source's fragment pointers, and `file` is
  the same document realized and checked against the size limits.

  The row is read FOR UPDATE, since a data read cannot take FOR SHARE.
  File-gc takes it FOR UPDATE too and marks every fragment that neither
  the source nor a committed snapshot names, so a snapshot that copies
  the source's pointers must commit before the next pass can read the
  row."
  [cfg source-id operation]
  (let [stored (bfc/get-file cfg source-id :lock-for-update? true)
        file   (-> (fdata/realize cfg stored)
                   (check-file-size-limits! operation))]
    [stored file]))

(defn- reattach-source-pointers
  "`file` with the source's stored fragment pointers put back on every
  page whose value still equals the source's, so a base or a merged
  source shares those fragments instead of writing copies, and an
  unchanged page keeps its fragment id. `stored` is the first value
  `read-source-for-write` returns."
  [cfg source-id stored file]
  (fdata/with-pointers cfg source-id
    (fdata/reattach-unchanged (fdata/original-pointers (:data stored)) file)))

(defn- get-base-data
  "The merge-base `:data` from the branch's base snapshot, migrated to
  the current data version (`bfc/migrate-branch-base`): `bfc/get-file`
  migrates the source and the branch on read, and a base left at an older
  version reads as a change on every side that migrated. Raises when the
  snapshot cannot be resolved: silently falling back to the source would
  degrade the three-way merge into \"branch overwrites source\" without
  conflicts.

  Prefer `branch-base-data` where the branch file was read with
  `:include-base-data? true`: the derive decoded the merge base to replay
  the op log over it, so this function would decode the same snapshot a
  second time.

  The snapshot stores the branch's id space — its media references name
  the branch's own paired rows, what the replay reproduces — while a
  comparison runs in the source's id space, so the media ids are
  normalized back here through the pairs, the way `branch-id-map`
  normalizes the
  branch's own data."
  [cfg {:keys [source-file-id branch-file-id base-snapshot-id] :as branch}]
  (when-not base-snapshot-id
    (ex/raise :type :not-found
              :code :base-snapshot-missing
              :hint "the branch has no merge-base snapshot"
              :branch-id (:id branch)))
  (when-some [data (some->> (fsnap/get-snapshot cfg source-file-id base-snapshot-id)
                            (bfc/realize-branch-base cfg)
                            (bfc/migrate-branch-base cfg))]
    (bm/remap-refs data (branch-media-pairs cfg branch-file-id source-file-id))))

(defn- branch-base-data
  "The comparison's `base`: the merge-base document that a branch file
  read with `:include-base-data? true` carries under `::bfc/base-data`.
  That document is the state the branch's op log was replayed over, that
  is the merge base before the replay, migrated to the current data
  version like the two sides it is compared with. The file's `:data`
  holds the branch's own state, so a comparison against `:data` would
  compare the branch with itself. Raises when the key is absent, so a
  read that forgot the flag fails loudly instead of comparing against
  nil.

  Like `get-base-data`, it comes back with the media ids normalized into
  the source's id space: the snapshot names the branch's own paired rows,
  the comparison runs against the source's."
  [cfg source-file-id {:keys [id] :as branch-file}]
  (-> (or (::bfc/base-data branch-file)
          (ex/raise :type :assertion
                    :code :branch-base-data-missing
                    :hint "the branch file was read without `:include-base-data? true`"))
      (bm/remap-refs (branch-media-pairs cfg id source-file-id))))

(defn- moved-pages
  "The pages on which the source can differ from a branch's base: every
  page whose fragment the source does not share with the base. Nil when
  that set cannot bound a comparison, which leaves the whole-file
  comparison in place: the source or the base holds no pointers, or the
  source ordered the pages both hold differently from the base. The
  page order lives in the document root rather than in a page's
  fragment, and a bounded comparison only orders the pages it covers.
  `stored` is the source's `:data` with its pointers unrealized, `base`
  the comparison's base, and
  `base-ids` the `::bfc/base-page-pointers` of the branch read."
  [stored base base-ids]
  (when (seq base-ids)
    (let [source-ids (fdata/get-page-pointer-ids stored)
          both       (set/intersection (set (:pages stored)) (set (:pages base)))]
      (when (and (seq source-ids)
                 (= (filterv both (:pages stored)) (filterv both (:pages base))))
        (into #{}
              (remove (fn [page-id]
                        (let [id (get source-ids page-id)]
                          (and (some? id) (= id (get base-ids page-id))))))
              (into (set (keys (:pages-index stored))) (keys base-ids)))))))

(defn- comparison-source
  "The source realized for a comparison with a branch, and the pages the
  source moved since the branch's base (`moved-pages`). Only the moved
  pages load: every other page holds the same document as the base's, so
  the base's page, which the derive already realized, stands in for it.
  `base-data` is the comparison's base, in the source's id space."
  [cfg source-file-id branch-file base-data]
  (let [stored (bfc/get-file cfg source-file-id)
        moved  (moved-pages (:data stored) base-data (::bfc/base-page-pointers branch-file))
        shared (fn [pages page-id page]
                 (if (contains? moved page-id)
                   (assoc pages page-id page)
                   (assoc pages page-id (get-in base-data [:pages-index page-id]))))
        stored (cond-> stored
                 (some? moved)
                 (update-in [:data :pages-index] #(reduce-kv shared {} %)))]
    [(fdata/realize cfg stored) moved]))

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

;; --- Helpers: design reviews of a branch
;;
;; Reviews live in `app.rpc.commands.files-branch-review`, which requires
;; THIS namespace (it reuses the diff-count helpers), so the branch
;; lifecycle hooks below work over the table directly to avoid a
;; circular dependency.

(def ^:private sql:get-open-branch-reviews
  "SELECT id, title
     FROM file_branch_review
    WHERE file_branch_id = ?
      AND status = 'open'
      AND deleted_at IS NULL")

(defn- close-branch-reviews
  "Close (or mark merged) any open review of `branch`, for a branch that
  merges, goes, is archived, or stops being a branch. The review row
  itself is kept as history. Must run inside a transaction."
  [{:keys [::db/conn]} {:keys [id]} {:keys [profile-id status]}]
  (doseq [review (db/exec! conn [sql:get-open-branch-reviews id])]
    (let [ts (ct/now)]
      (db/update! conn :file-branch-review
                  {:status (or status "closed")
                   :closed-at ts
                   :closed-by profile-id
                   :updated-at ts}
                  {:id (:id review)}
                  {::db/return-keys false}))))

;; --- Helpers: branch deletion (shared by delete-file-branch and merge)

(defn- delete-branch!
  "Logically delete a branch and its branch file (with the team's deletion
  delay), release the base snapshot, schedule the file object GC and
  return the msgbus notification for the branch file's open clients —
  the caller publishes it, and only once the transaction has committed
  (`tx-run-notify!`). Must run inside a transaction."
  [{:keys [::db/conn] :as cfg}
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
    ;; an open review has nothing left to review (`status` above is the
    ;; BRANCH status; a review closed this way is just "closed")
    (close-branch-reviews cfg branch {:profile-id profile-id
                                      :status "closed"})
    (jobs/submit cfg
                 {::jobs/name :delete-object
                  ::jobs/params {:object :file :deleted-at dt :id branch-file-id}})
    {:topic branch-file-id
     :message {:type :file-deleted
               :file-id branch-file-id
               :profile-id profile-id
               :session-id session-id}}))

;; --- Helpers: merge record

(defn- record-merge
  "Insert the record of an integration of `branch` into its source file
  (see the `file_branch_merge` migration), in the family of that file.
  `kind` says what it did with the branch: \"merge\" deleted it, \"apply\"
  kept it open. The open review of the branch is the design review the
  integration accepts, so it is read here, before the integration closes
  it. A blank comment is stored as no comment. Must run inside the
  integration transaction. Returns the id of the record."
  [conn {:keys [id source-file-id name]} {:keys [kind profile-id comment created-at]}]
  (let [review   (db/exec-one! conn [sql:get-open-branch-reviews id])
        family   (db/get conn :file {:id source-file-id}
                         {::sql/columns [:family-id]
                          ::db/remove-deleted false})
        comment  (when-not (str/blank? comment) (str/trim comment))
        merge-id (uuid/next)]
    (db/insert! conn :file-branch-merge
                {:id merge-id
                 :created-at created-at
                 :file-id source-file-id
                 :family-id (:family-id family)
                 :file-branch-id id
                 :branch-name name
                 :kind kind
                 :merged-by profile-id
                 :comment comment
                 :review-id (:id review)
                 :review-title (:title review)}
                {::db/return-keys false})
    merge-id))

(def ^:private sql:get-child-branches
  "SELECT id, base_snapshot_id
     FROM file_branch
    WHERE source_file_id = ?
      AND status <> 'merged'
      AND deleted_at IS NULL")

(defn- reparent-child-branches
  "Point the branches cut from the merged branch file `branch-file-id` at
  the merge target `target-id` (`:branch-stacking`), so an open child
  follows the work of its parent into the target. The merge base of a
  child is a snapshot of the parent branch file, stored under that file:
  it moves to the target with the edge, or the deletion of the parent
  would take it and the child could no longer derive its state. The base
  revision counted the revisions of the parent, a counter the target does
  not share, so it restarts at zero: the next comparison then runs the
  diff instead of trusting a delta of two unrelated counters. A child
  merged before keeps its edge: the edge names the file it was merged
  into."
  [conn branch-file-id target-id]
  (doseq [{:keys [id base-snapshot-id]} (db/exec! conn [sql:get-child-branches branch-file-id])]
    (when (some? base-snapshot-id)
      (db/update! conn :file-change
                  {:file-id target-id}
                  {:id base-snapshot-id :file-id branch-file-id}
                  {::db/return-keys false})
      (db/update! conn :file-data
                  {:file-id target-id}
                  {:id base-snapshot-id :file-id branch-file-id}
                  {::db/return-keys false}))
    (db/update! conn :file-branch
                {:source-file-id target-id
                 :base-source-revn 0
                 :updated-at (ct/now)}
                {:id id}
                {::db/return-keys false})))

(defn- persist-merge-into-branch
  "Persist a merge whose target is itself a branch (`:branch-stacking`).
  The target stores no data payload, so the `changes` of the merge go to
  the end of its op log, and only its `file` row and the project's
  modified-at are written. Media the merge copied into the target are
  rows of the target, so the op log row records no media pairing."
  [{:keys [::db/conn] :as cfg} file ts changes]
  (let [target (db/get conn :file-branch {:branch-file-id (:id file)})
        file   (-> file
                   (dissoc ::snapshot)
                   (assoc :modified-at ts)
                   (assoc :has-media-trimmed false))]
    (db/update! conn :project
                {:modified-at ts}
                {:id (:project-id file)}
                {::db/return-keys false})
    (db/insert! conn :file-branch-change
                {:id (uuid/next)
                 :branch-id (:id target)
                 :file-id (:id file)
                 :revn (:revn file)
                 :changes (blob/encode (vec changes))
                 :changes-count (count changes)
                 :data-version (fmg/data-version)
                 :created-at ts
                 :updated-at ts}
                {::db/return-keys false})
    (bfc/update-file-row! cfg file)
    nil))

;; --- Helpers: a branch as the source of another (`:branch-stacking`)

(def ^:private sql:get-source-edge
  "SELECT f.is_branch, fb.status, fb.deleted_at
     FROM file AS f
     LEFT JOIN file_branch AS fb ON (fb.branch_file_id = f.id)
    WHERE f.id = ?")

(defn- check-source-open
  "Refuse to use the file `file-id` as the source of a branch edge (cut a
  branch from it, merge into it, update from it) when the file is itself a
  branch whose own edge is not open: an archived branch is set aside, and
  a merged or deleted one is gone. A root file, and a file detached from
  its branch, pass. A caller that writes into `file-id` or pins it runs
  the check under the lock of `file-id`, the lock an archive takes, so
  the archive cannot commit between the check and the write."
  [conn file-id]
  (let [{:keys [is-branch status deleted-at]} (db/exec-one! conn [sql:get-source-edge file-id])]
    (when (and is-branch
               (or (not= "open" status) (some? deleted-at)))
      (ex/raise :type :validation
                :code :source-branch-not-open
                :hint "the source of this operation is a branch that is not open"
                :file-id file-id
                :status status))))

;; --- COMMAND: create-file-branch

(defn- check-branch-name!
  "Refuse a blank branch name. Shared by creation and rename so the API
  cannot create a branch the rename would refuse."
  [name]
  (when (str/blank? name)
    (ex/raise :type :validation
              :code :invalid-branch-name
              :hint "branch name cannot be blank")))

(def ^:private schema:create-file-branch
  [:map {:title "create-file-branch"}
   [:file-id ::sm/uuid]
   [:name [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::create-file-branch
  "Create a branch from a file. The branch file stores NO data payload:
  its `:data` is derived on every read by replaying the branch's op log
  over the merge base — a system snapshot of the source taken at
  creation. The branch file row still exists (permissions, libraries,
  msgbus topics and media ownership key on it), but nothing is
  duplicated. The branch joins the family of its source, which owns the
  reviews, threads, and tasks of every file in it.

  A branch of a branch is refused unless the `:branch-stacking` flag is
  on, and then only while the branch it is cut from is open."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:create-file-branch
   ::climit/id [[:create-file-branch/by-profile ::rpc/profile-id]
                [:create-file-branch/global]]}
  [cfg {:keys [::rpc/profile-id file-id name description]}]
  (check-branching-enabled!)
  (check-branch-name! name)
  (files/check-edition-permissions! cfg profile-id file-id)

  (let [tpoint   (ct/tpoint)
        file-row (db/get-by-id cfg :file file-id)
        project  (db/get-by-id cfg :project (:project-id file-row))]

    (when (and (:is-branch file-row)
               (not (contains? cf/flags :branch-stacking)))
      (ex/raise :type :validation
                :code :cannot-branch-a-branch
                :hint "branches of branches are not supported"
                :file-id file-id))

    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

                  ;; Serialize against concurrent update-file on the
                  ;; source (same advisory lock) so the base snapshot and
                  ;; the branch's initial state (the empty op log over
                  ;; that base) are taken from the SAME state of the source.
                  ;;
                  ;; The family id is locked too: the quota counts the
                  ;; whole family, and two creators cutting from two files
                  ;; of it (the root and a branch of it) would otherwise take
                  ;; two different locks and both pass the quota for the
                  ;; last slot. Sorted, as `merge-file-branch` takes its
                  ;; two, to avoid deadlocks. The family id is the root
                  ;; file id, so branching the root takes one lock.
                  (run! (partial db/xact-lock! conn)
                        (sort (distinct [file-id (:family-id file-row)])))

                  ;; an archived branch is no source for a new one
                  (check-source-open conn file-id)

                  ;; The quota check runs INSIDE the transaction and
                  ;; AFTER the locks: two creators racing for the last
                  ;; branch slot serialize on the family lock, and the
                  ;; loser's count must see the winner's committed row.
                  ;; `quotes/check!` reads through `::db/conn`, so it
                  ;; counts on this transaction's connection.
                  (-> cfg
                      (assoc ::quotes/profile-id profile-id)
                      (assoc ::quotes/project-id (:project-id file-row))
                      (assoc ::quotes/team-id (:team-id project))
                      (assoc ::quotes/file-id file-id)
                      (quotes/check! {::quotes/id ::quotes/branches-per-file}
                                     {::quotes/id ::quotes/branches-per-team}))

                  ;; 1. Pin the merge base: a system snapshot of the source,
                  ;; kept long-lived so it is not pruned by the snapshot
                  ;; GC while the branch is open (it is released when the
                  ;; branch is deleted or its base moves). The snapshot
                  ;; stores the source's fragment pointers, not its pages,
                  ;; so the source's row is read FOR UPDATE: file-gc takes it FOR
                  ;; UPDATE as well, and a pass that ran between this read
                  ;; and the commit could mark a fragment the base names.
                  (let [file (bfc/get-file cfg file-id :lock-for-update? true)
                        _    (fdata/with-pointers cfg file-id
                               (check-file-size-limits! file :create-branch))
                        base (fsnap/create! cfg file
                                            {:label (str "branch-base/" name)
                                             :profile-id profile-id
                                             :created-by "system"
                                             :deleted-at (ct/in-future {:days 3650})})

                        ts         (ct/now)
                        branch-id  (uuid/next)

                        ;; 2. Create the branch file: an empty file row
                        ;; carrying the source's project, features and data
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

                    (bfc/insert-file-row! cfg branch-file {::db/return-keys false})

                    ;; 3. Mark the new file row as a branch so it is
                    ;; hidden from the project/team file listings, and put
                    ;; it in the family of its source (the insert gave it
                    ;; a family of its own, which goes once it is empty).
                    ;; No file role is granted: a branch takes its
                    ;; permissions from the team (and project) roles
                    ;; alone, so every editor of the source can open and edit
                    ;; it, and its creator gets no extra rights.
                    (db/update! conn :file
                                {:is-branch true
                                 :family-id (:family-id file-row)}
                                {:id branch-id}
                                {::db/return-keys false})

                    ;; 4. The branch resolves libraries exactly like
                    ;; the source (component instances from shared libraries
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

                    ;; 5. Persist the branch metadata. `base-source-revn`
                    ;; tracks the SOURCE's revision counter and
                    ;; `base-branch-revn` the BRANCH file's one. Each
                    ;; starts at its own side's revn (the source's current
                    ;; revn, the new branch file's 0) and is compared
                    ;; against that side alone (see `revn-deltas`).
                    (db/insert! conn :file-branch
                                {:id meta-id
                                 :branch-file-id branch-id
                                 :source-file-id file-id
                                 :base-snapshot-id (:id base)
                                 :base-source-revn (:revn file)
                                 :base-branch-revn (:revn branch-file)
                                 :created-by profile-id
                                 :name name
                                 :description description
                                 :status "open"}
                                {::db/return-keys false})

                    (audited {:id meta-id
                              :branch-file-id branch-id
                              :source-file-id file-id
                              :base-source-revn (:revn file)
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
          fb.base_source_revn,
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
      AND fb.status != 'merged'
      AND (?::boolean OR fb.status = 'open')
    ORDER BY fb.created_at DESC")

(defn revn-deltas
  "Cheap `[ahead-revn behind-revn]` deltas gating the expensive diff. Each
  side is compared against ITS OWN counter captured when the base was
  (re)positioned: `branch-revn` vs `base-branch-revn` and `source-revn` vs
  `base-source-revn` — the two revns are independent counters and must
  never be mixed."
  [{:keys [branch-revn source-revn base-source-revn base-branch-revn]}]
  [(max 0 (- branch-revn base-branch-revn))
   (max 0 (- source-revn base-source-revn))])

(defn- branch-id-map
  "Reference-normalization map for diffing/merging a branch against its
  source: the branch file id plus the branch->source media pairs (see
  `branch-media-pairs`)."
  [cfg branch-file-id source-file-id]
  (-> (branch-media-pairs cfg branch-file-id source-file-id)
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
  file and its source — the same numbers the compare dialog lists
  (root-frame churn and structural noise filtered out). The revn deltas
  are used only as a cheap gate to skip the (expensive) 3-way diff when a
  side hasn't moved. `source-data` may be pre-realized and shared across
  branches of the same source; otherwise it is loaded on demand.

  The forward pass is bounded to the pages the branch's op log touched
  when that set is complete (`branch-affected-pages`), and the reverse
  pass, which reports the SOURCE's changes, to the pages whose fragment
  the source no longer shares with the base (`moved-pages`). Without a
  shared `source-data`, the source loads only those pages
  (`comparison-source`).

  Raises on failure instead of degrading: the caller decides whether the
  result is worth caching, and a value computed from an error must never
  enter the summary cache."
  [cfg source-data {:keys [source-file-id branch-file-id ahead-revn behind-revn]}]
  (bp/with-policies
    (if (and (zero? ahead-revn) (zero? behind-revn))
      [0 0 0]
      (let [;; the derive hands back the merge base it replayed the log over,
            ;; so the comparison's `base` costs one decode of the snapshot
            ;; rather than two
            branch-file (bfc/get-file cfg branch-file-id :realize? true
                                      :include-base-data? true)
            branch-data (bm/remap-refs
                         (:data branch-file)
                         (branch-id-map cfg branch-file-id source-file-id))
            base-data   (branch-base-data cfg source-file-id branch-file)
            base-ids    (::bfc/base-page-pointers branch-file)
            [source-data moved]
            (cond
              (nil? source-data)
              (let [[file moved] (comparison-source cfg source-file-id branch-file base-data)]
                [(:data file) moved])

              ;; the stored document is a handful of pointers, read only
              ;; when the base holds pointers to compare them with
              (seq base-ids)
              [source-data (moved-pages (:data (bfc/get-file cfg source-file-id)) base-data base-ids)]

              :else
              [source-data nil])
            clean-count (fn [m] (let [s (:stats m)]
                                  (+ (:added s) (:modified s) (:deleted s))))
            fwd (when (pos? ahead-revn)
                  (bm/compute-merge base-data source-data branch-data :branch->source
                                    (when-let [pages (branch-affected-pages cfg branch-file-id)]
                                      {:only-pages pages})))
            bwd (when (pos? behind-revn)
                  (bm/compute-merge base-data branch-data source-data :branch->source
                                    (when moved {:only-pages moved})))]
        [(if fwd (clean-count fwd) 0)
         (if bwd (clean-count bwd) 0)
         ;; conflicts are symmetric; only possible when both sides diverged
         (if (and (pos? ahead-revn) (pos? behind-revn)) (count (:conflicts fwd)) 0)]))))

(def ^:private branch-summary-cache
  "The per-branch `[ahead behind conflicts]` summary, keyed by
  `(base-snapshot-id, source-revn, branch-revn)` and a hash of the
  effective policies — exactly the inputs the computation reads. A
  stale key is a cache miss, and a cache miss is the old behaviour, so
  the cache needs no invalidation logic; `keepalive`
  and `max-size` bound memory, they never decide correctness.

  The window is a working day rather than minutes because the fetch this
  exists for is a file open: an entry is a uuid and five numbers, so
  keeping it costs nothing, and a shorter window pays the whole cold cost
  again for a designer who comes back after lunch.

  Both surfaces reporting these numbers share it: the branches listing and
  the review listing ask the same question about the same pair, so
  whichever runs first warms the other."
  (ucache/create :max-size 8192 :keepalive "8h"))

(defn- summary-cache-key
  "The cache key: the branch's own counters plus a hash of the policies
  the cached value is computed under, so flipping a policy can never
  answer from a summary computed with the old one."
  [{:keys [base-snapshot-id source-revn branch-revn]}]
  [base-snapshot-id source-revn branch-revn (bp/policies-hash)])

(defn cached-summary
  "The cached summary of a branch row, or nil when it would have to be
  computed. Public because a listing decides whether to realize the
  source before it pays for it, and a listing whose rows are all cached
  needs no source at all."
  [row]
  (bp/with-policies
    (ucache/get branch-summary-cache (summary-cache-key row))))

(defn cached-diff-counts
  "`branch-diff-counts!` behind the summary cache, answering `[ahead
  behind conflicts]` or nil when the comparison cannot be made: the base
  snapshot cannot be resolved (reading the branch file raises
  `:base-snapshot-missing`, and merge/update DO refuse loudly in that
  situation), so a listing that stays read-only reports the failure
  instead of repairing anything. The answer is nil and never zeros: zeros
  read as \"in sync\", and a branch that cannot be compared is not in
  sync; callers surface it as an error marker with no counts at all. The
  warning line is the trace left in the log. A value computed from an
  error never enters the cache.

  `branch` carries the key tuple and the pair being compared, with
  `source-file-id` naming the source and `branch-file-id` the branch, whichever
  listing is asking."
  [cfg source-data branch]
  (bp/with-policies
    (let [[ahead-revn behind-revn] (revn-deltas branch)]
      (try
        (ucache/get branch-summary-cache
                    (summary-cache-key branch)
                    (fn [_]
                      (branch-diff-counts! cfg source-data
                                           (assoc branch
                                                  :ahead-revn ahead-revn
                                                  :behind-revn behind-revn))))
        (catch Throwable cause
          (l/wrn :hint "unable to compute branch diff counts"
                 :branch-file-id (str (:branch-file-id branch))
                 :source-file-id (str (:source-file-id branch))
                 :cause cause)
          nil)))))

(sv/defmethod ::get-file-branches
  "List the branches of a file. `ahead`/`behind` are entity-level change
  counts (matching the compare dialog), gated by the cheap revn deltas so
  in-sync branches skip the diff entirely; the source is realized once and
  shared across branches.

  A row whose comparison cannot be made carries `:diff-error true` and no
  counts at all: zeros read as \"in sync\", and a branch that cannot be
  compared is not in sync. The listing stays read-only and repairs
  nothing; merge/update DO refuse loudly in that situation.

  The listing query takes one pooled connection. Every comparison takes
  its own connection and returns it, so no connection is held while the
  (expensive) diffs run: a listing of many diverged branches costs one
  connection at a time and cannot starve the pool.

  A cold listing is one of the slowest things a user meets, so the listing
  reports its own duration to `audit_log` and to the log, together with
  the two numbers that explain that duration: how many open diverged
  branches it had to look a comparison up for, and how many of those the
  summary cache answered without computing anything.

  The per-branch summary is cached by `(base-snapshot-id, source-revn,
  branch-revn)`: a repeated listing with no intervening save performs no
  comparison work, and the tuple names exactly the inputs the computation
  reads, so a stale key cannot exist and the cache needs no invalidation.
  The review listing reads the same cache, so either surface can
  arrive warm."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branches
   ::climit/id [[:get-file-branches/global]]}
  [cfg {:keys [::rpc/profile-id file-id include-archived]}]
  (check-branching-enabled!)
  (let [tpoint (ct/tpoint)
        rows   (db/run! cfg
                        (fn [{:keys [::db/conn] :as cfg}]
                          (files/check-read-permissions! cfg profile-id file-id)
                          (db/exec! conn [sql:get-file-branches file-id (boolean include-archived)])))
        ;; only OPEN branches get diff counts: merged/archived ones
        ;; are not going to be merged as-is, so the expensive diff
        ;; would be wasted work (and their base may be released)
        open?       (fn [row] (= "open" (:status row)))
        ;; an open branch whose cheap revn deltas say it diverged is
        ;; the only one the listing looks a comparison up for
        diverged?   (fn [row] (let [[a b] (revn-deltas row)] (or (pos? a) (pos? b))))
        compared    (filterv (fn [row] (and (open? row) (diverged? row))) rows)
        ;; the rows the summary cache answers without computing
        cached      (filterv (comp some? cached-summary) compared)
        ;; realize the source once, only if some compared branch is not
        ;; already cached
        need?       (< (count cached) (count compared))
        source-data (when need?
                      (db/run! cfg (fn [cfg] (:data (bfc/get-file cfg file-id :realize? true)))))
        branches    (mapv (fn [row]
                            (let [counts (if (open? row)
                                           ;; a cached summary answers without
                                           ;; touching the database; anything
                                           ;; else compares on a connection of
                                           ;; its own, taken and returned around
                                           ;; the comparison
                                           (or (cached-summary row)
                                               (db/run! cfg (fn [cfg] (cached-diff-counts cfg source-data row))))
                                           [0 0 0])
                                  [ahead behind conflicts] counts]
                              (cond-> (-> row
                                          (assoc :ahead ahead :behind behind :conflicts conflicts)
                                          (dissoc :branch-revn :source-revn :base-snapshot-id :base-branch-revn))
                                ;; the comparison could not be made: the
                                ;; marker stands where the counts would be
                                (nil? counts) (assoc :diff-error true))))
                          rows)]
    (audited branches tpoint :list-branches
             {:branches-compared (count compared)
              :branches-cached (count cached)})))

;; --- COMMAND QUERY: get-file-branch-diff

(def ^:private schema:get-file-branch-diff
  [:map {:title "get-file-branch-diff"}
   [:branch-id ::sm/uuid]
   ;; `:branch->source` (default) = the branch's outgoing changes to merge;
   ;; `:source->branch` = the source's incoming changes the branch is missing.
   [:direction {:optional true} [:enum :branch->source :source->branch]]])

(sv/defmethod ::get-file-branch-diff
  "Read-only three-way diff between a branch and its source, using the
  branch's current merge base: the snapshot taken at branch creation, or
  the one the latest update from the source repositioned it to
  (`reposition-base!` in `update-file-branch-from-source`). Returns the summary
  produced by `branch-merge/compute-merge` (stats, changes, conflicts).

  The base is read at the current data version, the version `bfc/get-file`
  reads the source and the branch at (`bfc/migrate-branch-base`), so a Penpot
  upgrade since the branch was cut does not read as a change on any side."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-diff
   ::climit/id [[:get-file-branch-diff/global]]
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id branch-id direction]}]
  (bp/with-policies
    (check-branching-enabled!)
    (let [branch (db/get* conn :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found
                  :code :branch-not-found
                  :hint "unable to find branch with the provided id"
                  :branch-id branch-id))

      (files/check-read-permissions! cfg profile-id (:source-file-id branch))

      (let [tpoint      (ct/tpoint)
            dir         (or direction :branch->source)
            _           (check-oplog-depth-limit! (bfc/get-branch-oplog-depth cfg (:branch-file-id branch))
                                                  (:branch-file-id branch)
                                                  :compare)
            log         (branch-log cfg (:branch-file-id branch))
            branch-file (bfc/get-file cfg (:branch-file-id branch)
                                      :realize? true :include-base-data? true)
            base-data   (branch-base-data cfg (:source-file-id branch) branch-file)
            ;; the size gate runs after the derive: the source loads only the
            ;; pages it moved since the base, so the base is needed first,
            ;; and the derive's own cost is bounded by the gates the base
            ;; passed when it was written
            [source-file moved] (comparison-source cfg (:source-file-id branch) branch-file base-data)
            _           (check-file-size-limits! source-file :compare)
            source-data (:data source-file)
            branch-data (bm/remap-refs
                         (:data branch-file)
                         (branch-id-map cfg (:branch-file-id branch) (:source-file-id branch)))
            ;; when the CURRENT merge-base snapshot row was created: the
            ;; base moves whenever `update-file-branch-from-source` repositions it,
            ;; and a pinned base carries a future `deleted-at`
            base-at     (:created-at (db/get* conn :file-change
                                              {:id (:base-snapshot-id branch)}
                                              {::sql/columns [:created-at]
                                               ::db/remove-deleted false}))
            ;; each direction is bounded to the pages the side it reports
            ;; can have changed: the branch's to the pages its log
            ;; touched, the source's to the pages whose fragment it no longer
            ;; shares with the base
            opts        (if (= dir :branch->source)
                          (when-let [pages (affected-pages log)]
                            {:only-pages pages})
                          (when moved
                            {:only-pages moved}))]
        ;; `:meta` carries the "when" of each side so the resolution UI can show
        ;; how recent the source and the branch are (base is the snapshot its
        ;; `base-snapshot-id` currently points at), plus
        ;; `:source-revn` so the client can do optimistic concurrency on merge
        ;; (`expected-source-revn`). The last editor's identity is intentionally
        ;; omitted: files do not store a reliable "modified-by".
        (-> (bm/compute-merge base-data source-data branch-data dir (assoc opts :copies? true))
            (assoc :meta {:base-at     base-at
                          :source-at   (:modified-at source-file)
                          :branch-at   (:modified-at branch-file)
                          :source-revn (:revn source-file)})
            (audited tpoint :compare))))))

;; --- Helpers: post-commit notification
;;
;; `app.db` offers no post-commit hook and `db/tx-run!` has committed
;; when it returns, so the messages a transaction collects in `messages`
;; leave here, right after the commit: a client that reloads on one of
;; them reads the committed state, and a rolled back transaction
;; publishes nothing. Shared with the review commands
;; (`app.rpc.commands.files-branch-review`).

(defn tx-run-notify!
  "Run `f` in a transaction, then publish the `messages` it collected (a
  volatile of `{:topic .. :message ..}` maps) and return its result."
  [cfg msgbus messages f]
  (let [result (db/tx-run! cfg f)]
    (run! (fn [{:keys [topic message]}]
            (mbus/pub! msgbus :topic topic :message message))
          @messages)
    result))

;; --- Helpers: write an integration into the source (merge and apply)

(defn- integrate-into-source
  "Write `changes` (the branch's work, in the source's id space) into the
  source: a safety snapshot of the source as it was, the media rows the
  branch added copied into the source under the ids `fresh-map`
  pre-allocated, the changes applied through the production change
  pipeline, the result repaired, the copies of every local component the
  changes (or the repair) touched synced (`bm/sync-copies`), validated
  again, logged in one `file_change` row and persisted. A page the write
  left as it was keeps its fragment (`reattach-source-pointers`).

  The validator, not the integration, decides validity: whatever stays
  broken after the repair raises `:merge-result-invalid` and the caller's
  transaction rolls back.

  Returns `{:file merged :extra changes}`: the source as written,
  realized, and the repair and sync changes the write added on top of
  `changes`, in the source's id space. An apply replays `extra` on the
  branch it keeps. Must run inside the transaction, under the source's
  lock."
  [{:keys [::db/conn] :as cfg}
   {:keys [source-id source-stored source-file changes fresh-map new-media
           label deleted-at profile-id session-id ts]}]
  (fdata/with-tracked-pointers cfg source-id
    (binding [cfeat/*current*  (:features source-file)
              cfeat/*previous* (:features source-file)]

      ;; rollback via versions; it names the source's fragments rather than
      ;; copying its pages
      (fsnap/create! cfg source-stored
                     {:label label
                      :created-by "system"
                      :deleted-at deleted-at
                      :profile-id profile-id})

      (copy-media-rows! conn source-id fresh-map new-media)

      (let [applied (-> source-file
                        (update :revn inc)
                        (update :data #(cpc/process-changes % changes)))
            libs    (bfc/get-resolved-file-libraries cfg applied)
            errors  (not-empty (cfv/validate-file applied libs))
            repair  (if errors
                      (cfr/repair-file applied libs errors)
                      [])
            merged  (cond-> applied
                      (seq repair)
                      (update :data cpc/process-changes repair))

            {synced :data sync :changes}
            (bm/sync-copies source-id (:data merged)
                            (into (bm/changed-components (:data source-file) changes)
                                  (bm/changed-components (:data applied) repair)))
            merged  (assoc merged :data synced)
            libs    (if (seq sync)
                      (bfc/get-resolved-file-libraries cfg merged)
                      libs)
            errors  (when (or errors (seq sync))
                      (not-empty (cfv/validate-file merged libs)))]
        (when errors
          (ex/raise :type :validation
                    :code :merge-result-invalid
                    :hint "the merge result still has validation errors after the repair"
                    :codes (into [] (comp (map :code) (distinct)) errors)))

        ;; change log (xlog), GC-eligible after the delay
        (db/insert! conn :file-change
                    {:id (uuid/next)
                     :session-id session-id
                     :kind fchanges/revision-kind
                     :profile-id profile-id
                     :created-at ts
                     :updated-at ts
                     :deleted-at (ct/in-future {:hours 1})
                     :file-id source-id
                     :revn (:revn merged)
                     :version (:version merged)
                     :features (into-array (:features merged))
                     :changes (blob/encode (-> (vec changes)
                                               (into repair)
                                               (into sync)))}
                    {::db/return-keys false})

        (let [all-changes (-> (vec changes) (into repair) (into sync))]
          (if (:is-branch source-file)
            ;; a target that is itself a branch (`:branch-stacking`)
            ;; stores no data
            (persist-merge-into-branch cfg merged ts all-changes)
            ;; a page the write left as it was keeps its fragment instead
            ;; of being written again
            (fupd/persist-file! (assoc cfg ::fupd/timestamp ts)
                                (reattach-source-pointers cfg source-id source-stored merged))))

        (when (contains? cf/flags :redis-cache)
          (fupd/invalidate-caches! cfg merged))

        {:file merged
         :extra (into (vec repair) sync)}))))

;; --- COMMAND: merge-file-branch

(def ^:private schema:merge-file-branch
  [:map {:title "merge-file-branch"}
   [:branch-id ::sm/uuid]
   ;; conflict resolutions keyed by entity id (uuid) or, for structural
   ;; conflicts, a keyword id like :active-themes. A resolution is either a
   ;; whole-entity choice (:source/:branch) or a per-attr map {attr -> side}.
   [:resolutions {:optional true} [:map-of :any [:or [:enum :source :branch] [:map-of :keyword [:enum :source :branch]]]]]
   ;; the compare panel's per-change selection as a set of change keys
   ;; (`app.common.files.branch-merge/change-key`): the entities the user
   ;; deselected, which the merge resolves to the target side
   [:excluded {:optional true} [::sm/set :string]]
   [:expected-source-revn {:optional true} ::sm/int]
   ;; what the user says about the merge; kept in its record
   ;; (`file_branch_merge`), shown in the list of merges of the source
   [:comment {:optional true} [:string {:max 4000}]]
   ;; tell the assignees of the open tasks of the branch, by email, that
   ;; the merge moved their tasks into the source
   [:notify-assignees {:optional true} ::sm/boolean]])

(sv/defmethod ::merge-file-branch
  "Merge a branch into its source file.

  Conflicts that `resolutions` leaves unresolved make the command return
  `{:status :conflicts}`, with the `:source-revn` they were computed against
  so the retry can pass it as `expected-source-revn` and the summary's
  `:copies` (`bm/compute-merge`); a resolved conflict
  merges the side its resolution picks, per entity or per attr. Change
  kinds the engine cannot translate (`bm/unsupported-kinds`) make it return
  `{:status :unsupported}` so no change is silently dropped. Otherwise it
  takes a safety snapshot of the source, applies the merge to the source through the
  production change pipeline, records the merge with its `comment`
  (`file_branch_merge`), deletes the branch and its file and notifies
  open clients via msgbus.

  Copies follow the merged components: a copy attr no side overrode is
  no conflict, and after the merge (and its repair) applies, the copies
  of every local component whose main it changed are synced on the source
  (`bm/sync-copies`). The sync changes go into the same `file_change`
  row and the same validation as the merge."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:merge-file-branch
   ::climit/id [[:merge-file-branch/by-profile ::rpc/profile-id]
                [:merge-file-branch/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id resolutions expected-source-revn comment excluded
           notify-assignees]}]
  (bp/with-policies
    (check-branching-enabled!)
    (let [branch (db/get* cfg :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found
                  :code :branch-not-found
                  :branch-id branch-id))
      (when (not= "open" (:status branch))
        (ex/raise :type :validation
                  :code :branch-not-open
                  :branch-id branch-id))

      (let [tpoint         (ct/tpoint)
            source-id      (:source-file-id branch)
            branch-file-id (:branch-file-id branch)
            messages       (volatile! [])]
        ;; Only editors of the source can integrate (same rule as Figma).
        (files/check-edition-permissions! cfg profile-id source-id)

        (tx-run-notify!
         cfg
         msgbus
         messages
         (fn [{:keys [::db/conn] :as cfg}]
           ;; Serialize against concurrent edits/merges on BOTH files (same
           ;; advisory lock the normal update-file path takes; stable order
           ;; to avoid deadlocks): without the branch lock, edits saved to
           ;; the branch while the merge runs would be silently lost when
           ;; the branch is marked merged and deleted below.
           (run! (partial db/xact-lock! conn) (sort [source-id branch-file-id]))

           ;; the row read before the lock is stale by now: a competing
           ;; merge, delete or archive may have committed while these
           ;; locks were taken, so everything below works off the row as
           ;; it stands under the lock
           (let [branch      (or (db/get* conn :file-branch {:id branch-id})
                                 (ex/raise :type :not-found
                                           :code :branch-not-found
                                           :branch-id branch-id))
                 _           (when (not= "open" (:status branch))
                               (ex/raise :type :validation
                                         :code :branch-not-open
                                         :branch-id branch-id))
                 ;; a merge into a branch needs that branch open: an
                 ;; archived target is set aside (`:branch-stacking`)
                 _           (check-source-open conn source-id)
                 [source-stored source-file] (read-source-for-write cfg source-id :merge)
                 _           (check-oplog-depth-limit! (bfc/get-branch-oplog-depth cfg branch-file-id)
                                                       branch-file-id
                                                       :merge)
                 branch-file (bfc/get-file cfg branch-file-id
                                           :realize? true :include-base-data? true)
                 base-data   (branch-base-data cfg source-id branch-file)

                 ;; canonicalize the branch's local refs to the source's ids: the
                 ;; branch file id (components, library color/typography
                 ;; refs) and the paired media ids; media ADDED on the
                 ;; branch gets fresh ids pre-allocated here — the rows are
                 ;; copied into the source only if the merge actually applies.
                 pairs       (branch-media-pairs cfg branch-file-id source-id)
                 new-media   (unpaired-media-rows cfg branch-file-id (:data branch-file) pairs)
                 fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
                 id-map      (-> pairs
                                 (merge fresh-map)
                                 (assoc branch-file-id source-id))
                 branch-data (bm/remap-refs (:data branch-file) id-map)]

             (when (and (some? expected-source-revn)
                        (not= expected-source-revn (:revn source-file)))
               (ex/raise :type :conflict
                         :code :file-modified
                         :hint "source was modified, recompute the diff and retry"))

             (let [merge-summary (bm/compute-merge base-data (:data source-file)
                                                   branch-data :branch->source
                                                   ;; `:copies` for the conflict dialog
                                                   {:copies? true})
                   conflicts  (:conflicts merge-summary)
                   ;; an excluded conflict is resolved: the exclusion IS the
                   ;; recorded choice, so it lands on the target side
                   ;; (`bm/compute-changes`: one side keeping its value is a recorded choice, never a silent win)
                   resolved?  (fn [c] (or (contains? excluded (:key c))
                                          (bm/conflict-resolved? c (get resolutions (:id c)))))
                   unresolved (remove resolved? conflicts)
                   ;; the user deselected everything: refuse rather than
                   ;; integrate nothing (the PoC's check at
                   ;; `merge-file-branch@2feb570d3f`)
                   _          (when (bm/nothing-to-merge? merge-summary excluded)
                                (ex/raise :type :validation
                                          :code :nothing-to-merge
                                          :hint "select at least one change to merge"))

                   finish-branch!
                   (fn [ts merged-data]
                     ;; record the merge, mark the branch merged and
                     ;; delete it and its file in this same transaction
                     ;; (no client-driven second call, no window where a
                     ;; crash leaves a stale copy); delete-branch!
                     ;; releases the pinned base snapshot. The record is
                     ;; written first: it reads the open review the lines
                     ;; below close.
                     (let [merge-id (record-merge conn branch {:kind "merge"
                                                               :profile-id profile-id
                                                               :comment comment
                                                               :created-at ts})
                           ;; the threads with an open task, and those
                           ;; written outside any review, move to the source
                           ;; with their tasks (on the layer the merge
                           ;; left them); the other review threads stay
                           ;; with the branch as the record of its review
                           moved    (ftask/move-branch-tasks cfg {:branch-file-id branch-file-id
                                                                  :target-id source-id
                                                                  :merge-id merge-id
                                                                  :branch-name (:name branch)
                                                                  :profile-id profile-id
                                                                  :notify-assignees notify-assignees
                                                                  :merged-data merged-data
                                                                  :branch-data branch-data
                                                                  :base-data base-data})]
                       ;; the source's open clients patch their task list
                       (when (seq moved)
                         (vswap! messages conj
                                 {:topic source-id
                                  :message (ftask/tasks-change-message
                                            conn {:file-id source-id
                                                  :profile-id profile-id
                                                  :session-id session-id
                                                  :task-ids (map :id moved)})})))
                     ;; the open branches cut from this one follow it into
                     ;; the target (`:branch-stacking`)
                     (reparent-child-branches conn branch-file-id source-id)
                     (db/update! conn :file-branch
                                 {:status "merged"
                                  :merged-at ts
                                  :merged-by profile-id
                                  :updated-at ts}
                                 {:id branch-id}
                                 {::db/return-keys false})
                     ;; an open review of this branch has served its
                     ;; purpose: mark it merged
                     (close-branch-reviews cfg branch
                                           {:profile-id profile-id
                                            :status "merged"})
                     (vswap! messages conj
                             (delete-branch! cfg branch {:profile-id profile-id
                                                         :session-id session-id})))]
               (cond
                 (seq unresolved)
                 ;; `:copies` tells the conflict dialog which copies the
                 ;; integration syncs (`bm/compute-merge`)
                 (audited {:status :conflicts :conflicts conflicts :copies (:copies merge-summary)
                           :source-revn (:revn source-file)}
                          tpoint :merge)

                 :else
                 (let [{:keys [changes unsupported]}
                       (bm/compute-changes base-data (:data source-file) branch-data
                                           (or resolutions {})
                                           merge-summary
                                           excluded)]
                   (cond
                     (seq unsupported)
                     (audited {:status :unsupported
                               :kinds (vec unsupported)
                               ;; the `:refuse` policies refuse on a named
                               ;; entity: surface the summary entries so
                               ;; the response names the parent or
                               ;; container it refuses on
                               :entities (vec (filter #(= :unsupported (:status %))
                                                      (:changes merge-summary)))}
                              tpoint :merge)

                     (empty? changes)
                     ;; Nothing to integrate (branch matches the source):
                     ;; close the branch without touching the source.
                     (let [ts (ct/now)]
                       (finish-branch! ts (:data source-file))
                       (audited {:status :merged :revn (:revn source-file) :source-file-id source-id}
                                tpoint :merge))

                     :else
                     (let [team  (teams/get-team conn :profile-id profile-id :file-id source-id)
                           delay (ldel/get-deletion-delay team)
                           ts    (ct/now)

                           {merged :file}
                           (integrate-into-source cfg {:source-id source-id
                                                       :source-stored source-stored
                                                       :source-file source-file
                                                       :changes changes
                                                       :fresh-map fresh-map
                                                       :new-media new-media
                                                       :label (str "pre-merge/" (:name branch))
                                                       :deleted-at (ct/in-future delay)
                                                       :profile-id profile-id
                                                       :session-id session-id
                                                       :ts ts})]
                       (finish-branch! ts (:data merged))

                       (vswap! messages conj
                               {:topic source-id
                                :message {:type :file-merged
                                          :file-id source-id
                                          :session-id session-id
                                          :revn (:revn merged)}})

                       (audited {:status :merged :revn (:revn merged) :source-file-id source-id}
                                tpoint :merge)))))))))))))

;; --- COMMAND: update-file-branch-from-source

(def ^:private schema:update-file-branch-from-source
  [:map {:title "update-file-branch-from-source"}
   [:branch-id ::sm/uuid]
   ;; resolutions in UI terms: id -> :source (take the source) | :branch (keep
   ;; branch) | {attr -> side} (per-attr)
   [:resolutions {:optional true} [:map-of :any [:or [:enum :source :branch] [:map-of :keyword [:enum :source :branch]]]]]
   ;; the compare panel's per-change selection as a set of change keys
   ;; (`app.common.files.branch-merge/change-key`): the source's changes the user
   ;; deselected, which the branch keeps its own value for
   [:excluded {:optional true} [::sm/set :string]]
   [:expected-source-revn {:optional true} ::sm/int]])

(defn- persist-branch-update!
  "Persist the result of an update-from-source integration on a branch
  file: update the `file` row and the project modified-at without any
  data payload, and REPLACE the branch's op log with the squashed
  branch-only changes. The repositioned merge base already carries
  everything the source contributed, so replaying the squash over it
  reproduces the updated branch exactly, and the log never accumulates
  source-side ops (which would otherwise be double-applied on read).

  The squash is computed between documents `bfc/get-file` read at the
  current data version, so its row records that version.

  The row also stamps the media id map the update applied, as applied
  ({source-media-id -> branch-media-id}): the squashed changes were
  computed against the source's document moved into the branch's id space, so
  the pairing the log describes after the squash is the one the stamps
  rebuild (`branch-media-pairs`), not the content identity heuristic."
  [{:keys [::db/conn] :as cfg} file ts branch-id changes media-map]
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
                   :changes-count (count changes)
                   :media-id-map (some-> (not-empty media-map) db/tjson)
                   :data-version (fmg/data-version)
                   :created-at ts
                   :updated-at ts}
                  {::db/return-keys false}))
    (bfc/update-file-row! cfg file)
    nil))

(defn- reposition-base!
  "Pin `base-data`, the source's document in the branch's media id space,
  as the new merge base of `branch`, release the base it replaces and
  store the counters the revn gate compares against: the source's revn
  and `branch-revn`.

  The base is pinned in the branch's id space — its media references name
  the branch's own rows — because it is what the op log replays over: the
  derived document carries that id space, so a base naming the source's
  rows would make every paired media read as deleted+added on the next
  squash. The comparisons normalize it back to the source's id space on
  read (`get-base-data`/`branch-base-data`). A page the remap left equal
  to the source's names the source's fragment: `source-stored` is the
  source as stored, with its fragment pointers, and `source-file` the
  source realized.

  The ahead/behind revn gate compares each side's revn against the stored
  counters to SKIP the diff. A `diverged` branch still differs from the
  new base even if its revn never moves again, so one revn less is
  stored to keep the gate open; the diff then reports the real counts.
  Must run inside the transaction."
  [{:keys [::db/conn] :as cfg}
   {:keys [branch source-stored source-file base-data branch-revn diverged
           profile-id deleted-at ts]}]
  (let [source-id     (:source-file-id branch)
        new-base    (fsnap/create! cfg (reattach-source-pointers cfg source-id source-stored
                                                                 (assoc source-file :data base-data))
                                   {:label (str "branch-base/" (:name branch))
                                    :created-by "system"
                                    :deleted-at (ct/in-future {:days 3650})
                                    :profile-id profile-id})
        branch-revn (if diverged (dec branch-revn) branch-revn)]
    ;; the previous base is superseded: release its pin
    (release-base-snapshot! cfg branch deleted-at)
    (db/update! conn :file-branch
                {:base-snapshot-id (:id new-base)
                 :base-source-revn (:revn source-file)
                 :base-branch-revn branch-revn
                 :updated-at ts}
                {:id (:id branch)}
                {::db/return-keys false})))

(sv/defmethod ::update-file-branch-from-source
  "Bring the changes the source received since the merge base into the
  branch (the reverse direction of a merge). Returns `{:status
  :conflicts}` (with the `:source-revn` they were computed against and
  the summary's `:copies`) when the source and the branch
  diverged on the same entity and it is not resolved in `resolutions`, or
  `{:status :unsupported}` for change kinds not yet translatable. On
  success it applies the source's changes (and any conflicts resolved to
  the source) to the branch, takes a safety snapshot, and repositions the
  merge base to the current state of the source.

  Copies follow the integrated components as in a merge: after the source's
  changes apply, the copies of every local component whose main they
  changed are synced on the branch, in the branch's id space
  (`bm/sync-copies`), before the squash records the branch's net."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:update-file-branch-from-source
   ::climit/id [[:update-file-branch-from-source/by-profile ::rpc/profile-id]
                [:update-file-branch-from-source/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id resolutions expected-source-revn excluded]}]
  (bp/with-policies
    (check-branching-enabled!)
    (let [branch (db/get* cfg :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
      (when (not= "open" (:status branch))
        (ex/raise :type :validation :code :branch-not-open :branch-id branch-id))

      (let [tpoint         (ct/tpoint)
            branch-file-id (:branch-file-id branch)
            source-id      (:source-file-id branch)
            messages       (volatile! [])]
        ;; Editing the branch -> need edition permissions on the branch file.
        (files/check-edition-permissions! cfg profile-id branch-file-id)

        (tx-run-notify!
         cfg
         msgbus
         messages
         (fn [{:keys [::db/conn] :as cfg}]
           (db/xact-lock! conn branch-file-id)

           ;; the row read before the lock is stale by now: a competing
           ;; merge, delete or archive may have committed while this lock
           ;; was taken, so everything below works off the row as it
           ;; stands under the lock
           (let [branch      (or (db/get* conn :file-branch {:id branch-id})
                                 (ex/raise :type :not-found
                                           :code :branch-not-found
                                           :branch-id branch-id))
                 _           (when (not= "open" (:status branch))
                               (ex/raise :type :validation
                                         :code :branch-not-open
                                         :branch-id branch-id))
                 ;; an archived source is set aside, so nothing flows
                 ;; from it either (`:branch-stacking`)
                 _           (check-source-open conn source-id)
                 [source-stored source-file] (read-source-for-write cfg source-id :update-from-source)
                 _           (when (and (some? expected-source-revn)
                                        (not= expected-source-revn (:revn source-file)))
                               (ex/raise :type :conflict
                                         :code :file-modified
                                         :hint "source was modified, recompute the diff and retry"))
                 _           (check-oplog-depth-limit! (bfc/get-branch-oplog-depth cfg branch-file-id)
                                                       branch-file-id
                                                       :update-from-source)
                 branch-file (bfc/get-file cfg branch-file-id :realize? true)
                 base-raw    (get-base-data cfg branch)

                 team  (teams/get-team conn :profile-id profile-id :file-id branch-file-id)
                 delay (ldel/get-deletion-delay team)

                 ;; Two id maps, one per direction of the crossing.
                 ;;
                 ;; The COMPARISON runs in the source's id space, the id space the
                 ;; merge base was captured in, the id space the compare view
                 ;; uses (`files_branch.clj::branch-id-map` in
                 ;; `files_branch.clj::get-file-branch-diff`), and the only id space
                 ;; the branch's inherited content agrees with. A branch stores
                 ;; no data, so `binfile.clj::branch-file-data` replays its op
                 ;; log over that base and everything it inherited carries the
                 ;; base's ids. Comparing in the branch's id space instead
                 ;; rewrites the source's and the base's local references to the
                 ;; branch file id, and a self reference in the source is then
                 ;; unequal to the branch's own copy of it, so every shape
                 ;; the source touched that carries one reads as an edit on both
                 ;; sides.
                 base-data   base-raw
                 source-raw  (:data source-file)
                 branch-cmp  (bm/remap-refs (:data branch-file)
                                            (branch-id-map cfg branch-file-id source-id))

                 ;; The WRITE runs in the branch's id space: a reference is only
                 ;; a reference in the file it names, so the source's file id and
                 ;; the source's media rows are re-pointed at the branch's ids before
                 ;; the change reaches the branch. Media ADDED on the source has no
                 ;; branch row yet, so it gets a fresh id here and its row is
                 ;; copied below only if the update applies.
                 ;; the WRITE direction ({source-media-id -> branch-media-id})
                 ;; is the inverse of the comparison's pairs
                 pairs       (set/map-invert
                              (branch-media-pairs cfg branch-file-id source-id))
                 new-media   (unpaired-media-rows cfg source-id source-raw pairs)
                 fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
                 ;; the branch's OWN id space: every media row the branch
                 ;; references is one of its own — a paired copy or one of
                 ;; the fresh copies made below. The write and the pinned
                 ;; base name those; `apply-map` adds the file id on top for
                 ;; the change payloads, which re-point their file refs too.
                 media-map   (merge pairs fresh-map)
                 apply-map   (assoc media-map source-id branch-file-id)
                 source-data (bm/remap-refs source-raw apply-map)

                 ;; did the branch carry its OWN changes before this update?
                 ;; (revn moved since the base was last positioned)
                 branch-diverged?
                 (pos? (- (:revn branch-file)
                          (:base-branch-revn branch)))

                 merge-summary
                 (bm/compute-merge base-data source-raw branch-cmp :source->branch {:copies? true})

                 conflicts  (:conflicts merge-summary)
                 resolved?  (fn [c] (or (contains? excluded (:key c))
                                        (bm/conflict-resolved? c (get resolutions (:id c)))))
                 unresolved (remove resolved? conflicts)

                 ;; Resolutions arrive document-keyed (`:source` = the source's value).
                 ;; The update runs compute-changes with the source file as
                 ;; the source and the branch as the target, where the
                 ;; `:branch` keyword means "take the source file" — so invert each side to that
                 ;; vocabulary, including the per-attr maps, value by value.
                 flip     (fn [v] (case v :source :branch, :branch :source, v))
                 inverted (into {} (map (fn [[k v]]
                                          [k (if (map? v)
                                               (update-vals v flip)
                                               (flip v))]))
                                resolutions)]

             (cond
               (seq unresolved)
               (audited {:status :conflicts :conflicts conflicts :copies (:copies merge-summary)
                         :source-revn (:revn source-file)}
                        tpoint :update-from-source)

               (bm/nothing-to-merge? merge-summary excluded)
               ;; the user deselected everything: refuse rather than
               ;; reposition the base over work nothing applies (the PoC's
               ;; check at `merge-file-branch@2feb570d3f`, mirrored here)
               (ex/raise :type :validation
                         :code :nothing-to-merge
                         :hint "select at least one change to merge")

               :else
               ;; target = branch, source = the source file -> changes that
               ;; bring the source's net changes (and conflicts resolved to
               ;; the source) into the branch. The `:source->branch` summary
               ;; reports the source's changes against the
               ;; branch, the same assignment compute-changes runs with here, so
               ;; it can be reused for the unsupported-kinds gate without
               ;; re-diffing.
               (let [{:keys [changes unsupported]}
                     (bm/compute-changes base-data branch-cmp source-raw
                                         (or inverted {})
                                         merge-summary
                                         excluded)]
                 (cond
                   (seq unsupported)
                   (audited {:status :unsupported
                             :kinds (vec unsupported)
                             ;; the `:refuse` policies refuse on a named
                             ;; entity: surface the summary entries so
                             ;; the response names the parent or
                             ;; container it refuses on
                             :entities (vec (filter #(= :unsupported (:status %))
                                                    (:changes merge-summary)))}
                            tpoint :update-from-source)

                   (and (empty? changes) (not branch-diverged?) (empty? excluded))
                   (let [ts (ct/now)]
                     ;; the branch is in sync with the source: the repositioned
                     ;; base already represents the branch, so the op log
                     ;; is emptied (old ops were built against the old base).
                     ;; The log is a branch's only durable record, so it may
                     ;; be emptied only when what it encodes already sits in
                     ;; the base. A diverged branch holds its own work in
                     ;; that log, so it falls through to the squash below,
                     ;; which re-derives the net against the new base. The
                     ;; shortcut needs no exclusion in play either: excluded
                     ;; source work is NOT in the branch, so the branch differs
                     ;; from the repositioned base and the squash has to
                     ;; record that difference as a branch-side delta.
                     (db/delete! conn :file-branch-change {:branch-id branch-id})
                     ;; in sync, so no row is copied and the pairs are all
                     ;; the id space the base needs: unpaired rows stay
                     ;; named by the id the source owns, which is what the branch
                     ;; references while it holds no copy
                     (reposition-base! cfg {:branch branch
                                            :source-stored source-stored
                                            :source-file source-file
                                            :base-data (bm/remap-refs source-raw pairs)
                                            :branch-revn (:revn branch-file)
                                            :diverged branch-diverged?
                                            :profile-id profile-id
                                            :deleted-at (ct/in-future delay)
                                            :ts ts})
                     (audited {:status :updated :revn (:revn branch-file)} tpoint :update-from-source))

                   :else
                   (let [ts (ct/now)]
                     (fdata/with-tracked-pointers cfg branch-file-id
                       (binding [cfeat/*current*  (:features branch-file)
                                 cfeat/*previous* (:features branch-file)]

                         (fsnap/create! cfg branch-file
                                        {:label (str "pre-update/" (:name branch))
                                         :created-by "system"
                                         :deleted-at (ct/in-future delay)
                                         :profile-id profile-id})

                         ;; media added on the source: copy its rows into the branch
                         ;; under the pre-allocated ids the changes reference
                         (copy-media-rows! conn branch-file-id fresh-map new-media)

                         (let [applied (bm/remap-changes changes apply-map)
                               ;; the write lands in the branch's id space, so
                               ;; the document is moved there first — its paired
                               ;; media refs re-pointed at the branch's own
                               ;; rows — or the remapped changes would name ids
                               ;; it does not hold and apply to nothing (a
                               ;; `:del-media` of a paired copy would keep it)
                               before  (bm/remap-refs (:data branch-file) media-map)
                               written (-> branch-file
                                           (update :revn inc)
                                           (assoc :data (cpc/process-changes before applied)))
                               libs    (bfc/get-resolved-file-libraries cfg written)
                               errors  (not-empty (cfv/validate-file written libs))
                               repair  (if errors
                                         (cfr/repair-file written libs errors)
                                         [])
                               updated (cond-> written
                                         (seq repair)
                                         (update :data cpc/process-changes repair))

                               ;; copies follow the integrated components: the
                               ;; copies of every local component whose main
                               ;; the update (or the repair) changed are
                               ;; synced, in the branch's id space the write
                               ;; runs in (`bm/sync-copies`). The squash below
                               ;; diffs `updated`, so it records the sync as
                               ;; branch work
                               {synced :data sync :changes}
                               (bm/sync-copies branch-file-id (:data updated)
                                               (into (bm/changed-components before applied)
                                                     (bm/changed-components (:data written) repair)))
                               updated (assoc updated :data synced)
                               libs    (if (seq sync)
                                         (bfc/get-resolved-file-libraries cfg updated)
                                         libs)

                               ;; the validator, not the update, decides
                               ;; validity: the repaired and synced result
                               ;; goes back to it, and whatever stays broken
                               ;; refuses the update and rolls the
                               ;; transaction back
                               errors  (when (or errors (seq sync))
                                         (not-empty (cfv/validate-file updated libs)))]
                           (when errors
                             (ex/raise :type :validation
                                       :code :update-result-invalid
                                       :hint "the update result still has validation errors after the repair"
                                       :codes (into [] (comp (map :code) (distinct)) errors)))

                           (db/insert! conn :file-change
                                       {:id (uuid/next)
                                        :session-id session-id
                                        :kind fchanges/revision-kind
                                        :profile-id profile-id
                                        :created-at ts
                                        :updated-at ts
                                        :deleted-at (ct/in-future {:hours 1})
                                        :file-id branch-file-id
                                        :revn (:revn updated)
                                        :version (:version updated)
                                        :features (into-array (:features updated))
                                        :changes (blob/encode (-> (vec applied)
                                                                  (into repair)
                                                                  (into sync)))}
                                       {::db/return-keys false})

                           ;; SQUASH: the new base (the source's current
                           ;; state) already carries everything the source contributed,
                           ;; so the op log is replaced with the net
                           ;; branch-only changes computed against the new
                           ;; base. Refuse if that net cannot be translated
                           ;; into replayable ops; nothing has been
                           ;; persisted yet, so the transaction rollback
                           ;; undoes the snapshot and media copies above.
                           (let [{net-changes :changes
                                  unsupported :unsupported}
                                 (bm/compute-changes source-data source-data (:data updated) {})]
                             (when (seq unsupported)
                               (ex/raise :type :validation
                                         :code :unsupported-update-squash
                                         :hint "the update produces branch-only changes that cannot be replayed"
                                         :kinds (vec unsupported)))
                             ;; the net row carries the media id map the
                             ;; update applied: the log it replaces was
                             ;; written against the OLD merge base, and
                             ;; the squash is what names the branch's
                             ;; pairing with the source from now on
                             (persist-branch-update! cfg updated ts branch-id
                                                     net-changes media-map))

                           (when (contains? cf/flags :redis-cache)
                             (fupd/invalidate-caches! cfg updated))
                           (reposition-base! cfg {:branch branch
                                                  :source-stored source-stored
                                                  :source-file source-file
                                                  :base-data (bm/remap-refs source-raw media-map)
                                                  :branch-revn (:revn updated)
                                                  :diverged branch-diverged?
                                                  :profile-id profile-id
                                                  :deleted-at (ct/in-future delay)
                                                  :ts ts})

                           (vswap! messages conj
                                   {:topic branch-file-id
                                    :message {:type :file-merged
                                              :file-id branch-file-id
                                              :session-id session-id
                                              :revn (:revn updated)}})

                           (audited {:status :updated :revn (:revn updated)}
                                    tpoint :update-from-source)))))))))))))))

;; --- COMMAND: apply-file-branch

(def ^:private schema:apply-file-branch
  [:map {:title "apply-file-branch"}
   [:branch-id ::sm/uuid]
   ;; the compare panel's per-change selection as a set of change keys
   ;; (`app.common.files.branch-merge/change-key`): the changes the user
   ;; deselected, which the source does not get and the branch keeps as its own
   [:excluded {:optional true} [::sm/set :string]]
   [:expected-source-revn {:optional true} ::sm/int]
   ;; what the user says about the apply; kept in its record
   ;; (`file_branch_merge`), shown in the list of merges of the source
   [:comment {:optional true} [:string {:max 4000}]]])

(defn- check-up-to-date
  "Refuse to apply a branch that lacks changes the source received since
  its base: an apply integrates without conflicts and leaves the branch
  sitting on the new source, which only holds when the source has
  nothing the branch is missing. The source's revn at the base is the
  cheap answer; past it, the comparison the listings report as `behind`
  decides, bounded to the pages the source moved since the base
  (`moved-pages`). Every document is in the source's id space;
  `source-stored` is the source with its fragment pointers."
  [{:keys [base-source-revn] :as branch} source-stored source-data base-data branch-data base-ids]
  (when (> (:revn source-stored) base-source-revn)
    (let [moved (moved-pages (:data source-stored) base-data base-ids)
          ;; the source as the side reported: its changes against the base
          {:keys [stats conflicts]}
          (bm/compute-merge base-data branch-data source-data :branch->source
                            (when moved {:only-pages moved}))]
      (when (or (pos? (+ (:added stats) (:modified stats) (:deleted stats)))
                (seq conflicts))
        (ex/raise :type :validation
                  :code :branch-behind-source
                  :hint "the source has changes the branch is missing; update the branch from the source first"
                  :branch-id (:id branch))))))

(defn- rebase-branch-onto-source
  "Make `branch` sit on the source as an apply left it: the source's
  document becomes the merge base and the op log is replaced with the net
  of the branch against it, which is empty when every change of the
  branch went in, or the changes the user left out. Returns the branch
  revn.

  `merged` is the source as written, `source-stored` the same document as
  stored (its fragment pointers, read back after the write), and `extra`
  the repair and sync changes the write added on top of the branch's own
  work, in the source's id space. `extra` is the only thing the source
  got that the branch lacks, so it is replayed on the branch, which moves
  its revn; with no `extra` the branch document is left as it is and its
  open clients keep working undisturbed.

  `media-map` ({source-media-id -> branch-media-id}) moves the source's
  document into the branch's id space, as the update does, and is stamped
  on the squash row (`persist-branch-update!`). The squash is bounded to
  the pages the branch and `extra` touched when the source did not move
  since the base, the only case where nothing else can differ."
  [{:keys [::db/conn] :as cfg}
   {:keys [branch branch-file log merged source-stored extra media-map source-unmoved
           profile-id session-id deleted-at ts]}]
  (let [branch-id      (:id branch)
        branch-file-id (:branch-file-id branch)
        source-id        (:source-file-id branch)
        apply-map      (assoc media-map source-id branch-file-id)
        source-data      (bm/remap-refs (:data merged) apply-map)
        extra          (bm/remap-changes extra apply-map)
        before         (bm/remap-refs (:data branch-file) media-map)
        file           (cond-> (assoc branch-file :data before)
                         (seq extra)
                         (-> (update :revn inc)
                             (update :data cpc/process-changes extra)))]

    (when (seq extra)
      (let [libs   (bfc/get-resolved-file-libraries cfg file)
            errors (not-empty (cfv/validate-file file libs))]
        (when errors
          (ex/raise :type :validation
                    :code :apply-result-invalid
                    :hint "the branch has validation errors after replaying the source's repair"
                    :codes (into [] (comp (map :code) (distinct)) errors))))

      (db/insert! conn :file-change
                  {:id (uuid/next)
                   :session-id session-id
                   :kind fchanges/revision-kind
                   :profile-id profile-id
                   :created-at ts
                   :updated-at ts
                   :deleted-at (ct/in-future {:hours 1})
                   :file-id branch-file-id
                   :revn (:revn file)
                   :version (:version file)
                   :features (into-array (:features file))
                   :changes (blob/encode (vec extra))}
                  {::db/return-keys false}))

    ;; SQUASH: the new base carries everything the source holds, so the op log
    ;; is the net branch-only changes against it. Nothing is committed
    ;; yet: a net that cannot be replayed rolls the source back too.
    (let [pages   (when source-unmoved
                    (let [log-pages   (affected-pages log)
                          extra-pages (affected-pages extra)]
                      (when (and log-pages extra-pages)
                        (into log-pages extra-pages))))
          summary (bm/compute-merge source-data source-data (:data file) :branch->source
                                    (when pages {:only-pages pages}))
          {net :changes unsupported :unsupported}
          (bm/compute-changes source-data source-data (:data file) {} summary)]
      (when (seq unsupported)
        (ex/raise :type :validation
                  :code :unsupported-update-squash
                  :hint "the apply leaves branch-only changes that cannot be replayed"
                  :kinds (vec unsupported)))

      (persist-branch-update! cfg file ts branch-id net media-map)

      (when (contains? cf/flags :redis-cache)
        (fupd/invalidate-caches! cfg file))

      (reposition-base! cfg {:branch branch
                             :source-stored source-stored
                             :source-file merged
                             :base-data (bm/remap-refs (:data merged) media-map)
                             :branch-revn (:revn file)
                             :diverged (boolean (seq net))
                             :profile-id profile-id
                             :deleted-at deleted-at
                             :ts ts}))
    (:revn file)))

(sv/defmethod ::apply-file-branch
  "Apply a branch to its source file and keep the branch open.

  The branch must be up to date with the source: when the source has
  changes the branch lacks it raises `:branch-behind-source`, and the user
  updates the branch from the source first. So an apply meets no
  conflict and takes no resolutions. Change kinds the engine cannot
  translate return `{:status :unsupported}`, as in a merge.

  The source gets the branch's changes as a merge writes them
  (`integrate-into-source`), the changes in `excluded` left out. Then the
  branch is rebased onto the new source (`rebase-branch-onto-source`):
  the source becomes its merge base and its op log holds only what was
  left out. The integration is recorded with `kind` \"apply\"
  (`file_branch_merge`), an open design review of the branch ends as
  merged, and the tasks stay on the branch.

  Returns `{:status :applied}` with the source's revn and the branch's: the
  branch revn moves only when the source's repair or copy sync had to be
  replayed on the branch, and only then are its clients told to reload."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:apply-file-branch
   ::climit/id [[:apply-file-branch/by-profile ::rpc/profile-id]
                [:apply-file-branch/global]]}
  [{:keys [::mbus/msgbus] :as cfg}
   {:keys [::rpc/profile-id ::rpc/session-id branch-id expected-source-revn comment excluded]}]
  (bp/with-policies
    (check-branching-enabled!)
    (let [branch (db/get* cfg :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found
                  :code :branch-not-found
                  :branch-id branch-id))
      (when (not= "open" (:status branch))
        (ex/raise :type :validation
                  :code :branch-not-open
                  :branch-id branch-id))

      (let [tpoint         (ct/tpoint)
            source-id        (:source-file-id branch)
            branch-file-id (:branch-file-id branch)
            messages       (volatile! [])]
        ;; the source gets the changes and the branch a new base
        (files/check-edition-permissions! cfg profile-id source-id)
        (files/check-edition-permissions! cfg profile-id branch-file-id)

        (tx-run-notify!
         cfg
         msgbus
         messages
         (fn [{:keys [::db/conn] :as cfg}]
           ;; both files change: the same locks, in the same order, as a
           ;; merge, so saves, merges and deletes serialize against it
           (run! (partial db/xact-lock! conn) (sort [source-id branch-file-id]))

           (let [branch      (or (db/get* conn :file-branch {:id branch-id})
                                 (ex/raise :type :not-found
                                           :code :branch-not-found
                                           :branch-id branch-id))
                 _           (when (not= "open" (:status branch))
                               (ex/raise :type :validation
                                         :code :branch-not-open
                                         :branch-id branch-id))
                 ;; an apply into a branch needs that branch open: an
                 ;; archived target is set aside (`:branch-stacking`)
                 _           (check-source-open conn source-id)
                 [source-stored source-file] (read-source-for-write cfg source-id :apply)
                 _           (when (and (some? expected-source-revn)
                                        (not= expected-source-revn (:revn source-file)))
                               (ex/raise :type :conflict
                                         :code :file-modified
                                         :hint "source was modified, recompute the diff and retry"))
                 _           (check-oplog-depth-limit! (bfc/get-branch-oplog-depth cfg branch-file-id)
                                                       branch-file-id
                                                       :apply)
                 log         (branch-log cfg branch-file-id)
                 branch-file (bfc/get-file cfg branch-file-id
                                           :realize? true :include-base-data? true)
                 base-data   (branch-base-data cfg source-id branch-file)

                 ;; the source's id space for the comparison and the write, as in
                 ;; a merge: media added on the branch gets fresh ids, its
                 ;; rows are copied into the source only if the write happens
                 pairs       (branch-media-pairs cfg branch-file-id source-id)
                 new-media   (unpaired-media-rows cfg branch-file-id (:data branch-file) pairs)
                 fresh-map   (into {} (map (fn [row] [(:id row) (uuid/next)])) new-media)
                 id-map      (-> pairs
                                 (merge fresh-map)
                                 (assoc branch-file-id source-id))
                 branch-data (bm/remap-refs (:data branch-file) id-map)

                 _           (check-up-to-date branch source-stored (:data source-file) base-data branch-data
                                               (::bfc/base-page-pointers branch-file))

                 ;; the branch's changes are bounded to the pages its log
                 ;; touched, exactly as the comparison the user saw
                 summary     (bm/compute-merge base-data (:data source-file) branch-data :branch->source
                                               (when-let [pages (affected-pages log)]
                                                 {:only-pages pages}))]

             ;; up to date means the source moved nowhere the branch did not, so
             ;; a conflict cannot be there; one that is refuses rather than
             ;; drop a side
             (when (seq (:conflicts summary))
               (ex/raise :type :validation
                         :code :branch-behind-source
                         :hint "the source has changes the branch is missing; update the branch from the source first"
                         :branch-id branch-id))

             (when (bm/nothing-to-merge? summary excluded)
               (ex/raise :type :validation
                         :code :nothing-to-merge
                         :hint "select at least one change to merge"))

             (let [{:keys [changes unsupported]}
                   (bm/compute-changes base-data (:data source-file) branch-data {} summary excluded)]
               (if (seq unsupported)
                 (audited {:status :unsupported
                           :kinds (vec unsupported)
                           :entities (vec (filter #(= :unsupported (:status %))
                                                  (:changes summary)))}
                          tpoint :apply)

                 (let [team       (teams/get-team conn :profile-id profile-id :file-id source-id)
                       deleted-at (ct/in-future (ldel/get-deletion-delay team))
                       ts         (ct/now)

                       ;; a branch equal to the source writes nothing into
                       ;; the source, and still gets its base moved onto it
                       {merged :file extra :extra}
                       (if (seq changes)
                         (integrate-into-source cfg {:source-id source-id
                                                     :source-stored source-stored
                                                     :source-file source-file
                                                     :changes changes
                                                     :fresh-map fresh-map
                                                     :new-media new-media
                                                     :label (str "pre-apply/" (:name branch))
                                                     :deleted-at deleted-at
                                                     :profile-id profile-id
                                                     :session-id session-id
                                                     :ts ts})
                         {:file source-file :extra []})

                       ;; the base names the fragments the source holds NOW: the
                       ;; write gave every page it changed a new one
                       source-stored (if (seq changes)
                                       (bfc/get-file cfg source-id)
                                       source-stored)

                       branch-revn
                       (rebase-branch-onto-source cfg {:branch branch
                                                       :branch-file branch-file
                                                       :log log
                                                       :merged merged
                                                       :source-stored source-stored
                                                       :extra extra
                                                       ;; {source-media-id -> branch-media-id}
                                                       :media-map (set/map-invert (merge pairs fresh-map))
                                                       :source-unmoved (= (:revn source-file) (:base-source-revn branch))
                                                       :profile-id profile-id
                                                       :session-id session-id
                                                       :deleted-at deleted-at
                                                       :ts ts})]

                   (record-merge conn branch {:kind "apply"
                                              :profile-id profile-id
                                              :comment comment
                                              :created-at ts})

                   ;; the review accepted what went in; the branch stays
                   ;; open for the next one. Its threads and tasks stay
                   ;; with it: the branch is still where they are worked
                   ;; on, so none moves to the source as at a merge
                   (close-branch-reviews cfg branch {:profile-id profile-id
                                                     :status "merged"})

                   (when (seq changes)
                     (vswap! messages conj
                             {:topic source-id
                              :message {:type :file-merged
                                        :file-id source-id
                                        :session-id session-id
                                        :revn (:revn merged)}}))

                   (when (not= branch-revn (:revn branch-file))
                     (vswap! messages conj
                             {:topic branch-file-id
                              :message {:type :file-merged
                                        :file-id branch-file-id
                                        :session-id session-id
                                        :revn branch-revn}}))

                   (audited {:status :applied
                             :revn (:revn merged)
                             :branch-revn branch-revn
                             :source-file-id source-id}
                            tpoint :apply)))))))))))

;; --- COMMAND: detach-file-branch

(def ^:private schema:detach-file-branch
  [:map {:title "detach-file-branch"}
   [:branch-id ::sm/uuid]])

(sv/defmethod ::detach-file-branch
  "Turn a branch file into an ordinary file: persist its derived state as
  the file's own data payload, drop the op log, release the pinned base
  snapshot, close any review over it, and clear the branch marker so
  every read and write path takes the ordinary route from then on
  (`binfile::get-file*` and `files-update::update-file*` both switch on
  that flag). The file stays in its family, with the reviews, threads,
  and tasks of the tree it was cut from.

  This is the preview's exit door: with it the `:branching` flag can go
  off and the engine can be replaced without stranding anybody with a file
  nobody can open.

  It is idempotent: a branch whose row is no longer live (detached,
  merged, or deleted) is reported as such without touching anything."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:detach-file-branch
   ::climit/id [[:detach-file-branch/by-profile ::rpc/profile-id]
                [:detach-file-branch/global]]}
  [cfg {:keys [::rpc/profile-id branch-id]}]
  (check-branching-enabled!)
  (let [tpoint         (ct/tpoint)
        branch-file-id (:branch-file-id (db/get* cfg :file-branch {:id branch-id}
                                                 {::db/remove-deleted false}))]
    (when (nil? branch-file-id)
      (ex/raise :type :not-found
                :code :branch-not-found
                :branch-id branch-id))
    (files/check-edition-permissions! cfg profile-id branch-file-id)
    (db/tx-run!
     cfg
     (fn [{:keys [::db/conn] :as cfg}]
       ;; the advisory lock every save on this file takes: a concurrent
       ;; branch save must not land between the derive and the persist, or
       ;; its op would be dropped together with the log
       (db/xact-lock! conn branch-file-id)

       (let [branch (db/get* conn :file-branch {:id branch-id})]
         (if (nil? branch)
           (audited {:status :detached :file-id branch-file-id :changed false}
                    tpoint :detach)
           (let [file  (bfc/get-file cfg branch-file-id :realize? true)
                 team  (teams/get-team conn :profile-id profile-id :file-id branch-file-id)
                 delay (ldel/get-deletion-delay team)
                 ts    (ct/now)]

             (fdata/with-tracked-pointers cfg branch-file-id
               (binding [cfeat/*current*  (:features file)
                         cfeat/*previous* (:features file)]

                 (let [;; the images the branch reads through its source's
                       ;; rows become its own: copies under fresh ids, and
                       ;; the data re-pointed at them
                       foreign (foreign-media-rows cfg branch-file-id (:data file))
                       fresh   (into {} (map (fn [row] [(:id row) (uuid/next)])) foreign)
                       _       (copy-media-rows! conn branch-file-id fresh foreign)
                       file    (update file :data bm/remap-refs fresh)

                       libs   (bfc/get-resolved-file-libraries cfg file)
                       errors (not-empty (cfv/validate-file file libs))
                       repair (if errors
                                (cfr/repair-file file libs errors)
                                [])
                       file   (cond-> file
                                (seq repair)
                                (update :data cpc/process-changes repair))

                       ;; the exit door never strands a user: the repaired
                       ;; result goes back to the validator, and whatever
                       ;; stays broken is logged, never refused
                       errors (when errors
                                (not-empty (cfv/validate-file file libs)))]

                   (when errors
                     (l/wrn :hint "detached file still has validation errors"
                            :file-id (str branch-file-id)
                            :codes (into [] (comp (map :code) (distinct)) errors)))

                   ;; the derived state becomes the file's own payload
                   (fupd/persist-file! (assoc cfg ::fupd/timestamp ts) file)

                   (when (contains? cf/flags :redis-cache)
                     (fupd/invalidate-caches! cfg file))

                   ;; from here the ordinary paths apply
                   (db/update! conn :file
                               {:is-branch false}
                               {:id branch-file-id}
                               {::db/return-keys false})

                   ;; a branch carries no file roles; as an ordinary file it
                   ;; gets the owner role its maker would get on creation
                   (fcreate/create-file-role! conn {:file-id branch-file-id
                                                    :profile-id profile-id
                                                    :role :owner})

                   ;; the payload replaced the log
                   (db/delete! conn :file-branch-change {:branch-id (:id branch)})

                   ;; a review over a file that is no longer a branch has
                   ;; nothing left to review
                   (close-branch-reviews cfg branch
                                         {:profile-id profile-id
                                          :status "closed"})

                   ;; nothing derives from the base any more, so the pin goes
                   (release-base-snapshot! cfg branch (ct/in-future delay))

                   ;; The branch row is archived rather than deleted: its
                   ;; reviews name it, and through it the file they reviewed,
                   ;; and `archived` is a status the schema already allows.
                   (db/update! conn :file-branch
                               {:status "archived"
                                :deleted-at (ct/in-future delay)
                                :updated-at ts}
                               {:id (:id branch)}
                               {::db/return-keys false})

                   (audited
                    {:status :detached
                     :file-id branch-file-id
                     :changed true
                     :revn (:revn file)}
                    tpoint :detach)))))))))))

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
          fb.base_source_revn,
          fb.base_branch_revn,
          fb.base_snapshot_id,
          bf.revn AS branch_revn,
          sf.revn AS source_revn,
          sf.name AS source_name,
          sf.is_shared AS source_is_shared
     FROM file_branch AS fb
     JOIN file AS bf ON (bf.id = fb.branch_file_id)
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE fb.branch_file_id = ?
      AND fb.deleted_at IS NULL")

(sv/defmethod ::get-file-branch-info
  "If the given file is a branch, return its branch metadata with
  entity-level `ahead`/`behind`/`conflicts` counts read through the
  summary cache (`cached-diff-counts`); otherwise nil. A cache miss on an
  open branch whose revn deltas are not both zero runs the three-way diff
  (`branch-diff-counts!`), which loads the source, the branch and its base.
  Returns nil when branching is disabled, so it is safe to call on every
  file open.

  The command declares the compare dialog's limit: a warm open answers
  from the summary cache without realizing anything, while a cold one on
  a diverged branch runs the same three-document comparison as
  `get-file-branch-diff`."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-info
   ::climit/id [[:get-file-branch-info/global]]}
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

;; --- COMMAND QUERY: get-file-branch-count

(def ^:private schema:get-file-branch-count
  [:map {:title "get-file-branch-count"}
   [:file-id ::sm/uuid]])

(def ^:private sql:get-file-branch-count
  ;; the same count as the `branches-per-file` quote
  ;; (`quotes.clj::sql:get-branches-per-file`), so the number a user sees
  ;; is the number the limit is checked against
  "SELECT count(*) AS count
     FROM file_branch AS fb
     JOIN file AS sf ON (sf.id = fb.source_file_id)
    WHERE sf.family_id = (SELECT f.family_id FROM file AS f WHERE f.id = ?)
      AND fb.deleted_at IS NULL
      AND fb.status != 'merged'")

(sv/defmethod ::get-file-branch-count
  "How many live branches the family of the file has, archived ones
  included and merged ones left out: the count the `branches-per-file`
  quote checks. A branch of a branch (`:branch-stacking`) counts in the
  family of its root, so a file and every branch in its family answer the
  same number.

  The workspace header asks this on every file open to decide whether to
  show its branch row, so it compares nothing and takes no climit: one
  indexed count. Returns a zero count when branching is disabled, so it
  is safe on every open."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-count}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (if (contains? cf/flags :branching)
    (db/run! cfg
             (fn [{:keys [::db/conn] :as cfg}]
               (files/check-read-permissions! cfg profile-id file-id)
               (-> (db/exec-one! conn [sql:get-file-branch-count file-id])
                   (select-keys [:count]))))
    {:count 0}))

;; --- COMMAND QUERY: get-file-branch-merges

(def ^:private schema:get-file-branch-merges
  [:map {:title "get-file-branch-merges"}
   [:file-id ::sm/uuid]])

(def ^:private sql:resolve-source-file
  "SELECT coalesce((SELECT b.source_file_id
                      FROM file_branch AS b
                     WHERE b.branch_file_id = ?
                       AND b.deleted_at IS NULL),
                   ?) AS id")

(def ^:private sql:get-file-branch-merges
  "SELECT fbm.id,
          fbm.created_at,
          fbm.file_id,
          fbm.file_branch_id,
          fbm.branch_name,
          fbm.kind,
          fbm.merged_by,
          fbm.comment,
          fbm.review_id,
          fbm.review_title
     FROM file_branch_merge AS fbm
    WHERE fbm.file_id = ?
    ORDER BY fbm.created_at DESC")

(sv/defmethod ::get-file-branch-merges
  "List the merges into a file, newest first: each one names the merged
  branch, who merged it and when, the comment left with it and the design
  review it accepted, if any. When the file is itself a branch, the
  merges into its source are listed. The records outlive the
  branches they name (see the `file_branch_merge` migration)."
  {::doc/added "2.16"
   ::sm/params schema:get-file-branch-merges}
  [cfg {:keys [::rpc/profile-id file-id]}]
  (check-branching-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (let [source-id (:id (db/exec-one! conn [sql:resolve-source-file file-id file-id]))]
               (files/check-read-permissions! cfg profile-id source-id)
               (db/exec! conn [sql:get-file-branch-merges source-id])))))

;; --- COMMANDS: the changes a profile marked as viewed

(defn- get-readable-branch
  "The branch `branch-id`, once `profile-id` is known to read its source.
  Raises when there is no such branch."
  [{:keys [::db/conn] :as cfg} profile-id branch-id]
  (let [branch (db/get* conn :file-branch {:id branch-id})]
    (when (nil? branch)
      (ex/raise :type :not-found
                :code :branch-not-found
                :hint "unable to find branch with the provided id"
                :branch-id branch-id))
    (files/check-read-permissions! cfg profile-id (:source-file-id branch))
    branch))

(def ^:private schema:get-branch-change-marks
  [:map {:title "get-branch-change-marks"}
   [:branch-id ::sm/uuid]
   [:direction [:enum :branch->source :source->branch]]])

(def ^:private sql:get-branch-change-marks
  "SELECT r.change_key, r.fingerprint
     FROM file_branch_change_mark AS r
    WHERE r.file_branch_id = ?
      AND r.profile_id = ?
      AND r.direction = ?")

(sv/defmethod ::get-branch-change-marks
  "The changes of a comparison the profile marked as viewed, each with
  the fingerprint of the change when it was marked. The marks are the
  profile's own: nobody else's are returned. A mark whose fingerprint no
  longer matches its change is stale, which the reader of the comparison
  decides: the change may have moved since."
  {::doc/added "2.16"
   ::sm/params schema:get-branch-change-marks}
  [cfg {:keys [::rpc/profile-id branch-id direction]}]
  (check-branching-enabled!)
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (get-readable-branch cfg profile-id branch-id)
             (db/exec! conn [sql:get-branch-change-marks
                             branch-id profile-id (name direction)]))))

(def ^:private schema:set-branch-change-mark
  [:map {:title "set-branch-change-mark"}
   [:branch-id ::sm/uuid]
   [:direction [:enum :branch->source :source->branch]]
   [:change-key [:string {:max 500}]]
   [:fingerprint [:string {:max 250}]]
   [:viewed ::sm/boolean]])

(def ^:private sql:upsert-branch-change-mark
  "INSERT INTO file_branch_change_mark
          (file_branch_id, profile_id, direction, change_key, fingerprint)
   VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (file_branch_id, profile_id, direction, change_key)
       DO UPDATE SET fingerprint = EXCLUDED.fingerprint,
                     created_at = clock_timestamp()")

(sv/defmethod ::set-branch-change-mark
  "Mark a change of a comparison as viewed by the profile, with the
  fingerprint of the change as it stands, or clear the mark. Reading the
  branch is enough: the mark is the profile's own note and changes
  nothing of the branch."
  {::doc/added "2.16"
   ::sm/params schema:set-branch-change-mark
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id branch-id direction change-key fingerprint viewed]}]
  (check-branching-enabled!)
  (get-readable-branch cfg profile-id branch-id)
  (if viewed
    (db/exec-one! conn [sql:upsert-branch-change-mark
                        branch-id profile-id (name direction) change-key fingerprint])
    (db/delete! conn :file-branch-change-mark
                {:file-branch-id branch-id
                 :profile-id profile-id
                 :direction (name direction)
                 :change-key change-key}))
  nil)

;; --- COMMAND: update-file-branch-info (rename / description)

(def ^:private schema:update-file-branch-info
  [:map {:title "update-file-branch-info"}
   [:branch-id ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]
   [:description {:optional true} [:string {:max 4000}]]])

(sv/defmethod ::update-file-branch-info
  "Rename a branch or update its description."
  {::doc/added "2.16"
   ::sm/params schema:update-file-branch-info
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id branch-id name description]}]
  (check-branching-enabled!)
  (when (some? name)
    (check-branch-name! name))
  (let [tpoint (ct/tpoint)
        branch (db/get* conn :file-branch {:id branch-id})]
    (when (nil? branch)
      (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    ;; Serialize against a merge of the same branch (which locks the
    ;; branch file) and re-read the row under the lock: the branch may
    ;; be merged and gone by now.
    (db/xact-lock! conn (:branch-file-id branch))
    (let [branch (db/get* conn :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
      (db/update! conn :file-branch
                  (cond-> {:updated-at (ct/now)}
                    (some? name)        (assoc :name name)
                    (some? description) (assoc :description description))
                  {:id branch-id}
                  {::db/return-keys false})
      ;; the branch file carries its own name (set from the branch name
      ;; at creation), so a rename has to reach the file row too
      (when (some? name)
        (files/rename-file conn {:id (:branch-file-id branch) :name name}))
      (audited {:id branch-id
                :name (or name (:name branch))
                :description (or description (:description branch))}
               tpoint :rename-branch))))

;; --- COMMAND: archive-file-branch

(def ^:private schema:archive-file-branch
  [:map {:title "archive-file-branch"}
   [:branch-id ::sm/uuid]
   [:archived {:optional true} ::sm/boolean]])

(sv/defmethod ::archive-file-branch
  "Archive (hide from the default list) or restore a branch."
  {::doc/added "2.16"
   ::sm/params schema:archive-file-branch
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id branch-id archived]}]
  (check-branching-enabled!)
  (let [tpoint (ct/tpoint)
        branch (db/get* conn :file-branch {:id branch-id})]
    (when (nil? branch)
      (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    ;; Serialize against a merge of the same branch (which locks the
    ;; branch file) and re-read the row under the lock: the branch may
    ;; be merged and gone by now.
    (db/xact-lock! conn (:branch-file-id branch))
    (let [branch (db/get* conn :file-branch {:id branch-id})]
      (when (nil? branch)
        (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
      (when (= "merged" (:status branch))
        (ex/raise :type :validation :code :branch-merged :branch-id branch-id))
      (let [status (if (false? archived) "open" "archived")]
        (db/update! conn :file-branch
                    {:status status :updated-at (ct/now)}
                    {:id branch-id}
                    {::db/return-keys false})
        ;; archiving hides the branch from the default flow, so its open
        ;; review has no reason to stay open either
        (when (= "archived" status)
          (close-branch-reviews cfg branch {:profile-id profile-id
                                            :status "closed"}))
        (audited {:id branch-id :status status} tpoint
                 (if (false? archived) :restore-branch :archive-branch))))))

;; --- COMMAND: delete-file-branch

(def ^:private schema:delete-file-branch
  [:map {:title "delete-file-branch"}
   [:branch-id ::sm/uuid]])

(sv/defmethod ::delete-file-branch
  "Logically delete a branch: marks the branch metadata and the branch
  file as deleted (with the team's deletion delay) and schedules the
  file object for GC, mirroring the normal file deletion path."
  {::doc/added "2.16"
   ::webhooks/event? true
   ::sm/params schema:delete-file-branch}
  [{:keys [::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id ::rpc/session-id branch-id]}]
  (check-branching-enabled!)
  (let [tpoint   (ct/tpoint)
        branch   (db/get* cfg :file-branch {:id branch-id})
        messages (volatile! [])]
    (when (nil? branch)
      (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
    (files/check-edition-permissions! cfg profile-id (:branch-file-id branch))
    (tx-run-notify!
     cfg
     msgbus
     messages
     (fn [{:keys [::db/conn] :as cfg}]
       ;; Serialize against a merge of the same branch (which locks both
       ;; files): without the lock a delete can commit while the merge
       ;; runs, and the merge then deletes the branch a second time. The
       ;; row is re-read under the lock: the branch may be gone by now.
       (db/xact-lock! conn (:branch-file-id branch))
       (let [branch (db/get* conn :file-branch {:id branch-id})]
         (when (nil? branch)
           (ex/raise :type :not-found :code :branch-not-found :branch-id branch-id))
         ;; no `:status` here: a delete keeps the status the row carries
         ;; (deleting a kept merged branch must not rewrite "merged")
         (vswap! messages conj
                 (delete-branch! cfg branch {:profile-id profile-id
                                             :session-id session-id}))
         (audited {:status :deleted} tpoint :delete-branch))))))


