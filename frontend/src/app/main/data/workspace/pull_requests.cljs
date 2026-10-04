;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.pull-requests
  "Data layer for pull requests over file branches. A pull request points
  to a pinned review snapshot of its branch; the review sandbox is that
  snapshot loaded read-only into the workspace (same mechanics as the
  version-history preview in `app.main.data.workspace.versions`), so it
  is never an editable copy of anything. Mirrors the patterns of
  `app.main.data.workspace.branches`."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn enabled?
  []
  (contains? cf/flags :branching))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PULL REQUEST LIST (sidebar section)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce default-state
  {:status :loading
   :data nil})

(defn- update-pull-requests-state
  [pr-state]
  (ptk/reify ::update-pull-requests-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-pull-requests merge pr-state))))

(defn fetch-pull-requests
  "Load the pull requests of the current file into
  `:workspace-pull-requests`. The backend resolves a branch file id to
  its source, so the list is complete whether the user is on main or on
  a branch. Closed ones are included; the UI decides what to show."
  []
  (ptk/reify ::fetch-pull-requests
    ptk/WatchEvent
    (watch [_ state _]
      (when (enabled?)
        (when-let [file-id (:current-file-id state)]
          (->> (rp/cmd! :get-file-pull-requests {:file-id file-id :include-closed true})
               (rx/map #(update-pull-requests-state {:status :loaded :data %}))
               (rx/catch (fn [_]
                           (rx/of (update-pull-requests-state {:status :loaded :data []}))))))))))

(defn init-pull-requests-state
  []
  (ptk/reify ::init-pull-requests-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-pull-requests default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-pull-requests)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PENDING REVIEWS (dashboard notifications area)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-pending-reviews
  [rows]
  (ptk/reify ::set-pending-reviews
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-pending-reviews rows))))

(defn fetch-pending-reviews
  "Load into `:dashboard-pending-reviews` the open pull requests of
  `team-id` where the current profile is a reviewer without a current
  verdict. Feeds the dashboard notifications dropdown."
  [team-id]
  (ptk/reify ::fetch-pending-reviews
    ptk/WatchEvent
    (watch [_ _ _]
      (when (and (enabled?) (some? team-id))
        (->> (rp/cmd! :get-profile-pending-reviews {:team-id team-id})
             (rx/map set-pending-reviews)
             (rx/catch (fn [_] (rx/of (set-pending-reviews [])))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LIFECYCLE (create / edit / publish / close / reopen)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare refresh-pull-request-preview-info)

(defn create-pull-request
  "Create a pull request from `branch-id` towards its source file. When
  the branch is the currently open file, pending local changes are
  force-persisted first so the review snapshot captures them. Errors
  surface as a toast; the caller closes the dialog."
  [branch-id {:keys [title description reviewers]}]
  (assert (uuid? branch-id) "expected valid uuid for `branch-id`")
  (assert (string? title) "expected string for `title`")
  (ptk/reify ::create-pull-request
    ptk/WatchEvent
    (watch [_ state _]
      (let [branch   (->> (get-in state [:workspace-branches :data])
                          (filter #(= branch-id (:id %)))
                          (first))
            from-ws? (= (:branch-file-id branch) (:current-file-id state))
            wait     (if from-ws?
                       (dwp/wait-persisted)
                       (rx/of :ready))]
        (rx/concat
         (if from-ws?
           (rx/of ::dwp/force-persist (ev/event {::ev/name "create-pull-request"}))
           (rx/of (ev/event {::ev/name "create-pull-request"})))
         (->> wait
              (rx/mapcat #(rp/cmd! :create-pull-request
                                   {:branch-id branch-id
                                    :title title
                                    :description description
                                    :reviewers (vec (or reviewers []))}))
              (rx/mapcat
               (fn [_]
                 (rx/of (ntf/success (tr "workspace.pull-requests.create.success" title))
                        (fetch-pull-requests))))
              (rx/catch
               (fn [cause]
                 (if (= :pull-request-already-exists (:code (ex-data cause)))
                   (rx/of (ntf/warn (tr "workspace.pull-requests.create.already-exists")))
                   (rx/of (ntf/error (tr "workspace.pull-requests.create.error"))))))))))))

(defn update-pull-request
  "Edit a pull request's title, description or reviewer list (author or
  team admins only, enforced server-side)."
  [id {:keys [title description reviewers]}]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::update-pull-request
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-pull-request
                    (cond-> {:id id}
                      (some? title)       (assoc :title title)
                      (some? description) (assoc :description description)
                      (some? reviewers)   (assoc :reviewers (vec reviewers))))
           (rx/mapcat (fn [_] (rx/of (fetch-pull-requests)
                                     (refresh-pull-request-preview-info id))))
           (rx/catch (fn [_] (rx/of (ntf/error (tr "workspace.pull-requests.lifecycle.error")))))))))

(defn update-pull-request-snapshot
  "Publish the branch's current state to the reviewers (repositions the
  review snapshot). When the branch is the currently open file, pending
  local changes are force-persisted first."
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::update-pull-request-snapshot
    ptk/WatchEvent
    (watch [_ state _]
      (let [from-ws? (some? (:current-file-id state))
            wait     (if from-ws? (dwp/wait-persisted) (rx/of :ready))]
        (rx/concat
         (if from-ws?
           (rx/of ::dwp/force-persist (ev/event {::ev/name "update-pull-request-snapshot"}))
           (rx/of (ev/event {::ev/name "update-pull-request-snapshot"})))
         (->> wait
              (rx/mapcat #(rp/cmd! :update-pull-request-snapshot {:id id}))
              (rx/mapcat
               (fn [{:keys [updated]}]
                 (rx/of (if updated
                          (ntf/success (tr "workspace.pull-requests.publish.success"))
                          (ntf/info (tr "workspace.pull-requests.publish.up-to-date")))
                        (fetch-pull-requests))))
              (rx/catch
               (fn [_]
                 (rx/of (ntf/error (tr "workspace.pull-requests.lifecycle.error")))))))))))

(defn submit-review
  "Submit (or retract, with \"pending\") a verdict on a pull request. Only
  assigned reviewers may submit (enforced server-side)."
  [id verdict comment]
  (assert (uuid? id) "expected valid uuid for `id`")
  (assert (contains? #{"approved" "changes-requested" "pending"} verdict)
          "expected a valid verdict")
  (ptk/reify ::submit-review
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (ev/event {::ev/name "submit-pull-request-review"}))
       (->> (rp/cmd! :submit-pull-request-review
                     (cond-> {:id id :state verdict}
                       (some? comment) (assoc :comment comment)))
            (rx/mapcat
             (fn [_]
               (rx/of (ntf/success (tr "workspace.pull-requests.review.submitted"))
                      (fetch-pull-requests)
                      (refresh-pull-request-preview-info id))))
            (rx/catch
             (fn [cause]
               (if (= :not-a-reviewer (:code (ex-data cause)))
                 (rx/of (ntf/warn (tr "workspace.pull-requests.review.not-a-reviewer")))
                 (rx/of (ntf/error (tr "workspace.pull-requests.lifecycle.error")))))))))))

(declare exit-pull-request-preview)

(defn close-pull-request
  "Close (discard) a pull request. Its sandbox stops existing; if the user
  is currently inside it, take them back to the branch file."
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::close-pull-request
    ptk/WatchEvent
    (watch [_ state _]
      (let [inside? (= id (get-in state [:workspace-pr-preview :pr-id]))]
        (->> (rp/cmd! :close-pull-request {:id id})
             (rx/mapcat
              (fn [_]
                (rx/concat
                 (rx/of (ntf/success (tr "workspace.pull-requests.lifecycle.closed"))
                        (fetch-pull-requests))
                 (if inside?
                   (rx/of (exit-pull-request-preview))
                   (rx/empty)))))
             (rx/catch
              (fn [_]
                (rx/of (ntf/error (tr "workspace.pull-requests.lifecycle.error"))))))))))

(defn reopen-pull-request
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::reopen-pull-request
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :reopen-pull-request {:id id})
           (rx/mapcat (fn [_] (rx/of (ntf/success (tr "workspace.pull-requests.lifecycle.reopened"))
                                     (fetch-pull-requests))))
           (rx/catch
            (fn [cause]
              (case (:code (ex-data cause))
                :branch-not-open
                (rx/of (ntf/warn (tr "workspace.pull-requests.lifecycle.branch-gone")))

                :pull-request-already-exists
                (rx/of (ntf/warn (tr "workspace.pull-requests.create.already-exists")))

                (rx/of (ntf/error (tr "workspace.pull-requests.lifecycle.error"))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REVIEW SANDBOX (read-only preview of the pinned snapshot)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open-pull-request
  "Navigate into the review sandbox of `pr`: the branch file's workspace
  with the `pr-id` query param, which loads the pinned snapshot
  read-only once the file is initialized."
  [pr]
  (assert (uuid? (:id pr)) "expected a pull request row with a valid `:id`")
  (ptk/reify ::open-pull-request
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :team-id (:current-team-id state))
                       (assoc :file-id (:source-file-id pr))
                       (assoc :pr-id (:id pr))
                       (dissoc :page-id))]
        (rx/of (rt/nav :workspace params))))))

(defn open-pull-request-viewer
  "Open the review sandbox in the interactions viewer (new window): the
  viewer bundle serves the pull request's pinned snapshot, so reviewers
  can play the prototype exactly as it was published for review."
  [pr]
  (assert (uuid? (:id pr)) "expected a pull request row with a valid `:id`")
  (ptk/reify ::open-pull-request-viewer
    ptk/WatchEvent
    (watch [_ state _]
      (let [params {:file-id (:source-file-id pr)
                    :page-id (:current-page-id state)
                    :pr-id (:id pr)
                    :section :interactions}]
        (rx/of (rt/nav :viewer
                       (d/without-nils params)
                       {::rt/new-window true
                        ::rt/window-name (dm/str "viewer-" (:source-file-id pr))}))))))

(defn- set-pull-request-preview
  [preview]
  (ptk/reify ::set-pull-request-preview
    ptk/UpdateEvent
    (update [_ state]
      (if (nil? preview)
        (dissoc state :workspace-pr-preview)
        (update state :workspace-pr-preview merge preview)))))

(defn refresh-pull-request-preview-info
  "Refetch the pull request row backing the active sandbox (verdicts,
  counts, outdated flag). No-op when the sandbox is not active or shows
  another pull request. If the pull request finished meanwhile (merged
  or cancelled), the review context is dropped: the branch is a normal
  branch again."
  [id]
  (ptk/reify ::refresh-pull-request-preview-info
    ptk/WatchEvent
    (watch [_ state _]
      (when (= id (get-in state [:workspace-pr-preview :pr-id]))
        (->> (rp/cmd! :get-pull-request {:id id})
             (rx/mapcat
              (fn [info]
                (if (not= "open" (:status info))
                  (rx/of (exit-pull-request-preview))
                  (rx/of (set-pull-request-preview {:info info})))))
             (rx/catch (fn [_] (rx/empty))))))))

(defn exit-pull-request-preview
  "Leave the review context: drop the state entry and the `pr-id` query
  param. The file itself was never altered (it is the live branch file,
  opened normally), so no reload is needed — the branch banner simply
  takes over again."
  []
  (ptk/reify ::exit-pull-request-preview
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-pr-preview))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (dissoc :pr-id))]
        (rx/of (rt/nav :workspace params))))))

(defn- enter-pull-request-preview
  "Worker for `initialize-pull-request-preview`: load the pull request
  info and attach the review context to the open file. The file stays a
  NORMAL workspace file — fully navigable and editable, so reviewers can
  try and validate anything (edits go to the branch, as always); the
  pinned snapshot backs the interactions viewer and the outdated
  tracking, not the canvas. Cleans the url when the pull request does
  not belong to the open file or cannot be loaded."
  [pr-id]
  (ptk/reify ::enter-pull-request-preview
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-pr-preview {:pr-id pr-id
                                          :status :loading}))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-pull-request {:id pr-id})
             (rx/mapcat
              (fn [info]
                (cond
                  ;; a stale pr-id param carried over a navigation (e.g.
                  ;; landing on main after merging from the review)
                  (not= file-id (:source-file-id info))
                  (rx/of (exit-pull-request-preview))

                  ;; the pull request already finished (merged or
                  ;; cancelled): the branch is a normal branch again, so
                  ;; there is no review context to restore
                  (not= "open" (:status info))
                  (rx/of (exit-pull-request-preview))

                  :else
                  (rx/of (set-pull-request-preview {:status :loaded :info info})))))
             (rx/catch
              (fn [_]
                (rx/of (ntf/warn (tr "workspace.pull-requests.preview.unavailable"))
                       (exit-pull-request-preview)))))))))

(defn initialize-pull-request-preview
  "Attach the review context for `pr-id` to the open file. Split from the
  worker event so a re-emission (page navigation re-runs the workspace
  effect) is a cheap no-op."
  [pr-id]
  (assert (uuid? pr-id) "expected valid uuid for `pr-id`")
  (ptk/reify ::initialize-pull-request-preview
    ptk/WatchEvent
    (watch [_ state _]
      (if (= pr-id (get-in state [:workspace-pr-preview :pr-id]))
        (rx/empty)
        (rx/of (enter-pull-request-preview pr-id))))))
