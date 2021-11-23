;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.selection
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.types.interactions :as cti]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as md]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [linked.set :as lks]
   [potok.core :as ptk]))

(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

(s/def ::ordered-set-of-uuid
  (s/every uuid? :kind d/ordered-set?))

(s/def ::set-of-string
  (s/every string? :kind set?))

;; --- Selection Rect

(declare select-shapes-by-current-selrect)
(declare deselect-all)

(defn update-selrect
  [selrect]
  (ptk/reify ::update-selrect
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] selrect))))

(defn handle-area-selection
  [preserve?]
  (letfn [(data->selrect [data]
            (let [start (:start data)
                  stop (:stop data)
                  start-x (min (:x start) (:x stop))
                  start-y (min (:y start) (:y stop))
                  end-x (max (:x start) (:x stop))
                  end-y (max (:y start) (:y stop))]
              {:type :rect
               :x start-x
               :y start-y
               :width (mth/abs (- end-x start-x))
               :height (mth/abs (- end-y start-y))}))]
    (ptk/reify ::handle-area-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [zoom (get-in state [:workspace-local :zoom] 1)
              stop? (fn [event] (or (dwc/interrupt? event) (ms/mouse-up? event)))
              stoper (->> stream (rx/filter stop?))]
          (rx/concat
            (when-not preserve?
              (rx/of (deselect-all)))
            (->> ms/mouse-position
                 (rx/scan (fn [data pos]
                            (if data
                              (assoc data :stop pos)
                              {:start pos :stop pos}))
                          nil)
                 (rx/map data->selrect)
                 (rx/filter #(or (> (:width %) (/ 10 zoom))
                                 (> (:height %) (/ 10 zoom))))

                 (rx/flat-map
                  (fn [selrect]
                    (rx/of (update-selrect selrect)
                           (select-shapes-by-current-selrect preserve?))))

                 (rx/take-until stoper))
            (rx/of (update-selrect nil))))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  ([id]
   (select-shape id false))

  ([id toggle?]
   (us/verify ::us/uuid id)
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (update-in state [:workspace-local :selected]
                  (fn [selected]
                    (if-not toggle?
                      (conj (d/ordered-set) id)
                      (if (contains? selected id)
                        (disj selected id)
                        (conj selected id))))))

     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)]
         (rx/of (dwc/expand-all-parents [id] objects)))))))

(defn deselect-shape
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::deselect-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :selected] disj id))))

(defn shift-select-shapes
  ([id]
   (ptk/reify ::shift-select-shapes
     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)
             selection (-> state
                           wsh/lookup-selected
                           (conj id))]
         (-> state
             (assoc-in [:workspace-local :selected]
                       (cp/expand-region-selection objects selection))))))))

(defn select-shapes
  [ids]
  (us/verify ::ordered-set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selected] ids))

    ptk/WatchEvent
    (watch [_ state _]
       (let [objects (wsh/lookup-page-objects state)]
        (rx/of (dwc/expand-all-parents ids objects))))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id      (:current-page-id state)
            objects      (wsh/lookup-page-objects state page-id)
            new-selected (let [selected-objs
                               (->> (wsh/lookup-selected state)
                                    (map #(get objects %)))

                               frame-ids
                               (reduce #(conj %1 (:frame-id %2))
                                       #{}
                                       selected-objs)

                               common-frame-id
                               (when (= (count frame-ids) 1) (first frame-ids))]

                           (if (and common-frame-id
                                    (not= (:id common-frame-id) uuid/zero))
                             (-> (get objects common-frame-id)
                                 :shapes)
                             (->> (cp/select-toplevel-shapes objects
                                                             {:include-frames? true
                                                              :include-frame-children? false})
                                  (map :id))))

            is-not-blocked (fn [shape-id] (not (get-in state [:workspace-data
                                                              :pages-index page-id
                                                              :objects shape-id
                                                              :blocked] false)))]
        (rx/of (->> new-selected
                    (filter is-not-blocked)
                    (into lks/empty-linked-set)
                    (select-shapes)))))))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user.
  When `check-modal` the method will check if a modal is opened
  and not deselect if it's true"
  ([] (deselect-all false))

  ([check-modal]
   (ptk/reify ::deselect-all
     ptk/UpdateEvent
     (update [_ state]

       ;; Only deselect if there is no modal openned
       (cond-> state
         (or (not check-modal)
             (not (::md/modal state)))
         (update :workspace-local
                 #(-> %
                      (assoc :selected (d/ordered-set))
                      (dissoc :selected-frame))))))))

;; --- Select Shapes (By selrect)

(defn select-shapes-by-current-selrect
  [preserve?]
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state)
            initial-set (if preserve?
                          selected
                          lks/empty-linked-set)
            selrect (get-in state [:workspace-local :selrect])
            blocked? (fn [id] (get-in objects [id :blocked] false))]
        (when selrect
          (->> (uw/ask! {:cmd :selection/query
                         :page-id page-id
                         :rect selrect
                         :include-frames? true
                         :full-frame? true})
               (rx/map #(cp/clean-loops objects %))
               (rx/map #(into initial-set (filter (comp not blocked?)) %))
               (rx/map select-shapes)))))))

(defn select-inside-group
  [group-id position]

  (ptk/reify ::select-inside-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            group    (get objects group-id)
            children (map #(get objects %) (:shapes group))

            ;; We need to reverse the children because if two children
            ;; overlap we want to select the one that's over (and it's
            ;; in the later vector position
            selected (->> children
                          reverse
                           (d/seek #(geom/has-point? % position)))]
        (when selected
          (rx/of (select-shape (:id selected))))))))


;; --- Duplicate Shapes
(declare prepare-duplicate-change)
(declare prepare-duplicate-frame-change)
(declare prepare-duplicate-shape-change)

(defn update-indices
  "Fixes the indices for a set of changes after a duplication. We need to
  fix the indices to take into the account the movement of indices.

  index-map is a map that goes from parent-id => vector([id index-in-parent])"
  [changes index-map]
  (let [inc-indices
        (fn [[offset result] [id index]]
          [(inc offset) (conj result [id (+ index offset)])])

        fix-indices
        (fn [_ entry]
          (->> entry
               (sort-by second)
               (reduce inc-indices [1 []])
               (second)
               (into {})))

        objects-indices (->> index-map (d/mapm fix-indices) (vals) (reduce merge))

        update-change
        (fn [change]
          (let [index (get objects-indices (:old-id change))]
            (-> change
                (assoc :index index))))]
    (mapv update-change changes)))

(defn prepare-duplicate-changes
  "Prepare objects to paste: generate new id, give them unique names,
  move to the position of mouse pointer, and find in what frame they
  fit."
  [objects page-id unames ids delta]
  (let [unames         (volatile! unames)
        update-unames! (fn [new-name] (vswap! unames conj new-name))
        all-ids        (reduce (fn [ids-set id]
                                 (into ids-set (cons id (cp/get-children id objects))))
                               #{}
                               ids)
        ids-map        (into {} (map #(vector % (uuid/next)) all-ids))]
    (loop [ids   (seq ids)
           chgs  []]
      (if ids
        (let [id     (first ids)
              result (prepare-duplicate-change objects page-id unames update-unames! ids-map id delta)
              result (if (vector? result) result [result])]
          (recur
            (next ids)
            (into chgs result)))
        chgs))))

(defn duplicate-changes-update-indices
  "Parses the change set when duplicating to set-up the appropriate indices"
  [objects ids changes]

  (let [process-id
        (fn [index-map id]
          (let [parent-id    (get-in objects [id :parent-id])
                parent-index (cp/position-on-parent id objects)]
            (update index-map parent-id (fnil conj []) [id parent-index])))
        index-map (reduce process-id {} ids)]
    (-> changes (update-indices index-map))))

(defn- prepare-duplicate-change
  [objects page-id unames update-unames! ids-map id delta]
  (let [obj (get objects id)]
    (if (= :frame (:type obj))
      (prepare-duplicate-frame-change objects page-id unames update-unames! ids-map obj delta)
      (prepare-duplicate-shape-change objects page-id unames update-unames! ids-map obj delta (:frame-id obj) (:parent-id obj)))))

(defn- prepare-duplicate-shape-change
  [objects page-id unames update-unames! ids-map obj delta frame-id parent-id]
  (when (some? obj)
    (let [new-id      (ids-map (:id obj))
          parent-id   (or parent-id frame-id)
          name        (dwc/generate-unique-name @unames (:name obj))
          _           (update-unames! name)

          new-obj     (-> obj
                          (assoc :id new-id
                                 :name name
                                 :frame-id frame-id)
                          (dissoc :shapes)
                          (geom/move delta)
                          (d/update-when :interactions #(cti/remap-interactions % ids-map objects)))

          children-changes
          (loop [result []
                 cid  (first (:shapes obj))
                 cids (rest (:shapes obj))]
            (if (nil? cid)
              result
              (let [obj (get objects cid)
                    changes (prepare-duplicate-shape-change objects page-id unames update-unames! ids-map obj delta frame-id new-id)]
                (recur
                 (into result changes)
                 (first cids)
                 (rest cids)))))]

      (into [{:type :add-obj
              :id new-id
              :page-id page-id
              :old-id (:id obj)
              :frame-id frame-id
              :parent-id parent-id
              :ignore-touched true
              :obj new-obj}]
            children-changes))))

(defn- prepare-duplicate-frame-change
  [objects page-id unames update-unames! ids-map obj delta]
  (let [new-id   (ids-map (:id obj))
        frame-name (dwc/generate-unique-name @unames (:name obj))
        _          (update-unames! frame-name)

        sch        (->> (map #(get objects %) (:shapes obj))
                        (mapcat #(prepare-duplicate-shape-change objects page-id unames update-unames! ids-map % delta new-id new-id)))

        new-frame  (-> obj
                      (assoc :id new-id
                             :name frame-name
                             :frame-id uuid/zero
                             :shapes [])
                      (geom/move delta)
                      (d/update-when :interactions #(cti/remap-interactions % ids-map objects)))

        fch {:type :add-obj
             :old-id (:id obj)
             :page-id page-id
             :id new-id
             :frame-id uuid/zero
             :obj new-frame}]

    (into [fch] sch)))

(defn clear-memorize-duplicated
  []
  (ptk/reify ::clear-memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-local :duplicated]))))

(defn memorize-duplicated
  "When duplicate an object, remember the operation during the following seconds.
  If the user moves the duplicated object, and then duplicates it again, check
  the displacement and apply it to the third copy. This is useful for doing
  grids or cascades of cloned objects."
  [id-original id-duplicated]
  (ptk/reify ::memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :duplicated] {:id-original id-original
                                                      :id-duplicated id-duplicated}))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper (rx/filter (ptk/type? ::memorize-duplicated) stream)]
        (->> (rx/timer 10000) ;; This time may be adjusted after some user testing.
             (rx/take-until stoper)
             (rx/map clear-memorize-duplicated))))))

(defn calc-duplicate-delta
  [obj state objects]
  (let [{:keys [id-original id-duplicated]}
        (get-in state [:workspace-local :duplicated])]
    (if (and (not= id-original (:id obj))
             (not= id-duplicated (:id obj)))

      ;; The default is leave normal shapes in place, but put
      ;; new frames to the right of the original.
      (if (= (:type obj) :frame)
        (gpt/point (+ (:width obj) 50) 0)
        (gpt/point 0 0))

      (let [pt-original   (-> (get objects id-original) :selrect gpt/point)
            pt-duplicated (-> (get objects id-duplicated) :selrect gpt/point)
            pt-obj        (-> obj :selrect gpt/point)
            distance       (gpt/subtract pt-duplicated pt-original)
            new-pos        (gpt/add pt-duplicated distance)]

        (gpt/subtract new-pos pt-obj)))))

(defn duplicate-selected [move-delta?]
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [it state _]
      (when (nil? (get-in state [:workspace-local :transform]))
        (let [page-id  (:current-page-id state)
              objects  (wsh/lookup-page-objects state page-id)
              selected (wsh/lookup-selected state)
              delta    (if (and move-delta? (= (count selected) 1))
                         (let [obj (get objects (first selected))]
                           (calc-duplicate-delta obj state objects))
                         (gpt/point 0 0))

              unames   (dwc/retrieve-used-names objects)

              rchanges (->> (prepare-duplicate-changes objects page-id unames selected delta)
                            (duplicate-changes-update-indices objects selected))

              uchanges (mapv #(array-map :type :del-obj :page-id page-id :id (:id %))
                             (reverse rchanges))

              id-original (when (= (count selected) 1) (first selected))

              selected (->> rchanges
                            (filter #(selected (:old-id %)))
                            (map #(get-in % [:obj :id]))
                            (into (d/ordered-set)))

              id-duplicated (when (= (count selected) 1) (first selected))]

          (rx/of (dch/commit-changes {:redo-changes rchanges
                                      :undo-changes uchanges
                                      :origin it})
                 (select-shapes selected)
                 (memorize-duplicated id-original id-duplicated)))))))

(defn change-hover-state
  [id value]
  (ptk/reify ::change-hover-state
    ptk/UpdateEvent
    (update [_ state]
      (let [hover-value (if value #{id} #{})]
        (assoc-in state [:workspace-local :hover] hover-value)))))
