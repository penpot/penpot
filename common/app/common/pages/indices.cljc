;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.indices
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as helpers]
   [app.common.uuid :as uuid]))

(defn calculate-z-index
  "Given a collection of shapes calculates their z-index. Greater index
  means is displayed over other shapes with less index."
  [objects]
  (let [is-frame? (fn [id] (= :frame (get-in objects [id :type])))
        root-children (or (get-in objects [uuid/zero :shapes]) [])
        num-frames (->> root-children (filterv is-frame?) count)]

    (when-not (empty? root-children)
      (loop [current (peek root-children)
             pending (pop root-children)
             current-idx (+ (count objects) num-frames -1)
             z-index (transient {})]

        (let [children (get-in objects [current :shapes])
              assigned? (contains? z-index current)
              is-frame? (is-frame? current)

              pending (cond
                        (not is-frame?)
                        (d/concat pending children)

                        (not assigned?)
                        (d/concat pending [current] children)

                        :else
                        pending)]

          (if (empty? pending)
            (-> (assoc! z-index current current-idx)
                (persistent!))
            (recur (peek pending)
                   (pop pending)
                   (dec current-idx)
                   (assoc! z-index current current-idx))))))))

(defn generate-child-parent-index
  [objects]
  (reduce-kv
   (fn [index id obj]
     (assoc index id (:parent-id obj)))
   {} objects))

(defn generate-child-all-parents-index
  "Creates an index where the key is the shape id and the value is a set
  with all the parents"
  ([objects]
   (generate-child-all-parents-index objects (vals objects)))

  ([objects shapes]
   (let [shape->parents
         (fn [shape]
           (->> (helpers/get-parents (:id shape) objects)
                (into [])))]
     (->> shapes
          (map #(vector (:id %) (shape->parents %)))
          (into {})))))

(defn create-mask-index
  "Retrieves the mask information for an object"
  [objects parents-index]
  (let [retrieve-masks
        (fn [id parents]
          (->> parents
               (map #(get objects %))
               (filter #(:masked-group? %))
               ;; Retrieve the masking element
               (mapv #(get objects (->> % :shapes first)))))]
    (->> parents-index
         (d/mapm retrieve-masks))))
