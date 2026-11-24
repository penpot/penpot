;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.branch-reviews
  "Data layer for branch reviews. Reviewing opens the live branch file as
  a normal, editable workspace with a review context attached
  (`:workspace-pr-preview`, which drives the review banner and header),
  so a reviewer's edits change the branch. Reviewers always see the live
  branch, in the canvas and in the interactions viewer: a verdict given
  before the branch changed again is stale. Mirrors the patterns of
  `app.main.data.workspace.branches`."
  (:require
   [app.config :as cf]
   [app.main.data.comments :as dcmt]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.layout :as dwl]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn enabled?
  []
  (contains? cf/flags :branching))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BRANCH REVIEW LIST (sidebar section)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce default-state
  {:status :loading
   :data nil})

(defn- update-branch-reviews-state
  [pr-state]
  (ptk/reify ::update-branch-reviews-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-branch-reviews merge pr-state))))

(defn fetch-branch-reviews
  "Load the branch reviews of the current file into
  `:workspace-branch-reviews`. The backend resolves a branch file id to
  its source, so the list is complete whether the user is on main or on
  a branch. Closed ones are included; the UI decides what to show."
  []
  (ptk/reify ::fetch-branch-reviews
    ptk/WatchEvent
    (watch [_ state _]
      (when (enabled?)
        (when-let [file-id (:current-file-id state)]
          (->> (rp/cmd! :get-file-branch-reviews {:file-id file-id :include-closed true})
               (rx/map #(update-branch-reviews-state {:status :loaded :data %}))
               (rx/catch (fn [_]
                           (rx/of (update-branch-reviews-state {:status :loaded :data []}))))))))))

(defn init-branch-reviews-state
  []
  (ptk/reify ::init-branch-reviews-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-branch-reviews default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-branch-reviews)))))

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
  "Load into `:dashboard-pending-reviews` the open branch reviews of
  `team-id` where the current profile is a reviewer without a current
  verdict. Feeds the dashboard notifications dropdown."
  [team-id]
  (ptk/reify ::fetch-pending-reviews
    ptk/WatchEvent
    (watch [_ _ _]
      (when (and (enabled?) (some? team-id))
        (->> (rp/cmd! :get-profile-review-requests {:team-id team-id})
             (rx/map set-pending-reviews)
             (rx/catch (fn [_] (rx/of (set-pending-reviews [])))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LIFECYCLE (create / edit / close / reopen)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare refresh-branch-review-preview-info)

(defn create-branch-review
  "Create a branch review from `branch-id` towards its source file. When
  the branch is the currently open file, pending local changes are
  force-persisted first so the review starts from the branch the author
  sees. Errors surface as a toast; the caller closes the dialog."
  [branch-id {:keys [title description reviewers]}]
  (assert (uuid? branch-id) "expected valid uuid for `branch-id`")
  (assert (string? title) "expected string for `title`")
  (ptk/reify ::create-branch-review
    ptk/WatchEvent
    (watch [_ state _]
      (let [;; the branch context is the open file's, and it is loaded
            ;; with the file; the listing loads only with the switcher
            ctx      (:workspace-branch-context state)
            from-ws? (and (= (:file-id ctx) (:current-file-id state))
                          (= branch-id (get-in ctx [:info :id])))
            wait     (if from-ws?
                       (dwp/wait-persisted)
                       (rx/of :ready))]
        (rx/concat
         (if from-ws?
           (rx/of ::dwp/force-persist (ev/event {::ev/name "create-branch-review"}))
           (rx/of (ev/event {::ev/name "create-branch-review"})))
         (->> wait
              (rx/mapcat #(rp/cmd! :create-branch-review
                                   {:branch-id branch-id
                                    :title title
                                    :description description
                                    :reviewers (vec (or reviewers []))}))
              (rx/mapcat
               (fn [_]
                 (rx/of (ntf/success (tr "workspace.branch-review.create.success" title))
                        (fetch-branch-reviews))))
              (rx/catch
               (fn [cause]
                 (if (= :branch-review-already-exists (:code (ex-data cause)))
                   (rx/of (ntf/warn (tr "workspace.branch-review.create.already-exists")))
                   (rx/of (ntf/error (tr "workspace.branch-review.create.error"))))))))))))

(defn update-branch-review
  "Edit a branch review's title, description or reviewer list (author or
  team admins only, enforced server-side)."
  [id {:keys [title description reviewers]}]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::update-branch-review
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-branch-review-info
                    (cond-> {:id id}
                      (some? title)       (assoc :title title)
                      (some? description) (assoc :description description)
                      (some? reviewers)   (assoc :reviewers (vec reviewers))))
           (rx/mapcat (fn [_] (rx/of (fetch-branch-reviews)
                                     (refresh-branch-review-preview-info id))))
           (rx/catch (fn [_] (rx/of (ntf/error (tr "workspace.branch-review.lifecycle.error")))))))))

(defn submit-review
  "Submit a verdict on a branch review, or retract it with `nil`. Only
  assigned reviewers may submit (enforced server-side)."
  [id verdict comment]
  (assert (uuid? id) "expected valid uuid for `id`")
  (assert (or (nil? verdict)
              (contains? #{"approved" "changes-requested"} verdict))
          "expected a valid verdict")
  (ptk/reify ::submit-review
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (ev/event {::ev/name "submit-branch-review-verdict"}))
       (->> (rp/cmd! :submit-branch-review-verdict
                     (cond-> {:id id}
                       (some? verdict) (assoc :verdict verdict)
                       (some? comment) (assoc :comment comment)))
            (rx/mapcat
             (fn [_]
               (rx/of (ntf/success (tr "workspace.branch-review.review.submitted"))
                      (fetch-branch-reviews)
                      (refresh-branch-review-preview-info id))))
            (rx/catch
             (fn [cause]
               (if (= :not-a-reviewer (:code (ex-data cause)))
                 (rx/of (ntf/warn (tr "workspace.branch-review.review.not-a-reviewer")))
                 (rx/of (ntf/error (tr "workspace.branch-review.lifecycle.error")))))))))))

(declare exit-branch-review-preview)

(defn close-branch-review
  "Close (discard) a branch review. Its sandbox stops existing; if the user
  is currently inside it, take them back to the branch file."
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::close-branch-review
    ptk/WatchEvent
    (watch [_ state _]
      (let [inside? (= id (get-in state [:workspace-pr-preview :pr-id]))]
        (->> (rp/cmd! :close-branch-review {:id id})
             (rx/mapcat
              (fn [_]
                (rx/concat
                 (rx/of (ntf/success (tr "workspace.branch-review.lifecycle.closed"))
                        (fetch-branch-reviews))
                 (if inside?
                   (rx/of (exit-branch-review-preview))
                   (rx/empty)))))
             (rx/catch
              (fn [_]
                (rx/of (ntf/error (tr "workspace.branch-review.lifecycle.error"))))))))))

(defn reopen-branch-review
  [id]
  (assert (uuid? id) "expected valid uuid for `id`")
  (ptk/reify ::reopen-branch-review
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :reopen-branch-review {:id id})
           (rx/mapcat (fn [_] (rx/of (ntf/success (tr "workspace.branch-review.lifecycle.reopened"))
                                     (fetch-branch-reviews))))
           (rx/catch
            (fn [cause]
              (case (:code (ex-data cause))
                :branch-not-open
                (rx/of (ntf/warn (tr "workspace.branch-review.lifecycle.branch-gone")))

                :branch-review-already-exists
                (rx/of (ntf/warn (tr "workspace.branch-review.create.already-exists")))

                (rx/of (ntf/error (tr "workspace.branch-review.lifecycle.error"))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REVIEW CONTEXT (the live, editable branch file plus the review banner)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open-branch-review
  "Navigate into the review of `pr`: the branch file's workspace with the
  `pr-id` query param, which attaches the review context once the file is
  initialized (`initialize-branch-review-preview`). The canvas is the live
  branch, editable as always."
  [pr]
  (assert (uuid? (:id pr)) "expected a branch review row with a valid `:id`")
  (ptk/reify ::open-branch-review
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :team-id (:current-team-id state))
                       (assoc :file-id (:source-file-id pr))
                       (assoc :pr-id (:id pr))
                       (dissoc :page-id))]
        (rx/of (rt/nav :workspace params))))))

(defn- set-branch-review-preview
  [preview]
  (ptk/reify ::set-branch-review-preview
    ptk/UpdateEvent
    (update [_ state]
      (if (nil? preview)
        (dissoc state :workspace-pr-preview)
        (update state :workspace-pr-preview merge preview)))))

(defn refresh-branch-review-preview-info
  "Refetch the branch review row backing the active sandbox (verdicts and
  their staleness, counts). No-op when the sandbox is not active or shows
  another branch review. If the branch review finished meanwhile (merged
  or closed), the review context is dropped: the branch is a normal
  branch again."
  [id]
  (ptk/reify ::refresh-branch-review-preview-info
    ptk/WatchEvent
    (watch [_ state _]
      (when (= id (get-in state [:workspace-pr-preview :pr-id]))
        (->> (rp/cmd! :get-branch-review {:id id})
             (rx/mapcat
              (fn [info]
                (if (not= "open" (:status info))
                  (rx/of (exit-branch-review-preview))
                  (rx/of (set-branch-review-preview {:info info})))))
             (rx/catch (fn [_] (rx/empty))))))))

(defn exit-branch-review-preview
  "Leave the review context: drop the state entry and the `pr-id` query
  param. The file itself was never altered (it is the live branch file,
  opened normally), so no reload is needed — the branch banner simply
  takes over again."
  []
  (ptk/reify ::exit-branch-review-preview
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-pr-preview :workspace-review-local :workspace-review-activity
              :workspace-review-layers))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (dissoc :pr-id))]
        (rx/of (dwdc/clear-drawing)
               (dcmt/close-thread)
               (rt/nav :workspace params))))))

(defn- enter-branch-review-preview
  "Worker for `initialize-branch-review-preview`: load the branch review
  info and attach the review context to the open file. The file stays a
  NORMAL workspace file — fully navigable and editable, so reviewers can
  try and validate anything (edits go to the branch, as always). Cleans
  the url when the branch review does not belong to the open file or
  cannot be loaded."
  [pr-id]
  (ptk/reify ::enter-branch-review-preview
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-pr-preview {:pr-id pr-id
                                          :status :loading}))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-branch-review {:id pr-id})
             (rx/mapcat
              (fn [info]
                (cond
                  ;; a stale pr-id param carried over a navigation (e.g.
                  ;; landing on main after merging from the review)
                  (not= file-id (:source-file-id info))
                  (rx/of (exit-branch-review-preview))

                  ;; the branch review already finished (merged or
                  ;; closed): the branch is a normal branch again, so
                  ;; there is no review context to restore
                  (not= "open" (:status info))
                  (rx/of (exit-branch-review-preview))

                  :else
                  (rx/of (set-branch-review-preview {:status :loaded :info info})
                         ;; the review opens on the inspect of the selection:
                         ;; the move tool picks layers, and the comments and
                         ;; tasks show on the canvas
                         (dwdc/clear-drawing)
                         (dwl/toggle-layout-flag :display-comments :force? true)))))
             (rx/catch
              (fn [_]
                (rx/of (ntf/warn (tr "workspace.branch-review.preview.unavailable"))
                       (exit-branch-review-preview)))))))))

(defn initialize-branch-review-preview
  "Attach the review context for `pr-id` to the open file. Split from the
  worker event so a re-emission (page navigation re-runs the workspace
  effect) is a cheap no-op."
  [pr-id]
  (assert (uuid? pr-id) "expected valid uuid for `pr-id`")
  (ptk/reify ::initialize-branch-review-preview
    ptk/WatchEvent
    (watch [_ state _]
      (if (= pr-id (get-in state [:workspace-pr-preview :pr-id]))
        (rx/empty)
        (rx/of (enter-branch-review-preview pr-id))))))
