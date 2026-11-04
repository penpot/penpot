;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.markers
  "The marks of the design reviews and the tasks on the canvas. They live
  in the comments layer, inside its box translated by the viewbox, so a
  canvas point `p` sits at `p * zoom`.

  A thread written in a design review shows as a numbered review mark
  (in the review colour, wherever the file is open), a thread attached
  to a task and a task show as a task mark, and a thread on an area
  draws its area while it is open."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.main.data.comments :as dcmt]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.ds.product.review-marker :refer [review-marker*]]
   [app.main.ui.workspace.review.common :as rvc]
   [app.main.ui.workspace.review.tasks :as rvt]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private panel-gap
  "Screen distance between a mark and the panel it opens."
  20)

(defn thread-position
  "Where the mark of `thread` goes: the top right corner of its layer
  while the layer is there, the spot it was written on otherwise."
  [objects {:keys [anchor-type shape-id position]}]
  (or (when (= :layer anchor-type)
        (dwtk/anchor-position objects {:shape-id shape-id}))
      position))

(defn review-thread?
  "Whether the review layer draws `thread` instead of the plain comment
  bubbles: a thread of a design review, or one attached to a task."
  [task-thread-ids thread]
  (or (some? (:pull-request-id thread))
      (contains? task-thread-ids (:id thread))))

(defn- screen-style
  [{:keys [x y]} zoom]
  #js {:left (dm/str (* x zoom) "px")
       :top (dm/str (* y zoom) "px")})

(def ^:private thread-width
  "The width of an open comment thread: a task made from a thread opens
  beside it."
  300)

(defn panel-style
  "The style that puts a panel beside the mark at canvas `position`,
  `offset` screen pixels further right."
  ([position zoom] (panel-style position zoom 0))
  ([position zoom offset]
   #js {:left (dm/str (+ (* (:x position) zoom) panel-gap offset) "px")
        :top (dm/str (- (* (:y position) zoom) panel-gap) "px")}))

(mf/defc area-box*
  "The area a thread points at."
  {::mf/private true}
  [{:keys [area zoom]}]
  (let [{:keys [x y width height]} area]
    [:div {:class (stl/css :area-box)
           :style #js {:left (dm/str (* x zoom) "px")
                       :top (dm/str (* y zoom) "px")
                       :width (dm/str (* width zoom) "px")
                       :height (dm/str (* height zoom) "px")}}]))

(mf/defc marker-slot*
  "A mark on the canvas, centered on its point. Hovering it shows `preview`
  unless it is open, like the bubbles of the comments."
  {::mf/private true}
  [{:keys [position zoom is-open preview children]}]
  (let [hover*   (mf/use-state false)
        hover?   (deref hover*)
        on-enter (mf/use-fn #(reset! hover* true))
        on-leave (mf/use-fn #(reset! hover* false))
        shown?   (and hover? (not is-open))]
    [:div {:class (stl/css-case :marker true
                                :is-hovered shown?)
           :style (screen-style position zoom)
           :on-pointer-enter on-enter
           :on-pointer-leave on-leave}
     children
     (when shown? preview)]))

(mf/defc thread-marker*
  {::mf/private true}
  [{:keys [thread position zoom is-open]}]
  (let [owner (mf/with-memo [thread]
                (dcmt/get-owner thread))
        on-click
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcmt/open-thread thread))))]
    [:> marker-slot* {:position position
                      :zoom zoom
                      :is-open is-open
                      :preview (mf/html [:> cmt/thread-preview* {:thread thread :owner owner}])}
     [:> review-marker* {:label (str (:seqn thread))
                         :state (cond is-open "active"
                                      (:is-resolved thread) "resolved"
                                      :else "default")
                         :aria-label (tr "workspace.review.marker.comment" (:seqn thread))
                         :on-click on-click}]]))

(mf/defc task-marker*
  "The mark of `tasks`: a task on its own, or the tasks of a thread on the
  same layer, which open as that thread."
  {::mf/private true}
  [{:keys [tasks position zoom is-open]}]
  (let [task (first tasks)
        on-click
        (mf/use-fn
         (mf/deps task)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtk/open-task (:id task)))))]
    [:> marker-slot* {:position position
                      :zoom zoom
                      :is-open is-open
                      :preview (mf/html [:> rvt/tasks-preview* {:tasks tasks}])}
     [:> review-marker* {:type "task"
                         :state (cond is-open "active"
                                      (some dwtk/lost? tasks) "lost"
                                      :else "default")
                         :aria-label (tr "workspace.review.marker.task" (:title task))
                         :on-click on-click}]]))

(defn- group-task-marks
  "The marks of the tasks of a page: one per task on its own, and one per
  layer for the tasks of a thread, which all open the same thread."
  [tasks]
  (->> (group-by (fn [{:keys [id thread-id shape-id]}]
                   (if (some? thread-id) [thread-id shape-id] id))
                 tasks)
       (vals)
       (mapv #(vec (sort-by :created-at %)))))

(mf/defc review-markers*
  "The marks of the page `page-id`: the threads the review layer draws
  (`threads`) and the open tasks, the area of the open thread or draft,
  and the panel of the open task or of the new one."
  [{:keys [threads page-id zoom open-id draft]}]
  (let [objects     (mf/deref refs/workspace-page-objects)
        tasks       (mf/deref refs/tasks)
        tasks-local (mf/deref refs/tasks-local)
        open-task   (some->> (:open tasks-local) (get tasks))
        new-task    (:new tasks-local)

        task-thread-ids
        (mf/with-memo [tasks]
          (into #{} (keep :thread-id) (vals tasks)))

        task-marks
        (mf/with-memo [tasks page-id]
          (->> (vals tasks)
               (remove dwtk/history?)
               (remove dwtk/closed?)
               (filter #(= page-id (:page-id %)))
               (group-task-marks)))

        open-thread (some #(when (= open-id (:id %)) %) threads)]

    [:*
     ;; the area of the thread open now, or of the draft being written
     (when (= :area (:anchor-type open-thread))
       [:> area-box* {:area (:area open-thread) :zoom zoom}])
     (when (= :area (get-in draft [:anchor :type]))
       [:> area-box* {:area (get-in draft [:anchor :area]) :zoom zoom}])

     (for [thread threads]
       (when-not (contains? task-thread-ids (:id thread))
         (when-let [position (thread-position objects thread)]
           [:> thread-marker* {:key (dm/str (:id thread))
                               :thread thread
                               :position position
                               :zoom zoom
                               :is-open (= open-id (:id thread))}])))

     (for [mark task-marks]
       (let [task (first mark)]
         (when-let [position (dwtk/anchor-position objects task)]
           [:> task-marker* {:key (dm/str (:id task))
                             :tasks mark
                             :position position
                             :zoom zoom
                             :is-open (or (= (:id task) (:id open-task))
                                          (and (some? (:thread-id task))
                                               (= open-id (:thread-id task))))}])))

     ;; a task without a thread opens as a panel by its mark
     (when (and (some? open-task)
                (nil? (:thread-id open-task))
                (= page-id (:page-id open-task)))
       (when-let [position (dwtk/anchor-position objects open-task)]
         [:> rvt/task-panel* {:key (dm/str (:id open-task))
                              :task open-task
                              :style (panel-style position zoom)}]))

     (when (and (some? new-task) (= page-id (:page-id new-task)))
       (when-let [position (or (dwtk/anchor-position objects new-task)
                               (:position new-task))]
         [:> rvt/new-task-form* {:key (dm/str (:shape-id new-task) (:comment-id new-task))
                                 :params new-task
                                 :style (panel-style position zoom
                                                     (if (some? (:thread-id new-task))
                                                       thread-width
                                                       0))}]))]))

(mf/defc area-layer*
  "The area tool of the review mode, over the whole comments layer: drag on
  the canvas to comment on a rectangle."
  [{:keys [vbox zoom]}]
  (let [start*  (mf/use-state nil)
        start   (deref start*)
        current* (mf/use-state nil)
        current (deref current*)

        on-down
        (mf/use-fn
         (mf/deps vbox zoom)
         (fn [event]
           (dom/stop-propagation event)
           (dom/capture-pointer event)
           (let [pt (rvc/event->canvas event vbox zoom)]
             (reset! start* pt)
             (reset! current* pt))))

        on-move
        (mf/use-fn
         (mf/deps vbox zoom start)
         (fn [event]
           (when (some? start)
             (reset! current* (rvc/event->canvas event vbox zoom)))))

        on-up
        (mf/use-fn
         (mf/deps start current)
         (fn [event]
           (dom/release-pointer event)
           (when (and (some? start) (some? current))
             (let [x (min (:x start) (:x current))
                   y (min (:y start) (:y current))
                   w (mth/abs (- (:x current) (:x start)))
                   h (mth/abs (- (:y current) (:y start)))]
               ;; a click, not a drag, draws nothing
               (when (and (> (* w zoom) 4) (> (* h zoom) 4))
                 (st/emit! (dwrv/create-area-draft {:x x :y y :width w :height h})))))
           (reset! start* nil)
           (reset! current* nil)))]

    [:div {:class (stl/css :area-drawer)
           :on-pointer-down on-down
           :on-pointer-move on-move
           :on-pointer-up on-up
           :on-click dom/stop-propagation}
     (when (and (some? start) (some? current))
       (let [x (min (:x start) (:x current))
             y (min (:y start) (:y current))]
         [:div {:class (stl/css :area-box)
                :style #js {:left (dm/str (* (- x (:x vbox)) zoom) "px")
                            :top (dm/str (* (- y (:y vbox)) zoom) "px")
                            :width (dm/str (* (mth/abs (- (:x current) (:x start))) zoom) "px")
                            :height (dm/str (* (mth/abs (- (:y current) (:y start))) zoom) "px")}}]))]))
