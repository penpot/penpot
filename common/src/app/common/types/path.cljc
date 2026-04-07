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

(defn get-default-bool-fills
  []
  (bool/get-default-fills))

(def schema:path-data impl/schema:path-data)
(def schema:segments impl/schema:segments)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTRUCTORS & TYPE METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn path-data?
  [o]
  (impl/path-data? o))

(defn path-data
  "Create path data from plain data or bytes, returns itself if it
   is already PathData instance"
  [data]
  (impl/path-data data))

(defn from-bytes
  [data]
  (impl/from-bytes data))

(defn from-string
  [data]
  (impl/from-string data))

(defn from-plain
  [data]
  (impl/from-plain data))

(defn check-path-data
  [path-data]
  (impl/check-path-data path-data))

(defn get-byte-size
  "Get byte size of path data"
  [path-data]
  (impl/-get-byte-size path-data))

(defn write-to
  [path-data buffer offset]
  (impl/-write-to path-data buffer offset))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn close-subpaths
  "Given path-data, searches a path for possible subpaths that can
   create closed loops and merge them; then return the transformed path
   data as PathData instance"
  [path-data]
  (-> (subpath/close-subpaths path-data)
      (impl/from-plain)))

(defn apply-path-data-modifiers
  "Apply delta modifiers over the path data"
  [path-data modifiers]
  (assert (impl/check-path-data path-data))

  (letfn [(apply-to-index [path-data [index params]]
            (if (contains? path-data index)
              (cond-> path-data
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in path-data [index :command])))

                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params]
                              (helpers/make-curve-params
                               (get-in path-data [index :params])
                               (get-in path-data [(dec index) :params]))))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              path-data))]

    (if (some? modifiers)
      (impl/path-data
       (reduce apply-to-index (vec path-data) modifiers))
      path-data)))

(defn transform-path-data
  "Applies a transformation matrix over path-data and returns a new
   path-data as PathData instance."
  [path-data transform]
  (segment/transform-path-data path-data transform))

(defn move-path-data
  [path-data move-vec]
  (if (gpt/zero? move-vec)
    path-data
    (segment/move-path-data path-data move-vec)))

(defn update-geometry
  "Update shape with new geometry calculated from provided path-data"
  ([shape path-data]
   (update-geometry (assoc shape :path-data path-data)))
  ([shape]
   (let [flip-x
         (get shape :flip-x)

         flip-y
         (get shape :flip-y)

         ;; NOTE: we ensure that content is PathData instance
         path-data
         (impl/path-data
          (get shape :path-data))

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
             (segment/path-data-center path-data))

         base-path-data
         (segment/transform-path-data path-data (gmt/transform-in center transform-inverse))

         ;; Calculates the new selrect with points given the old center
         points
         (-> (segment/path-data->selrect base-path-data)
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
         (assoc :path-data path-data)
         (assoc :points points)
         (assoc :selrect selrect)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH SHAPE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-points
  "Returns points for the given path-data. Accepts PathData instances or
   plain segment vectors. Returns nil for nil path-data."
  [path-data]
  (when (some? path-data)
    (let [path-data (if (impl/path-data? path-data)
                      path-data
                      (impl/path-data path-data))]
      (segment/get-points path-data))))

(defn calc-selrect
  "Calculate selrect from path-data. The path-data can be in a PathData
   instance or plain vector of segments."
  [path-data]
  (segment/path-data->selrect path-data))

(defn- calc-bool-path-data*
  "Calculate the boolean path-data from shape and objects. Returns plain
   vector of segments"
  [shape objects]
  (let [extract-path-data-xf
        (comp (map (d/getf objects))
              (remove :hidden)
              (remove cpf/svg-raw-shape?)
              (map #(stp/convert-to-path % objects))
              (map :path-data))

        path-data-items
        (sequence extract-path-data-xf (:shapes shape))]

    (ex/try!
     (bool/calculate-path-data (:bool-type shape) path-data-items)

     :on-exception
     (fn [cause]
       (ex/raise :type :internal
                 :code :invalid-path-content
                 :hint (str "unable to calculate bool path-data for shape " (:id shape))
                 :shapes (:shapes shape)
                 :type (:bool-type shape)
                 :path-data (vec path-data-items)
                 :cause cause)))))

(def wasm:calc-bool-path-data
  "A overwrite point for setup a WASM version of the `calc-bool-path-data*` function"
  nil)

(defn calc-bool-path-data
  "Calculate the boolean path-data from shape and objects. Returns a
   packed PathData instance"
  [shape objects]
  (let [path-data (calc-bool-path-data* shape objects)]
    (impl/path-data path-data)))

(defn update-bool-shape
  "Calculates the selrect+points for the boolean shape"
  [shape objects]
  (let [path-data (if (fn? wasm:calc-bool-path-data)
                    (wasm:calc-bool-path-data shape objects)
                    (calc-bool-path-data shape objects))
        shape     (assoc shape :path-data path-data)]
    (update-geometry shape)))

(defn shape-with-open-path?
  [shape]
  (let [svg? (contains? shape :svg-attrs)
        ;; No close subpaths for svgs imported
        maybe-close (if svg? identity subpath/close-subpaths)]
    (and (= :path (:type shape))
         (not (->> shape
                   :path-data
                   (maybe-close)
                   (subpath/get-subpaths)
                   (every? subpath/is-closed?))))))

(defn convert-to-path
  "Transform a shape to a path shape"
  ([shape]
   (convert-to-path shape {}))
  ([shape objects]
   (-> (stp/convert-to-path shape objects)
       (update :path-data impl/path-data))))

(dm/export impl/decode-segments)
