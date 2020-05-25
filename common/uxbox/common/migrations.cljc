(ns uxbox.common.migrations
  (:require
   [uxbox.common.pages :as p]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.data :as d]))

(defmulti migrate :version)

(defn migrate-data
  ([data from-version to-version]
   (-> data
       (assoc :version to-version)
       (migrate)))
  
  ([data]
   (try
     (reduce #(migrate-data %1 %2 (inc %2))
             data
             (range (:version data 0) p/page-version))

     ;; If an error is thrown, we log the error and return the data without migrations
     #?(:clj (catch Exception e (.printStackTrace e) data)
        :cljs (catch :default e (.error js/console e) data)))))

;; Default handler, noop
(defmethod migrate :default [data] data)

;; -- MIGRATIONS --

(defmethod migrate 4 [data]
  ;; We changed the internal model of the shapes so they have their selection rect
  ;; and the vertices
  
  (letfn [;; Creates a new property `points` that stores the transformed points inside the shape
          ;; this will be used for the snaps and the selection rect
          (calculate-shape-points [objects]
            (->> objects
                 (d/mapm
                  (fn [id shape]
                    (if (= (:id shape) uuid/zero)
                      shape
                      (assoc shape :points (gsh/shape->points shape)))))))

          ;; Creates a new property `selrect` that stores the selection rect for the shape
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



