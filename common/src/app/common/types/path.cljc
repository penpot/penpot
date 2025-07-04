;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cpf]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]
   [app.common.types.path.segment :as segment]
   [app.common.types.path.shape-to-path :as stp]
   [app.common.types.path.subpath :as subpath]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:cosnt bool-group-style-properties bool/group-style-properties)
(def ^:const bool-style-properties bool/style-properties)
(def ^:const default-bool-fills bool/default-fills)

(def schema:content impl/schema:content)
(def schema:segments impl/schema:segments)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn content?
  [o]
  (impl/path-data? o))

(defn content
  "Create path content from plain data or bytes, returns itself if it
  is already PathData instance"
  [data]
  (impl/path-data data))

(defn from-bytes
  [data]
  (impl/from-bytes data))

(defn from-string
  [data]
  (impl/from-string data))

(defn check-content
  [content]
  (impl/check-content content))

(defn get-byte-size
  "Get byte size of a path content"
  [content]
  (impl/-get-byte-size content))

(defn write-to
  [content buffer offset]
  (impl/-write-to content buffer offset))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn close-subpaths
  "Given a content, searches a path for possible subpaths that can
  create closed loops and merge them; then return the transformed path
  conten as PathData instance"
  [content]
  (-> (subpath/close-subpaths content)
      (impl/from-plain)))

(defn apply-content-modifiers
  "Apply delta modifiers over the path content"
  [content modifiers]
  (assert (impl/check-content content))

  (letfn [(apply-to-index [content [index params]]
            (if (contains? content index)
              (cond-> content
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in content [index :command])))

                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params]
                              (helpers/make-curve-params
                               (get-in content [index :params])
                               (get-in content [(dec index) :params]))))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              content))]

    (impl/path-data
     (reduce apply-to-index (vec content) modifiers))))

(defn transform-content
  "Applies a transformation matrix over content and returns a new
  content as PathData instance."
  [content transform]
  (segment/transform-content content transform))

(defn move-content
  [content move-vec]
  (if (gpt/zero? move-vec)
    content
    (segment/move-content content move-vec)))

(defn update-geometry
  "Update shape with new geometry calculated from provided content"
  ([shape content]
   (update-geometry (assoc shape :content content)))
  ([shape]
   (let [flip-x
         (get shape :flip-x)

         flip-y
         (get shape :flip-y)

         ;; NOTE: we ensure that content is PathData instance
         content
         (impl/path-data
          (get shape :content))

         ;; Ensure plain format once
         transform
         (cond-> (:transform shape (gmt/matrix))
           flip-x (gmt/scale (gpt/point -1 1))
           flip-y (gmt/scale (gpt/point 1 -1)))

         transform-inverse
         (cond-> (gmt/matrix)
           flip-x (gmt/scale (gpt/point -1 1))
           flip-y (gmt/scale (gpt/point 1 -1))
           :always (gmt/multiply (:transform-inverse shape (gmt/matrix))))

         center
         (or (some-> (dm/get-prop shape :selrect) grc/rect->center)
             (segment/content-center content))

         base-content
         (segment/transform-content content (gmt/transform-in center transform-inverse))

         ;; Calculates the new selrect with points given the old center
         points
         (-> (segment/content->selrect base-content)
             (grc/rect->points)
             (gco/transform-points center transform))

         points-center
         (gco/points->center points)

         ;; Points is now the selrect but the center is different so we can create the selrect
         ;; through points
         selrect
         (-> points
             (gco/transform-points points-center transform-inverse)
             (grc/points->rect))]

     (-> shape
         (assoc :content content)
         (assoc :points points)
         (assoc :selrect selrect)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH SHAPE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-points
  "Returns points for the given segment, faster version of
  the `content->points`."
  [content]
  (some-> content segment/get-points))

(defn- calc-bool-content*
  "Calculate the boolean content from shape and objects. Returns plain
  vector of segments"
  [shape objects]
  (let [extract-content-xf
        (comp (map (d/getf objects))
              (remove :hidden)
              (remove cpf/svg-raw-shape?)
              (map #(stp/convert-to-path % objects))
              (map :content))

        contents
        (sequence extract-content-xf (:shapes shape))]

    (ex/try!
     (bool/calculate-content (:bool-type shape) contents)

     :on-exception
     (fn [cause]
       (ex/raise :type :internal
                 :code :invalid-path-content
                 :hint (str "unable to calculate bool content for shape " (:id shape))
                 :shapes (:shapes shape)
                 :type (:bool-type shape)
                 :content (vec contents)
                 :cause cause)))))

(defn calc-bool-content
  "Calculate the boolean content from shape and objects. Returns a
  packed PathData instance"
  [shape objects]
  (-> (calc-bool-content* shape objects)
      (impl/path-data)))

(defn update-bool-shape
  "Calculates the selrect+points for the boolean shape"
  [shape objects]
  (let [content (calc-bool-content shape objects)
        shape   (assoc shape :content content)]
    (update-geometry shape)))

(defn shape-with-open-path?
  [shape]
  (let [svg? (contains? shape :svg-attrs)
        ;; No close subpaths for svgs imported
        maybe-close (if svg? identity subpath/close-subpaths)]
    (and (= :path (:type shape))
         (not (->> shape
                   :content
                   (maybe-close)
                   (subpath/get-subpaths)
                   (every? subpath/is-closed?))))))

(defn convert-to-path
  "Transform a shape to a path shape"
  ([shape]
   (convert-to-path shape {}))
  ([shape objects]
   (-> (stp/convert-to-path shape objects)
       (update :content impl/path-data))))

