(ns uxbox.state.shapes
  "A collection of functions for manage shapes insinde the state."
  (:require [uxbox.shapes :as sh]
            [uxbox.util.data :refer (index-of)]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-shape-to-page
  [state shape page]
  (let [sid (random-uuid)
        shape (merge shape {:id sid :page page})]
    (as-> state $
      (update-in $ [:pages-by-id page :shapes] conj sid)
      (assoc-in $ [:shapes-by-id sid] shape))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Delete Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dissoc-from-index
  "A function that dissoc shape from the indexed
  data structure of shapes from the state."
  [state {:keys [id type] :as shape}]
  (if (= :builtin/group type)
    (let [items (map #(get-in state [:shapes-by-id %]) (:items shape))]
      (as-> state $
        (update-in $ [:shapes-by-id] dissoc id)
        (reduce dissoc-from-index $ items)))
    (update-in state [:shapes-by-id] dissoc id)))

(defn dissoc-from-page
  "Given a shape, try to remove its reference from the
  corresponding page."
  [state {:keys [id page] :as shape}]
  (as-> (get-in state [:pages-by-id page :shapes]) $
    (into [] (remove #(= % id) $))
    (assoc-in state [:pages-by-id page :shapes] $)))

(defn dissoc-from-group
  "Given a shape, try to remove its reference from the
  corresponding group (only if it belongs to one group)."
  [state {:keys [id group] :as shape}]
  (if-let [group' (get-in state [:shapes-by-id group])]
    (as-> (:items group') $
      (into [] (remove #(= % id) $))
      (assoc-in state [:shapes-by-id group :items] $))
    state))

(declare dissoc-shape)

(defn clear-empty-groups
  "Given the shape, try to clean all empty groups
  that this shape belongs to.

  The main purpose of this function is remove the
  all empty parent groups of recently removed
  shape."
  [state {:keys [group] :as shape}]
  (if-let [group' (get-in state [:shapes-by-id group])]
    (if (empty? (:items group'))
      (dissoc-shape state group')
      state)
    state))

(defn dissoc-shape
  "Given a shape, removes it from the state."
  [state shape]
  (as-> state $
    (dissoc-from-page $ shape)
    (dissoc-from-group $ shape)
    (dissoc-from-index $ shape)
    (clear-empty-groups $ shape)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape Movements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- drop-at-index
  [index coll v]
  (let [[fst snd] (split-at index coll)]
    (into [] (concat fst [v] snd))))

(defn drop-aside
  [state loc tid sid]
  {:pre [(not= tid sid)
         (not (nil? tid))
         (not (nil? sid))]}
  (let [{:keys [page group]} (get-in state [:shapes-by-id tid])
        source (get-in state [:shapes-by-id sid])

        state (-> state
                  (dissoc-from-page source)
                  (dissoc-from-group source))

        shapes (if group
                 (get-in state [:shapes-by-id group :items])
                 (get-in state [:pages-by-id page :shapes]))

        index (case loc
                :after (inc (index-of shapes tid))
                :before (index-of shapes tid))

        shapes (drop-at-index index shapes sid)]
    (if group
      (as-> state $
        (assoc-in $ [:shapes-by-id group :items] shapes)
        (update-in $ [:shapes-by-id sid] assoc :group group)
        (clear-empty-groups $ source))
      (as-> state $
        (assoc-in $ [:pages-by-id page :shapes] shapes)
        (update-in $ [:shapes-by-id sid] dissoc :group)
        (clear-empty-groups $ source)))))

(def ^:static drop-after #(drop-aside %1 :after %2 %3))
(def ^:static drop-before #(drop-aside %1 :before %2 %3))

(defn drop-inside
  [state tid sid]
  {:pre [(not= tid sid)]}
  (let [source (get-in state [:shapes-by-id sid])
        state (-> state
                  (dissoc-from-page source)
                  (dissoc-from-group source))
        shapes (get-in state [:shapes-by-id tid :items])]
    (if (seq shapes)
      (as-> state $
        (assoc-in $ [:shapes-by-id tid :items] (conj shapes sid))
        (update-in $ [:shapes-by-id sid] assoc :group tid))
      state)))

(defn drop-shape
  [state tid sid loc]
  (if (= tid sid)
    state
    (case loc
      :inside (drop-inside state tid sid)
      :before (drop-before state tid sid)
      :after (drop-after state tid sid)
      (throw (ex-info "Invalid data" {})))))
