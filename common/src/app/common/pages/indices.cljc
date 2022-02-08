;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.indices
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

(defn calculate-frame-z-index [z-index frame-id objects]
  (let [is-frame?    (fn [id] (= :frame (get-in objects [id :type])))
        frame-shapes (->> objects (vals) (filterv #(= (:frame-id %) frame-id)))
        children     (or (get-in objects [frame-id :shapes]) [])]

    (if (empty? children)
      z-index
      (loop [current (peek children)
             pending (pop children)
             current-idx (count frame-shapes)
             z-index z-index]

        (let [children  (get-in objects [current :shapes])
              is-frame? (is-frame? current)
              pending   (if (not is-frame?)
                          (d/concat-vec pending children)
                          pending)]

          (if (empty? pending)
            (assoc z-index current current-idx)
            (recur (peek pending)
                   (pop pending)
                   (dec current-idx)
                   (assoc z-index current current-idx))))))))

;; The z-index is really calculated per-frame. Every frame will have its own
;; internal z-index. To calculate the "final" z-index we add the shape z-index with
;; the z-index of its frame. This way we can update the z-index per frame without
;; the need of recalculate all the frames
(defn calculate-z-index
  "Given a collection of shapes calculates their z-index. Greater index
  means is displayed over other shapes with less index."
  [objects]

  (let [frames  (cph/get-frames objects)
        z-index (calculate-frame-z-index {} uuid/zero objects)]
    (->> frames
         (map :id)
         (reduce #(calculate-frame-z-index %1 %2 objects) z-index))))

(defn update-z-index
  "Updates the z-index given a set of ids to change and the old and new objects
  representations"
  [z-index changed-ids old-objects new-objects]

  (let [old-frames (into #{} (map #(get-in old-objects [% :frame-id])) changed-ids)
        new-frames (into #{} (map #(get-in new-objects [% :frame-id])) changed-ids)

        changed-frames (set/union old-frames new-frames)

        frames (->> (cph/get-frames new-objects)
                    (map :id)
                    (filter #(contains? changed-frames %)))

        z-index (calculate-frame-z-index z-index uuid/zero new-objects)]

    (->> frames
         (reduce #(calculate-frame-z-index %1 %2 new-objects) z-index))))

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
   (let [xf-parents (comp
                     (map :id)
                     (map #(vector % (cph/get-parent-ids objects %))))]
     (into {} xf-parents shapes))))

(defn create-clip-index
  "Retrieves the mask information for an object"
  [objects parents-index]
  (let [retrieve-clips
        (fn [_ parents]
          (let [lookup-object (fn [id] (get objects id))
                get-clip-parents
                (fn [shape]
                  (cond-> []
                    (:masked-group? shape)
                    (conj (get objects (->> shape :shapes first)))

                    (= :bool (:type shape))
                    (conj shape)))]

            (into []
                  (comp (map lookup-object)
                        (mapcat get-clip-parents))
                  parents)))]
    (->> parents-index
         (d/mapm retrieve-clips))))
