;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.shapes
  (:require
   [cljs.spec.alpha :as s]
   [uxbox.main.geom :as geom]
   [uxbox.util.data :refer [index-of]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.spec :as us]
   [uxbox.util.uuid :as uuid]))

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::blocked boolean?)
(s/def ::collapsed boolean?)
(s/def ::content string?)
(s/def ::fill-color string?)
(s/def ::fill-opacity number?)
(s/def ::font-family string?)
(s/def ::font-size number?)
(s/def ::font-style string?)
(s/def ::font-weight string?)
(s/def ::height number?)
(s/def ::hidden boolean?)
(s/def ::id uuid?)
(s/def ::letter-spacing number?)
(s/def ::line-height number?)
(s/def ::locked boolean?)
(s/def ::name string?)
(s/def ::page uuid?)
(s/def ::proportion number?)
(s/def ::proportion-lock boolean?)
(s/def ::rx number?)
(s/def ::ry number?)
(s/def ::stroke-color string?)
(s/def ::stroke-opacity number?)
(s/def ::stroke-style #{:none :solid :dotted :dashed :mixed})
(s/def ::stroke-width number?)
(s/def ::text-align #{"left" "right" "center" "justify"})
(s/def ::type #{:rect :path :circle :image :text})
(s/def ::width number?)
(s/def ::x1 number?)
(s/def ::x2 number?)
(s/def ::y1 number?)
(s/def ::y2 number?)

(s/def ::attributes
  (s/keys :opt-un [::blocked
                   ::collapsed
                   ::content
                   ::fill-color
                   ::fill-opacity
                   ::font-family
                   ::font-size
                   ::font-style
                   ::font-weight
                   ::hidden
                   ::letter-spacing
                   ::line-height
                   ::locked
                   ::proportion
                   ::proportion-lock
                   ::rx ::ry
                   ::stroke-color
                   ::stroke-opacity
                   ::stroke-style
                   ::stroke-width
                   ::text-align
                   ::x1 ::x2
                   ::y1 ::y2]))

(s/def ::minimal-shape
  (s/keys :req-un [::id ::page ::type ::name]))

(s/def ::shape
  (s/and ::minimal-shape ::attributes))

(s/def ::rect-like-shape
  (s/keys :req-un [::x1 ::y1 ::x2 ::y2 ::type]))

;; --- Shape Creation

(defn retrieve-used-names
  "Returns a set of already used names by shapes
  in the current page."
  [{:keys [shapes] :as state}]
  (let [pid (get-in state [:workspace :current])
        xf  (comp (filter #(= pid (:page %)))
                  (map :name))]
    (into #{} xf (vals shapes))))

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
    (as-> state $
      (if (= :canvas (:type shape))
        (update-in $ [:pages page :canvas] conj shape-id)
        (update-in $ [:pages page :shapes] conj shape-id))
      (assoc-in $ [:shapes shape-id] shape))))

(defn- duplicate-shape
  [state shape page]
  (let [id (uuid/random)
        name (generate-unique-name state (str (:name shape) "-copy"))
        shape (assoc shape :id id :page page :name name)]
    (-> state
        (update-in [:pages page :shapes] #(into [] (cons id %)))
        (assoc-in [:shapes id] shape))))

(defn duplicate-shapes
  ([state shapes]
   (duplicate-shapes state shapes nil))
  ([state shapes page]
   (let [shapes (reverse (map #(get-in state [:shapes %]) shapes))
         page (or page (:page (first shapes)))]
     (reduce #(duplicate-shape %1 %2 page) state shapes))))

;; --- Delete Shapes

(defn dissoc-from-index
  "A function that dissoc shape from the indexed
  data structure of shapes from the state."
  [state shape]
  (update state :shapes dissoc (:id shape)))

(defn dissoc-from-page
  "Given a shape, try to remove its reference from the
  corresponding page."
  [state {:keys [id page type] :as shape}]
  (if (= :canvas type)
    (update-in state [:pages page :canvas]
               (fn [items] (vec (remove #(= % id) items))))
    (update-in state [:pages page :shapes]
               (fn [items] (vec (remove #(= % id) items))))))

(defn dissoc-shape
  "Given a shape, removes it from the state."
  [state shape]
  (-> state
      (dissoc-from-page shape)
      (dissoc-from-index shape)))

;; --- Shape Vertical Ordering

(defn order-shape
  [state sid opt]
  (let [{:keys [page] :as shape} (get-in state [:shapes sid])
        shapes (get-in state [:pages page :shapes])
        index (case opt
                :top 0
                :down (min (- (count shapes) 1) (inc (index-of shapes sid)))
                :up (max 0 (- (index-of shapes sid) 1))
                :bottom (- (count shapes) 1))]
    (update-in state [:pages page :shapes]
               (fn [items]
                 (let [[fst snd] (->> (remove #(= % sid) items)
                                      (split-at index))]
                   (into [] (concat fst [sid] snd)))))))

;; --- Shape Selection

(defn- try-match-shape
  [xf selrect acc {:keys [type id items] :as shape}]
  (cond
    (geom/contained-in? shape selrect)
    (conj acc id)

    (geom/overlaps? shape selrect)
    (conj acc id)

    :else
    acc))

(defn match-by-selrect
  [state page-id selrect]
  (let [xf (comp (map #(get-in state [:shapes %]))
                 (remove :hidden)
                 (remove :blocked)
                 (remove #(= :canvas (:type %)))
                 (map geom/selection-rect))
        match (partial try-match-shape xf selrect)
        shapes (get-in state [:pages page-id :shapes])]
    (reduce match #{} (sequence xf shapes))))

(defn materialize-xfmt
  [state id xfmt]
  (let [{:keys [type items] :as shape} (get-in state [:shapes id])]
   (if (= type :group)
      (reduce #(materialize-xfmt %1 %2 xfmt) state items)
      (update-in state [:shapes id] geom/transform xfmt))))
