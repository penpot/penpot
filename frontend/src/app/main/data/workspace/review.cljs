;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.review
  "Data layer of the review mode: what the reviewer does inside a design
  review. The review is read-only (the branch is edited outside it), so
  the toolbar offers only the review tools: move around, comment on a
  spot or a layer, comment on an area, create a task, and show the
  changes of the branch on the canvas.

  `:workspace-review-local` keeps the tool in use, the tab and filters of
  the review panel and the change shown on the canvas;
  `:workspace-review-activity` the activity of the review, one row per
  save of the branch since the review opened."
  (:require
   [app.common.files.review-activity :as ra]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dcmt]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.layout :as dwl]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def tools
  #{:move :comment :comment-area :task})

(defn reviewing?
  [state]
  (some? (:workspace-pr-preview state)))

(defn review-id
  "The id of the design review open now."
  [state]
  (get-in state [:workspace-pr-preview :pr-id]))

;; --- Tools

(defn- forget-task-tool
  []
  (ptk/reify ::forget-task-tool
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local dissoc :tool))))

(defn set-tool
  "Use one of the review `tools`. The commenting tools and the task tool
  take the clicks of the canvas, so they all ride on the comments drawing
  tool; the review tool says what a click does."
  [tool]
  (assert (contains? tools tool) "expected a review tool")
  (ptk/reify ::set-tool
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-review-local assoc :tool tool)
          (update :comments-local dissoc :draft)))

    ptk/WatchEvent
    (watch [_ state stream]
      (if (= tool :move)
        (rx/of (dwdc/clear-drawing))
        ;; what the tool makes must be seen: comments and tasks show
        (rx/merge
         (rx/of (dwd/select-for-drawing :comments)
                (dwl/toggle-layout-flag :display-comments :force? true))
         ;; outside a review the task tool is for one task: leaving it
         ;; gives the clicks back to the comments
         (when (and (= tool :task) (not (reviewing? state)))
           (->> stream
                (rx/filter #(or (dwc/interrupt? %)
                                (ptk/type? ::dwdc/clear-drawing %)))
                (rx/take 1)
                (rx/map (fn [_] (forget-task-tool))))))))))

(defn get-tool
  [state]
  (if (= :comments (get-in state [:workspace-drawing :tool]))
    (get-in state [:workspace-review-local :tool] :comment)
    :move))

;; --- Panel

(defn set-panel-tab
  [tab]
  (assert (contains? #{:inspect :comments :tasks} tab) "expected a panel tab")
  (ptk/reify ::set-panel-tab
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local assoc :panel-tab tab))))

(defn set-task-filter
  [filter]
  (assert (contains? #{:all :open :mine :to-verify} filter) "expected a task filter")
  (ptk/reify ::set-task-filter
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local assoc :task-filter filter))))

(def task-statuses
  "The states a task can be in, in the order the filters show them."
  ["open" "done" "reopened" "verified" "wont-do"])

(defn set-task-status-filter
  "Show the tasks of the details of a review in one state (`:all` for
  every one)."
  [status]
  (assert (or (= :all status) (contains? (set task-statuses) status))
          "expected a task status or :all")
  (ptk/reify ::set-task-status-filter
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local assoc :task-status-filter status))))

(defn toggle-hide-closed-tasks
  []
  (ptk/reify ::toggle-hide-closed-tasks
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-review-local :hide-closed-tasks] not))))

;; --- Creating from the canvas

(defn- draft-params
  [state position]
  {:position position
   :page-id (:current-page-id state)
   :file-id (:current-file-id state)})

(defn handle-canvas-click
  "A click on the canvas with a review tool: the comment tool starts a
  thread on the layer under the pointer (on the spot of the board when
  there is no layer), the task tool starts a task on that layer. The
  area tool draws with the pointer instead (`start-area-comment`).
  Outside a review the task tool is also in use for one click."
  [position]
  (ptk/reify ::handle-canvas-click
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            shape   (dwtk/shape-at-position objects position)
            pr-id   (review-id state)]
        (case (get-tool state)
          :comment
          (rx/of (dcmt/create-draft
                  (cond-> (assoc (draft-params state position) :pull-request-id pr-id)
                    (and (some? shape) (not= :frame (:type shape)))
                    (assoc :anchor {:type :layer :shape-id (:id shape)})

                    (or (nil? shape) (= :frame (:type shape)))
                    (assoc :anchor {:type :canvas}))))

          ;; on the empty canvas the task points at the canvas itself
          :task
          (rx/concat
           (rx/of (dwtk/start-new-task
                   {:page-id page-id
                    :frame-id (cond
                                (nil? shape)              uuid/zero
                                (= :frame (:type shape))  (:id shape)
                                :else                     (:frame-id shape))
                    :shape-id (:id shape)
                    :position position
                    :pull-request-id pr-id}))
           (if (nil? pr-id)
             (rx/of (forget-task-tool))
             (rx/empty)))

          (rx/empty))))))

(defn create-area-draft
  "The pointer drew `area` (canvas coordinates) with the area tool: start
  a thread on it. The thread sits at the top right corner of the area,
  just inside it so it belongs to the board the area is on."
  [{:keys [x y width] :as area}]
  (ptk/reify ::create-area-draft
    ptk/WatchEvent
    (watch [_ state _]
      (let [position {:x (- (+ x width) 1) :y (+ y 1)}]
        (rx/of (dcmt/create-draft
                (-> (draft-params state (gpt/point position))
                    (assoc :pull-request-id (review-id state))
                    (assoc :anchor {:type :area :area area}))))))))

;; --- Activity

(defn- activity-fetched
  [pr-id rows]
  (ptk/reify ::activity-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-review-activity
             {:pr-id pr-id
              :status :loaded
              :rows rows
              :entries (ra/net-entries rows)
              :sessions (ra/sessions rows)}))))

(defn fetch-activity
  "Load what the saves of the branch did since the review `pr-id` opened."
  [pr-id]
  (assert (uuid? pr-id) "expected a valid uuid for `pr-id`")
  (ptk/reify ::fetch-activity
    ptk/UpdateEvent
    (update [_ state]
      (if (= pr-id (get-in state [:workspace-review-activity :pr-id]))
        state
        (assoc state :workspace-review-activity {:pr-id pr-id :status :loading})))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-pull-request-activity {:id pr-id})
           (rx/map (partial activity-fetched pr-id))
           (rx/catch (fn [_]
                       (rx/of (fn [state]
                                (assoc state :workspace-review-activity
                                       {:pr-id pr-id :status :error})))))))))

;; --- Details

(defn open-details
  "Open the details of the review on `tab` (\"overview\", \"activity\" or
  \"tasks\")."
  ([] (open-details "overview"))
  ([tab]
   (ptk/reify ::open-details
     ptk/WatchEvent
     (watch [_ state _]
       (if-let [pr (get-in state [:workspace-pr-preview :info])]
         (rx/of (modal/show :review-details {:pr pr :tab tab}))
         (rx/empty))))))

;; --- Changes on the canvas

(defn- change-entries
  [state]
  (let [filter (get-in state [:workspace-review-local :change-filter] :all)]
    (cond->> (get-in state [:workspace-review-activity :entries])
      (not= :all filter) (filterv #(= filter (:kind %))))))

(defn set-change-filter
  [filter]
  (assert (contains? #{:all :added :modified :deleted} filter) "expected a change filter")
  (ptk/reify ::set-change-filter
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local assoc :change-filter filter))))

(defn- center-on-entry
  [{:keys [page-id id] :as entry}]
  (ptk/reify ::center-on-entry
    ptk/WatchEvent
    (watch [_ state _]
      (rx/concat
       (if (not= page-id (:current-page-id state))
         (rx/of (rt/nav :workspace (assoc (rt/get-params state) :page-id page-id)
                        {::rt/replace true}))
         (rx/empty))
       (->> (rx/of (dwtk/center-to-task {:page-id page-id
                                         :shape-id id
                                         :frame-id (:frame-id entry)}))
            (rx/observe-on :async))))))

(defn show-change
  "Show the changes of the branch on the canvas, with the one at `index`
  (of the filtered activity) in front, and take the viewport to it."
  [index]
  (ptk/reify ::show-change
    ptk/UpdateEvent
    (update [_ state]
      (let [total (count (change-entries state))
            index (if (pos? total) (mod index total) 0)]
        (update state :workspace-review-local assoc :show-changes {:index index})))

    ptk/WatchEvent
    (watch [_ state _]
      (let [index (get-in state [:workspace-review-local :show-changes :index])]
        (if-let [entry (get (change-entries state) index)]
          (rx/of (modal/hide)
                 (center-on-entry entry))
          (rx/of (modal/hide)))))))

(defn show-change-of
  "Show on the canvas the change of the layer `id`."
  [id]
  (ptk/reify ::show-change-of
    ptk/WatchEvent
    (watch [_ state _]
      (let [entries (change-entries state)
            index   (or (first (keep-indexed (fn [i e] (when (= id (:id e)) i)) entries)) 0)]
        (rx/of (show-change index))))))

(declare hide-changes)

(defn toggle-changes
  "The show changes tool: show the changes on the canvas, or stop."
  []
  (ptk/reify ::toggle-changes
    ptk/WatchEvent
    (watch [_ state _]
      (if (some? (get-in state [:workspace-review-local :show-changes]))
        (rx/of (hide-changes))
        (let [pr-id (review-id state)]
          (rx/concat
           (if (some? pr-id) (rx/of (fetch-activity pr-id)) (rx/empty))
           (rx/of (fn [state]
                    (update state :workspace-review-local assoc :show-changes {:index 0})))))))))

(defn hide-changes
  []
  (ptk/reify ::hide-changes
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-review-local dissoc :show-changes))))

(defn back-to-details
  "Leave the changes on the canvas and go back to the activity."
  []
  (ptk/reify ::back-to-details
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (hide-changes)
             (open-details "activity")))))

(defn reset-review-local
  []
  (ptk/reify ::reset-review-local
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-review-local :workspace-review-activity))))
