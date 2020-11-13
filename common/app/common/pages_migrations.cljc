(ns app.common.pages-migrations
  (:require
   [app.common.pages :as cp]
   [app.common.geom.shapes :as gsh]
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
