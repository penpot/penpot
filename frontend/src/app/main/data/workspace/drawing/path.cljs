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
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.math :as mth]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.util.data :as ud]
   [app.common.data :as cd]
   [app.util.geom.path :as ugp]
   [app.main.streams :as ms]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.common :as common]
   [app.common.geom.shapes.path :as gsp]))

;; CONSTANTS
(defonce enter-keycode 13)


;; PRIVATE METHODS

(defn get-path-id [state]
  (or (get-in state [:workspace-local :edition])
      (get-in state [:workspace-drawing :object :id])))

(defn get-path [state & path]
  (let [edit-id (get-in state [:workspace-local :edition])
        page-id (:current-page-id state)]
    (cd/concat
     (if edit-id
       [:workspace-data :pages-index page-id :objects edit-id]
       [:workspace-drawing :object])
     path)))

(defn last-start-path [content]
  (->> content
       reverse
       (cd/seek (fn [{cmd :command}] (= cmd :move-to)))
       :params))

(defn update-selrect [shape]
  (let [selrect (gsh/content->selrect (:content shape))
        points (gsh/rect->points selrect)]
    (assoc shape :points points :selrect selrect)))

(defn next-node
  "Calculates the next-node to be inserted."
  [shape position prev-point prev-handler]
  (let [last-command (-> shape :content last :command)
        start-point  (-> shape :content last-start-path)

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

(defn suffix-keyword
  [kw suffix]
  (let [strkw (if kw (name kw) "")]
    (keyword (str strkw suffix))))

(defn move-handler
  [shape index handler-type match-opposite? position]
  (let [content (:content shape)
        [command next-command] (-> (ud/with-next content) (nth index))

        update-command
        (fn [{cmd :command params :params :as command} param-prefix prev-command]
          (if (#{:line-to :curve-to} cmd)
            (let [command (if (= cmd :line-to)
                            {:command :curve-to
                             :params (ugp/make-curve-params params (:params prev-command))}
                            command)]
              (-> command
                  (update :params assoc
                          (suffix-keyword param-prefix "x") (:x position)
                          (suffix-keyword param-prefix "y") (:y position))))
            command))

        update-content
        (fn [shape index prefix]
          (if (contains? (:content shape) index)
            (let [prev-command (get-in shape [:content (dec index)])
                  content (-> shape :content (update index update-command prefix prev-command))]
              (-> shape
                  (assoc :content content)
                  (update-selrect)))
            shape))]

    (cond-> shape
      (= :prev handler-type)
      (update-content index :c2)

      (and (= :next handler-type) next-command)
      (update-content (inc index) :c1)

      match-opposite?
      (move-handler
       index
       (if (= handler-type :prev) :next :prev)
       false
       (ugp/opposite-handler (gpt/point (:params command))
                             (gpt/point position))))))

(defn end-path-event? [{:keys [type shift] :as event}]
  (or (= event ::end-path)
      (= (ptk/type event) :esc-pressed)
      (= event :interrupt) ;; ESC
      (and (ms/keyboard-event? event)
           (= type :down)
           ;; TODO: Enter now finish path but can finish drawing/editing as well
           (= enter-keycode (:key event)))))


;; EVENTS

(defn init-path [id]
  (ptk/reify ::init-path
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          #_(assoc-in [:workspace-drawing :object :initialized?] true)
          #_(assoc-in [:workspace-local :edit-path :last-point] nil)))))

(defn finish-path [id]
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-local :edit-path id] dissoc :last-point :prev-handler :drag-handler :preview)))))

(defn preview-next-point [{:keys [x y]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            position (gpt/point x y)
            shape (get-in state (get-path state))
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            
            command (next-node shape position last-point prev-handler)]
        (assoc-in state [:workspace-local :edit-path id :preview] command)))))

(defn add-node [{:keys [x y]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            position (gpt/point x y)
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])]
        (-> state
            (assoc-in  [:workspace-local :edit-path id :last-point] position)
            (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
            (update-in (get-path state) append-node position last-point prev-handler))))))

(defn drag-handler [{:keys [x y]}]
  (ptk/reify ::drag-handler
    ptk/UpdateEvent
    (update [_ state]

      (let [id (get-path-id state)
            position (gpt/point x y)
            shape (get-in state (get-path state))
            index (dec (count (:content shape)))]
        (-> state
            (update-in (get-path state) move-handler index :next true position)
            (assoc-in [:workspace-local :edit-path id :prev-handler] position)
            (assoc-in [:workspace-local :edit-path id :drag-handler] position))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler))))))

;; EVENT STREAMS

(defn make-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-click?)
       (rx/debounce 200)
       (rx/first)
       (rx/map #(add-node down-event))))

(defn make-drag-stream
  [stream down-event]
  (let [mouse-up    (->> stream (rx/filter ms/mouse-up?))
        drag-events (->> ms/mouse-position
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]
    (->> (rx/timer 400)
         (rx/merge-map #(rx/concat
                         (rx/of (add-node down-event))
                         drag-events
                         (rx/of (finish-drag)))))))

(defn make-dbl-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-double-click?)
       (rx/first)
       (rx/merge-map
        #(rx/of (add-node down-event)
                ::end-path))))

;; MAIN ENTRIES

(defn handle-drawing-path
  [id]
  (ptk/reify ::handle-drawing-path
    ptk/WatchEvent
    (watch [_ state stream]

      (let [mouse-down    (->> stream (rx/filter ms/mouse-down?))
            end-path-events (->> stream (rx/filter end-path-event?))

            ;; Mouse move preview
            mousemove-events
            (->> ms/mouse-position
                 (rx/take-until end-path-events)
                 (rx/throttle 50)
                 (rx/map #(preview-next-point %)))

            ;; From mouse down we can have: click, drag and double click
            mousedown-events
            (->> mouse-down
                 (rx/take-until end-path-events)
                 (rx/throttle 50)
                 (rx/with-latest merge ms/mouse-position)

                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-click-stream stream %)
                            (make-drag-stream stream %)
                            (make-dbl-click-stream stream %))))]

        (->> (rx/concat
              (rx/of (init-path id))
              (rx/merge mousemove-events
                        mousedown-events)
              (rx/of (finish-path id))))))))



#_(def handle-drawing-path
  (ptk/reify ::handle-drawing-path
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [flags]} (:workspace-local state)

            last-point (volatile! @ms/mouse-position)

            stoper (->> (rx/filter stoper-event? stream)
                        (rx/share))

            mouse (rx/sample 10 ms/mouse-position)

            points (->> stream
                        (rx/filter ms/mouse-click?)
                        (rx/filter #(false? (:shift %)))
                        (rx/with-latest vector mouse)
                        (rx/map second))

            counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))

            stream' (->> mouse
                         (rx/with-latest vector ms/mouse-position-ctrl)
                         (rx/with-latest vector counter)
                         (rx/map flatten))

            imm-transform #(vector (- % 7) (+ % 7) %)
            immanted-zones (vec (concat
                                 (map imm-transform (range 0 181 15))
                                 (map (comp imm-transform -) (range 0 181 15))))

            align-position (fn [angle pos]
                             (reduce (fn [pos [a1 a2 v]]
                                       (if (< a1 angle a2)
                                         (reduced (gpt/update-angle pos v))
                                         pos))
                                     pos
                                     immanted-zones))]

        (rx/merge
         (rx/of #(initialize-drawing % @last-point))

         (->> points
              (rx/take-until stoper)
              (rx/map (fn [pt] #(insert-point-segment % pt))))

         (rx/concat
          (->> stream'
               (rx/take-until stoper)
               (rx/map (fn [[point ctrl? index :as xxx]]
                         (let [point (if ctrl?
                                       (as-> point $
                                         (gpt/subtract $ @last-point)
                                         (align-position (gpt/angle $) $)
                                         (gpt/add $ @last-point))
                                       point)]
                           #(update-point-segment % index point)))))
          (rx/of finish-drawing-path
                 common/handle-finish-drawing)))))))


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

(defn modify-point [index dx dy]
  (ptk/reify ::modify-point

    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers (inc index)] assoc
                       :c1x dx :c1y dy)
            (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                       :x dx :y dy :c2x dx :c2y dy)
            )))))

(defn modify-handler [index type dx dy]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (let [s1 (if (= type :prev) -1 1)
              s2 (if (= type :prev) 1 -1)]
          (-> state
              (update-in [:workspace-local :edit-path id :content-modifiers (inc index)] assoc
                         :c1x (* s1 dx) :c1y (* s1 dy))
              (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                         :c2x (* s2 dx) :c2y (* s2 dy) ))
          )))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-data :pages-index page-id :objects id :content])
            old-selrect (get-in state [:workspace-data :pages-index page-id :objects id :selrect])
            old-points (get-in state [:workspace-data :pages-index page-id :objects id :points])
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            new-content (gsp/apply-content-modifiers old-content content-modifiers)
            new-selrect (gsh/content->selrect new-content)
            new-points (gsh/rect->points new-selrect)
            rch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val new-content}
                               {:type :set :attr :selrect :val new-selrect}
                               {:type :set :attr :points  :val new-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]

            uch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val old-content}
                               {:type :set :attr :selrect :val old-selrect}
                               {:type :set :attr :points  :val old-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true})
               (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers)))))))

(defn save-path-content []
  (ptk/reify ::save-path-content
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            old-selrect (gsh/content->selrect old-content)
            old-points  (gsh/rect->points old-content)
            new-content (get-in state [:workspace-data :pages-index page-id :objects id :content])
            new-selrect (get-in state [:workspace-data :pages-index page-id :objects id :selrect])
            new-points  (get-in state [:workspace-data :pages-index page-id :objects id :points])

            rch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val new-content}
                               {:type :set :attr :selrect :val new-selrect}
                               {:type :set :attr :points  :val new-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]

            uch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val old-content}
                               {:type :set :attr :selrect :val old-selrect}
                               {:type :set :attr :points  :val old-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]]

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
          :else (rx/of (finish-path id)))))))

(defn start-move-path-point
  [index]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            start-point @ms/mouse-position
            start-delta-x (get-in state [:workspace-local :edit-path id :content-modifiers index :x] 0)
            start-delta-y (get-in state [:workspace-local :edit-path id :content-modifiers index :y] 0)]
        (rx/concat
         (->> ms/mouse-position
              (rx/take-until (->> stream (rx/filter ms/mouse-up?)))
              (rx/map #(modify-point
                        index
                        (+ start-delta-x (- (:x %) (:x start-point)))
                        (+ start-delta-y (- (:y %) (:y start-point))))))
         (rx/concat (rx/of (apply-content-modifiers)))
         )))))

(defn start-move-handler
  [index type]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            [cx cy] (if (= :prev type) [:c2x :c2y] [:c1x :c1y])
            cidx (if (= :prev type) index (inc index))

            start-point @ms/mouse-position
            start-delta-x (get-in state [:workspace-local :edit-path id :content-modifiers cidx cx] 0)
            start-delta-y (get-in state [:workspace-local :edit-path id :content-modifiers cidx cy] 0)]

        (rx/concat
         (->> ms/mouse-position
              (rx/take-until (->> stream (rx/filter ms/mouse-up?)))
              (rx/map #(modify-handler
                        index
                        type
                        (+ start-delta-x (- (:x %) (:x start-point)))
                        (+ start-delta-y (- (:y %) (:y start-point)))))
              )
         (rx/concat (rx/of (apply-content-modifiers))))))))

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
          (and id (= :move mode)) (rx/of ::end-path)
          (and id (= :draw mode)) (rx/of (start-draw-mode))
          :else (rx/empty))))))

(defn select-handler [index type]
  (ptk/reify ::select-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected] (fnil conj #{}) [index type]))))))

(defn select-node [index]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected] (fnil conj #{}) index))))))

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

(defn handle-new-shape-result [shape-id]
  (ptk/reify ::handle-new-shape-result
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of common/handle-finish-drawing
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
              (rx/map #(handle-new-shape-result shape-id)))
         )))))
