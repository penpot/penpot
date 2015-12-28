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

;; TODO: validate shape

(defn add-shape
  "Mark a shape selected for drawing in the canvas."
  [shape props]
  (sc/validate! +shape-props-schema+ props)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [id (random-uuid)
            pageid (get-in state [:workspace :page])
            _ (assert pageid)
            shape (merge shape props {:id id})]
        (as-> state $
          (update-in $ [:pages-by-id pageid :shapes] conj id)
          (update-in $ [:pages-by-id pageid :shapes-by-id] assoc id shape))))

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
  [shapeid [dx dy :as delta]]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      ;; (println "apply-delta" shapeid delta)
      (let [pageid (get-in state [:workspace :page])
            _ (assert pageid)
            shape (get-in state [:pages-by-id pageid :shapes-by-id shapeid])]
        (update-in state [:pages-by-id pageid :shapes-by-id shapeid] merge
                   {:x (+ (:x shape) dx)
                    :y (+ (:y shape) dy)})))))


;; (defn apply-delta'
;;   "Mark a shape selected for drawing in the canvas."
;;   [shapeid [dx dy :as delta]]
;;   (reify
;;     rs/UpdateEvent
;;     (-apply-update [_ state]
;;       ;; (println "apply-delta'" shapeid delta)
;;       (let [pageid (get-in state [:workspace :page])
;;             shape (get-in state [:pages-by-id pageid :shapes-by-id shapeid])]
;;         (update-in state [:pages-by-id pageid :shapes-by-id shapeid] merge
;;                    {:x dx :y dy})))))
