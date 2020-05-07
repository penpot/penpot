;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.notifications :as dwn]
   [uxbox.main.data.workspace.persistence :as dwp]
   [uxbox.main.data.workspace.transforms :as dwt]
   [uxbox.main.data.workspace.texts :as dwtxt]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.worker :as uw]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.snap :as snap]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt]
   [uxbox.util.transit :as t]
   [uxbox.util.webapi :as wapi]))

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)

(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

(s/def ::set-of-string
  (s/every string? :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare initialized)
(declare initialize-group-check)

;; --- Initialize Workspace

(def default-layout
  #{:sitemap
    :sitemap-pages
    :layers
    :element-options
    :rules})

(s/def ::options-mode #{:design :prototype})

(def workspace-default
  {:zoom 1
   :flags #{}
   :selected #{}
   :drawing nil
   :drawing-tool nil
   :tooltip nil
   :options-mode :design})

(def initialize-layout
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

(defn initialize
  [project-id file-id]
  (us/verify ::us/uuid project-id)
  (us/verify ::us/uuid file-id)

  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (dwp/fetch-bundle project-id file-id))

       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/mapcat (fn [_] (rx/of (dwn/initialize file-id))))
            (rx/first))

       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/map deref)
            (rx/map dwc/setup-selection-index)
            (rx/first))

       (->> stream
            (rx/filter #(= ::dwc/index-initialized %))
            (rx/map (constantly
                     (initialized project-id file-id))))))))

(defn- initialized
  [project-id file-id]
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-file
              (fn [file]
                (if (= (:id file) file-id)
                  (assoc file :initialized true)
                  file))))))

(defn finalize
  [project-id file-id]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-file :workspace-project))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwn/finalize file-id)))))


(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page  (get-in state [:workspace-pages page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (-> state
            (assoc :current-page-id page-id   ; mainly used by events
                   :workspace-local local
                   :workspace-page (dissoc page :data)
                   :workspace-snap-data (snap/initialize-snap-data (get-in page [:data :objects])))
            (assoc-in [:workspace-data page-id] (:data page)))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwp/initialize-page-persistence page-id)
             initialize-group-check))))

(defn finalize-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-local state)]
        (-> state
            (assoc-in [:workspace-cache page-id] local)
            (update :workspace-data dissoc page-id))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of ::dwp/finalize))))

(declare adjust-group-shapes)

(def initialize-group-check
  (ptk/reify ::initialize-group-check
    ptk/WatchEvent
    (watch [_ state stream]
      ;; TODO: add stoper
      (->> stream
           (rx/filter #(satisfies? dwc/IUpdateGroup %))
           (rx/map #(adjust-group-shapes (dwc/get-ids %)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn adjust-group-shapes
  [ids]
  (ptk/reify ::adjust-group-shapes
    dwc/IBatchedChange

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            groups-to-adjust (->> ids
                                  (mapcat #(reverse (cp/get-all-parents % objects)))
                                  (map #(get objects %))
                                  (filter #(= (:type %) :group))
                                  (map #(:id %))
                                  distinct)
            update-group
            (fn [state group]
              (let [objects (get-in state [:workspace-data page-id :objects])
                    group-center (geom/center group)
                    group-objects (->> (:shapes group)
                                       (map #(get objects %))
                                       (map #(-> %
                                                 (assoc :modifiers
                                                        (dwt/rotation-modifiers group-center % (- (:rotation group 0))))
                                                 (geom/transform-shape))))
                    selrect (geom/selection-rect group-objects)]

                ;; Rotate the group shape change the data and rotate back again
                (-> group
                    (assoc-in [:modifiers :rotation] (- (:rotation group)))
                    (geom/transform-shape)
                    (merge (select-keys selrect [:x :y :width :height]))
                    (assoc-in [:modifiers :rotation] (:rotation group))
                    (geom/transform-shape))))

            reduce-fn
            #(update-in %1 [:workspace-data page-id :objects %2] (partial update-group %1))]

        (reduce reduce-fn state groups-to-adjust)))))


;; --- Toggle layout flag

(defn toggle-layout-flag
  [& flags]
  (ptk/reify ::toggle-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (let [reduce-fn
            (fn [state flag]
              (update state :workspace-layout
                      (fn [flags]
                        (if (contains? flags flag)
                          (disj flags flag)
                          (conj flags flag)))))]
        (reduce reduce-fn state flags)))))

;; --- Set element options mode

(defn set-options-mode
  [mode]
  (us/assert ::options-mode mode)
  (ptk/reify ::set-options-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :options-mode] mode))))

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-local :tooltip] content)
        (assoc-in state [:workspace-local :tooltip] nil)))))

;; --- Zoom Management

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(nth c/zoom-levels
                           (+ (d/index-of c/zoom-levels %) 1)
                           (last c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(nth c/zoom-levels
                           (- (d/index-of c/zoom-levels %) 1)
                           (first c/zoom-levels))]
        (update-in state [:workspace-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 1))))

(def zoom-to-50
  (ptk/reify ::zoom-to-50
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 0.5))))

(def zoom-to-200
  (ptk/reify ::zoom-to-200
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :zoom] 2))))

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
               :width (- end-x start-x)
               :height (- end-y start-y)}))]
    (ptk/reify ::handle-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(or (interrupt? %)
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
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::select-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :selected]
                 (fn [selected]
                   (if (contains? selected id)
                     (disj selected id)
                     (conj selected id)))))))

(defn select-shapes
  [ids]
  (us/verify ::set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selected] ids))))

(def deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user."
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local #(-> %
                                          (assoc :selected #{})
                                          (dissoc :selected-frame))))))


;; --- Add shape to Workspace

(defn- retrieve-used-names
  [objects]
  (into #{} (map :name) (vals objects)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[match p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn- generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (let [[prefix initial] (extract-numeric-suffix basename)]
    (loop [counter initial]
      (let [candidate (str prefix "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(declare start-edition-mode)

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            id       (uuid/next)
            shape    (geom/setup-proportions attrs)

            unames   (retrieve-used-names objects)
            name     (generate-unique-name unames (:name shape))

            frames   (cp/select-frames objects)

            frame-id (if (= :frame (:type shape))
                       uuid/zero
                       (dwc/calculate-frame-overlap frames shape))

            shape    (merge
                      (if (= :frame (:type shape))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)
                      (assoc shape
                             :id id
                             :name name
                             :frame-id frame-id))

            rchange  {:type :add-obj
                      :id id
                      :frame-id frame-id
                      :obj shape}
            uchange  {:type :del-obj
                      :id id}]

        (rx/of (dwc/commit-changes [rchange] [uchange] {:commit-local? true})
               (select-shapes #{id})
               (when (= :text (:type attrs))
                 (start-edition-mode id)))))))


;; --- Duplicate Shapes

(declare prepare-duplicate-changes)
(declare prepare-duplicate-change)
(declare prepare-duplicate-frame-change)
(declare prepare-duplicate-shape-change)

(def ^:private change->name #(get-in % [:obj :name]))

(defn- prepare-duplicate-changes
  "Prepare objects to paste: generate new id, give them unique names,
  move to the position of mouse pointer, and find in what frame they
  fit."
  [objects names ids delta]
  (loop [names names
         chgs []
         id   (first ids)
         ids  (rest ids)]
    (if (nil? id)
      chgs
      (let [result (prepare-duplicate-change objects names id delta)
            result (if (vector? result) result [result])]
        (recur
         (into names (map change->name) result)
         (into chgs result)
         (first ids)
         (rest ids))))))

(defn- prepare-duplicate-change
  [objects names id delta]
  (let [obj (get objects id)]
    (if (= :frame (:type obj))
      (prepare-duplicate-frame-change objects names obj delta)
      (prepare-duplicate-shape-change objects names obj delta nil nil))))

(defn- prepare-duplicate-shape-change
  [objects names obj delta frame-id parent-id]
  (let [id (uuid/next)
        name (generate-unique-name names (:name obj))
        renamed-obj (assoc obj :id id :name name)
        moved-obj (geom/move renamed-obj delta)
        frames (cp/select-frames objects)
        frame-id (if frame-id
                   frame-id
                   (dwc/calculate-frame-overlap frames moved-obj))

        parent-id (or parent-id frame-id)

        children-changes
        (loop [names names
               result []
               cid (first (:shapes obj))
               cids (rest (:shapes obj))]
          (if (nil? cid)
            result
            (let [obj (get objects cid)
                  changes (prepare-duplicate-shape-change objects names obj delta frame-id id)]
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
            :old-id (:id obj)
            :frame-id frame-id
            :parent-id parent-id
            :obj (dissoc reframed-obj :shapes)}]
          children-changes)))

(defn- prepare-duplicate-frame-change
  [objects names obj delta]
  (let [frame-id   (uuid/next)
        frame-name (generate-unique-name names (:name obj))
        sch (->> (map #(get objects %) (:shapes obj))
                 (mapcat #(prepare-duplicate-shape-change objects names % delta frame-id frame-id)))

        renamed-frame (-> obj
                          (assoc :id frame-id)
                          (assoc :name frame-name)
                          (assoc :frame-id uuid/zero)
                          (dissoc :shapes))

        moved-frame (geom/move renamed-frame delta)

        fch {:type :add-obj
             :old-id (:id obj)
             :id frame-id
             :frame-id uuid/zero
             :obj moved-frame}]

    (into [fch] sch)))

(declare select-shapes)

(def duplicate-selected
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            selected (get-in state [:workspace-local :selected])
            objects (get-in state [:workspace-data page-id :objects])
            delta (gpt/point 0 0)
            unames (retrieve-used-names objects)

            rchanges (prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (select-shapes selected))))))


;; --- Select Shapes (By selrect)

(def select-shapes-by-current-selrect
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (get-in state [:workspace-page :id])
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
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            group (get objects group-id)
            children (map #(get objects %) (:shapes group))
            selected (->> children (filter #(geom/has-point? % position)) first)]
        (cond-> state
          selected (assoc-in [:workspace-local :selected] #{(:id selected)}))))))

;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-shape
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (update-in state [:workspace-data pid :objects id] merge attrs)))))

(defn update-shape-recursive
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (letfn [(update-shape [shape]
            (cond-> (merge shape attrs)
              (and (= :text (:type shape))
                   (string? (:fill-color attrs)))
              (dwtxt/impl-update-shape-attrs {:fill (:fill-color attrs)})))]
    (ptk/reify ::update-shape
      dwc/IBatchedChange
      dwc/IUpdateGroup
      (get-ids [_] [id])

      ptk/UpdateEvent
      (update [_ state]
        (let [page-id (:current-page-id state)
              grouped #{:frame :group}]
          (update-in state [:workspace-data page-id :objects]
                     (fn [objects]
                       (->> (d/concat [id] (cp/get-children id objects))
                            (map #(get objects %))
                            (remove #(grouped (:type %)))
                            (reduce #(update %1 (:id %2) update-shape) objects)))))))))

;; --- Update Page Options

(defn update-options
  [opts]
  (us/verify ::cp/options opts)
  (ptk/reify ::update-options
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (update-in state [:workspace-data pid :options] merge opts)))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from (map #(update-shape % attrs) selected))))))

(defn update-color-on-selected-shapes
  [{:keys [fill-color stroke-color] :as attrs}]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-color-on-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            page-id  (get-in state [:workspace-page :id])]
        (->> (rx/from selected)
             (rx/map (fn [id]
                       (update-shape-recursive id attrs))))))))

;; --- Shape Movement (using keyboard shorcuts)

(declare initial-selection-align)

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::loc  #{:up :down :bottom :top})

;; --- Delete Selected
(defn- delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            session-id (:session-id state)
            objects (get-in state [:workspace-data page-id :objects])
            cpindex (cp/calculate-child-parent-map objects)

            del-change #(array-map :type :del-obj :id %)

            rchanges
            (reduce (fn [res id]
                      (let [chd (cp/get-children id objects)]
                        (into res (d/concat
                                   (mapv del-change (reverse chd))
                                   [(del-change id)]))))
                    []
                    ids)

            uchanges
            (mapv (fn [id]
                    (let [obj (get objects id)]
                     {:type :add-obj
                      :id id
                      :frame-id (:frame-id obj)
                      :parent-id (get cpindex id)
                      :obj obj}))
                  (reverse (map :id rchanges)))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            lookup   #(get-in state [:workspace-data page-id :objects %])
            selected (get-in state [:workspace-local :selected])

            shapes (map lookup selected)
            shape? #(not= (:type %) :frame)]
        (rx/of (delete-shapes selected))))))


;; --- Rename Shape

(defn rename-shape
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-shape
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id] assoc :name name)))))

;; --- Shape Vertical Ordering

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
  (ptk/reify ::vertical-order-selected-shpes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (seq (get-in state [:workspace-local :selected]))

            rchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :rel-order :id id :loc loc}]}))
                           selected)
            uchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])
                                   shapes (get-in objects [frame-id :shapes])
                                   cindex (d/index-of shapes id)]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :abs-order :id id :index cindex}]}))
                           selected)]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Change Shape Order (D&D Ordering)

;; TODO: pending UNDO

(defn relocate-shape
  [id ref-id index]
  (us/verify ::us/uuid id)
  (us/verify ::us/uuid ref-id)
  (us/verify number? index)

  (ptk/reify ::relocate-shape
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            selected (get-in state [:workspace-local :selected])
            objects (get-in state [:workspace-data page-id :objects])
            parent-id (cp/get-parent ref-id objects)]
        (rx/of (dwc/commit-changes [{:type :mov-objects
                                 :parent-id parent-id
                                 :index index
                                 :shapes (vec selected)}]
                               []
                               {:commit-local? true}))))))

;; --- Change Page Order (D&D Ordering)

(defn relocate-page
  [id index]
  (ptk/reify ::relocate-pages
    ptk/UpdateEvent
    (update [_ state]
      (let [pages (get-in state [:workspace-file :pages])
            [before after] (split-at index pages)
            p? (partial = id)
            pages' (d/concat []
                             (remove p? before)
                             [id]
                             (remove p? after))]
        (assoc-in state [:workspace-file :pages] pages')))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)]
        (->> (rp/mutation! :reorder-pages {:page-ids (:pages file)
                                           :file-id (:id file)})
             (rx/ignore))))))

;; --- Shape / Selection Alignment and Distribution

(declare align-object-to-frame)
(declare align-objects-list)

(defn align-objects
  [axis]
  (us/verify ::geom/align-axis axis)
  (ptk/reify :align-objects
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            moved-objs (if (= 1 (count selected))
                         (align-object-to-frame objects (first selected) axis)
                         (align-objects-list objects selected axis))
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))

(defn align-object-to-frame
  [objects object-id axis]
  (let [object (get objects object-id)
        frame (get objects (:frame-id object))]
    (geom/align-to-rect object frame axis objects)))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (geom/selection-rect selected-objs)]
    (mapcat #(geom/align-to-rect % rect axis objects) selected-objs)))

(defn distribute-objects
  [axis]
  (us/verify ::geom/dist-axis axis)
  (ptk/reify :align-objects
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            selected-objs (map #(get objects %) selected)
            moved-objs (geom/distribute-space selected-objs axis objects)
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))

;; --- Start shape "edition mode"

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :edition] id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :edition))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :drawing-tool :drawing))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-local assoc :drawing-tool tool :drawing data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [cancel-event? (fn [event]
                             (interrupt? event))
             stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (->> (rx/filter cancel-event? stream)
              (rx/take 1)
              (rx/map (constantly clear-drawing))
              (rx/take-until stoper)))))))

;; --- Update Dimensions

(defn update-rect-dimensions
  [id attr value]
  (us/verify ::us/uuid id)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-rect-dimensions
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/resize-rect attr value)))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            shape (get-in state [:workspace-data page-id :objects id])]
        (if (:proportion-lock shape)
          (assoc-in state [:workspace-data page-id :objects id :proportion-lock] false)
          (->> (geom/assign-proportions (assoc shape :proportion-lock true))
               (assoc-in state [:workspace-data page-id :objects id])))))))

;; --- Update Shape Position

(s/def ::x number?)
(s/def ::y number?)
(s/def ::position
  (s/keys :opt-un [::x ::y]))

(defn update-position
  [id position]
  (us/verify ::us/uuid id)
  (us/verify ::position position)
  (ptk/reify ::update-position
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            shape (get-in state [:workspace-data page-id :objects id])
            current-position (gpt/point (:x shape) (:y shape))
            position (gpt/point (or (:x position) (:x shape)) (or (:y position) (:y shape)))
            displacement (gmt/translate-matrix (gpt/subtract position current-position))]
        (rx/of (dwt/set-modifiers [id] {:displacement displacement})
               (dwt/apply-modifiers [id]))))))

;; --- Path Modifications

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  (us/verify ::us/uuid id)
  (us/verify ::us/integer index)
  (us/verify gpt/point? delta)
  (ptk/reify ::update-path
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id :segments index]
                   gpt/add delta)))))

;; --- Shape attrs (Layers Sidebar)

(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(def collapse-all
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))

(defn recursive-assign
  "A helper for assign recursively a shape attr."
  [id attr value]
  (ptk/reify ::recursive-assign
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:workspace-page :id])
            objects (get-in state [:workspace-data page-id :objects])
            childs (cp/get-children id objects)]
        (update-in state [:workspace-data page-id :objects]
                   (fn [objects]
                     (reduce (fn [objects id]
                               (assoc-in objects [id attr] value))
                             objects
                             (conj childs id))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::go-to-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [project-id (get-in state [:workspace-project :id])
            file-id (get-in state [:workspace-page :file-id])
            path-params {:file-id file-id :project-id project-id}
            query-params {:page-id page-id}]
        (rx/of (rt/nav :workspace path-params query-params))))))

(def go-to-file
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)

            file-id (:id file)
            project-id (:project-id file)
            page-ids (:pages file)

            path-params {:project-id project-id :file-id file-id}
            query-params {:page-id (first page-ids)}]
        (rx/of (rt/nav :workspace path-params query-params))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::point gpt/point?)

(defn show-context-menu
  [{:keys [position] :as params}]
  (us/verify ::point position)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] {:position position}))))

(defn show-shape-context-menu
  [{:keys [position shape] :as params}]
  (us/verify ::point position)
  (us/verify ::cp/minimal-shape shape)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace-local :selected])
            selected (cond
                       (empty? selected)
                       (conj selected (:id shape))

                       (contains? selected (:id shape))
                       selected

                       :else
                       #{(:id shape)})
            mdata {:position position
                   :selected selected
                   :shape shape}]
        (-> state
            (assoc-in [:workspace-local :context-menu] mdata)
            (assoc-in [:workspace-local :selected] selected))))))

(def hide-context-menu
  (ptk/reify ::hide-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] nil))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clipboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def copy-selected
  (letfn [(prepare-selected [objects selected]
            (let [data (reduce #(prepare %1 objects %2) {} selected)]
              {:type :copied-shapes
               :selected selected
               :objects data}))

          (prepare [result objects id]
            (let [obj (get objects id)]
              (as-> result $$
                (assoc $$ id obj)
                (reduce #(prepare %1 objects %2) $$ (:shapes obj)))))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state stream]
        (let [page-id (:current-page-id state)
              objects (get-in state [:workspace-data page-id :objects])
              selected (get-in state [:workspace-local :selected])
              cdata    (prepare-selected objects selected)]
          (->> (t/encode cdata)
               (wapi/write-to-clipboard)
               (rx/from)
               (rx/catch on-copy-error)
               (rx/ignore)))))))

(defn- paste-impl
  [{:keys [selected objects] :as data}]
  (ptk/reify ::paste-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected-objs (map #(get objects %) selected)
            wrapper (geom/selection-rect selected-objs)
            orig-pos (gpt/point (:x1 wrapper) (:y1 wrapper))
            mouse-pos @ms/mouse-position
            delta (gpt/subtract mouse-pos orig-pos)

            page-id (:current-page-id state)
            unames (-> (get-in state [:workspace-data page-id :objects])
                       (retrieve-used-names))

            rchanges (prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (select-shapes selected))))))

(def paste
  (ptk/reify ::paste
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/from (wapi/read-from-clipboard))
           (rx/map t/decode)
           (rx/filter #(= :copied-shapes (:type %)))
           (rx/map #(select-keys % [:selected :objects]))
           (rx/map paste-impl)
           (rx/catch (fn [err]
                       (js/console.error "Clipboard error:" err)
                       (rx/empty)))))))


;; --- Change Page Order (D&D Ordering)

(defn change-page-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (ptk/reify ::change-page-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages id])
            pages (get-in state [:projects (:project-id page) :pages])
            pages (into [] (remove #(= % id)) pages)
            [before after] (split-at index pages)
            pages (vec (concat before [id] after))]
        (assoc-in state [:projects (:project-id page) :pages] pages)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-shape
  [id frame-id selected selection-rect]
  {:id id
   :type :group
   :name (name (gensym "Group-"))
   :shapes []
   :frame-id frame-id
   :x (:x selection-rect)
   :y (:y selection-rect)
   :width (:width selection-rect)
   :height (:height selection-rect)})

(def create-group
  (ptk/reify ::create-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (uuid/next)
            selected (get-in state [:workspace-local :selected])]
        (when (not-empty selected)
          (let [page-id (get-in state [:workspace-page :id])
                objects (get-in state [:workspace-data page-id :objects])
                selected-objects (map (partial get objects) selected)
                selection-rect (geom/selection-rect selected-objects)
                frame-id (-> selected-objects first :frame-id)
                group-shape (group-shape id frame-id selected selection-rect)
                frame-children (get-in objects [frame-id :shapes])
                index-frame (->> frame-children
                                 (map-indexed vector)
                                 (filter #(selected (second %)))
                                 (ffirst))

                rchanges [{:type :add-obj
                           :id id
                           :frame-id frame-id
                           :obj group-shape
                           :index index-frame}
                          {:type :mov-objects
                           :parent-id id
                           :shapes (vec selected)}]
                uchanges [{:type :mov-objects
                           :parent-id frame-id
                           :shapes (vec selected)}
                          {:type :del-obj
                           :id id}]]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (select-shapes #{id}))))))))

(def remove-group
  (ptk/reify ::remove-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (= (:type group) :group))
          (let [shapes    (:shapes group)
                parent-id (cp/get-parent group-id objects)
                parent    (get objects parent-id)
                index-in-parent (->> (:shapes parent)
                                     (map-indexed vector)
                                     (filter #(#{group-id} (second %)))
                                     (ffirst))
                rchanges [{:type :mov-objects
                           :parent-id parent-id
                           :shapes shapes
                           :index index-in-parent}]
                uchanges [{:type :add-obj
                           :id group-id
                           :frame-id (:frame-id group)
                           :obj (assoc group :shapes [])}
                          {:type :mov-objects
                           :parent-id group-id
                           :shapes shapes}
                          {:type :mov-objects
                           :parent-id parent-id
                           :shapes [group-id]
                           :index index-in-parent}]]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Transform

(def start-rotate dwt/start-rotate)
(def start-resize dwt/start-resize)
(def start-move-selected dwt/start-move-selected)
(def move-selected dwt/move-selected)

(def set-rotation dwt/set-rotation)
(def set-modifiers dwt/set-modifiers)
(def apply-modifiers dwt/apply-modifiers)

;; Persistence

(def upload-image dwp/upload-image)
(def rename-page dwp/rename-page)
(def delete-page dwp/delete-page)
(def create-empty-page dwp/create-empty-page)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts impl https://github.com/ccampbell/mousetrap

(def shortcuts
  {"ctrl+shift+m" #(st/emit! (toggle-layout-flag :sitemap))
   "ctrl+shift+i" #(st/emit! (toggle-layout-flag :libraries))
   "ctrl+shift+l" #(st/emit! (toggle-layout-flag :layers))
   "+" #(st/emit! increase-zoom)
   "-" #(st/emit! decrease-zoom)
   "ctrl+g" #(st/emit! create-group)
   "ctrl+shift+g" #(st/emit! remove-group)
   "shift+0" #(st/emit! zoom-to-50)
   "shift+1" #(st/emit! reset-zoom)
   "shift+2" #(st/emit! zoom-to-200)
   "ctrl+d" #(st/emit! duplicate-selected)
   "ctrl+z" #(st/emit! dwc/undo)
   "ctrl+shift+z" #(st/emit! dwc/redo)
   "ctrl+y" #(st/emit! dwc/redo)
   "ctrl+q" #(st/emit! dwc/reinitialize-undo)
   "ctrl+b" #(st/emit! (select-for-drawing :rect))
   "ctrl+e" #(st/emit! (select-for-drawing :circle))
   "ctrl+t" #(st/emit! (select-for-drawing :text))
   "ctrl+c" #(st/emit! copy-selected)
   "ctrl+v" #(st/emit! paste)
   "escape" #(st/emit! :interrupt deselect-all)
   "del" #(st/emit! delete-selected)
   "ctrl+up" #(st/emit! (vertical-order-selected :up))
   "ctrl+down" #(st/emit! (vertical-order-selected :down))
   "ctrl+shift+up" #(st/emit! (vertical-order-selected :top))
   "ctrl+shift+down" #(st/emit! (vertical-order-selected :bottom))
   "shift+up" #(st/emit! (dwt/move-selected :up true))
   "shift+down" #(st/emit! (dwt/move-selected :down true))
   "shift+right" #(st/emit! (dwt/move-selected :right true))
   "shift+left" #(st/emit! (dwt/move-selected :left true))
   "up" #(st/emit! (dwt/move-selected :up false))
   "down" #(st/emit! (dwt/move-selected :down false))
   "right" #(st/emit! (dwt/move-selected :right false))
   "left" #(st/emit! (dwt/move-selected :left false))})

