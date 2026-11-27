;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence.commits
  "Pure functions over the commits of the persistence state: buffered,
  queued and saved ones, and the plan to roll some of them back."
  (:require
   [app.common.data :as d]))

(def ^:private entry-keys
  "Keys a queue entry keeps of the first commit merged into it."
  [:id :created-at :origin :features :file-id :file-revn :file-vern])

(def ^:private commit-keys
  "Keys a queue entry keeps of each of its commits."
  [:id :origin :undo-group :redo-changes :undo-changes :remote-seq :undo-index
   :undo-uncertain :compensation :replay-base :replay-targets])

(def ^:private saved-keys
  "Keys a saved commit takes from its queue entry."
  [:file-id :file-revn :file-vern :features])

(defn queue-entry
  "Merges `commits` of one file, oldest first, into a queue entry."
  [[commit :as commits]]
  (-> (select-keys commit entry-keys)
      (assoc :commits (mapv #(select-keys % commit-keys) commits))))

(defn- of-version?
  [file-id vern commit]
  (and (= file-id (:file-id commit))
       (= vern (:file-vern commit))))

(defn- get-pending-commits
  [{:keys [queue index buffer]} matches?]
  (let [xf:queued (comp (map #(get index %))
                        (remove :confirmed)
                        (filter matches?)
                        (mapcat :commits))]
    (-> []
        (into xf:queued queue)
        (into (filter matches?) buffer))))

(defn pending-commits
  "Unconfirmed commits of `file-id`, oldest first: queued, then buffered.
  With `vern`, only commits of that exact version, including nil."
  ([pstate file-id]
   (get-pending-commits pstate #(= file-id (:file-id %))))
  ([pstate file-id vern]
   (get-pending-commits pstate (partial of-version? file-id vern))))

(defn- pending-files
  "Files with unsaved commits."
  [{:keys [queue index buffer]}]
  (-> #{}
      (into (comp (map #(get index %))
                  (remove :confirmed)
                  (filter (comp seq :commits))
                  (map :file-id))
            queue)
      (into (map :file-id) buffer)))

(defn mark-undo-uncertain
  "Marks the tracked commits of `file-id` and `vern`: a repair or a fresh
  server base may make their absolute undo changes unsafe."
  [pstate file-id vern]
  (let [matches? (partial of-version? file-id vern)
        mark     #(assoc % :undo-uncertain true)
        mark-all #(mapv (fn [commit] (if (matches? commit) (mark commit) commit)) %)]
    (-> pstate
        (d/update-when :index d/update-vals
                       (fn [entry]
                         (cond-> entry
                           (matches? entry)
                           (update :commits #(mapv mark %)))))
        (d/update-when :buffer mark-all)
        (d/update-when :saved mark-all))))

(defn without-unsent-compensations
  "Drops compensations of this version that have never been sent. A
  queue entry left empty stays in place for the runner to acknowledge."
  [pstate file-id vern unsent?]
  (let [matches? (partial of-version? file-id vern)
        trim     #(into [] (remove :compensation) %)]
    (-> pstate
        (d/update-when :index d/update-vals
                       (fn [entry]
                         (if (and (matches? entry) (unsent? entry))
                           (update entry :commits trim)
                           entry)))
        (d/update-when :buffer
                       #(into [] (remove (fn [commit]
                                           (and (:compensation commit) (matches? commit)))) %)))))

(defn prune-remote-changes
  "Forgets the remote changes of `file-id` older than its oldest pending
  commit, which a rollback never applies again."
  [pstate file-id]
  (if-let [since (some-> (first (pending-commits pstate file-id)) :remote-seq)]
    (d/update-in-when pstate [:remote-changes file-id]
                      (fn [entries] (into [] (filter #(> (:seq %) since)) entries)))
    (update pstate :remote-changes dissoc file-id)))

(defn without-other-versions
  "`pstate` without the commits of `file-id` made on a version other than
  `vern`, the one just loaded: a restore replaced it, so they are not in
  the data and the server refuses them. Their queue entries stay, emptied,
  for the runner to settle the one in flight and skip the rest."
  [pstate file-id vern]
  (let [other?  #(and (= file-id (:file-id %))
                      (not= vern (:file-vern %)))
        emptied (into {}
                      (keep (fn [id]
                              (let [entry (get-in pstate [:index id])]
                                (when (and (other? entry) (seq (:commits entry)))
                                  [id (assoc entry :commits [])]))))
                      (:queue pstate))]
    (if (or (seq emptied)
            (some other? (:buffer pstate))
            (some other? (:saved pstate)))
      (-> pstate
          (update :index merge emptied)
          (update :buffer #(into [] (remove other?) %))
          (update :saved #(into [] (remove other?) %))
          (prune-remote-changes file-id))
      pstate)))

(defn- saved-commits
  "The `commits` of `entry` as saved commits, which carry their file."
  [entry commits]
  (let [file (select-keys entry saved-keys)]
    (map #(merge % file) commits)))

(defn keep-saved-commits
  "Keeps the saved commits, `entry`'s included, whose undo group a pending
  commit shares: if that commit is rejected they may be undone too."
  [pstate {:keys [commits] :as entry}]
  (let [groups (into #{}
                     (keep :undo-group)
                     (mapcat #(pending-commits pstate %) (pending-files pstate)))]
    (assoc pstate :saved
           (into []
                 (filter #(contains? groups (:undo-group %)))
                 (concat (:saved pstate) (saved-commits entry commits))))))

(defn- reverted-commits
  "Pending commits to revert per file: from the first one that is rejected
  in `file-id`, or in an undo group of `groups`, to the last."
  [pending file-id rejected kept groups]
  (let [reverted?
        (fn [fid {:keys [id undo-group]}]
          (and (not (contains? kept id))
               (or (and (= fid file-id) (contains? rejected id))
                   (contains? groups undo-group))))

        from-first-reverted
        (fn [[fid commits]]
          (when-let [i (d/index-of-pred commits (partial reverted? fid))]
            [fid (subvec commits i)]))]
    (into {} (keep from-first-reverted) pending)))

(defn rollback-plan
  "How to roll back the save of `entry` that came back `rejected`. Whole
  undo groups are reverted, across files and saves.

  Each file in `:files` undoes all its pending commits, applies again the
  remote changes that came after them and redoes the commits that stay, so
   a peer's later value for an attribute survives. Files in `:reconcile`
   must be reloaded instead of applying that local rebase.

   Saved commits of reverted groups go to `:compensate` when safe to undo
   by a new commit, or `:kept-saved` when their group has an uncertain undo
   or the file itself needs reconciliation. `:undo-index`
   is the undo history index before the oldest undo or redo commit the
   plan reverts, if any."
  [pstate {:keys [id file-id] :as entry} {:keys [commit-ids uncertain preview-file-id]}]
  (let [commits   (:commits (get-in pstate [:index id] entry))
        rejected  (set commit-ids)
        accepted  (into [] (remove #(contains? rejected (:id %))) commits)
        kept      (into #{} (map :id) accepted)
        pending   (into {} (map (juxt identity #(pending-commits pstate %))) (pending-files pstate))

        [reverted groups]
        (loop [groups #{}]
          (let [reverted (reverted-commits pending file-id rejected kept groups)
                groups'  (into groups (comp (mapcat val) (keep :undo-group)) reverted)]
            (if (= groups groups')
              [reverted groups]
              (recur groups'))))

        saved            (into (vec (:saved pstate)) (saved-commits entry accepted))
        uncertain-groups (into #{}
                               (comp (filter :undo-uncertain) (keep :undo-group))
                               (concat saved (mapcat val pending)))

        {kept-saved true candidates false}
        (group-by #(boolean (or uncertain (contains? uncertain-groups (:undo-group %))))
                  (filter #(contains? groups (:undo-group %)) saved))

        reconcile
        (cond-> (into (into #{} (map :file-id) kept-saved)
                      (filter #(some :undo-uncertain (get pending %)))
                      (keys reverted))
          (or (contains? reverted preview-file-id)
              (some #(= preview-file-id (:file-id %)) candidates))
          (conj preview-file-id)

          uncertain (conj file-id))

        ;; A compensation needs a reliable base even when its own group
        ;; wasn't marked. Never apply it in a file we are about to read.
        {also-kept true compensate false}
        (group-by #(contains? reconcile (:file-id %)) candidates)

        file-plan
        (fn [[fid commits]]
          (let [all       (get pending fid)
                since     (:remote-seq (first all) 0)
                xf:remote (comp (filter #(> (:seq %) since))
                                (mapcat :changes))]
            [fid {:undo     all
                  :remote   (into [] xf:remote (get-in pstate [:remote-changes fid]))
                  :redo     (subvec all 0 (- (count all) (count commits)))
                  :reverted commits}]))]

    {:files      (into {} (map file-plan) reverted)
     :compensate (or compensate [])
     :kept-saved (into (or kept-saved []) also-kept)
     :reconcile  reconcile
     :groups     groups
     ;; Compensated commits are older than pending ones. Kept commits
     ;; need no index rewind, since their effects stay.
     :undo-index (some :undo-index (concat compensate (mapcat val reverted)))}))

(defn release-plan
  "How to roll back every pending commit of `file-id`, as if the server had
  rejected them all, together with the rest of their undo groups."
  ([pstate file-id] (release-plan pstate file-id nil))
  ([pstate file-id preview-file-id]
   (rollback-plan pstate
                  {:file-id file-id :commits []}
                  {:commit-ids (map :id (pending-commits pstate file-id))
                   :preview-file-id preview-file-id})))

(defn rollback-summary
  "Counts reverted commits and their files; counts any kept saved commits
  separately."
  [{:keys [files compensate kept-saved]}]
  (cond-> {:reverted (+ (transduce (map (comp count :reverted val)) + files)
                        (count compensate))
           :files    (count (into (set (keys files)) (map :file-id) compensate))}
    (seq kept-saved)
    (assoc :kept-saved (count kept-saved))))

(def ^:private page-change-types
  "Changes whose `:id` is a page id."
  #{:add-page :mod-page :del-page :mov-page})

(def ^:private shape-change-types
  "Changes whose `:id` is a shape id."
  #{:add-obj :mod-obj :del-obj :fix-obj})

(defn- change-page-id
  [{:keys [type] :as change}]
  (if (contains? page-change-types type)
    (:id change)
    (:page-id change)))

(defn- change-shape-ids
  "Ids of the page shapes `change` touches; none for component shapes."
  [{:keys [type page-id] :as change}]
  (when (some? page-id)
    (cond
      (contains? shape-change-types type)
      [(:id change)]

      (contains? #{:mov-objects :reorder-children} type)
      (:shapes change))))

(defn rollback-targets
  "The files and undo groups `plan` reverts, and the pages, page shapes
  and comment thread positions their changes touch."
  [{:keys [files compensate]}]
  (let [commits (into (vec compensate) (mapcat (comp :reverted val)) files)
        changes (into [] (mapcat :redo-changes) commits)]
    {:file-ids           (into (set (keys files)) (map :file-id) compensate)
     :undo-groups        (into #{} (keep :undo-group) commits)
     :page-ids           (into #{} (keep change-page-id) changes)
     :shape-ids          (into #{} (mapcat change-shape-ids) changes)
     :comment-thread-ids (into #{}
                               (comp (filter #(= :set-comment-thread-position (:type %)))
                                     (map :comment-thread-id))
                               changes)}))

(defn- drop-commits
  "Removes the commits in `ids` from the queue and the buffer; an entry left
  empty leaves the queue."
  [pstate ids]
  (let [dropped? (comp ids :id)
        trim     (fn [id]
                   [id (update (get-in pstate [:index id]) :commits
                               #(into [] (remove dropped?) %))])
        trimmed  (into {}
                       (comp (filter #(some dropped? (get-in pstate [:index % :commits])))
                             (map trim))
                       (:queue pstate))
        emptied  (into #{} (comp (filter (comp empty? :commits val)) (map key)) trimmed)]
    (-> pstate
        (update :queue #(into (empty %) (remove emptied) %))
        (update :index #(apply dissoc (merge % trimmed) emptied))
        (update :buffer #(into [] (remove dropped?) %)))))

(defn without-rolled-back
  "Removes reverted pending commits and forgets the saved commits of those
  groups, compensated or not. The in-flight `entry` keeps only the commits
  that stay saved."
  [pstate {:keys [id file-id commits]} {:keys [files compensate kept-saved]}]
  (let [head        (into #{} (map :id) commits)
        reverted    (into #{} (comp (mapcat (comp :reverted val)) (map :id)) files)
        compensated (into #{} (map :id) compensate)
        forgotten   (into compensated (map :id) kept-saved)
        unsaved     (into reverted compensated)]
    (as-> pstate $
      (drop-commits $ (into #{} (remove head) reverted))
      (d/update-in-when $ [:index id :commits]
                        (fn [commits]
                          (into [] (remove (comp unsaved :id)) commits)))
      (update $ :saved (fn [saved] (into [] (remove (comp forgotten :id)) saved)))
      (update $ :remote-changes dissoc file-id)
      (reduce prune-remote-changes $ (disj (set (keys files)) file-id)))))

(defn compensation-changes
  "Changes that undo the saved `commits` of one file, newest first."
  [commits]
  {:redo-changes (into [] (mapcat :undo-changes) (reverse commits))
   :undo-changes (into [] (mapcat :redo-changes) commits)})
