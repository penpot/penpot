(ns uxbox.common.pages-migrations
  (:require
   [uxbox.common.pages :as p]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.data :as d]))

(defmulti migrate :version)

(defn migrate-data
  ([data]
   (if (= (:version data) p/page-version)
     data
     (reduce #(migrate-data %1 %2 (inc %2))
             data
             (range (:version data 0) p/page-version))))

  ([data from-version to-version]
   (-> data
       (assoc :version to-version)
       (migrate))))

;; Default handler, noop
(defmethod migrate :default [data] data)

;; -- MIGRATIONS --

(defn- generate-child-parent-index
  [objects]
  (reduce-kv
   (fn [index id obj]
     (into index (map #(vector % id) (:shapes obj []))))
   {} objects))

(defmethod migrate 5
  [data]
  (update data :objects
          (fn [objects]
            (let [index (generate-child-parent-index objects)]
              (d/mapm
               (fn [id obj]
                 (let [parent-id (get index id)]
                   (assoc obj :parent-id parent-id)))
               objects)))))

;; We changed the internal model of the shapes so they have their
;; selection rect and the vertices

(defmethod migrate 4
  [data]

  (letfn [;; Creates a new property `points` that stores the
          ;; transformed points inside the shape this will be used for
          ;; the snaps and the selection rect
          (calculate-shape-points [objects]
            (->> objects
                 (d/mapm
                  (fn [id shape]
                    (if (= (:id shape) uuid/zero)
                      shape
                      (assoc shape :points (gsh/shape->points shape)))))))

          ;; Creates a new property `selrect` that stores the
          ;; selection rect for the shape
          (calculate-shape-selrects [objects]
            (->> objects
                 (d/mapm
                  (fn [id shape]
                    (if (= (:id shape) uuid/zero)
                      shape
                      (assoc shape :selrect (gsh/points->selrect (:points shape))))))))]
    (-> data
        ;; Adds vertices to shapes
        (update :objects calculate-shape-points)

        ;; Creates selection rects for shapes
        (update :objects calculate-shape-selrects))))



