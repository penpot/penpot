;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.selection
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.streams :as ms]
   [app.main.worker :as uw]))

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

(def handle-selection
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
    (ptk/reify ::handle-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(or (dwc/interrupt? %)
                                     (ms/mouse-up? %))
                                stream)]
          (rx/concat
           (rx/of deselect-all)
           (->> ms/mouse-position
                (rx/scan (fn [data pos]
                           (if data
                             (assoc data :stop pos)
                             {:start pos :stop pos}))
                         nil)
                (rx/map data->selrect)
                (rx/filter #(or (> (:width %) 10)
                                (> (:height %) 10)))
                (rx/map update-selrect)
                (rx/take-until stoper))
           (rx/of select-shapes-by-current-selrect)))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  ([id] (select-shape id false))
  ([id toggle?]
   (us/verify ::us/uuid id)
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (update-in state [:workspace-local :selected]
                  (fn [selected]
                    (if-not toggle?
                      (conj selected id)
                      (if (contains? selected id)
                        (disj selected id)
                        (conj selected id))))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (:current-page-id state)
             objects (dwc/lookup-page-objects state page-id)]
         (rx/of (dwc/expand-all-parents [id] objects)))))))

(defn select-shapes
  [ids]
  (us/verify ::ordered-set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selected] ids))

    ptk/WatchEvent
    (watch [_ state stream]
       (let [page-id (:current-page-id state)
             objects (dwc/lookup-page-objects state page-id)]
        (rx/of (dwc/expand-all-parents ids objects))))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local #(-> %
                                          (assoc :selected (d/ordered-set))
                                          (dissoc :selected-frame))))))

;; --- Select Shapes (By selrect)

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            selrect (get-in state [:workspace-local :selrect])]
        (rx/merge
         (rx/of (update-selrect nil))
         (when selrect
           (->> (uw/ask! {:cmd :selection/query
                          :page-id page-id
                          :rect selrect})
                (rx/map select-shapes))))))))

(defn select-inside-group
  [group-id position]
  (ptk/reify ::select-inside-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            group    (get objects group-id)
            children (map #(get objects %) (:shapes group))

            ;; We need to reverse the children because if two children
            ;; overlap we want to select the one that's over (and it's
            ;; in the later vector position
            selected (->> children
                          reverse
                          (d/seek #(geom/has-point? % position)))]
        (when selected
          (rx/of deselect-all (select-shape (:id selected))))))))


;; --- Group shapes

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (map #(get objects %))
       (filter #(not= :frame (:type %)))
       (map #(assoc % ::index (cph/position-on-parent (:id %) objects)))
       (sort-by ::index)))

(defn- make-group
  [shapes prefix keep-name]
  (let [selrect   (geom/selection-rect shapes)
        frame-id  (-> shapes first :frame-id)
        parent-id (-> shapes first :parent-id)
        group-name (if (and keep-name
                            (= (count shapes) 1)
                            (= (:type (first shapes)) :group))
                     (:name (first shapes))
                     (name (gensym prefix)))]
    (-> (cp/make-minimal-group frame-id selrect group-name)
        (geom/setup selrect)
        (assoc :shapes (map :id shapes)))))

(defn prepare-create-group
  [page-id shapes prefix keep-name]
  (let [group (make-group shapes prefix keep-name)
        rchanges [{:type :add-obj
                   :id (:id group)
                   :page-id page-id
                   :frame-id (:frame-id (first shapes))
                   :parent-id (:parent-id (first shapes))
                   :obj group
                   :index (::index (first shapes))}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes (map :id shapes)}]

        uchanges (conj
                   (map (fn [obj] {:type :mov-objects
                                   :page-id page-id
                                   :parent-id (:parent-id obj)
                                   :index (::index obj)
                                   :shapes [(:id obj)]})
                        shapes)
                   {:type :del-obj
                    :id (:id group)
                    :page-id page-id})]
    [group rchanges uchanges]))

(defn prepare-remove-group
  [page-id group objects]
  (let [shapes    (:shapes group)
        parent-id (cph/get-parent (:id group) objects)
        parent    (get objects parent-id)
        index-in-parent (->> (:shapes parent)
                             (map-indexed vector)
                             (filter #(#{(:id group)} (second %)))
                             (ffirst))
        rchanges [{:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes shapes
                   :index index-in-parent}]
        uchanges [{:type :add-obj
                   :page-id page-id
                   :id (:id group)
                   :frame-id (:frame-id group)
                   :obj (assoc group :shapes [])}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes shapes}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes [(:id group)]
                   :index index-in-parent}]]
    [rchanges uchanges]))


;; --- Duplicate Shapes
(declare prepare-duplicate-change)
(declare prepare-duplicate-frame-change)
(declare prepare-duplicate-shape-change)

(def ^:private change->name #(get-in % [:obj :name]))

(defn- prepare-duplicate-changes
  "Prepare objects to paste: generate new id, give them unique names,
  move to the position of mouse pointer, and find in what frame they
  fit."
  [objects page-id names ids delta]
  (loop [names names
         ids   (seq ids)
         chgs  []]
    (if ids
      (let [id     (first ids)
            result (prepare-duplicate-change objects page-id names id delta)
            result (if (vector? result) result [result])]
        (recur
         (into names (map change->name) result)
         (next ids)
         (into chgs result)))
      chgs)))

(defn- prepare-duplicate-change
  [objects page-id names id delta]
  (let [obj (get objects id)]
    (if (= :frame (:type obj))
      (prepare-duplicate-frame-change objects page-id names obj delta)
      (prepare-duplicate-shape-change objects page-id names obj delta (:frame-id obj) (:parent-id obj)))))

(defn- prepare-duplicate-shape-change
  [objects page-id names obj delta frame-id parent-id]
  (let [id          (uuid/next)
        name        (dwc/generate-unique-name names (:name obj))
        renamed-obj (assoc obj :id id :name name)
        moved-obj   (geom/move renamed-obj delta)
        frames      (cph/select-frames objects)
        frame-id    (if frame-id
                      frame-id
                      (dwc/calculate-frame-overlap frames moved-obj))

        parent-id   (or parent-id frame-id)

        children-changes
        (loop [names names
               result []
               cid (first (:shapes obj))
               cids (rest (:shapes obj))]
          (if (nil? cid)
            result
            (let [obj (get objects cid)
                  changes (prepare-duplicate-shape-change objects page-id names obj delta frame-id id)]
              (recur
               (into names (map change->name changes))
               (into result changes)
               (first cids)
               (rest cids)))))

        reframed-obj (-> moved-obj
                         (assoc  :frame-id frame-id)
                         (dissoc :shapes))]
    (into [{:type :add-obj
            :id id
            :page-id page-id
            :old-id (:id obj)
            :frame-id frame-id
            :parent-id parent-id
            :obj (dissoc reframed-obj :shapes)}]
          children-changes)))

(defn- prepare-duplicate-frame-change
  [objects page-id names obj delta]
  (let [frame-id   (uuid/next)
        frame-name (dwc/generate-unique-name names (:name obj))
        sch        (->> (map #(get objects %) (:shapes obj))
                        (mapcat #(prepare-duplicate-shape-change objects page-id names % delta frame-id frame-id)))

        frame     (-> obj
                      (assoc :id frame-id)
                      (assoc :name frame-name)
                      (assoc :frame-id uuid/zero)
                      (dissoc :shapes)
                      (geom/move delta))

        fch {:type :add-obj
             :old-id (:id obj)
             :page-id page-id
             :id frame-id
             :frame-id uuid/zero
             :obj frame}]

    (into [fch] sch)))

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)

            selected (get-in state [:workspace-local :selected])
            delta    (gpt/point 0 0)
            unames   (dwc/retrieve-used-names objects)

            rchanges (prepare-duplicate-changes objects page-id unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :page-id page-id :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into (d/ordered-set)))]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (select-shapes selected))))))

(defn change-hover-state
  [id value]
  (letfn [(update-hover [items]
            (if value
              (conj items id)
              (disj items id)))]
    (ptk/reify ::change-hover-state
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace-local :hover] (fnil update-hover #{}))))))
