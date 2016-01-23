(ns uxbox.data.workspace
  (:require [bouncer.validators :as v]
            [beicon.core :as rx]
            [uxbox.shapes :as sh]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [uxbox.xforms :as xf]
            [uxbox.shapes :as shapes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +shape-props-schema+
  {:x [v/integer]
   :y [v/integer]
   :width [v/integer]
   :height [v/integer]})

(def ^:static +shape-schema+
  {:x [v/integer]
   :y [v/integer]
   :width [v/integer]
   :height [v/integer]
   :type [v/required sc/shape-type]})

(def ^:static +shape-update-size-schema+
  {:width [v/integer]
   :height [v/integer]
   :lock [v/boolean]})

(def ^:static +shape-update-fill-schema+
  {:fill [sc/color]
   :opacity [v/number]})

(def ^:static +shape-update-stroke-schema+
  {:color [sc/color]
   :opacity [v/number]})

(def ^:static +shape-update-position-schema+
  {:x [v/integer]
   :y [v/integer]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events (explicit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initialize
  "Initialize the workspace state."
  [projectid pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [s {:project projectid
               :toolboxes #{:layers}
               :flags #{}
               :drawing nil
               :selected #{}
               :page pageid}]
        (assoc state :workspace s)))))

(defn toggle-tool
  "Toggle the enabled flag of the specified tool."
  [key]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [flags (get-in state [:workspace :flags])]
        (if (contains? flags key)
          (assoc-in state [:workspace :flags] (disj flags key))
          (assoc-in state [:workspace :flags] (conj flags key)))))))

(defn toggle-toolbox
  "Toggle the visibility flag of the specified toolbox."
  [toolname]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [toolboxes (get-in state [:workspace :toolboxes])]
        (assoc-in state [:workspace :toolboxes]
                  (if (contains? toolboxes toolname)
                    (disj toolboxes toolname)
                    (conj toolboxes toolname)))))))

(defn select-for-drawing
  "Mark a shape selected for drawing in the canvas."
  [shape]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if shape
        (assoc-in state [:workspace :drawing] shape)
        (update-in state [:workspace] dissoc :drawing)))))

(defn select-shape
  "Mark a shape selected for drawing in the canvas."
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (if (contains? selected id)
          (update-in state [:workspace :selected] disj id)
          (update-in state [:workspace :selected] conj id))))))

(defn select-shapes
  "Select shapes that matches the select rect."
  [selrect]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [pageid (get-in state [:workspace :page])
            xf (comp
                (filter #(= (:page %) pageid))
                (remove :hidden)
                (remove :blocked)
                (filter #(sh/contained-in? % selrect))
                (map :id))]
        (->> (into #{} xf (vals (:shapes-by-id state)))
             (assoc-in state [:workspace :selected]))))))

(defn add-shape
  "Create and add shape to the current selected page."
  [shape props]
  (sc/validate! +shape-schema+ shape)
  (sc/validate! +shape-props-schema+ props)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [sid (random-uuid)
            pid (get-in state [:workspace :page])
            shape (merge shape props {:id sid :page pid})]
        (as-> state $
          (update-in $ [:pages-by-id pid :shapes] conj sid)
          (assoc-in $ [:shapes-by-id sid] shape))))))


(defn delete-shape
  "Remove the shape using its id."
  [id]
  (letfn [(dissoc-group [state {:keys [id] :as shape}]
            (let [state (update-in state [:shapes-by-id] dissoc id)]
              (->> (:items shape)
                   (map #(get-in state [:shapes-by-id %]))
                   (reduce dissoc-from-index state))))

          (dissoc-icon [state {:keys [id] :as shape}]
            (update-in state [:shapes-by-id] dissoc id))

          (dissoc-from-group [state {:keys [id group] :as shape}]
            (if-let [group' (get-in state [:shapes-by-id group])]
              (as-> (:items group') $
                (into [] (remove #(= % id) $))
                (assoc-in state [:shapes-by-id group :items] $))
              state))

          (dissoc-from-page [state {:keys [page id] :as shape}]
            (as-> (get-in state [:pages-by-id page :shapes]) $
              (into [] (remove #(= % id) $))
              (assoc-in state [:pages-by-id page :shapes] $)))

          (dissoc-from-index [state shape]
            (case (:type shape)
              :builtin/icon (dissoc-icon state shape)
              :builtin/group (dissoc-group state shape)))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [shape (get-in state [:shapes-by-id id])]
          (as-> state $
            (dissoc-from-page $ shape)
            (dissoc-from-group $ shape)
            (dissoc-from-index $ shape)))))))

(defn move-shape
  "Mark a shape selected for drawing in the canvas."
  [sid [dx dy :as delta]]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (update-in state [:shapes-by-id sid] shapes/-move {:dx dx :dy dy})))))

(defn update-shape-rotation
  [sid rotation]
  {:pre [(number? rotation)
         (>= rotation 0)
         (>= 360 rotation)]}
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 shapes/-rotate rotation))))

(defn update-shape-size
  [sid {:keys [width height lock] :as opts}]
  (sc/validate! +shape-update-size-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            size (select-keys shape [:width :height])
            size (merge size
                        (when width {:width width})
                        (when height {:height height}))]
        (update-in state [:shapes-by-id sid]
                   shapes/-resize size)))))

(defn update-shape-position
  [sid {:keys [x y] :as opts}]
  (sc/validate! +shape-update-position-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when x {:x x})
                 (when y {:y y})))))

(defn rebuild-group-size
  [id]
  (letfn [(update-shape-pos [state {:keys [id x y] :as data}]
            (update-in state [:shapes-by-id id] assoc :x x :y y))

          (update-group-size [shape shapes]
            (let [{:keys [width height]} (sh/group-dimensions shapes)]
              (assoc shape
                     :width width
                     :height height
                     :view-box [0 0 width height])))

          (update-group [shape shapes x y]
            (-> shape
                (update-group-size shapes)
                (sh/translate-coords x y +)))]

    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [shape (get-in state [:shapes-by-id id])
              shapes (map #(get-in state [:shapes-by-id %]) (:items shape))
              ;; shapes (->> (:items shape)
              ;;             (map #(get-in state [:shapes-by-id %]))
              ;;             (map (fn [v] (merge v (sh/container-rect v)))))
              x (apply min (map :x shapes))
              y (apply min (map :y shapes))
              shapes (map #(sh/translate-coords % x y) shapes)]
          (as-> state $
            (reduce update-shape-pos $ shapes)
            (update-in $ [:shapes-by-id id] #(update-group % shapes x y))))))))

;; TODO: rename fill to "color" for consistency.

(defn update-shape-fill
  [sid {:keys [fill opacity] :as opts}]
  (sc/validate! +shape-update-fill-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when fill {:fill fill})
                 (when opacity {:opacity opacity})))))

(defn update-shape-stroke
  [sid {:keys [color opacity width] :as opts}]
  (sc/validate! +shape-update-stroke-schema+ opts)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (update-in state [:shapes-by-id sid]
                 merge
                 (when width {:stroke-width width})
                 (when color {:stroke color})
                 (when opacity {:stroke-opacity opacity})))))

(defn toggle-shape-visibility
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            hidden? (:hidden shape false)]
        (if hidden?
          (assoc-in state [:shapes-by-id sid] (assoc shape :hidden false))
          (assoc-in state [:shapes-by-id sid] (assoc shape :hidden true)))))))

(defn toggle-shape-blocking
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            blocked? (:blocked shape false)]
        (if blocked?
          (assoc-in state [:shapes-by-id sid] (assoc shape :blocked false))
          (assoc-in state [:shapes-by-id sid] (assoc shape :blocked true)))))))

(defn toggle-shape-locking
  [sid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])
            locked? (:locked shape false)]
        (if locked?
          (assoc-in state [:shapes-by-id sid] (assoc shape :locked false))
          (assoc-in state [:shapes-by-id sid] (assoc shape :locked true)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events (for selected)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn deselect-all
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (let [selected (get-in state [:workspace :selected])
            mevent (rs/swap-state #(assoc-in state [:workspace :selected] #{}))]
        ;; (rx/just mevent)))))
        (->> (map #(get-in state [:shapes-by-id %]) selected)
             (rx/from-coll)
             (rx/filter :group)
             (rx/map :group)
             (rx/map rebuild-group-size)
             (rx/merge (rx/just mevent)))))))

(defn copy-selected
  "Copy the selected shapes."
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (as-> selected $
          (map #(get-in state [:shapes-by-id %]) $)
          (map #(assoc % :id (random-uuid)) $)
          (map #(add-shape % %) $)
          (rx/from-coll $))))))


(defn group-selected
  []
  (letfn [(update-shapes-on-page [state pid selected group]
            (as-> (get-in state [:pages-by-id pid :shapes]) $
              (remove selected $)
              (into [group] $)
              (assoc-in state [:pages-by-id pid :shapes] $)))

          (update-shapes-on-index [state shapes dimensions group]
            (let [{:keys [x y]} dimensions]
              (reduce (fn [state {:keys [id] :as shape}]
                        (as-> shape $
                          (sh/translate-coords $ x y)
                          (assoc $ :group group)
                          (assoc-in state [:shapes-by-id id] $)))
                      state
                      shapes)))]
    (reify rs/UpdateEvent
      (-apply-update [_ state]
        (let [shapes-by-id (get state :shapes-by-id)
              sid (random-uuid)
              pid (get-in state [:workspace :page])
              selected (get-in state [:workspace :selected])
              selected' (map #(get shapes-by-id %) selected)
              dimensions (sh/group-dimensions selected')

              data {:type :builtin/group
                    :name (str "Group " (rand-int 1000))
                    :items (into [] selected)
                    :id sid
                    :page pid}
              group (merge data dimensions)]
          (as-> state $
            (update-shapes-on-index $ selected' dimensions sid)
            (update-shapes-on-page $ pid selected sid)
            (update $ :shapes-by-id assoc sid group)
            (update $ :workspace assoc :selected #{})))))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(delete-shape %) selected)))))))

(defn move-selected
  "Move a minimal position unit the selected shapes."
  [dir]
  {:pre [(contains? #{:up :down :right :left} dir)]}
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (let [selected (get-in state [:workspace :selected])
            delta (case dir
                    :up [0 -1]
                    :down [0 +1]
                    :right [+1 0]
                    :left [-1 0])]
        (rx/from-coll
         (map #(move-shape % delta) selected))))))

(defn update-selected-shapes-fill
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (sc/validate! +shape-update-fill-schema+ opts)
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(update-shape-fill % opts)))))))

