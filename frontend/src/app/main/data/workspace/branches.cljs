;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.branches
  "Data layer for file branching: the branches of a file, the diff of a
  branch against main, and merging and updating it. Mirrors the patterns
  in `app.main.data.workspace.versions`."
  (:require
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn- reload-file-window
  "Hard-reload the browser window so the open file is re-fetched from
  scratch. Used after merge / update-from-main, which rewrite the file
  server-side (including repair changes); a full reload is the most
  robust way to show the new state without local/server divergence."
  []
  (ptk/reify ::reload-file-window
    ptk/EffectEvent
    (effect [_ _ _]
      (dom/reload-current-window))))

(declare open-branch)

(defn- show-merge-result
  "After merging a branch into main, surface the merged result: if main is
  the file currently open, hard-reload it; otherwise navigate to main."
  [source-file-id]
  (ptk/reify ::show-merge-result
    ptk/WatchEvent
    (watch [_ state _]
      (cond
        (nil? source-file-id)                          (rx/of (reload-file-window))
        (= source-file-id (:current-file-id state))    (rx/of (reload-file-window))
        :else                                          (rx/of (open-branch source-file-id))))))

(defonce default-state
  {:status :loading
   :data nil
   :limits nil})

(declare fetch-branches)
(declare fetch-branching-limits)
(declare fetch-branch-context)

(defn init-branches-state
  []
  (ptk/reify ::init-branches-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branches default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-branches)
             (fetch-branching-limits)))))

(defn update-branches-state
  [branch-state]
  (ptk/reify ::update-branches-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-branches merge branch-state))))

(defn- set-branch-count
  "Store the branch count of `file-id`: `{:file-id .. :count n}`, or
  `{:file-id .. :status :error}` when the count could not be loaded."
  [file-id count-state]
  (ptk/reify ::set-branch-count
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-count (assoc count-state :file-id file-id)))))

(defn fetch-branch-count
  "Load how many live branches the family of the open file has, archived
  ones included: the count the branch quota checks. The header reads it
  to decide whether to show its branch row, so it loads with the file; it
  compares nothing, unlike the listing, which loads only when a user opens
  the switcher or the all-branches dialog. A count belonging to ANOTHER
  file is dropped synchronously, as `fetch-branch-context` drops its own.
  A failure is kept as an error, which shows the row: a count that is
  unknown must not hide branches the file may have. With `listed`, the
  number of branches a listing just returned, a failure keeps that number
  instead: the listing is a lower bound of the family's count, which is
  all the header needs to show its row. The count lives under its own key
  because `init-branches-state` resets `:workspace-branches`."
  ([] (fetch-branch-count nil))
  ([listed]
   (ptk/reify ::fetch-branch-count
     ptk/UpdateEvent
     (update [_ state]
       (let [file-id (:current-file-id state)]
         (if (= file-id (get-in state [:workspace-branch-count :file-id]))
           state
           (dissoc state :workspace-branch-count))))

     ptk/WatchEvent
     (watch [_ state _]
       (when-let [file-id (:current-file-id state)]
         (->> (rp/cmd! :get-file-branch-count {:file-id file-id})
              (rx/map #(set-branch-count file-id {:count (:count %)}))
              (rx/catch (fn [_]
                          (rx/of (set-branch-count file-id (if (some? listed)
                                                             {:count listed}
                                                             {:status :error})))))))))))

(defn branch-count-failed?
  "True when the branch count of the open file could not be loaded."
  [state]
  (= :error (get-in state [:workspace-branch-count :status])))

(defn fetch-branches
  "Load the branch listing of the open file. A failure leaves the listing
  in `:error`, never in an empty list, so it cannot read as a file with no
  branches. A successful listing also reloads the branch count, so the
  header follows a delete or a materialize made from the listing. The
  count is reloaded rather than taken from the listing, which holds only
  the branches of one source while the count covers the family; the
  listing's size stands in only when that reload fails."
  []
  (ptk/reify ::fetch-branches
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        ;; the open file may itself be a branch; branches are listed by their
        ;; source (main), so resolve the source first (nil when on main) and
        ;; list siblings against it, so the panel is complete from a branch too
        (->> (rp/cmd! :get-file-branch-info {:file-id file-id})
             (rx/mapcat (fn [info]
                          (let [root (or (:source-file-id info) file-id)]
                            (rp/cmd! :get-file-branches {:file-id root :include-archived true}))))
             (rx/mapcat (fn [data]
                          (rx/of (update-branches-state {:status :loaded :data data})
                                 (fetch-branch-count (count data)))))
             (rx/catch (fn [_]
                         (rx/of (update-branches-state {:status :error})))))))))

(defn- set-branch-merges
  [merges-state]
  (ptk/reify ::set-branch-merges
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-merges merges-state))))

(defn fetch-branch-merges
  "Load the merges into the open file's main, newest first, into
  `:workspace-branch-merges` (`{:status :loading|:loaded|:error}` plus
  `:data`): who merged each branch, when, and the comment left with it.
  The command resolves a branch to its main, so it is asked about the
  open file as is. A failure is kept as an error, never as an empty list,
  so it cannot read as a file with no merges."
  []
  (ptk/reify ::fetch-branch-merges
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-merges {:status :loading}))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-file-branch-merges {:file-id file-id})
             (rx/map #(set-branch-merges {:status :loaded :data %}))
             (rx/catch (fn [_] (rx/of (set-branch-merges {:status :error})))))))))

(defn fetch-branching-limits
  "Load the size limits this instance refuses beyond, so the panel can
  state them before a user meets one. The numbers live in the backend
  config and are never restated here."
  []
  (ptk/reify ::fetch-branching-limits
    ptk/WatchEvent
    (watch [_ state _]
      (when (nil? (get-in state [:workspace-branches :limits]))
        (->> (rp/cmd! :get-branching-limits {})
             (rx/map #(update-branches-state {:limits %}))
             (rx/catch (fn [_] (rx/empty))))))))

(defn- refusal-notification
  "The notification for a branching refusal, naming the limit it hit and
  what to do instead, or `fallback` when the cause is not one. The number
  comes from the refusal itself: the copy never keeps its own copy of a
  limit, so it cannot drift from the config the gate reads."
  [cause fallback]
  (let [{:keys [code limit]} (ex-data cause)]
    (case code
      :branching-shape-limit-exceeded
      (ntf/warn (tr "workspace.branches.limits.refuse-shapes" (str limit)))

      :branching-page-limit-exceeded
      (ntf/warn (tr "workspace.branches.limits.refuse-pages" (str limit)))

      :branching-oplog-limit-exceeded
      (ntf/warn (tr "workspace.branches.limits.refuse-changes" (str limit)))

      :cannot-branch-a-branch
      (ntf/warn (tr "workspace.branches.limits.refuse-nested"))

      :base-snapshot-missing
      (ntf/error (tr "workspace.branches.limits.refuse-base-missing"))

      ;; the branch changed state under an open panel: one line for the
      ;; whole family, because what the user does next is the same
      (:branch-not-found :branch-merged :branch-not-open)
      (ntf/warn (tr "workspace.branches.limits.refuse-stale"))

      ;; the branch to cut from, merge into, or update from is archived
      ;; or gone (`:branch-stacking`)
      :source-branch-not-open
      (ntf/warn (tr "workspace.branches.limits.refuse-source-not-open"))

      :invalid-branch-name
      (ntf/warn (tr "workspace.branches.limits.refuse-name"))

      ;; the same refusal as the `:unsupported` merge status, raised while
      ;; squashing an update, so it reads the same to a user
      :unsupported-update-squash
      (ntf/warn (tr "workspace.branches.update.unsupported"))

      ;; every change of the comparison was deselected: nothing to merge
      ;; or to update, and the branch stays as it is
      :nothing-to-merge
      (ntf/warn (tr "workspace.branches.merge.nothing-selected"))

      (ntf/error fallback))))

;; --- Dashboard popover (list a file's branches from the dashboard)

(defn- set-dashboard-branches
  [bs]
  (ptk/reify ::set-dashboard-branches
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-branches bs))))

(defn load-file-branches
  "Load the open and archived branches of `file-id` into
  `:dashboard-branches` (used by the dashboard branches popover). These
  are the file's direct branches: the badge counts the whole family, so
  the two agree only while branches of branches are off. The previous
  list is cleared first so a popover opened on another file never shows
  stale entries."
  [file-id]
  (ptk/reify ::load-file-branches
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-branches []))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-file-branches {:file-id file-id :include-archived true})
           (rx/map set-dashboard-branches)
           (rx/catch (fn [_] (rx/of (set-dashboard-branches []))))))))

(defn rename-branch
  [id name]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::rename-branch
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-file-branch-info {:branch-id id :name name})
           (rx/mapcat (fn [_] (rx/of (fetch-branches) (fetch-branch-context))))
           (rx/catch (fn [cause] (rx/of (refusal-notification cause (tr "workspace.branches.lifecycle.error")))))))))

(defn set-branch-description
  "Update a branch's free-text description (shown in the Branch info modal).
  Refreshes the panel and the on-branch banner so the new value is visible."
  [id description]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::set-branch-description
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-file-branch-info {:branch-id id :description description})
           (rx/mapcat (fn [_] (rx/of (fetch-branches) (fetch-branch-context))))
           (rx/catch (fn [cause] (rx/of (refusal-notification cause (tr "workspace.branches.lifecycle.error")))))))))

(defn archive-branch
  "Archive a branch, or restore it with `archived?` false. The context
  refreshes as well, so the banner of the open branch follows its status."
  ([id] (archive-branch id true))
  ([id archived?]
   (assert (uuid? id) "expected valid uuid for `id`")
   (ptk/reify ::archive-branch
     ptk/WatchEvent
     (watch [_ _ _]
       (->> (rp/cmd! :archive-file-branch {:branch-id id :archived archived?})
            (rx/mapcat (fn [_] (rx/of (fetch-branches) (fetch-branch-context))))
            (rx/catch (fn [cause] (rx/of (refusal-notification cause (tr "workspace.branches.lifecycle.error"))))))))))

(defn delete-branch
  "Delete a branch. Deleting the open branch takes its file away, so the
  caller passes `open-file-id` (the file it comes from) to land there once
  the branch is gone; this session is not sent away by the deletion it
  made itself."
  ([id] (delete-branch id nil))
  ([id open-file-id]
   (assert (uuid? id) "expected valid uuid for `id`")
   (ptk/reify ::delete-branch
     ptk/WatchEvent
     (watch [_ _ _]
       (->> (rp/cmd! :delete-file-branch {:branch-id id})
            (rx/mapcat (fn [_] (rx/concat
                                (rx/of (ntf/success (tr "workspace.branches.lifecycle.deleted"))
                                       (fetch-branches))
                                (if (uuid? open-file-id)
                                  (rx/of (open-branch open-file-id))
                                  (rx/empty)))))
            (rx/catch (fn [cause] (rx/of (refusal-notification cause (tr "workspace.branches.lifecycle.error"))))))))))

(defn materialize-branch
  "Turn a branch into an ordinary file: `branch-id` is the branch row id
  (the `:id` of a row of `get-file-branches`), and the command persists
  the branch's derived state as the file's own data payload, drops the op
  log and clears the branch marker, so every later read and write takes
  the ordinary route. The content stays; what goes is the branch itself,
  so the file can no longer be compared with main or merged into it. The
  branch row is soft-deleted, so refreshing the panel drops the row and
  refreshing the context drops the banner when the file that left the
  branch is the open one."
  [branch-id]
  (assert (uuid? branch-id) "expected valid uuid for `branch-id`")
  (ptk/reify ::materialize-branch
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :detach-file-branch {:branch-id branch-id})
           (rx/mapcat (fn [_] (rx/of (ntf/success (tr "workspace.branches.materialize.success"))
                                     (fetch-branches)
                                     (fetch-branch-context))))
           (rx/catch (fn [cause] (rx/of (refusal-notification cause (tr "workspace.branches.lifecycle.error")))))))))

(defn create-branch
  "Create a branch from the open file, force-persisting it first so the
  merge base captures the latest edits, and jump into the new branch.
  Errors surface as a toast; the caller closes the dialog."
  [name description]
  (assert (string? name) "expected string for `name`")
  (ptk/reify ::create-branch
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (rx/concat
         ;; Force-persist the open file first so the merge base captures
         ;; the latest edits. The ::force-persist event must be dispatched
         ;; at the top level (not piped into the mapcat below).
         (rx/of ::dwp/force-persist (ev/event {::ev/name "create-branch"}))
         ;; `wait-persisted` emits exactly one value, so the branch is
         ;; created exactly once.
         (->> (dwp/wait-persisted)
              (rx/mapcat #(rp/cmd! :create-file-branch
                                   {:file-id file-id
                                    :name name
                                    :description description}))
              (rx/mapcat
               (fn [{:keys [branch-file-id]}]
                 ;; jump into the new branch; the branch switcher in the
                 ;; header shows where the user landed
                 (rx/of (ntf/success (tr "workspace.branches.create.success" name))
                        (open-branch branch-file-id))))
              (rx/catch
               (fn [cause]
                 (rx/of (refusal-notification cause (tr "workspace.branches.create.error")))))))))))

(defn toggle-branch-switcher
  "Open or close the branch switcher popover of the workspace header. With
  no argument it flips the current state; `open?` forces it."
  ([] (toggle-branch-switcher nil))
  ([open?]
   (ptk/reify ::toggle-branch-switcher
     ptk/UpdateEvent
     (update [_ state]
       (let [open? (if (some? open?)
                     (boolean open?)
                     (not (:workspace-branch-switcher state)))]
         (assoc state :workspace-branch-switcher open?))))))

(defn has-branches?
  "True when the open file takes part in branching: it is a branch, or it
  is main and has at least one branch (archived ones count, so they stay
  reachable). The header only shows the branch switcher then. It reads the
  branch count, never the listing, so a listing that fails or is still
  loading cannot hide the switcher. A count that failed shows the
  switcher too: opening it loads the listing, which settles the count."
  [state]
  (or (some? (get-in state [:workspace-branch-context :info]))
      (branch-count-failed? state)
      (pos? (get-in state [:workspace-branch-count :count] 0))))

(defn open-branch
  "Navigate to the branch file as a normal workspace file. The branch
  switcher and any open dialog close; once there, the header shows the
  branch."
  [branch-file-id]
  (assert (uuid? branch-file-id) "expected valid uuid for `branch-file-id`")
  (ptk/reify ::open-branch
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-switcher false))

    ptk/WatchEvent
    (watch [_ _ _]
      ;; the all-branches dialog may be the one that asked
      (rx/of (modal/hide)
             (dcm/go-to-workspace :file-id branch-file-id)))))

(defn show-branches
  "The entry point of the File menu and the Ctrl/Cmd+Alt+B shortcut. A
  file that has branches gets the branches dialog (the full list, the
  same the switcher's \"All branches\" opens, which shows this shortcut);
  one with none gets the create-branch dialog. No-op when the branching
  feature flag is disabled."
  []
  (ptk/reify ::show-branches
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? cf/flags :branching)
        (if (has-branches? state)
          (rx/of (toggle-branch-switcher false)
                 (modal/show :branches-list {}))
          (rx/of (modal/show :create-branch {})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPARE (read-only 3-way diff)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn item-key
  "The key a change or a conflict of a diff is chosen and marked as
  viewed by: its change key
  (`app.common.files.branch-merge/change-key`), or its kind and id for an
  entry that carries none."
  [item]
  (or (:key item) (str (name (:kind item)) ":" (:id item))))

(defn change-fingerprint
  "A digest of what the diff entry `item` is: what happened to it and the
  values it moved between. A review mark is kept with it, so a mark made
  on a change that later became another one no longer counts."
  [item]
  (str (hash [(:status item) (:changed-attrs item) (:shape item) (:source item) (:branch item)])))

(declare fetch-diff-viewed)

(defn update-branch-diff
  [diff-state]
  (ptk/reify ::update-branch-diff
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-branch-diff merge diff-state))))

(defn fetch-branch-diff
  "Load the 3-way diff for `branch-id`. `direction` selects which side's
  changes to show: `:branch->source` (default, the branch's outgoing
  changes) or `:source->branch` (the source's incoming changes the branch
  lacks). A newer fetch cancels any previous in-flight one, so a stale
  response can never land over a direction/branch switch."
  ([branch-id] (fetch-branch-diff branch-id :branch->source))
  ([branch-id direction]
   (assert (uuid? branch-id) "expected valid uuid for `branch-id`")
   (ptk/reify ::fetch-branch-diff
     ptk/UpdateEvent
     (update [_ state]
       (let [prev  (:workspace-branch-diff state)
             same? (= branch-id (:branch-id prev))
             same-direction? (and same? (= direction (:direction prev)))]
         (assoc state :workspace-branch-diff
                {:status    :loading
                 :branch-id branch-id
                 :direction direction
                 ;; a refetch for the SAME branch keeps the per-change
                 ;; selection of each direction and the conflict choices
                 ;; (they name the same entities), so going from one
                 ;; direction to the other and back loses nothing; a
                 ;; different branch starts over
                 :excluded  (if same? (or (:excluded prev) {}) {})
                 ;; the review marks are the ones of the direction: they
                 ;; stay while it reloads, and load again with the diff
                 :viewed  (if same-direction? (or (:viewed prev) #{}) #{})
                 :resolutions (when same? (:resolutions prev))})))
     ptk/WatchEvent
     (watch [_ _ stream]
       (->> (rp/cmd! :get-file-branch-diff {:branch-id branch-id :direction direction})
            (rx/mapcat #(rx/of (update-branch-diff {:status :loaded :diff %})
                               (fetch-diff-viewed)))
            (rx/catch (fn [_]
                        (rx/of (update-branch-diff {:status :error}))))
            (rx/take-until (rx/filter (ptk/type? ::fetch-branch-diff) stream)))))))

(defn diff-excluded
  "The per-change selection of `diff-state` (the `:workspace-branch-diff`
  entry) for the direction it shows: the change keys left OUT of the
  merge or update. Each direction keeps its own."
  [diff-state]
  (get-in diff-state [:excluded (:direction diff-state :branch->source)] #{}))

(defn toggle-diff-excluded
  "Toggle the per-change selection of the current diff, in the direction
  it shows. `change-keys` are change keys
  (`app.common.files.branch-merge/change-key`); a key the selection holds
  is left OUT of the merge or update, and a key it lacks travels.
  `include?` sets them all to that side instead of toggling (the group
  checkbox): nil flips the whole batch, which is what a single row
  checkbox needs too."
  ([change-keys] (toggle-diff-excluded change-keys nil))
  ([change-keys include?]
   (assert (set? change-keys) "expected a set of change keys")
   (ptk/reify ::toggle-diff-excluded
     ptk/UpdateEvent
     (update [_ state]
       (let [direction (get-in state [:workspace-branch-diff :direction] :branch->source)]
         (update-in state [:workspace-branch-diff :excluded direction]
                    (fn [excluded]
                      (let [excluded (or excluded #{})
                            include? (if (some? include?)
                                       include?
                                       (every? excluded change-keys))]
                        (if include?
                          (apply disj excluded change-keys)
                          (into excluded change-keys))))))))))

(defn- diff-items
  "Every entry of the diff in `diff-state`, by its key (`item-key`)."
  [diff-state]
  (into {}
        (map (juxt item-key identity))
        (concat (get-in diff-state [:diff :changes])
                (get-in diff-state [:diff :conflicts]))))

(defn- set-diff-viewed
  "Keep `viewed` as the review marks of the diff of `branch-id` in
  `direction`, when that is still the one in the store."
  [branch-id direction viewed]
  (ptk/reify ::set-diff-viewed
    ptk/UpdateEvent
    (update [_ state]
      (let [diff-st (:workspace-branch-diff state)]
        (if (and (= branch-id (:branch-id diff-st))
                 (= direction (:direction diff-st)))
          (assoc-in state [:workspace-branch-diff :viewed] viewed)
          state)))))

(defn fetch-diff-viewed
  "Load the changes the profile marked as viewed in the comparison the
  store holds. A mark counts while its change is still what it was when
  it was marked (`change-fingerprint`): a layer that changed again asks
  to be viewed again. The marks are the profile's own and the same
  inside and outside a branch review. A failure leaves the marks as they
  are."
  []
  (ptk/reify ::fetch-diff-viewed
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [branch-id direction status] :as diff-st} (:workspace-branch-diff state)]
        (if (= :loaded status)
          (let [items (diff-items diff-st)]
            (->> (rp/cmd! :get-branch-change-marks {:branch-id branch-id :direction direction})
                 (rx/map (fn [marks]
                           (set-diff-viewed
                            branch-id direction
                            (into #{}
                                  (keep (fn [{:keys [change-key fingerprint]}]
                                          (when (= fingerprint
                                                   (some-> (get items change-key) change-fingerprint))
                                            change-key)))
                                  marks))))
                 (rx/catch (fn [_] (rx/empty)))))
          (rx/empty))))))

(defn toggle-diff-viewed
  "Mark the change `item-key` as viewed in the compare mode, or clear
  the mark. `viewed?` forces one side; nil flips it. The mark shows at
  once and is kept for the profile, with the fingerprint of the change
  as it stands; if it cannot be kept, the marks are read again. The
  marks only guide the review: the merge does not read them."
  ([item-key] (toggle-diff-viewed item-key nil))
  ([item-key viewed?]
   (ptk/reify ::toggle-diff-viewed
     ptk/UpdateEvent
     (update [_ state]
       (update-in state [:workspace-branch-diff :viewed]
                  (fn [viewed]
                    (let [viewed  (or viewed #{})
                          viewed? (if (some? viewed?)
                                    viewed?
                                    (not (contains? viewed item-key)))]
                      (if viewed?
                        (conj viewed item-key)
                        (disj viewed item-key))))))

     ptk/WatchEvent
     (watch [_ state _]
       (let [{:keys [branch-id direction viewed] :as diff-st} (:workspace-branch-diff state)
             item (get (diff-items diff-st) item-key)]
         ;; only a change of the diff has a mark to keep
         (if (and (some? item) (uuid? branch-id))
           (->> (rp/cmd! :set-branch-change-mark
                         {:branch-id branch-id
                          :direction direction
                          :change-key item-key
                          :fingerprint (change-fingerprint item)
                          :viewed (contains? viewed item-key)})
                (rx/ignore)
                (rx/catch (fn [_] (rx/of (fetch-diff-viewed)))))
           (rx/empty)))))))

(defn clear-diff-excluded
  "Drop the per-change selection of both directions: the compare mode
  closes without applying it."
  []
  (ptk/reify ::clear-diff-excluded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-branch-diff :excluded] {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BRANCH CONTEXT (banner when the open file is a branch)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-branch-context
  "Store the branch context of `file-id`. The state value is
  `{:file-id .. :loaded? true :info <row-or-nil>}`, so consumers can tell
  \"this file is not a branch\" (loaded, nil info) apart from \"not loaded
  yet\" — see `refs/branch-context` for the `:info` lens."
  [file-id info]
  (ptk/reify ::set-branch-context
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-context {:file-id file-id
                                              :loaded? true
                                              :info info}))))

(defn fetch-branch-context
  "Load branch metadata for the current file (nil when it is not a branch).
  A context belonging to ANOTHER file is dropped synchronously so the
  banner/header never show stale data while the RPC is in flight. If the
  file is already known NOT to be a branch the RPC is skipped entirely (a
  file cannot become a branch, and this event fires on every save/focus).
  If `behind` grew since the previous value of the SAME file (main advanced
  while working on the branch), surface a notification."
  []
  (ptk/reify ::fetch-branch-context
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            ctx     (:workspace-branch-context state)]
        (if (or (nil? ctx) (= file-id (:file-id ctx)))
          state
          (dissoc state :workspace-branch-context))))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (let [ctx         (:workspace-branch-context state)
              same-file?  (= file-id (:file-id ctx))
              not-branch? (and same-file? (:loaded? ctx) (nil? (:info ctx)))
              prev-behind (if same-file? (or (:behind (:info ctx)) 0) 0)]
          (when-not not-branch?
            (->> (rp/cmd! :get-file-branch-info {:file-id file-id})
                 (rx/mapcat
                  (fn [info]
                    (rx/concat
                     (rx/of (set-branch-context file-id info))
                     (if (and info (> (or (:behind info) 0) prev-behind))
                       (rx/of (ntf/info (tr "workspace.branches.main-advanced")))
                       (rx/empty)))))
                 (rx/catch (fn [_] (rx/of (set-branch-context file-id nil)))))))))))

(defn open-conflict-resolutions
  "Seed the store with the conflict set a merge or update just returned.
  The compare mode reacts to this event and takes the user to resolve
  them (`app.main.data.workspace.compare/watch-events`).

  Those conflicts are authoritative while they are resolved: they are the
  very ones the command's own `resolved?` matches against, while the diff
  the compare mode fetches on its own is computed in another id space and
  may list a different set, or none at all.

  `source-revn` is the source's revn when the command computed those
  conflicts. It is stored with them because the diff fetch can still be in
  flight when the user applies; see `conflict-source-revn`. `excluded` is
  the per-change selection the attempt was made with and `comment` what
  the user said about the merge: applying the resolutions sends both
  again."
  [{:keys [branch mode conflicts source-revn excluded comment]}]
  (ptk/reify ::open-conflict-resolutions
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-conflicts
             {:branch-id (:id branch)
              :branch branch
              :mode mode
              :conflicts (vec conflicts)
              :source-revn source-revn
              :excluded excluded
              :comment comment}))))

(defn close-conflict-resolutions
  "Forget the conflict set of the last merge or update attempt: the user
  left it unresolved."
  []
  (ptk/reify ::close-conflict-resolutions
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-branch-conflicts))))

(defn- conflict-source-revn
  "The source's revn when the server computed the conflict set stored for
  `branch-id`, or nil when the stored set belongs to another branch.

  Resolving fetches the diff in its own direction, and that fetch
  replaces the loaded diff with a loading state until it answers. The user
  can resolve every conflict and apply inside that window, so merge and
  update fall back to this revn: resolutions never leave without the token
  that lets the server refuse them when the source has moved
  (`:file-modified`). The source's revn is the same in both directions, so
  the mode does not matter."
  [state branch-id]
  (let [conflicts-st (:workspace-branch-conflicts state)]
    (when (= branch-id (:branch-id conflicts-st))
      (:source-revn conflicts-st))))

(defn update-branch-from-main
  "Bring the source's changes into the branch (reverse of merge). `branch`
  is the branch row. With conflicts and no `resolutions`, hands them over
  to be resolved (`open-conflict-resolutions`); with resolutions, applies
  them.

  Options (`opts`): `:resolutions` `{entity-id (:source|:branch)}` as
  below, and `:excluded`, the per-change selection (change keys,
  `app.common.files.branch-merge/change-key`) naming the source's changes
  the user deselected, which the branch keeps its own value for.

  When the current diff in state is this branch's `:source->branch` diff,
  its source revn is sent as `expected-source-revn` so the server refuses
  to apply resolutions computed against a stale diff (`:file-modified`);
  in that case the diff is re-fetched and the user is asked to review it
  again (same as `merge-branch`). Resolutions sent before that diff has
  loaded carry the revn of the conflict answer instead
  (`conflict-source-revn`)."
  ([branch] (update-branch-from-main branch nil))
  ([branch {:keys [resolutions excluded]}]
   (ptk/reify ::update-branch-from-main
     ptk/WatchEvent
     (watch [_ state _]
       (let [branch-id   (:id branch)
             diff-st     (:workspace-branch-diff state)
             source-revn (or (when (and (= branch-id (:branch-id diff-st))
                                        (= :source->branch (:direction diff-st)))
                               (get-in diff-st [:diff :meta :source-revn]))
                             (when (seq resolutions)
                               (conflict-source-revn state branch-id)))]
         (rx/concat
          (rx/of (ev/event {::ev/name "update-branch-from-main"}))
          (->> (rp/cmd! :update-file-branch-from-source
                        (cond-> {:branch-id branch-id}
                          (seq resolutions)   (assoc :resolutions resolutions)
                          (seq excluded)      (assoc :excluded excluded)
                          (some? source-revn) (assoc :expected-source-revn source-revn)))
               (rx/mapcat
                (fn [{:keys [status conflicts] :as answer}]
                  (case status
                    ;; the open branch file just changed server-side: hard-reload
                    ;; it so the pulled changes are shown
                    :updated     (rx/of (ntf/success (tr "workspace.branches.update.success"))
                                        (reload-file-window))
                    ;; the returned conflicts are authoritative: they are the very
                    ;; ones the command computes `resolved?` against, and the diff
                    ;; fetched to compare is computed in another id space, which
                    ;; can disagree with them
                    :conflicts   (rx/of (open-conflict-resolutions {:branch branch
                                                                    :mode :update
                                                                    :conflicts conflicts
                                                                    :source-revn (:source-revn answer)
                                                                    :excluded excluded}))
                    :unsupported (rx/of (ntf/warn (tr "workspace.branches.update.unsupported")))
                    (rx/of (ntf/error (tr "workspace.branches.update.error"))))))
               (rx/catch
                (fn [cause]
                  (if (= :file-modified (:code (ex-data cause)))
                    ;; main moved under our feet: reload the diff so the
                    ;; user resolves against the current state (no
                    ;; update-specific "main moved" key exists, so this
                    ;; reuses merge's)
                    (rx/of (ntf/warn (tr "workspace.branches.merge.main-moved"))
                           (fetch-branch-diff branch-id :source->branch))
                    (rx/of (refusal-notification cause (tr "workspace.branches.update.error")))))))))))))

(defn set-conflict-resolution
  "Choose `:source` or `:branch` for a whole conflicting entity (by id).
  This is the per-element \"Use this\" action; it overwrites any per-attr
  map."
  [id choice]
  (ptk/reify ::set-conflict-resolution
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-branch-diff :resolutions id] choice))))

(defn set-conflict-resolutions
  "Store several conflict resolutions at once, `{entity-id resolution}`,
  over the ones already chosen. A resolution is `:source`, `:branch` or a
  per-attr `{attr side}` map."
  [resolutions]
  (ptk/reify ::set-conflict-resolutions
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-branch-diff :resolutions] merge resolutions))))

(defn clear-conflict-resolution
  "Forget the choice made for conflict `id`, so it asks again."
  [id]
  (ptk/reify ::clear-conflict-resolution
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-branch-diff :resolutions] dissoc id))))

(defn merge-branch
  "Merge `branch` (a branch row/context map with at least `:id`) into main.
  On success the server records the merge and deletes the branch and its
  file, and open clients reload main via the `:file-merged` msgbus event.

  Options:
   - `:resolutions` — optional `{entity-id (:source|:branch)}` map used to
     resolve conflicts before integrating.
   - `:comment` — what the user says about the merge, kept with its
     record and shown in the reviews dialog.
   - `:notify-assignees` — tell the assignees of the open tasks of the
     branch, by email, that the merge moved their tasks into the target.
   - `:excluded` — the per-change selection (change keys,
     `app.common.files.branch-merge/change-key`) naming the changes the
     user deselected, which the target keeps its own value for.

  When the current diff in state belongs to this branch, its source revn
  is sent as `expected-source-revn` so the server refuses to apply
  resolutions computed against a stale diff (`:file-modified`); in that
  case the diff is re-fetched and the user is asked to review it again.
  Resolutions sent before that diff has loaded carry the revn of the
  conflict answer instead (`conflict-source-revn`). Unresolved conflicts
  are handed over to be resolved (`open-conflict-resolutions`)."
  ([branch] (merge-branch branch nil))
  ([branch {:keys [resolutions comment excluded notify-assignees]}]
   (assert (uuid? (:id branch)) "expected a branch row with a valid `:id`")
   (ptk/reify ::merge-branch
     ptk/WatchEvent
     (watch [_ state _]
       (let [branch-id   (:id branch)
             diff-st     (:workspace-branch-diff state)
             source-revn (or (when (= branch-id (:branch-id diff-st))
                               (get-in diff-st [:diff :meta :source-revn]))
                             (when (seq resolutions)
                               (conflict-source-revn state branch-id)))]
         (rx/concat
          (rx/of (ev/event {::ev/name "merge-branch"}))
          (->> (rp/cmd! :merge-file-branch
                        (cond-> {:branch-id branch-id}
                          (seq resolutions)  (assoc :resolutions resolutions)
                          (seq excluded)     (assoc :excluded excluded)
                          notify-assignees   (assoc :notify-assignees true)
                          (not (str/blank? comment)) (assoc :comment (str/trim comment))
                          (some? source-revn) (assoc :expected-source-revn source-revn)))
               (rx/mapcat
                (fn [{:keys [status source-file-id conflicts] :as answer}]
                  (case status
                    ;; the merged result lives in main: take the user there
                    ;; to see it (navigate if elsewhere, hard-reload if
                    ;; already on main). Other clients reload via the
                    ;; `:file-merged` event; the branch deletion already
                    ;; happened server-side.
                    :merged      (rx/of (ntf/success (tr "workspace.branches.merge.success"))
                                        (show-merge-result source-file-id))
                    :conflicts   (rx/of (open-conflict-resolutions {:branch branch
                                                                    :mode :merge
                                                                    :conflicts conflicts
                                                                    :source-revn (:source-revn answer)
                                                                    :excluded excluded
                                                                    :comment comment}))
                    :unsupported (rx/of (ntf/warn (tr "workspace.branches.merge.unsupported")))
                    (rx/of (ntf/error (tr "workspace.branches.merge.error"))))))
               (rx/catch
                (fn [cause]
                  (if (= :file-modified (:code (ex-data cause)))
                    ;; main moved under our feet: reload the diff so the
                    ;; user resolves against the current state
                    (rx/of (ntf/warn (tr "workspace.branches.merge.main-moved"))
                           (fetch-branch-diff branch-id))
                    (rx/of (refusal-notification cause (tr "workspace.branches.merge.error")))))))))))))

