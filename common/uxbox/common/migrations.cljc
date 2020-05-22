(ns uxbox.common.migrations
  (:require
   [uxbox.common.pages :as p]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.data :as d]))

(defmulti migrate :version)

(defn migrate-page
  ([page from-version to-version]
   (-> page
       (assoc :version to-version)
       (migrate)))
  
  ([{:keys [version] :as page}]
   (reduce #(migrate-page % (:version %1) %2)
           page
           (range version (inc p/page-version)))))

;; Default handler, noop
(defmethod migrate :default [page] page)

;; -- MIGRATIONS --

(defmethod migrate 4 [page]
  (prn "Migrate " (:id page))
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
    (-> page

        ;; We only store the version in the page data
        (update :data dissoc :version )

        ;; Adds vertices to shapes
        (update-in [:data :objects] calculate-shape-points)

        ;; Creates selection rects for shapes
        (update-in [:data :objects] calculate-shape-selrects))))



