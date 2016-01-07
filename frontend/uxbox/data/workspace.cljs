(ns uxbox.data.workspace
  (:require [bouncer.validators :as v]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [uxbox.shapes :as shapes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +shape-props-schema+
  {:x [v/required v/integer]
   :y [v/required v/integer]
   :width [v/required v/integer]
   :height [v/required v/integer]})

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
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-tool
  "Toggle the enabled flag of the specified tool."
  [toolname]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [key (keyword (str (name toolname) "-enabled"))]
        (update-in state [:workspace key] (fnil not false))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.w/toggle-tool>"))))

(defn toggle-toolbox
  "Toggle the visibility flag of the specified toolbox."
  [toolname]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [key (keyword (str (name toolname) "-toolbox-enabled"))
            val (get-in state [:workspace key] false)
            state (assoc-in state [:workspace key] (not val))]
        (if val
          (update-in state [:workspace :toolboxes] disj toolname)
          (update-in state [:workspace :toolboxes] conj toolname))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.w/toggle-toolbox>"))))

(defn select-for-drawing
  "Mark a shape selected for drawing in the canvas."
  [shape]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if shape
        (assoc-in state [:workspace :drawing] shape)
        (update-in state [:workspace] dissoc :drawing)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.w/select-for-drawing>"))))

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
      (let [pid (get-in state [:workspace :page])
            shapes (->> (vals (:shapes-by-id state))
                        (filter #(= (:page %) pid))
                        (filter #(contained-in-selrect? % selrect))
                        (map :id))]
        (assoc-in state [:workspace :selected] (into #{} shapes))))))

(defn deselect-all
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:workspace :selected] #{}))))

(defn add-shape
  "Mark a shape selected for drawing in the canvas."
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
          (assoc-in $ [:shapes-by-id sid] shape))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.w/add-shape>"))))

(defn initialize
  "Initialize the workspace state."
  [projectid pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [s {:project projectid
               :toolboxes #{}
               :drawing nil
               :selected #{}
               :page pageid}]
        (assoc state :workspace s)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.w/initialize>"))))

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
