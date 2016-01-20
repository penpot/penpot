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

(defn contained-in-selrect?
  [shape selrect]
  (let [sx1 (:x selrect)
        sx2 (+ sx1 (:width selrect))
        sy1 (:y selrect)
        sy2 (+ sy1 (:height selrect))
        rx1 (:x shape)
        rx2 (+ rx1 (:width shape))
        ry1 (:y shape)
        ry2 (+ ry1 (:height shape))]
    (and (neg? (- (:y selrect) (:y shape)))
         (neg? (- (:x selrect) (:x shape)))
         (pos? (- (+ (:y selrect)
                     (:height selrect))
                  (+ (:y shape)
                     (:height shape))))
         (pos? (- (+ (:x selrect)
                     (:width selrect))
                  (+ (:x shape)
                     (:width shape)))))))

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
                (filter #(contained-in-selrect? % selrect))
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
  (letfn [(dissoc-shape [state {:keys [id] :as shape}]
            (if (= (:type shape) :builtin/group)
              (let [state (update-in state [:shapes-by-id] dissoc id)]
                (->> (map #(get-in state [:shapes-by-id %]) (:items shape))
                     (reduce dissoc-shape state)))
              (update-in state [:shapes-by-id] dissoc id)))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id id])
            pageid (:page shape)
            shapes (get-in state [:pages-by-id pageid :shapes])
            shapes (into [] (remove #(= % id) shapes))]
        (as-> state $
          (assoc-in $ [:pages-by-id pageid :shapes] shapes)
          (dissoc-shape $ shape)))))))

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

;; TODO: implement locked resize

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events (for selected)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn deselect-all
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:workspace :selected] #{}))))

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
  (reify rs/UpdateEvent
    (-apply-update [_ state]
      (let [shapes-by-id (get state :shapes-by-id)
            sid (random-uuid)
            pid (get-in state [:workspace :page])
            selected (get-in state [:workspace :selected])
            shapes (->> selected
                        (map #(get shapes-by-id %))
                        (map #(assoc % :group sid)))
            {:keys [width height x y]} (sh/group-size-and-position shapes)
            group {:type :builtin/group
                   :id sid
                   :name (str "Group " (rand-int 1000))
                   :x x
                   :y y
                   :width width
                   :height height
                   :items (mapv :id shapes)
                   :page (get-in state [:workspace :page])
                   :view-box [0 0 width height]}
            shapes-map (->> shapes
                            (map #(sh/translate-coords % x y))
                            (reduce #(assoc %1 (:id %2) %2) {}))
            shapes-list (->> (get-in state [:pages-by-id pid :shapes])
                             (filter (comp not selected))
                             (into [sid]))]

        (as-> state $
          (update $ :shapes-by-id merge shapes-map)
          (update $ :shapes-by-id assoc sid group)
          (update $ :workspace assoc :selected #{})
          (update-in $ [:pages-by-id pid] assoc :shapes shapes-list))))))

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

