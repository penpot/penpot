(ns app.common.pages-migrations
  (:require
   [app.common.pages :as cp]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.data :as d]))

;; TODO: revisit this and rename to file-migrations

(defmulti migrate :version)

(defn migrate-data
  ([data]
   (if (= (:version data) cp/file-version)
     data
     (reduce #(migrate-data %1 %2 (inc %2))
             data
             (range (:version data 0) cp/file-version))))

  ([data from-version to-version]
   (-> data
       (assoc :version to-version)
       (migrate))))

(defn migrate-file
  [file]
  (update file :data migrate-data))

;; Default handler, noop
(defmethod migrate :default [data] data)

;; -- MIGRATIONS --

;; Ensure that all :shape attributes on shapes are vectors.
(defmethod migrate 2
  [data]
  (letfn [(update-object [id object]
            (d/update-when object :shapes
                           (fn [shapes]
                             (if (seq? shapes)
                               (into [] shapes)
                               shapes))))

          (update-page [id page]
            (update page :objects #(d/mapm update-object %)))]

    (update data :pages-index #(d/mapm update-page %))))

;; Changes paths formats
(defmethod migrate 3
  [data]
  (letfn [(migrate-path [shape]
            (if-not (contains? shape :content)
              (let [content (gsp/segments->content (:segments shape) (:close? shape))
                    selrect (gsh/content->selrect content)
                    points (gsh/rect->points selrect)]
                (-> shape
                    (dissoc :segments)
                    (dissoc :close?)
                    (assoc :content content)
                    (assoc :selrect selrect)
                    (assoc :points points)))
              ;; If the shape contains :content is already in the new format
              shape))

          (fix-frames-selrects [frame]
            (if (= (:id frame) uuid/zero)
              frame
              (let [frame-rect (select-keys frame [:x :y :width :height])]
                (-> frame
                    (assoc :selrect (gsh/rect->selrect frame-rect))
                    (assoc :points (gsh/rect->points frame-rect))))))

          (fix-empty-points [shape]
            (let [shape (cond-> shape
                          (empty? (:selrect shape)) (gsh/setup-selrect))]
              (cond-> shape
                (empty? (:points shape))
                (assoc :points (gsh/rect->points (:selrect shape))))))

          (update-object [id object]
            (cond-> object
              (= :curve (:type object))
              (assoc :type :path)

              (or (#{:curve :path} (:type object)))
              (migrate-path)

              (= :frame (:type object))
              (fix-frames-selrects)

              (and (empty? (:points object)) (not= (:id object) uuid/zero))
              (fix-empty-points)

              :always
              (->
               ;; Setup an empty transformation to re-calculate selrects
               ;; and points data
               (assoc :modifiers {:displacement (gmt/matrix)})
               (gsh/transform-shape))

              ))

          (update-page [id page]
            (update page :objects #(d/mapm update-object %)))]

    (update data :pages-index #(d/mapm update-page %))))

(defmethod migrate 4
  [data]
  (letfn [(update-object [id object]
            (cond-> object
              (= (:id object) uuid/zero)
              (assoc :points []
                     :selrect {:x 0 :y 0
                               :width 1 :height 1
                               :x1 0 :y1 0
                               :x2 1 :y2 1})))

          (update-page [id page]
            (update page :objects #(d/mapm update-object %)))]
    (update data :pages-index #(d/mapm update-page %))))
