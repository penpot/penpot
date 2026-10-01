;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns branch-merge-cases
  "Ready-made branch-merge policy scenarios.

  `create!` builds one file per tree-shape case in a new project of the
  profile's default team, with main and a branch already diverged:
  open the branch in the UI, press Compare to see the case, press Merge
  to trigger it. Every file name says its case.

  The cases:

    (a) the branch reorders one parent's children (Compare shows the
        child-order change; a `:merge` applies it as a `:mov-objects`
        change, `:refuse` refuses naming the parent, `:ignore` drops
        it silently)
    (b) main and the branch reorder the same parent differently
        (`:merge` makes it a conflict on the parent)
    (c) main deletes the frame P and the branch adds a shape inside P
    (d) the branch deletes the frame F and main recolours F's child C
        and adds N inside F
    (e) the branch adds three siblings in the order A, B, C (a merge
        must keep that order)

  HOW TO RUN IT

  The builder runs in the backend's own REPL inside the devenv, so the
  files it creates are ordinary files in the database the UI serves:

  1. `.devenv/devenv repl` on the host bridges the container's backend
     nREPL (port 6064, the default `scripts/nrepl-eval.mjs` connects
     to). The bridge and its constraints are described in
     `.devenv/USAGE.md` (\"The entry point and the pieces behind it\").
  2. From the repo root, evaluate (the nREPL session persists between
     invocations, so the second call sees the loaded namespace):

         node scripts/nrepl-eval.mjs '(require (quote branch-merge-cases))'
         node scripts/nrepl-eval.mjs \\
           '(branch-merge-cases/create! {:email \"you@example.com\" :project-name \"20261001-branch-merge-policies\"})'

  The email is the devenv test account's, readable from
  `~/.config/penpot-devenv/test-account.json` (`.email`; never print
  its password). The call returns `{:project ... :files [...]}` and
  prints the created files with their ids.

  If the backend process predates the branch-merge policy code, load it
  first (the compare path then binds the policies file around every
  comparison):

      node scripts/nrepl-eval.mjs \\
        '(require (quote app.rpc.commands.files-branch-policies) (quote app.rpc.commands.files-branch))'

  HOW TO FLIP A POLICY

  Edit `backend/resources/app/branch-merge-policies.edn` (or whatever
  file `:branch-merge-policies-file` /
  `PENPOT_BRANCH_MERGE_POLICIES_FILE` names), save it, and press
  Compare again: the backend re-reads the file whenever its mtime
  changes — no restart. The alternatives:

    :same-parent-reorder              :merge | :refuse | :ignore
    :addition-under-deleted-parent    :conflict | :refuse | :reparent-to-ancestor | :page-root
    :container-delete-over-main-edits :conflict | :expand | :cascade

  An invalid file logs a warning and the engine runs the built-in
  defaults until the file is fixed."
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.system :as sys]
   [clojure.pprint :refer [print-table]]
   [clojure.string :as str]))

;; --- Plumbing: the same RPC command functions the API serves, run on
;; the live system map (`app.system/sys/system`).

(defn- command!
  "Run the registered RPC command `type` with `params` the way the RPC
  middleware does: the params carry the profile id and the request
  time. Audit is skipped — its middleware wants the HTTP request of a
  real call, and the RPC tests drive commands the same way."
  [type params]
  (let [[_ method-fn] (get-in sys/system [:app.rpc/methods type])]
    (when-not method-fn
      (throw (ex-info (str "rpc method not found: " (name type)) {:type type})))
    (with-redefs [audit/prepare-rpc-event (fn [& _] nil)
                  audit/submit (constantly nil)]
      (method-fn (assoc params :app.rpc/request-at (ct/now))))))

(defn- get-profile
  [email]
  (or (db/get* sys/system :profile {:email (str/lower-case email)})
      (throw (ex-info "no profile for this email" {:email email}))))

(defn- file-row
  [file-id]
  (db/get* sys/system :file {:id file-id}))

(defn- page-id-of
  [profile-id file-id]
  (-> (command! :get-file {:app.rpc/profile-id profile-id :id file-id})
      :data :pages first))

(defn- update!
  "Apply `changes` to `file-id` through the ordinary update-file RPC —
  which is what the editor sends and what routes a branch file's saves
  into its op log."
  [profile-id file-id changes]
  (let [{:keys [revn vern]} (file-row file-id)]
    (command! :update-file
              {:app.rpc/profile-id profile-id
               :id file-id
               :session-id (uuid/next)
               :revn revn
               :vern vern
               :features cfeat/supported-features
               :changes (vec changes)})))

;; --- The change vocabulary the editor emits

(defn- shape
  [id name type parent-id frame-id]
  (cts/setup-shape {:id id :name name :type type
                    :parent-id parent-id :frame-id frame-id}))

(defn- add-obj
  [page-id {:keys [id parent-id frame-id] :as obj}]
  {:type :add-obj
   :page-id page-id
   :id id
   :parent-id parent-id
   :frame-id frame-id
   :obj obj})

(defn- del-obj
  [page-id id]
  {:type :del-obj :page-id page-id :id id})

(defn- mod-obj
  [page-id id operations]
  {:type :mod-obj :page-id page-id :id id :operations (vec operations)})

(defn- reorder-children
  [page-id parent-id shapes]
  {:type :reorder-children
   :page-id page-id
   :parent-id parent-id
   :shapes (vec shapes)})

;; --- The cases: each returns {:file-name :branch-name :base :branch
;; :main}, where `base` is main's content before the fork and
;; `:branch`/`:main` are the diverging edits after it — each a function
;; of the page id returning a change vector.

(defn- case-reorder-branch-only
  "The branch reorders the children of the parent frame P; main is
  untouched."
  []
  (let [parent (uuid/next)
        c1     (uuid/next)
        c2     (uuid/next)
        c3     (uuid/next)]
    {:file-name   "(a) same-parent reorder on the branch only"
     :branch-name "reorder"
     :base        (fn [page-id]
                    [(add-obj page-id (shape parent "parent" :frame uuid/zero uuid/zero))
                     (add-obj page-id (shape c1 "child-1" :rect parent parent))
                     (add-obj page-id (shape c2 "child-2" :rect parent parent))
                     (add-obj page-id (shape c3 "child-3" :rect parent parent))])
     :branch      (fn [page-id]
                    [(reorder-children page-id parent [c2 c3 c1])])
     :main        nil}))

(defn- case-reorder-both-sides
  "Main and the branch reorder the same parent frame's children into
  two different orders."
  []
  (let [parent (uuid/next)
        c1     (uuid/next)
        c2     (uuid/next)
        c3     (uuid/next)]
    {:file-name   "(b) both sides reorder one parent differently"
     :branch-name "reorder-diverging"
     :base        (fn [page-id]
                    [(add-obj page-id (shape parent "parent" :frame uuid/zero uuid/zero))
                     (add-obj page-id (shape c1 "child-1" :rect parent parent))
                     (add-obj page-id (shape c2 "child-2" :rect parent parent))
                     (add-obj page-id (shape c3 "child-3" :rect parent parent))])
     :branch      (fn [page-id]
                    [(reorder-children page-id parent [c3 c1 c2])])
     :main        (fn [page-id]
                    [(reorder-children page-id parent [c2 c3 c1])])}))

(defn- case-add-under-deleted-parent
  "Main deletes the frame P while the branch adds a shape inside it."
  []
  (let [parent (uuid/next)
        child  (uuid/next)
        added  (uuid/next)]
    {:file-name   "(c) main deletes frame P, branch adds a shape inside P"
     :branch-name "add-under-deleted"
     :base        (fn [page-id]
                    [(add-obj page-id (shape parent "P" :frame uuid/zero uuid/zero))
                     (add-obj page-id (shape child "child" :rect parent parent))])
     :branch      (fn [page-id]
                    [(add-obj page-id (shape added "added-inside-P" :rect parent parent))])
     :main        (fn [page-id]
                    [(del-obj page-id parent)])}))

(defn- case-delete-container-over-main-edits
  "The branch deletes the frame F while main recolours F's child C and
  adds N inside F."
  []
  (let [frame (uuid/next)
        child (uuid/next)
        added (uuid/next)]
    {:file-name   "(d) branch deletes frame F, main recolours child C and adds N inside F"
     :branch-name "delete-container"
     :base        (fn [page-id]
                    [(add-obj page-id (shape frame "F" :frame uuid/zero uuid/zero))
                     (add-obj page-id (shape child "C" :rect frame frame))])
     :branch      (fn [page-id]
                    [(del-obj page-id frame)])
     :main        (fn [page-id]
                    [(mod-obj page-id child
                              [{:type :set
                                :attr :fills
                                :val [{:fill-color "#ff0000" :fill-opacity 1}]}])
                     (add-obj page-id (shape added "N" :rect frame frame))])}))

(defn- case-addition-order
  "The branch adds three siblings under the frame S, in the order A, B,
  C — a merge must keep that order."
  []
  (let [frame (uuid/next)
        a     (uuid/next)
        b     (uuid/next)
        c     (uuid/next)]
    {:file-name   "(e) branch adds three siblings in the order A, B, C"
     :branch-name "add-three"
     :base        (fn [page-id]
                    [(add-obj page-id (shape frame "S" :frame uuid/zero uuid/zero))])
     :branch      (fn [page-id]
                    [(add-obj page-id (shape a "sibling-A" :rect frame frame))
                     (add-obj page-id (shape b "sibling-B" :rect frame frame))
                     (add-obj page-id (shape c "sibling-C" :rect frame frame))])
     :main        nil}))

(defn- create-case!
  "Create the case's file, write its base content on main, fork the
  branch, then apply the two diverging edits. Returns the file ids."
  [profile-id project-id {:keys [file-name branch-name base branch main]}]
  (let [file-id   (:id (command! :create-file
                                 {:app.rpc/profile-id profile-id
                                  :project-id project-id
                                  :name file-name}))
        page-id   (page-id-of profile-id file-id)
        _         (when base
                    (update! profile-id file-id (base page-id)))
        create    (command! :create-file-branch
                            {:app.rpc/profile-id profile-id
                             :file-id file-id
                             :name branch-name})
        branch-id (:id create)]
    (when branch
      (update! profile-id (:branch-file-id create) (branch page-id)))
    (when main
      (update! profile-id file-id (main page-id)))
    {:id file-id
     :name file-name
     :branch-id branch-id
     :branch-file-id (:branch-file-id create)}))

(defn create!
  "Create the five policy cases as files in a NEW project of the
  profile's default team. Returns `{:project {:id :name} :files [...]}`
  and prints the created files with their ids and names."
  [{:keys [email project-name]}]
  (when-not (and (string? email) (string? project-name))
    (throw (ex-info "expected {:email \"..\" :project-name \"..\"}"
                    {:email email :project-name project-name})))
  (let [profile (get-profile email)
        project (command! :create-project
                          {:app.rpc/profile-id (:id profile)
                           :team-id (:default-team-id profile)
                           :name project-name})
        files   (mapv (fn [build] (create-case! (:id profile) (:id project) (build)))
                      [case-reorder-branch-only
                       case-reorder-both-sides
                       case-add-under-deleted-parent
                       case-delete-container-over-main-edits
                       case-addition-order])]
    (println "project:" (:name project) (str (:id project)))
    (print-table [:id :name :branch-id :branch-file-id] files)
    {:project {:id (:id project) :name (:name project)}
     :files files}))
