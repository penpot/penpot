;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.shapes-impl
  (:require [lentes.core :as l]
            [uxbox.main.geom :as geom]
            [uxbox.main.lenses :as ul]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.data :refer (index-of)]))

;; --- Shape Creation

(defn retrieve-used-names
  "Returns a set of already used names by shapes
  in the current page."
  [{:keys [shapes] :as state}]
  (let [page (l/focus ul/selected-page state)
        xform (comp (map second)
                    (filter #(= page (:page %)))
                    (map :name))]
    (into #{} xform shapes)))

(defn generate-unique-name
  "A unique name generator based on the previous
  state of the used names."
  [state basename]
  (let [used (retrieve-used-names state)]
    (loop [counter 1]
      (let [candidate (str basename "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(defn assoc-shape-to-page
  [state shape page]
  (let [shape-id (uuid/random)
        shape-name (generate-unique-name state (:name shape))
        shape (assoc shape
                     :page page
                     :id shape-id
                     :name shape-name)]
    (-> state
        (update-in [:pages page :shapes] #(into [] (cons shape-id %)))
        (assoc-in [:shapes shape-id] shape))))

(defn duplicate-shapes'
  ([state shapes page]
   (duplicate-shapes' state shapes page nil))
  ([state shapes page group]
   (letfn [(duplicate-shape [state shape page group]
             (if (= (:type shape) :group)
               (let [id (uuid/random)
                     items (:items shape)
                     name (generate-unique-name state (str (:name shape) "-copy"))
                     shape (assoc shape
                                  :id id
                                  :page page
                                  :items []
                                  :name name)
                     state (if (nil? group)
                             (-> state
                                 (update-in [:pages page :shapes]
                                            #(into [] (cons id %)))
                                 (assoc-in [:shapes id] shape))
                             (-> state
                                 (update-in [:shapes group :items]
                                            #(into [] (cons id %)))
                                 (assoc-in [:shapes id] shape)))]
                 (->> (map #(get-in state [:shapes %]) items)
                      (reverse)
                      (reduce #(duplicate-shape %1 %2 page id) state)))
               (let [id (uuid/random)
                     name (generate-unique-name state (str (:name shape) "-copy"))
                     shape (-> (dissoc shape :group)
                               (assoc :id id :page page :name name)
                               (merge (when group {:group group})))]
                 (if (nil? group)
                   (-> state
                       (update-in [:pages page :shapes] #(into [] (cons id %)))
                       (assoc-in [:shapes id] shape))
                   (-> state
                       (update-in [:shapes group :items] #(into [] (cons id %)))
                       (assoc-in [:shapes id] shape))))))]
     (reduce #(duplicate-shape %1 %2 page group) state shapes))))

(defn duplicate-shapes
  ([state shapes]
   (duplicate-shapes state shapes nil))
  ([state shapes page]
   (letfn [(all-toplevel? [coll]
             (every? #(nil? (:group %)) coll))
           (all-same-group? [coll]
             (let [group (:group (first coll))]
               (every? #(= group (:group %)) coll)))]
     (let [shapes (reverse (mapv #(get-in state [:shapes %]) shapes))]
       (cond
         (all-toplevel? shapes)
         (let [page (or page (:page (first shapes)))]
           (duplicate-shapes' state shapes page))

         (all-same-group? shapes)
         (let [page (or page (:page (first shapes)))
               group (:group (first shapes))]
           (duplicate-shapes' state shapes page group))

         :else
         (let [page (or page (:page (first shapes)))]
           (duplicate-shapes' state shapes page)))))))

;; --- Delete Shapes

(defn dissoc-from-index
  "A function that dissoc shape from the indexed
  data structure of shapes from the state."
  [state {:keys [id type] :as shape}]
  (if (= :group type)
    (let [items (map #(get-in state [:shapes %]) (:items shape))]
      (as-> state $
        (update-in $ [:shapes] dissoc id)
        (reduce dissoc-from-index $ items)))
    (update-in state [:shapes] dissoc id)))

(defn dissoc-from-page
  "Given a shape, try to remove its reference from the
  corresponding page."
  [state {:keys [id page] :as shape}]
  (as-> (get-in state [:pages page :shapes]) $
    (into [] (remove #(= % id) $))
    (assoc-in state [:pages page :shapes] $)))

(defn dissoc-from-group
  "Given a shape, try to remove its reference from the
  corresponding group (only if it belongs to one group)."
  [state {:keys [id group] :as shape}]
  (if-let [group' (get-in state [:shapes group])]
    (as-> (:items group') $
      (into [] (remove #(= % id) $))
      (assoc-in state [:shapes group :items] $))
    state))

(declare dissoc-shape)

(defn clear-empty-groups
  "Given the shape, try to clean all empty groups
  that this shape belongs to.

  The main purpose of this function is remove the
  all empty parent groups of recently removed
  shape."
  [state {:keys [group] :as shape}]
  (if-let [group' (get-in state [:shapes group])]
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

;; --- Shape Movements

(defn- drop-at-index
  [index coll v]
  (let [[fst snd] (split-at index coll)]
    (into [] (concat fst [v] snd))))

(defn drop-relative
  [state loc sid]
  {:pre [(not (nil? sid))]}
  (let [shape (get-in state [:shapes (first sid)])
        {:keys [page group]} shape
        sid (:id shape)

        shapes (if group
                 (get-in state [:shapes group :items])
                 (get-in state [:pages page :shapes]))

        index (case loc
                :first 0
                :after (min (- (count shapes) 1) (inc (index-of shapes sid)))
                :before (max 0 (- (index-of shapes sid) 1))
                :last (- (count shapes) 1))

        state (-> state
                  (dissoc-from-page shape)
                  (dissoc-from-group shape))

        shapes (if group
                 (get-in state [:shapes group :items])
                 (get-in state [:pages page :shapes]))

        shapes (drop-at-index index shapes sid)]

    (if group
      (as-> state $
        (assoc-in $ [:shapes group :items] shapes)
        (update-in $ [:shapes sid] assoc :group group)
        (clear-empty-groups $ shape))
      (as-> state $
        (assoc-in $ [:pages page :shapes] shapes)
        (update-in $ [:shapes sid] dissoc :group)
        (clear-empty-groups $ shape)))))

(defn drop-aside
  [state loc tid sid]
  {:pre [(not= tid sid)
         (not (nil? tid))
         (not (nil? sid))]}
  (let [{:keys [page group]} (get-in state [:shapes tid])
        source (get-in state [:shapes sid])

        state (-> state
                  (dissoc-from-page source)
                  (dissoc-from-group source))

        shapes (if group
                 (get-in state [:shapes group :items])
                 (get-in state [:pages page :shapes]))

        index (case loc
                :after (inc (index-of shapes tid))
                :before (index-of shapes tid))

        shapes (drop-at-index index shapes sid)]
    (if group
      (as-> state $
        (assoc-in $ [:shapes group :items] shapes)
        (update-in $ [:shapes sid] assoc :group group)
        (clear-empty-groups $ source))
      (as-> state $
        (assoc-in $ [:pages page :shapes] shapes)
        (update-in $ [:shapes sid] dissoc :group)
        (clear-empty-groups $ source)))))

(def drop-after #(drop-aside %1 :after %2 %3))
(def drop-before #(drop-aside %1 :before %2 %3))

(defn drop-inside
  [state tid sid]
  {:pre [(not= tid sid)]}
  (let [source (get-in state [:shapes sid])
        state (-> state
                  (dissoc-from-page source)
                  (dissoc-from-group source))
        shapes (get-in state [:shapes tid :items])]
    (if (seq shapes)
      (as-> state $
        (assoc-in $ [:shapes tid :items] (conj shapes sid))
        (update-in $ [:shapes sid] assoc :group tid))
      state)))

(defn drop-shape
  [state sid tid loc]
  (if (= tid sid)
    state
    (case loc
      :inside (drop-inside state tid sid)
      :before (drop-before state tid sid)
      :after (drop-after state tid sid)
      (throw (ex-info "Invalid data" {})))))

(defn move-layer
  [state shape loc]
  (case loc
    :up (drop-relative state :before shape)
    :down (drop-relative state :after shape)
    :top (drop-relative state :first shape)
    :bottom (drop-relative state :last shape)
    (throw (ex-info "Invalid data" {}))))

;; --- Shape Selection

(defn- try-match-shape
  [xf selrect acc {:keys [type id items] :as shape}]
  (cond
    (geom/contained-in? shape selrect)
    (conj acc id)

    (geom/overlaps? shape selrect)
    (conj acc id)

    (:locked shape)
    acc

    (= type :group)
    (reduce (partial try-match-shape xf selrect)
            acc (sequence xf items))

    :else
    acc))

(defn match-by-selrect
  [state page selrect]
  (let [xf (comp (map #(get-in state [:shapes %]))
                 (remove :hidden)
                 (remove :blocked)
                 (map geom/selection-rect))
        match (partial try-match-shape xf selrect)
        shapes (get-in state [:pages page :shapes])]
    (reduce match #{} (sequence xf shapes))))

(defn group-shapes
  [state shapes used-names page]
  (letfn [(replace-first-item [pred coll replacement]
            (into []
              (concat
                (take-while #(not (pred %)) coll)
                [replacement]
                (drop 1 (drop-while #(not (pred %)) coll)))))

          (move-shapes-to-new-group [state page shapes new-group]
            (reduce (fn [state {:keys [id group] :as shape}]
                      (-> state
                        (update-in [:shapes group :items] #(remove (set [id]) %))
                        (update-in [:pages page :shapes] #(remove (set [id]) %))
                        (clear-empty-groups shape)
                        (assoc-in [:shapes id :group] new-group)
                        ))
                    state
                    shapes))

          (update-shapes-on-page [state page shapes group]
            (as-> (get-in state [:pages page :shapes]) $
              (replace-first-item (set shapes) $ group)
              (remove (set shapes) $)
              (into [] $)
              (assoc-in state [:pages page :shapes] $)))

          (update-shapes-on-group [state parent-group shapes group]
            (as-> (get-in state [:shapes parent-group :items]) $
              (replace-first-item (set shapes) $ group)
              (remove (set shapes) $)
              (into [] $)
              (assoc-in state [:shapes parent-group :items] $)))

          (update-shapes-on-index [state shapes group]
            (reduce (fn [state {:keys [id] :as shape}]
                      (as-> shape $
                        (assoc $ :group group)
                        (assoc-in state [:shapes id] $)))
                    state
                    shapes))]
    (let [sid (uuid/random)
          shapes' (map #(get-in state [:shapes %]) shapes)
          distinct-groups (distinct (map :group shapes'))
          parent-group (cond
                         (not= 1 (count distinct-groups)) :multi
                         (nil? (first distinct-groups)) :page
                         :else (first distinct-groups))
          name (generate-unique-name state "Group")
          group {:type :group
                 :name name
                 :items (into [] shapes)
                 :id sid
                 :page page}]
      (as-> state $
        (update-shapes-on-index $ shapes' sid)
        (cond
          (= :multi parent-group)
            (-> $
              (move-shapes-to-new-group page shapes' sid)
              (update-in [:pages page :shapes] #(into [] (cons sid %))))
          (= :page parent-group)
            (update-shapes-on-page $ page shapes sid)
          :else
            (update-shapes-on-group $ parent-group shapes sid))
        (update $ :shapes assoc sid group)
        (cond
          (= :multi parent-group) $
          (= :page parent-group) $
          :else (assoc-in $ [:shapes sid :group] parent-group))
        (update $ :workspace assoc :selected #{sid})))))

(defn degroup-shapes
  [state shapes page]
  (letfn [(empty-group [state page-id group-id]
            (let [group (get-in state [:shapes group-id])
                  parent-id (:group group)
                  position (if (nil? parent-id)
                             (index-of (get-in state [:pages page-id :shapes]) group-id)
                             (index-of (get-in state [:shapes parent-id :items]) group-id))
                  reduce-func (fn [state item]
                                (if (nil? parent-id)
                                  (-> state
                                    (update-in [:pages page-id :shapes] #(drop-at-index position % item))
                                    (update-in [:shapes item] dissoc :group))
                                  (-> state
                                    (update-in [:shapes parent-id :items] #(drop-at-index position % item))
                                    (assoc-in [:shapes item :group] parent-id))))]

              (as-> state $
                (reduce reduce-func $ (reverse (:items group)))
                (if (nil? parent-id)
                  (update-in $ [:pages page-id :shapes] #(into [] (remove #{group-id} %)))
                  (update-in $ [:shapes parent-id :items] #(into [] (remove #{group-id} %))))
                (update-in $ [:shapes] dissoc group-id))))

          (empty-groups [state page-id groups-ids]
            (reduce (fn [state group-id]
                      (empty-group state page-id group-id))
                    state
                    groups-ids))]
    (let [shapes' (map #(get-in state [:shapes %]) shapes)
          groups (filter #(= (:type %) :group) shapes')
          groups-ids (map :id groups)
          groups-items (remove (set groups-ids) (mapcat :items groups))]
      (as-> state $
        (empty-groups $ page groups-ids)
        (update $ :workspace assoc :selected (set groups-items))))))

(defn materialize-xfmt
  [state id xfmt]
  (let [{:keys [type items] :as shape} (get-in state [:shapes id])]
    (if (= type :group)
      (-> (reduce #(materialize-xfmt %1 %2 xfmt) state items)
          (update-in [:shapes id] geom/transform xfmt))
      (update-in state [:shapes id] geom/transform xfmt))))
