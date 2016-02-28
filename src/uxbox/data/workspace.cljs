(ns uxbox.data.workspace
  (:require [bouncer.validators :as v]
            [beicon.core :as rx]
            [uxbox.shapes :as sh]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.state.shapes :as stsh]
            [uxbox.schema :as sc]
            [uxbox.util.time :as time]
            [uxbox.xforms :as xf]
            [uxbox.shapes :as sh]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (index-of)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events (explicit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initialize
  "Initialize the workspace state."
  [project page]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if-let [workspace (:workspace state)]
        (update state :workspace merge
                {:project project
                 :page page
                 :selected #{}
                 :drawing nil})
        (update state :workspace merge
                {:project project
                 :page page
                 :flags #{:layers :element-options}
                 :selected #{}
                 :drawing nil})))))

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [flags (get-in state [:workspace :flags])]
        (if (contains? flags key)
          (assoc-in state [:workspace :flags] (disj flags key))
          (assoc-in state [:workspace :flags] (conj flags key)))))))

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
                (map sh/outer-rect')
                (filter #(sh/contained-in? % selrect))
                (map :id))]
        (->> (into #{} xf (vals (:shapes-by-id state)))
             (assoc-in state [:workspace :selected]))))))

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

(defn group-selected
  []
  (letfn [(update-shapes-on-page [state pid selected group]
            (as-> (get-in state [:pages-by-id pid :shapes]) $
              (remove selected $)
              (into [group] $)
              (assoc-in state [:pages-by-id pid :shapes] $)))

          (update-shapes-on-index [state shapes group]
            (reduce (fn [state {:keys [id] :as shape}]
                      (as-> shape $
                        (assoc $ :group group)
                        (assoc-in state [:shapes-by-id id] $)))
                    state
                    shapes))
          (valid-selection? [shapes]
            (let [groups (into #{} (map :group shapes))]
              (= 1 (count groups))))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [shapes-by-id (get state :shapes-by-id)
              sid (random-uuid)
              pid (get-in state [:workspace :page])
              selected (get-in state [:workspace :selected])
              selected' (map #(get shapes-by-id %) selected)
              group {:type :builtin/group
                    :name (str "Group " (rand-int 1000))
                    :items (into [] selected)
                    :id sid
                    :page pid}]
          (if (valid-selection? selected')
            (as-> state $
              (update-shapes-on-index $ selected' sid)
              (update-shapes-on-page $ pid selected sid)
              (update $ :shapes-by-id assoc sid group)
              (update $ :workspace assoc :selected #{}))
            state))))))

;; TODO: maybe split in two separate events
(defn duplicate-selected
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (stsh/duplicate-shapes state selected)))))

(defn delete-selected
  "Deselect all and remove all selected shapes."
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (let [selected (get-in state [:workspace :selected])]
        (rx/from-coll
         (into [(deselect-all)] (map #(uds/delete-shape %) selected)))))))

(defn move-selected
  "Move a minimal position unit the selected shapes."
  ([dir] (move-selected dir 1))
  ([dir n]
   {:pre [(contains? #{:up :down :right :left} dir)]}
   (reify
     rs/WatchEvent
     (-apply-watch [_ state]
       (let [selected (get-in state [:workspace :selected])
             delta (case dir
                    :up (gpt/point 0 (- n))
                    :down (gpt/point 0 n)
                    :right (gpt/point n 0)
                    :left (gpt/point (- n) 0))]
         (rx/from-coll
          (map #(uds/move-shape % delta) selected)))))))

(defn update-selected-shapes-fill
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (sc/validate! uds/+shape-fill-attrs-schema+ opts)
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(uds/update-fill-attrs % opts)))))))


(defn update-selected-shapes-stroke
  "Update the fill related attributed on
  selected shapes."
  [opts]
  (sc/validate! uds/+shape-stroke-attrs-schema+ opts)
  (reify
    rs/WatchEvent
    (-apply-watch [_ state]
      (rx/from-coll
       (->> (get-in state [:workspace :selected])
            (map #(uds/update-stroke-attrs % opts)))))))

