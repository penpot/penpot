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
  [:id :origin :undo-group :redo-changes :undo-changes :remote-seq :undo-index])

(def ^:private saved-keys
  "Keys a saved commit takes from its queue entry."
  [:file-id :file-revn :file-vern :features])

(defn queue-entry
  "Merges `commits` of one file, oldest first, into a queue entry."
  [[commit :as commits]]
  (-> (select-keys commit entry-keys)
      (assoc :commits (mapv #(select-keys % commit-keys) commits))))

(defn pending-commits
  "Unsaved commits of `file-id`, oldest first: queued, then buffered."
  [pstate file-id]
  (let [file?     #(= file-id (:file-id %))
        xf:queued (comp (map #(get-in pstate [:index %]))
                        (filter file?)
                        (mapcat :commits))]
    (-> []
        (into xf:queued (:queue pstate))
        (into (filter file?) (:buffer pstate)))))

(defn- pending-files
  "Files with unsaved commits."
  [pstate]
  (-> #{}
      (into (map #(get-in pstate [:index % :file-id])) (:queue pstate))
      (into (map :file-id) (:buffer pstate))))

(defn prune-remote-changes
  "Forgets the remote changes of `file-id` older than its oldest pending
  commit, which a rollback never applies again."
  [pstate file-id]
  (if-let [since (some-> (first (pending-commits pstate file-id)) :remote-seq)]
    (d/update-in-when pstate [:remote-changes file-id]
                      (fn [entries] (into [] (filter #(> (:seq %) since)) entries)))
    (update pstate :remote-changes dissoc file-id)))

(defn- saved-commits
  "The `commits` of `entry` as saved commits, which carry their file."
  [entry commits]
  (let [file (select-keys entry saved-keys)]
    (map #(merge % file) commits)))

(defn keep-saved-commits
  "Keeps the saved commits, `entry`'s included, whose undo group a pending
  commit shares: if that commit is rejected they are undone too."
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
  a peer's later value for an attribute survives. Saved commits of a
  reverted group go to `:compensate`, to be undone by a new commit.
  `:undo-index` is the undo history index before the oldest undo or redo
  commit it reverts, if any."
  [pstate {:keys [file-id commits] :as entry} {:keys [commit-ids]}]
  (let [rejected  (set commit-ids)
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

        saved     (into (vec (:saved pstate)) (saved-commits entry accepted))

        compensate
        (into [] (filter #(contains? groups (:undo-group %))) saved)

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
     :compensate compensate
     :groups     groups
     ;; Saved commits are older than pending ones
     :undo-index (some :undo-index (concat compensate (mapcat val reverted)))}))

(defn release-plan
  "How to roll back every pending commit of `file-id`, as if the server had
  rejected them all, together with the rest of their undo groups."
  [pstate file-id]
  (rollback-plan pstate
                 {:file-id file-id :commits []}
                 {:commit-ids (map :id (pending-commits pstate file-id))}))

(defn rollback-summary
  "Counts the commits `plan` reverts and the files they belong to."
  [{:keys [files compensate]}]
  {:reverted (+ (transduce (map (comp count :reverted val)) + files)
                (count compensate))
   :files    (count (into (set (keys files)) (map :file-id) compensate))})

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
  "Removes from `pstate` the commits `plan` reverts or compensates. The
  in-flight `entry` keeps only the commits that stay saved."
  [pstate {:keys [id file-id commits]} {:keys [files compensate]}]
  (let [head        (into #{} (map :id) commits)
        reverted    (into #{} (comp (mapcat (comp :reverted val)) (map :id)) files)
        compensated (into #{} (map :id) compensate)
        unsaved     (into reverted compensated)]
    (as-> pstate $
      (drop-commits $ (into #{} (remove head) reverted))
      (d/update-in-when $ [:index id :commits]
                        (fn [commits]
                          (into [] (remove (comp unsaved :id)) commits)))
      (update $ :saved (fn [saved] (into [] (remove (comp compensated :id)) saved)))
      (update $ :remote-changes dissoc file-id)
      (reduce prune-remote-changes $ (disj (set (keys files)) file-id)))))

(defn compensation-changes
  "Changes that undo the saved `commits` of one file, newest first."
  [commits]
  {:redo-changes (into [] (mapcat :undo-changes) (reverse commits))
   :undo-changes (into [] (mapcat :redo-changes) commits)})
