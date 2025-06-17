;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.matrix
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:cljs [cljs.pprint :as pp]
      :clj  [clojure.pprint :as pp])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [clojure.spec.alpha :as s])
  #?(:clj
     (:import
      java.util.List)))


(def precision 6)

;; --- Matrix Impl
(cr/defrecord Matrix [^double a
                      ^double b
                      ^double c
                      ^double d
                      ^double e
                      ^double f]
  Object
  (toString [this]
    (dm/fmt "matrix(%, %, %, %, %, %)"
            (mth/to-fixed (.-a this) precision)
            (mth/to-fixed (.-b this) precision)
            (mth/to-fixed (.-c this) precision)
            (mth/to-fixed (.-d this) precision)
            (mth/to-fixed (.-e this) precision)
            (mth/to-fixed (.-f this) precision))))

(defn format-precision
  [mtx precision]
  (when mtx
    (dm/fmt "matrix(%, %, %, %, %, %)"
            (mth/to-fixed (.-a mtx) precision)
            (mth/to-fixed (.-b mtx) precision)
            (mth/to-fixed (.-c mtx) precision)
            (mth/to-fixed (.-d mtx) precision)
            (mth/to-fixed (.-e mtx) precision)
            (mth/to-fixed (.-f mtx) precision))))

(defn matrix?
  "Return true if `v` is Matrix instance."
  [v]
  (instance? Matrix v))

(defn matrix
  "Create a new matrix instance."
  ([]
   (pos->Matrix 1 0 0 1 0 0))
  ([a b c d e f]
   (pos->Matrix a b c d e f)))

(def ^:private schema:matrix-attrs
  [:map {:title "MatrixAttrs"}
   [:a ::sm/safe-double]
   [:b ::sm/safe-double]
   [:c ::sm/safe-double]
   [:d ::sm/safe-double]
   [:e ::sm/safe-double]
   [:f ::sm/safe-double]])

(def valid-matrix?
  (sm/validator
   [:and [:fn matrix?] schema:matrix-attrs]))

(defn matrix-generator
  []
  (->> (sg/tuple (sg/small-double)
                 (sg/small-double)
                 (sg/small-double)
                 (sg/small-double)
                 (sg/small-double)
                 (sg/small-double))
       (sg/fmap #(apply pos->Matrix %))))

(def ^:private number-regex
  #"[+-]?\d*(\.\d+)?([eE][+-]?\d+)?")

(defn str->matrix
  [matrix-str]
  (let [params (->> (re-seq number-regex matrix-str)
                    (filter #(-> % first seq))
                    (map (comp d/parse-double first)))]
    (apply matrix params)))

(defn- matrix->str
  [o]
  (if (matrix? o)
    (dm/str (dm/get-prop o :a) ","
            (dm/get-prop o :b) ","
            (dm/get-prop o :c) ","
            (dm/get-prop o :d) ","
            (dm/get-prop o :e) ","
            (dm/get-prop o :f) ",")
    o))

(defn- matrix->json
  [o]
  (if (matrix? o)
    (into {} o)
    o))

(defn- decode-matrix
  [o]
  (if (map? o)
    (map->Matrix o)
    (if (string? o)
      (str->matrix o)
      o)))

(def schema:matrix
  (sm/register!
   {:type ::matrix
    :pred valid-matrix?
    :type-properties
    {:title "matrix"
     :description "Matrix instance"
     :error/message "expected a valid matrix instance"
     :gen/gen (matrix-generator)
     :decode/json decode-matrix
     :decode/string decode-matrix
     :encode/json matrix->json
     :encode/string matrix->str
     ::oapi/type "string"
     ::oapi/format "matrix"}}))

;; FIXME: deprecated
(s/def ::a ::us/safe-float)
(s/def ::b ::us/safe-float)
(s/def ::c ::us/safe-float)
(s/def ::d ::us/safe-float)
(s/def ::e ::us/safe-float)
(s/def ::f ::us/safe-float)

(s/def ::matrix-attrs
  (s/keys :req-un [::a ::b ::c ::d ::e ::f]))

(s/def ::matrix
  (s/and ::matrix-attrs matrix?))

(defn close?
  [^Matrix m1 ^Matrix m2]
  (and ^boolean (mth/close? (.-a m1) (.-a m2))
       ^boolean (mth/close? (.-b m1) (.-b m2))
       ^boolean (mth/close? (.-c m1) (.-c m2))
       ^boolean (mth/close? (.-d m1) (.-d m2))
       ^boolean (mth/close? (.-e m1) (.-e m2))
       ^boolean (mth/close? (.-f m1) (.-f m2))))

(defn unit? [^Matrix m1]
  (and ^boolean (some? m1)
       ^boolean (mth/close? (.-a m1) 1)
       ^boolean (mth/close? (.-b m1) 0)
       ^boolean (mth/close? (.-c m1) 0)
       ^boolean (mth/close? (.-d m1) 1)
       ^boolean (mth/close? (.-e m1) 0)
       ^boolean (mth/close? (.-f m1) 0)))

(defn multiply!
  [^Matrix m1 ^Matrix m2]
  (let [m1a (.-a m1)
        m1b (.-b m1)
        m1c (.-c m1)
        m1d (.-d m1)
        m1e (.-e m1)
        m1f (.-f m1)
        m2a (.-a m2)
        m2b (.-b m2)
        m2c (.-c m2)
        m2d (.-d m2)
        m2e (.-e m2)
        m2f (.-f m2)]
    #?@(:cljs
        [(set! (.-a m1) (+ (* m1a m2a) (* m1c m2b)))
         (set! (.-b m1) (+ (* m1b m2a) (* m1d m2b)))
         (set! (.-c m1) (+ (* m1a m2c) (* m1c m2d)))
         (set! (.-d m1) (+ (* m1b m2c) (* m1d m2d)))
         (set! (.-e m1) (+ (* m1a m2e) (* m1c m2f) m1e))
         (set! (.-f m1) (+ (* m1b m2e) (* m1d m2f) m1f))
         m1]
        :clj
        [(pos->Matrix
          (+ (* m1a m2a) (* m1c m2b))
          (+ (* m1b m2a) (* m1d m2b))
          (+ (* m1a m2c) (* m1c m2d))
          (+ (* m1b m2c) (* m1d m2d))
          (+ (* m1a m2e) (* m1c m2f) m1e)
          (+ (* m1b m2e) (* m1d m2f) m1f))])))

(defn multiply
  ([^Matrix m1 ^Matrix m2]
   (cond
     ;; nil matrixes are equivalent to unit-matrix
     (and (nil? m1) (nil? m2)) (matrix)
     (nil? m1) m2
     (nil? m2) m1

     :else
     (let [m1a (.-a m1)
           m1b (.-b m1)
           m1c (.-c m1)
           m1d (.-d m1)
           m1e (.-e m1)
           m1f (.-f m1)

           m2a (.-a m2)
           m2b (.-b m2)
           m2c (.-c m2)
           m2d (.-d m2)
           m2e (.-e m2)
           m2f (.-f m2)]

       (pos->Matrix
        (+ (* m1a m2a) (* m1c m2b))
        (+ (* m1b m2a) (* m1d m2b))
        (+ (* m1a m2c) (* m1c m2d))
        (+ (* m1b m2c) (* m1d m2d))
        (+ (* m1a m2e) (* m1c m2f) m1e)
        (+ (* m1b m2e) (* m1d m2f) m1f)))))

  ([m1 m2 & others]
   (reduce multiply! (multiply m1 m2) others)))

(defn add-translate
  "Given two TRANSLATE matrixes (only e and f have significative
  values), combine them. Quicker than multiplying them, for this
  precise case."
  ([^Matrix m1 ^Matrix m2]
   (let [m1e (dm/get-prop m1 :e)
         m1f (dm/get-prop m1 :f)
         m2e (dm/get-prop m2 :e)
         m2f (dm/get-prop m2 :f)]
     (pos->Matrix 1 0 0 1 (+ m1e m2e) (+ m1f m2f))))

  ([m1 m2 & others]
   (reduce add-translate (add-translate m1 m2) others)))

;; FIXME: optimize?

(defn substract
  [{m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f}
   {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f}]
  (pos->Matrix
   (- m1a m2a) (- m1b m2b) (- m1c m2c)
   (- m1d m2d) (- m1e m2e) (- m1f m2f)))

(def base (matrix))

(defn base?
  [v]
  (= v base))

(defn translate-matrix
  ([pt]
   (dm/assert! (gpt/point? pt))
   (pos->Matrix 1 0 0 1
                (dm/get-prop pt :x)
                (dm/get-prop pt :y)))

  ([x y]
   (pos->Matrix 1 0 0 1 x y)))


(defn translate-matrix-neg
  ([pt]
   (dm/assert! (gpt/point? pt))
   (pos->Matrix 1 0 0 1
                (- (dm/get-prop pt :x))
                (- (dm/get-prop pt :y))))

  ([x y]
   (pos->Matrix 1 0 0 1 (- x) (- y))))

(defn scale-matrix
  ([pt center]
   (let [sx (dm/get-prop pt :x)
         sy (dm/get-prop pt :y)
         cx (dm/get-prop center :x)
         cy (dm/get-prop center :y)]
     (pos->Matrix sx 0 0 sy (- cx (* cx sx)) (- cy (* cy sy)))))
  ([pt]
   (dm/assert! (gpt/point? pt))
   (pos->Matrix (dm/get-prop pt :x) 0 0 (dm/get-prop pt :y) 0 0)))

(defn rotate-matrix
  ([angle point]
   (let [cx (dm/get-prop point :x)
         cy (dm/get-prop point :y)
         nx (- cx)
         ny (- cy)
         a (mth/radians angle)
         c (mth/cos a)
         s (mth/sin a)
         ns (- s)
         tx (+ (* c nx) (* ns ny) cx)
         ty (+ (* s nx) (*  c ny) cy)]
     (pos->Matrix c s ns c tx ty)))
  ([angle]
   (let [a (mth/radians angle)]
     (pos->Matrix (mth/cos a)
                  (mth/sin a)
                  (- (mth/sin a))
                  (mth/cos a)
                  0
                  0))))

(defn skew-matrix
  ([angle-x angle-y point]
   (multiply (translate-matrix point)
             (skew-matrix angle-x angle-y)
             (translate-matrix (gpt/negate point))))
  ([angle-x angle-y]
   (let [m1 (mth/tan (mth/radians angle-x))
         m2 (mth/tan (mth/radians angle-y))]
     (pos->Matrix 1 m2 m1 1 0 0))))

(defn rotate
  "Apply rotation transformation to the matrix."
  ([m angle]
   (multiply m (rotate-matrix angle)))
  ([m angle center]
   (multiply m (rotate-matrix angle center))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m scale]
   (multiply m (scale-matrix scale)))
  ([m scale center]
   (multiply m (scale-matrix scale center))))

(defn scale!
  "Apply scale transformation to the matrix."
  ([m scale]
   (multiply! m (scale-matrix scale)))
  ([m scale center]
   (multiply! m (scale-matrix scale center))))

(defn translate
  "Apply translate transformation to the matrix."
  [m pt]
  (multiply m (translate-matrix pt)))

(defn translate!
  "Apply translate transformation to the matrix."
  [m pt]
  (multiply! m (translate-matrix pt)))

(defn skew
  "Apply translate transformation to the matrix."
  ([m angle-x angle-y]
   (multiply m (skew-matrix angle-x angle-y)))
  ([m angle-x angle-y p]
   (multiply m (skew-matrix angle-x angle-y p))))

(defn m-equal [m1 m2 threshold]
  (let [th-eq (fn [a b] (<= (mth/abs (- a b)) threshold))
        {m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f} m1
        {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f} m2]
    (and (th-eq m1a m2a)
         (th-eq m1b m2b)
         (th-eq m1c m2c)
         (th-eq m1d m2d)
         (th-eq m1e m2e)
         (th-eq m1f m2f))))

(defmethod pp/simple-dispatch Matrix [obj] (pr obj))

(defn transform-in [pt mtx]
  (if (and (some? pt) (some? mtx))
    (-> (matrix)
        (translate pt)
        (multiply mtx)
        (translate (gpt/negate pt)))
    mtx))

;; FIXME: performance
(defn determinant
  "Determinant for the affinity transform"
  [{:keys [a b c d _ _]}]
  (- (* a d) (* c b)))

(defn inverse
  "Gets the inverse of the affinity transform `mtx`"
  [{:keys [a b c d e f] :as mtx}]
  (let [det (determinant mtx)]
    (when-not ^boolean (mth/almost-zero? det)
      (let [a' (/  d det)
            b' (/ (- b) det)
            c' (/ (- c) det)
            d' (/  a det)
            e' (/ (- (* c f) (* d e)) det)
            f' (/ (- (* b e) (* a f)) det)]
        (pos->Matrix a' b' c' d' e' f')))))

(defn round
  [mtx]
  (-> mtx
      (update :a mth/precision 4)
      (update :b mth/precision 4)
      (update :c mth/precision 4)
      (update :d mth/precision 4)
      (update :e mth/precision 4)
      (update :f mth/precision 4)))

(defn transform-point-center
  "Transform a point around the shape center"
  [point center matrix]
  (if (and (some? point) (some? matrix) (some? center))
    (gpt/transform
     point
     (multiply (translate-matrix center)
               matrix
               (translate-matrix (gpt/negate center))))
    point))

(defn move?
  [m]
  (and ^boolean (mth/almost-zero? (- (dm/get-prop m :a) 1))
       ^boolean (mth/almost-zero? (dm/get-prop m :b))
       ^boolean (mth/almost-zero? (dm/get-prop m :c))
       ^boolean (mth/almost-zero? (- (dm/get-prop m :d) 1))))

#?(:clj
   (fres/add-handlers!
    {:name "penpot/matrix"
     :class Matrix
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-list! w (List/of (.-a ^Matrix o)
                                         (.-b ^Matrix o)
                                         (.-c ^Matrix o)
                                         (.-d ^Matrix o)
                                         (.-e ^Matrix o)
                                         (.-f ^Matrix o))))
     :rfn (fn [rdr]
            (let [^List x (fres/read-object! rdr)]
              (pos->Matrix (.get x 0)
                           (.get x 1)
                           (.get x 2)
                           (.get x 3)
                           (.get x 4)
                           (.get x 5))))}))

(t/add-handlers!
 {:id "matrix"
  :class Matrix
  :wfn #(into {} %)
  :rfn (fn [m]
         (pos->Matrix (get m :a)
                      (get m :b)
                      (get m :c)
                      (get m :d)
                      (get m :e)
                      (get m :f)))})
