(ns uxbox.data.workspace
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +shape-props-schema+
  {:x [v/required v/integer]
   :y [v/required v/integer]
   :width [v/required v/integer]
   :height [v/required v/integer]})

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
      (println "select-for-drawing" shape)
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

(defn deselect-all
  "Mark a shape selected for drawing in the canvas."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:workspace :selected] #{}))))

;; TODO: validate shape

(defn add-shape
  "Mark a shape selected for drawing in the canvas."
  [shape props]
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

(defn apply-delta
  "Mark a shape selected for drawing in the canvas."
  [sid [dx dy :as delta]]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [shape (get-in state [:shapes-by-id sid])]
        (update-in state [:shapes-by-id sid] merge
                   {:x (+ (:x shape) dx)
                    :y (+ (:y shape) dy)})))))

