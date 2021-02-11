;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.path
  (:require
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.math :as mth]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.matrix :as gmt]
   [app.util.data :as ud]
   [app.common.data :as cd]
   [app.util.geom.path :as ugp]
   [app.main.streams :as ms]
   [app.main.store :as st]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.common :as common]
   [app.common.geom.shapes.path :as gsp]
   [app.common.pages :as cp]))

;; SCHEMAS

(s/def ::command #{:move-to
                   :line-to
                   :line-to-horizontal
                   :line-to-vertical
                   :curve-to
                   :smooth-curve-to
                   :quadratic-bezier-curve-to
                   :smooth-quadratic-bezier-curve-to
                   :elliptical-arc
                   :close-path})

(s/def :paths.params/x number?)
(s/def :paths.params/y number?)
(s/def :paths.params/c1x number?)
(s/def :paths.params/c1y number?)
(s/def :paths.params/c2x number?)
(s/def :paths.params/c2y number?)

(s/def ::relative? boolean?)

(s/def ::params
  (s/keys :req-un [:path.params/x
                   :path.params/y]
          :opt-un [:path.params/c1x
                   :path.params/c1y
                   :path.params/c2x
                   :path.params/c2y]))

(s/def ::content-entry
  (s/keys :req-un [::command]
          :req-opt [::params
                    ::relative?]))
(s/def ::content
  (s/coll-of ::content-entry :kind vector?))


;; CONSTANTS
(defonce enter-keycode 13)
(defonce drag-threshold 5)

;; PRIVATE METHODS

(defn get-path-id
  "Retrieves the currently editing path id"
  [state]
  (or (get-in state [:workspace-local :edition])
      (get-in state [:workspace-drawing :object :id])))

(defn get-path
  "Retrieves the location of the path object and additionaly can pass
  the arguments. This location can be used in get-in, assoc-in... functions"
  [state & path]
  (let [edit-id (get-in state [:workspace-local :edition])
        page-id (:current-page-id state)]
    (cd/concat
     (if edit-id
       [:workspace-data :pages-index page-id :objects edit-id]
       [:workspace-drawing :object])
     path)))

(defn- points->components [shape content]
  (let [transform (:transform shape)
        transform-inverse (:transform-inverse shape)
        center (gsh/center-shape shape)
        base-content (gsh/transform-content
                      content
                      (gmt/transform-in center transform-inverse))

        ;; Calculates the new selrect with points given the old center
        points (-> (gsh/content->selrect base-content)
                   (gsh/rect->points)
                   (gsh/transform-points center (:transform shape (gmt/matrix))))

        points-center (gsh/center-points points)

        ;; Points is now the selrect but the center is different so we can create the selrect
        ;; through points
        selrect (-> points
                    (gsh/transform-points points-center (:transform-inverse shape (gmt/matrix)))
                    (gsh/points->selrect))]
    [points selrect]))

(defn update-selrect
  "Updates the selrect and points for a path"
  [shape]
  (if (= (:rotation shape 0) 0)
    (let [content (:content shape)
          selrect (gsh/content->selrect content)
          points (gsh/rect->points selrect)]
      (assoc shape :points points :selrect selrect))

    (let [content (:content shape)
          [points selrect] (points->components shape content)]
      (assoc shape :points points :selrect selrect))))

(defn closest-angle [angle]
  (cond
    (or  (> angle 337.5)  (<= angle 22.5))  0
    (and (> angle 22.5)   (<= angle 67.5))  45
    (and (> angle 67.5)   (<= angle 112.5)) 90
    (and (> angle 112.5)	(<= angle 157.5)) 135
    (and (> angle 157.5)	(<= angle 202.5)) 180
    (and (> angle 202.5)	(<= angle 247.5)) 225
    (and (> angle 247.5)	(<= angle 292.5)) 270
    (and (> angle 292.5)	(<= angle 337.5)) 315))

(defn position-fixed-angle [point from-point]
  (if (and from-point point)
    (let [angle (mod (+ 360 (- (gpt/angle point from-point))) 360)
          to-angle (closest-angle angle)
          distance (gpt/distance point from-point)]
      (gpt/angle->point from-point (mth/radians to-angle) distance))
    point))

(defn next-node
  "Calculates the next-node to be inserted."
  [shape position prev-point prev-handler]
  (let [last-command (-> shape :content last :command)
        add-line?   (and prev-point (not prev-handler) (not= last-command :close-path))
        add-curve?  (and prev-point prev-handler (not= last-command :close-path))]
    (cond
      add-line?   {:command :line-to
                   :params position}
      add-curve?  {:command :curve-to
                   :params (ugp/make-curve-params position prev-handler)}
      :else       {:command :move-to
                   :params position})))

(defn append-node
  "Creates a new node in the path. Usualy used when drawing."
  [shape position prev-point prev-handler]
  (let [command (next-node shape position prev-point prev-handler)]
    (-> shape
        (update :content (fnil conj []) command)
        (update-selrect))))

(defn move-handler-modifiers [content index prefix match-opposite? dx dy]
  (let [[cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
        [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
        opposite-index (ugp/opposite-index content index prefix)]

    (cond-> {}
      :always
      (update index assoc cx dx cy dy)

      (and match-opposite? opposite-index)
      (update opposite-index assoc ocx (- dx) ocy (- dy)))))

(defn end-path-event? [{:keys [type shift] :as event}]
  (or (= (ptk/type event) ::finish-path)
      (= (ptk/type event) :esc-pressed)
      (= event :interrupt) ;; ESC
      (and (ms/mouse-double-click? event))
      (and (ms/keyboard-event? event)
           (= type :down)
           ;; TODO: Enter now finish path but can finish drawing/editing as well
           (= enter-keycode (:key event)))))

(defn generate-path-changes [page-id shape old-content new-content]
  (us/verify ::content old-content)
  (us/verify ::content new-content)
  (let [shape-id (:id shape)
        [old-points old-selrect] (points->components shape old-content)
        [new-points new-selrect] (points->components shape new-content)

        rch [{:type :mod-obj
              :id shape-id
              :page-id page-id
              :operations [{:type :set :attr :content :val new-content}
                           {:type :set :attr :selrect :val new-selrect}
                           {:type :set :attr :points  :val new-points}]}
             {:type :reg-objects
              :page-id page-id
              :shapes [shape-id]}]

        uch [{:type :mod-obj
              :id shape-id
              :page-id page-id
              :operations [{:type :set :attr :content :val old-content}
                           {:type :set :attr :selrect :val old-selrect}
                           {:type :set :attr :points  :val old-points}]}
             {:type :reg-objects
              :page-id page-id
              :shapes [shape-id]}]]
    [rch uch]))

(defn clean-edit-state
  [state]
  (dissoc state :last-point :prev-handler :drag-handler :preview))

(defn dragging? [start zoom]
  (fn [current]
    (>= (gpt/distance start current) (/ drag-threshold zoom))))

(defn drag-stream [to-stream]
  (let [start @ms/mouse-position
        zoom  (get-in @st/state [:workspace-local :zoom] 1)
        mouse-up (->> st/stream (rx/filter #(ms/mouse-up? %)))]
    (->> ms/mouse-position
         (rx/take-until mouse-up)
         (rx/filter (dragging? start zoom))
         (rx/take 1)
         (rx/merge-map (fn [] to-stream)))))

(defn position-stream []
  (->> ms/mouse-position
       (rx/with-latest merge (->> ms/mouse-position-shift (rx/map #(hash-map :shift? %))))
       (rx/with-latest merge (->> ms/mouse-position-alt (rx/map #(hash-map :alt? %))))))

;; EVENTS

(defn init-path []
  (ptk/reify ::init-path))

(defn finish-path [source]
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state))))))

(defn preview-next-point [{:keys [x y shift?]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            fix-angle? shift?
            last-point (get-in state [:workspace-local :edit-path id :last-point])
            position (cond-> (gpt/point x y)
                       fix-angle? (position-fixed-angle last-point))
            shape (get-in state (get-path state))
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            command (next-node shape position last-point prev-handler)]
        (assoc-in state [:workspace-local :edit-path id :preview] command)))))

(defn add-node [{:keys [x y shift?]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            fix-angle? shift?
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            position (cond-> (gpt/point x y)
                       fix-angle? (position-fixed-angle last-point))]
        (if-not (= last-point position)
          (-> state
              (assoc-in  [:workspace-local :edit-path id :last-point] position)
              (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
              (update-in [:workspace-local :edit-path id] dissoc :preview)
              (update-in (get-path state) append-node position last-point prev-handler))
          state)))))

(defn start-drag-handler []
  (ptk/reify ::start-drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (get-path state :content))
            index (dec (count content))
            command (get-in state (get-path state :content index :command))

            make-curve
            (fn [command]
              (let [params (ugp/make-curve-params
                            (get-in content [index :params])
                            (get-in content [(dec index) :params]))]
                (-> command
                    (assoc :command :curve-to :params params))))]

        (cond-> state
          (= command :line-to)
          (update-in (get-path state :content index) make-curve))))))

(defn drag-handler [{:keys [x y alt? shift?]}]
  (ptk/reify ::drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            shape (get-in state (get-path state))
            content (:content shape)
            index (dec (count content))
            node-position (ugp/command->point (nth content index))
            handler-position (cond-> (gpt/point x y)
                               shift? (position-fixed-angle node-position))
            {dx :x dy :y} (gpt/subtract handler-position node-position)
            match-opposite? (not alt?)
            modifiers (move-handler-modifiers content (inc index) :c1 match-opposite? dx dy)]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
            (assoc-in [:workspace-local :edit-path id :prev-handler] handler-position)
            (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (update-in (get-path state :content) ugp/apply-content-modifiers modifiers)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (get-path state) update-selrect))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler))))))

(declare close-path-drag-end)

(defn close-path-drag-start [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            zoom (get-in state [:workspace-local :zoom])
            start-position @ms/mouse-position

            stop-stream
            (->> stream (rx/filter #(or (end-path-event? %)
                                        (ms/mouse-up? %))))

            drag-events-stream
            (->> (position-stream)
                 (rx/take-until stop-stream)
                 (rx/map #(drag-handler %)))]

        (rx/concat
         (rx/of (add-node position))
         (drag-stream
          (rx/concat
           (rx/of (start-drag-handler))
           drag-events-stream
           (rx/of (finish-drag))
           (rx/of (close-path-drag-end))))
         (rx/of (finish-path "close-path")))))))

(defn close-path-drag-end []
  (ptk/reify ::close-path-drag-end
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (update-in state [:workspace-local :edit-path id] dissoc :prev-handler)))))

(defn path-pointer-enter [position]
  (ptk/reify ::path-pointer-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-points] (fnil conj #{}) position)))))

(defn path-pointer-leave [position]
  (ptk/reify ::path-pointer-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-points] disj position)))))

(defn start-path-from-point [position]
  (ptk/reify ::start-path-from-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [start-point @ms/mouse-position
            zoom (get-in state [:workspace-local :zoom])
            mouse-up    (->> stream (rx/filter #(or (end-path-event? %)
                                                    (ms/mouse-up? %))))
            drag-events (->> ms/mouse-position
                             (rx/take-until mouse-up)
                             (rx/map #(drag-handler %)))]

        (rx/concat
         (rx/of (add-node position))
         (drag-stream
          (rx/concat
           (rx/of (start-drag-handler))
           drag-events
           (rx/of (finish-drag)))))))))

(defn make-corner []
  (ptk/reify ::make-corner
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (get-path state))
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            new-content (reduce ugp/make-corner-point (:content shape) selected-points)
            [rch uch] (generate-path-changes page-id shape (:content shape) new-content)]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(defn make-curve []
  (ptk/reify ::make-curve
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (get-path state))
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            new-content (reduce ugp/make-curve-point (:content shape) selected-points)
            [rch uch] (generate-path-changes page-id shape (:content shape) new-content)]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(defn path-handler-enter [index prefix]
  (ptk/reify ::path-handler-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] (fnil conj #{}) [index prefix])))))

(defn path-handler-leave [index prefix]
  (ptk/reify ::path-handler-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] disj [index prefix])))))

;; EVENT STREAMS

(defn make-drag-stream
  [stream down-event zoom]
  (let [mouse-up    (->> stream (rx/filter #(or (end-path-event? %)
                                                (ms/mouse-up? %))))
        drag-events (->> (position-stream)
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]

    (rx/concat
     (rx/of (add-node down-event))
     (drag-stream
      (rx/concat
       (rx/of (start-drag-handler))
       drag-events
       (rx/of (finish-drag)))))))

(defn make-node-events-stream
  [stream]
  (->> stream
       (rx/filter (ptk/type? ::close-path-drag-start))
       (rx/take 1)
       (rx/merge-map #(rx/empty))))

;; MAIN ENTRIES

(defn handle-drawing-path
  [id]
  (ptk/reify ::handle-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom            (get-in state [:workspace-local :zoom])
            mouse-down      (->> stream (rx/filter ms/mouse-down?))
            end-path-events (->> stream (rx/filter end-path-event?))

            ;; Mouse move preview
            mousemove-events
            (->> (position-stream)
                 (rx/take-until end-path-events)
                 (rx/map #(preview-next-point %)))

            ;; From mouse down we can have: click, drag and double click
            mousedown-events
            (->> mouse-down
                 (rx/take-until end-path-events)
                 (rx/with-latest merge (position-stream))

                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-node-events-stream stream)
                            (make-drag-stream stream % zoom))))]

        (rx/concat
         (rx/of (init-path))
         (rx/merge mousemove-events
                   mousedown-events)
         (rx/of (finish-path "after-events")))))))

(defn stop-path-edit []
  (ptk/reify ::stop-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (update state :workspace-local dissoc :edit-path id)))))

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      ;; Only edit if the object has been created
      (if-let [id (get-in state [:workspace-local :edition])]
        (assoc-in state [:workspace-local :edit-path id] {:edit-mode :move
                                                          :selected #{}
                                                          :snap-toggled true})
        state))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter #(= % :interrupt))
           (rx/take 1)
           (rx/map #(stop-path-edit))))))

(defn modify-point [index prefix dx dy]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers (inc index)] assoc
                       :c1x dx :c1y dy)
            (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                       :x dx :y dy :c2x dx :c2y dy))))))

(defn modify-handler [id index prefix dx dy match-opposite?]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (get-path state :content))
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
            opposite-index (ugp/opposite-index content index prefix)]
        (cond-> state
          :always
          (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                     cx dx cy dy)

          (and match-opposite? opposite-index)
          (update-in [:workspace-local :edit-path id :content-modifiers opposite-index] assoc
                     ocx (- dx) ocy (- dy)))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (get-path state))
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            new-content (ugp/apply-content-modifiers (:content shape) content-modifiers)
            [rch uch] (generate-path-changes page-id shape (:content shape) new-content)]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true})
               (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers)))))))

(defn save-path-content []
  (ptk/reify ::save-path-content
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (get-path state :content))
            content (if (= (-> content last :command) :move-to)
                      (into [] (take (dec (count content)) content))
                      content)]
        (assoc-in state (get-path state :content) content)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            shape (get-in state (get-path state))
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            [rch uch] (generate-path-changes page-id shape old-content (:content shape))]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(declare start-draw-mode)
(defn check-changed-content []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            content (get-in state (get-path state :content))
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])]

        (cond
          (not= content old-content) (rx/of (save-path-content)
                                            (start-draw-mode))
          (= mode :draw) (rx/of :interrupt)
          :else (rx/of (finish-path "changed-content")))))))

(defn move-path-point [start-point end-point]
  (ptk/reify ::move-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            content (get-in state (get-path state :content))

            {dx :x dy :y} (gpt/subtract end-point start-point)

            handler-indices (-> (ugp/content->handlers content)
                                (get start-point))

            command-for-point (fn [[index command]]
                                (let [point (ugp/command->point command)]
                                  (= point start-point)))

            point-indices (->> (cd/enumerate content)
                               (filter command-for-point)
                               (map first))


            point-reducer (fn [modifiers index]
                            (-> modifiers
                                (assoc-in [index :x] dx)
                                (assoc-in [index :y] dy)))

            handler-reducer (fn [modifiers [index prefix]]
                              (let [cx (ud/prefix-keyword prefix :x)
                                    cy (ud/prefix-keyword prefix :y)]
                                (-> modifiers
                                    (assoc-in [index cx] dx)
                                    (assoc-in [index cy] dy))))

            modifiers (as-> (get-in state [:workspace-local :edit-path id :content-modifiers] {}) $
                        (reduce point-reducer $ point-indices)
                        (reduce handler-reducer $ handler-indices))]

        (assoc-in state [:workspace-local :edit-path id :content-modifiers] modifiers)))))

(defn start-move-path-point
  [position]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [start-position @ms/mouse-position
            stopper (->> stream (rx/filter ms/mouse-up?))
            zoom (get-in state [:workspace-local :zoom])]

        (drag-stream
         (rx/concat
          (->> ms/mouse-position
               (rx/take-until stopper)
               (rx/map #(move-path-point position %)))
          (rx/of (apply-content-modifiers))))))))

(defn start-move-handler
  [index prefix]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            cx (ud/prefix-keyword prefix :x)
            cy (ud/prefix-keyword prefix :y)
            start-point @ms/mouse-position
            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            start-delta-x (get-in modifiers [index cx] 0)
            start-delta-y (get-in modifiers [index cy] 0)

            content (get-in state (get-path state :content))
            opposite-index   (ugp/opposite-index content index prefix)
            opposite-prefix  (if (= prefix :c1) :c2 :c1)
            opposite-handler (-> content (get opposite-index) (ugp/get-handler opposite-prefix))

            point (-> content (get (if (= prefix :c1) (dec index) index)) (ugp/command->point))
            handler (-> content (get index) (ugp/get-handler prefix))

            current-distance (when opposite-handler (gpt/distance (ugp/opposite-handler point handler) opposite-handler))
            match-opposite? (and opposite-handler (mth/almost-zero? current-distance))]

        (drag-stream
         (rx/concat
          (->> (position-stream)
               (rx/take-until (->> stream (rx/filter ms/mouse-up?)))
               (rx/map
                (fn [{:keys [x y alt? shift?]}]
                  (let [pos (cond-> (gpt/point x y)
                              shift? (position-fixed-angle point))]
                    (modify-handler
                     id
                     index
                     prefix
                     (+ start-delta-x (- (:x pos) (:x start-point)))
                     (+ start-delta-y (- (:y pos) (:y start-point)))
                     (and (not alt?) match-opposite?))))))
          (rx/concat (rx/of (apply-content-modifiers)))))))))

(defn start-draw-mode []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-data :pages-index page-id :objects id :content])]
        (-> state
            (assoc-in [:workspace-local :edit-path id :old-content] old-content))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            edit-mode (get-in state [:workspace-local :edit-path id :edit-mode])]
        (if (= :draw edit-mode)
          (rx/concat
           (rx/of (handle-drawing-path id))
           (->> stream
                (rx/filter (ptk/type? ::finish-path))
                (rx/take 1)
                (rx/merge-map #(rx/of (check-changed-content)))))
          (rx/empty))))))

(defn change-edit-mode [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (cond-> state
          id (assoc-in [:workspace-local :edit-path id :edit-mode] mode))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)]
        (cond
          (and id (= :move mode)) (rx/of (finish-path "change-edit-mode"))
          (and id (= :draw mode)) (rx/of (start-draw-mode))
          :else (rx/empty))))))

(defn select-handler [index type]
  (ptk/reify ::select-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-handlers] (fnil conj #{}) [index type]))))))

(defn select-node [position]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (assoc-in [:workspace-local :edit-path id :selected-points] #{position}))))))

(defn deselect-node [position]
  (ptk/reify ::deselect-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-points] (fnil disj #{}) position))))))

(defn add-to-selection-handler [index type]
  (ptk/reify ::add-to-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn add-to-selection-node [index]
  (ptk/reify ::add-to-selection-node
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-handler [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-node [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :selected-handlers] #{})
            (assoc-in [:workspace-local :edit-path id :selected-points] #{}))))))

(defn setup-frame-path []
  (ptk/reify ::setup-frame-path
    ptk/UpdateEvent
    (update [_ state]

      (let [objects (dwc/lookup-page-objects state)
            content (get-in state [:workspace-drawing :object :content] [])
            position (get-in content [0 :params] nil)
            frame-id  (cp/frame-id-by-position objects position)]
        (-> state
            (assoc-in [:workspace-drawing :object :frame-id] frame-id))))))

(defn handle-new-shape-result [shape-id]
  (ptk/reify ::handle-new-shape-result
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (us/verify ::content content)
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of (setup-frame-path)
                  common/handle-finish-drawing
                  (dwc/start-edition-mode shape-id)
                  (start-path-edit shape-id)
                  (change-edit-mode :draw))))))

(defn handle-new-shape
  "Creates a new path shape"
  []
  (ptk/reify ::handle-new-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (handle-drawing-path shape-id))
         (->> stream
              (rx/filter (ptk/type? ::finish-path))
              (rx/take 1)
              (rx/observe-on :async)
              (rx/map #(handle-new-shape-result shape-id))))))))
