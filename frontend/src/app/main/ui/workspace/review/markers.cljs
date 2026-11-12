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
  (in the review colour, wherever the file is open), the tasks of a
  thread show as a task mark that opens it, and a thread on an area
  draws its area while it is open. Marks that would overlap show as one,
  with a counter, and fan out on a click. Hovering a mark outlines its
  layer."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.data.comments :as dcmt]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.highlight :as dwh]
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
  "The style that puts a mark at canvas `position`, `offset` screen pixels
  away from it. `ring` is where it starts to fan out from."
  [{:keys [x y]} zoom offset ring]
  #js {:left (dm/str (+ (* x zoom) (or (:x offset) 0)) "px")
       :top (dm/str (+ (* y zoom) (or (:y offset) 0)) "px")
       "--marker-ring-x" (some-> (:x ring) - (dm/str "px"))
       "--marker-ring-y" (some-> (:y ring) - (dm/str "px"))})

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
  "A mark on the canvas, centered on its point. Hovering it outlines the
  layers `shape-ids` and shows `preview` unless it is open, like the bubbles
  of the comments."
  {::mf/private true}
  [{:keys [position zoom offset ring is-open preview shape-ids children]}]
  (let [hover*   (mf/use-state false)
        hover?   (deref hover*)
        on-enter (mf/use-fn #(reset! hover* true))
        on-leave (mf/use-fn #(reset! hover* false))
        shown?   (and hover? (not is-open))]

    ;; an effect, because a mark that fans out goes away under the pointer
    ;; and never hears it leave
    (mf/with-effect [hover? shape-ids]
      (when hover?
        (apply st/emit! (map dwh/highlight-shape shape-ids))
        (fn []
          (apply st/emit! (map dwh/dehighlight-shape shape-ids)))))

    [:div {:class (stl/css-case :marker true
                                :is-expanded (some? ring)
                                :is-hovered shown?)
           :style (screen-style position zoom offset ring)
           :on-pointer-enter on-enter
           :on-pointer-leave on-leave}
     children
     (when shown? preview)]))

(def ^:private preview-limit
  "How many items the card of a mark lists: a big group at a low zoom would
  not fit the screen."
  5)

(mf/defc thread-preview-item*
  {::mf/private true}
  [{:keys [thread]}]
  (let [owner (mf/with-memo [thread]
                (dcmt/get-owner thread))]
    [:> cmt/comment-info* {:item thread :profile owner}]))

(mf/defc marks-preview*
  "The small card of `marks`: their comments and tasks, oldest first."
  {::mf/private true}
  [{:keys [marks]}]
  (let [items  (mf/with-memo [marks]
                 (->> (sort-by :seqn marks)
                      (mapcat (fn [{:keys [thread tasks]}]
                                (if (some? thread)
                                  [{:thread thread}]
                                  (map (fn [task] {:task task}) tasks))))
                      (vec)))
        hidden (- (count items) preview-limit)]
    [:> cmt/preview-card* {}
     (for [{:keys [thread task]} (take preview-limit items)]
       (if (some? thread)
         [:> thread-preview-item* {:key (dm/str (:id thread)) :thread thread}]
         [:> rvt/task-preview* {:key (dm/str (:id task)) :task task}]))
     (when (pos? hidden)
       [:span {:class (stl/css :preview-more)}
        (tr "workspace.review.marker.more" hidden)])]))

(mf/defc thread-marker*
  "The mark of a thread. `offset` and `ring` place it when it is fanned out."
  {::mf/private true}
  [{:keys [mark zoom offset ring is-open]}]
  (let [thread (:thread mark)
        marks  (mf/with-memo [mark] [mark])
        on-click
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtk/close-task)
                     (dcmt/open-thread thread))))]
    [:> marker-slot* {:position (:position mark)
                      :zoom zoom
                      :offset offset
                      :ring ring
                      :is-open is-open
                      :shape-ids (:shape-ids mark)
                      :preview (mf/html [:> marks-preview* {:marks marks}])}
     [:> review-marker* {:label (str (:seqn thread))
                         :state (cond is-open "active"
                                      (:is-resolved thread) "resolved"
                                      :else "default")
                         :aria-label (tr "workspace.review.marker.comment" (:seqn thread))
                         :on-click on-click}]]))

(mf/defc task-marker*
  "The mark of its `tasks`: the tasks of a thread on the same layer, which
  open as that thread. `offset` and `ring` place it when it is fanned
  out."
  {::mf/private true}
  [{:keys [mark zoom offset ring is-open]}]
  (let [tasks (:tasks mark)
        task  (first tasks)
        many? (some? (next tasks))
        marks (mf/with-memo [mark] [mark])
        on-click
        (mf/use-fn
         (mf/deps task)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtk/open-task (:id task)))))]
    [:> marker-slot* {:position (:position mark)
                      :zoom zoom
                      :offset offset
                      :ring ring
                      :is-open is-open
                      :shape-ids (:shape-ids mark)
                      :preview (mf/html [:> marks-preview* {:marks marks}])}
     [:> review-marker* {:type "task"
                         :state (cond is-open "active"
                                      (some dwtk/lost? tasks) "lost"
                                      :else "default")
                         :counter (when many? (count tasks))
                         :aria-label (if many?
                                       (tr "workspace.review.marker.tasks" (count tasks))
                                       (tr "workspace.review.marker.task" (:title task)))
                         :on-click on-click}]]))

(mf/defc mark-marker*
  {::mf/private true}
  [{:keys [mark zoom offset ring is-open]}]
  [:> (if (some? (:thread mark)) thread-marker* task-marker*)
   {:mark mark :zoom zoom :offset offset :ring ring :is-open is-open}])

(defn- cluster-look
  "What the one mark of `marks` shows: the square of the tasks when it holds
  any, as they ask for work, and how many items it holds."
  [marks]
  (let [tasks   (into [] (mapcat :tasks) marks)
        threads (into [] (keep :thread) marks)
        total   (+ (count tasks) (count threads))]
    {:type (if (seq tasks) "task" "comment")
     :state (cond (some dwtk/lost? tasks) "lost"
                  (and (empty? tasks) (every? :is-resolved threads)) "resolved"
                  :else "default")
     :counter total
     :aria-label (cond (empty? threads) (tr "workspace.review.marker.tasks" total)
                       (empty? tasks) (tr "workspace.review.marker.comments" total)
                       :else (tr "workspace.review.marker.items" total))}))

(mf/defc mark-cluster*
  "The one mark of `marks`, which overlap: a click fans them out so the
  user picks one."
  {::mf/private true}
  [{:keys [marks zoom is-open]}]
  (let [look      (mf/with-memo [marks] (cluster-look marks))
        mark-ids  (mf/with-memo [marks] (into #{} (map :id) marks))
        shape-ids (mf/with-memo [marks] (into #{} (mapcat :shape-ids) marks))
        on-click
        (mf/use-fn
         (mf/deps mark-ids)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtk/close-task)
                     (dwtk/cancel-new-task)
                     (dcmt/expand-comment-group mark-ids))))]
    [:> marker-slot* {:position (cmt/expanded-group-center marks)
                      :zoom zoom
                      :is-open is-open
                      :shape-ids shape-ids
                      :preview (mf/html [:> marks-preview* {:marks marks}])}
     [:> review-marker* {:type (:type look)
                         :state (if is-open "active" (:state look))
                         :counter (:counter look)
                         :aria-label (:aria-label look)
                         :on-click on-click}]]))

(mf/defc cluster-ghost*
  "What stays at the center of `marks` while they are fanned out."
  {::mf/private true}
  [{:keys [marks zoom]}]
  (let [look (mf/with-memo [marks] (cluster-look marks))]
    [:div {:class (stl/css :marker :is-ghost)
           :style (screen-style (cmt/expanded-group-center marks) zoom nil nil)}
     [:> review-marker* {:type (:type look)
                         :state "ghost"
                         :counter (:counter look)
                         :aria-hidden true
                         :aria-label (:aria-label look)
                         :tab-index -1}]]))

(defn- group-task-marks
  "The tasks of a page by mark: one per thread and layer, as the tasks of
  a thread all open it."
  [tasks]
  (->> (group-by (juxt :thread-id :shape-id) tasks)
       (vals)
       (mapv #(vec (sort-by :created-at %)))))

(defn- thread-mark
  [objects {:keys [id created-at anchor-type shape-id] :as thread}]
  (when-let [position (thread-position objects thread)]
    {:id id
     :created-at created-at
     :position (gpt/point position)
     :thread thread
     :shape-ids (when (= :layer anchor-type)
                  (some-> (dwtk/anchor-shape objects {:shape-id shape-id}) :id vector))}))

(defn- task-mark
  [objects tasks]
  (let [{:keys [id created-at] :as task} (first tasks)]
    (when-let [position (dwtk/anchor-position objects task)]
      {:id id
       :created-at created-at
       :position (gpt/point position)
       :tasks tasks
       :shape-ids (some-> (dwtk/anchor-shape objects task) :id vector)})))

(defn mark-clusters
  "The marks of `threads` and `tasks` at `zoom`, grouped when they would
  overlap: a vector of marks for each spot of the canvas. `:seqn` keeps them
  oldest first, whatever their kind."
  [objects zoom threads tasks]
  (->> (concat (keep #(thread-mark objects %) threads)
               (keep #(task-mark objects %) (group-task-marks tasks)))
       (sort-by :created-at)
       (map-indexed (fn [index mark] (assoc mark :seqn index)))
       (dwcm/group-bubbles zoom)))

(defn- mark-open?
  "Whether `mark` is open now: its thread, a task of it, or the thread of one."
  [{:keys [thread tasks]} open-task open-id]
  (or (and (some? thread) (= open-id (:id thread)))
      (boolean
       (some (fn [{:keys [id thread-id]}]
               (or (= id (:id open-task))
                   (= open-id thread-id)))
             tasks))))

(mf/defc review-markers*
  "The marks of the page `page-id`: the threads the review layer draws
  (`threads`) and the open tasks, the area of the open thread or draft,
  and the form of the new task. An open task shows as its thread."
  [{:keys [threads page-id zoom open-id draft expanded]}]
  (let [objects     (mf/deref refs/workspace-page-objects)
        tasks       (mf/deref refs/tasks)
        tasks-local (mf/deref refs/tasks-local)
        open-task   (some->> (:open tasks-local) (get tasks))
        new-task    (:new tasks-local)

        task-thread-ids
        (mf/with-memo [tasks]
          (into #{} (keep :thread-id) (vals tasks)))

        clusters
        (mf/with-memo [objects threads tasks task-thread-ids page-id zoom]
          (mark-clusters objects zoom
                         (remove #(contains? task-thread-ids (:id %)) threads)
                         (->> (vals tasks)
                              (remove dwtk/history?)
                              (remove dwtk/closed?)
                              (filter #(= page-id (:page-id %))))))

        open-thread (some #(when (= open-id (:id %)) %) threads)]

    [:*
     ;; the area of the thread open now, or of the draft being written
     (when (= :area (:anchor-type open-thread))
       [:> area-box* {:area (:area open-thread) :zoom zoom}])
     (when (= :area (get-in draft [:anchor :type]))
       [:> area-box* {:area (get-in draft [:anchor :area]) :zoom zoom}])

     (for [marks clusters]
       (let [mark      (first marks)
             cluster?  (some? (next marks))
             expanded? (and cluster?
                            (= expanded (into #{} (map :id) marks)))]
         (cond
           expanded?
           [:* {:key (dm/str (:id mark))}
            [:> cluster-ghost* {:marks marks :zoom zoom}]
            (for [[mark offset ring] (cmt/expanded-group-offsets zoom marks)]
              [:> mark-marker* {:key (dm/str (:id mark))
                                :mark mark
                                :zoom zoom
                                :offset offset
                                :ring ring
                                :is-open false}])]

           cluster?
           [:> mark-cluster* {:key (dm/str (:id mark))
                              :marks marks
                              :zoom zoom
                              :is-open (some #(mark-open? % open-task open-id) marks)}]

           :else
           [:> mark-marker* {:key (dm/str (:id mark))
                             :mark mark
                             :zoom zoom
                             :is-open (mark-open? mark open-task open-id)}])))

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
