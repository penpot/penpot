;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.geom.matrix
  (:require [cuerdas.core :as str]
            [cognitect.transit :as t]
            [uxbox.util.math :as mth]
            [uxbox.util.geom.point :as gpt]))

;; --- Matrix Impl

(defrecord Matrix [a b c d e f])

(defprotocol ICoerce
  "Matrix coersion protocol."
  (-matrix [v] "Return a matrix instance."))

(extend-type Matrix
  cljs.core/IDeref
  (-deref [v]
    (mapv #(get v %) [:a :b :c :d :e :f]))

  Object
  (toString [v]
    (->> (str/join "," @v)
         (str/format "matrix(%s)"))))

(extend-protocol ICoerce
  nil
  (-matrix [_]
    (Matrix. 1 0 0 1 0 0))

  Matrix
  (-matrix [v] v)

  cljs.core/PersistentVector
  (-matrix [v]
    (let [[a b c d e f] v]
      (Matrix. a b c d e f)))

  cljs.core/IndexedSeq
  (-matrix [v]
    (let [[a b c d e f] v]
      (Matrix. a b c d e f))))

(defn multiply
  ([m1 m2]
   (Matrix.
    (+ (* (:a m1) (:a m2)) (* (:c m1) (:b m2)))
    (+ (* (:b m1) (:a m2)) (* (:d m1) (:b m2)))
    (+ (* (:a m1) (:c m2)) (* (:c m1) (:d m2)))
    (+ (* (:b m1) (:c m2)) (* (:d m1) (:d m2)))
    (+ (* (:a m1) (:e m2)) (* (:c m1) (:f m2)) (:e m1))
    (+ (* (:b m1) (:e m2)) (* (:d m1) (:f m2)) (:f m1))))

  ([m1 m2 & others]
   (reduce multiply (multiply m1 m2) others)))

(defn ^boolean matrix?
  "Return true if `v` is Matrix instance."
  [v]
  (instance? Matrix v))

(defn matrix
  "Create a new matrix instance."
  ([]
   (Matrix. 1 0 0 1 0 0))
  ([v]
   (-matrix v))
  ([a b c d e f]
   (Matrix. a b c d e f)))

(defn translate-matrix
  [pt]
  (let [pt (gpt/point pt)]
    (Matrix. 1 0 0 1 (:x pt) (:y pt))))

(defn scale-matrix
  [s]
  (let [pt (gpt/point s)]
    (Matrix. (:x pt) 0 0 (:y pt) 0 0)))

(defn rotate-matrix
  [a]
  (let [a (mth/radians a)]
    (Matrix.
     (mth/cos a)
     (mth/sin a)
     (- (mth/sin a))
     (mth/cos a)
     0
     0)))

;; OLD
;; (defn rotate
;;   "Apply rotation transformation to the matrix."
;;   ([m angle]
;;    (multiply m (rotate-matrix angle)))
;;   ([m angle center]
;;    (multiply m
;;            (translate-matrix center)
;;            (rotate-matrix angle)
;;            (translate-matrix (gpt/negate center)))))

;; -- ROTATE
;; r = radians(r)
;; const cos = Math.cos(r)
;; const sin = Math.sin(r)
;;
;; const { a, b, c, d, e, f } = this
;;
;; this.a = a * cos - b * sin
;; this.b = b * cos + a * sin
;; this.c = c * cos - d * sin
;; this.d = d * cos + c * sin
;; this.e = e * cos - f * sin + cy * sin - cx * cos + cx
;; this.f = f * cos + e * sin - cx * sin - cy * cos + cy

;; (defn rotate
;;   ([m angle] (rotate m angle (gpt/point 0 0)))
;;   ([m angle center]
;;    (let [{:keys [a b c d e f]} m
;;          {cx :x cy :y} center
;;          r (mth/radians angle)
;;          cos (mth/cos r)
;;          sin (mth/sin r)
;;          a' (- (* a cos) (* b sin))
;;          b' (+ (* b cos) (* a sin))
;;          c' (- (* c cos) (* d sin))
;;          d' (+ (* d cos) (* c sin))
;;          e' (+ (- (* e cos) (* f sin))
;;                 (- (* cy sin) (* cx cos))
;;                 cx)
;;          f' (+ (- (+ (* f cos) (* e sin))
;;                    (* cx sin)
;;                    (* cy cos))
;;                 cy)]
;;      (Matrix. a' b' c' d' e' f'))))


;; export function rotate (angle, cx, cy) {
;;   const cosAngle = cos(angle)
;;   const sinAngle = sin(angle)
;;   const rotationMatrix = {
;;     a: cosAngle,
;;     c: -sinAngle,
;;     e: 0,
;;     b: sinAngle,
;;     d: cosAngle,
;;     f: 0
;;   }
;;   if (isUndefined(cx) || isUndefined(cy)) {
;;     return rotationMatrix
;;   }

;;   return transform([
;;     translate(cx, cy),
;;     rotationMatrix,
;;     translate(-cx, -cy)
;;   ])
;; }

(defn rotate
  "Apply rotation transformation to the matrix."
  ([m angle]
   (multiply m (rotate-matrix angle)))
  ([m angle center]
   (multiply m
             (translate-matrix center)
             (rotate-matrix angle)
             (translate-matrix (gpt/negate center)))))

;; TODO: temporal backward compatibility
(def rotate* rotate)

  ;; ([m v] (scale m v v))
  ;; ([m x y]
  ;;  (assoc m
  ;;         :a (* (:a m) x)
  ;;         :c (* (:c m) x)
  ;;         :b (* (:b m) y)
  ;;         :d (* (:d m) y))))



  ;; scaleO (x, y = x, cx = 0, cy = 0) {
  ;;   // Support uniform scaling
  ;;   if (arguments.length === 3) {
  ;;     cy = cx
  ;;     cx = y
  ;;     y = x
  ;;   }

  ;;   const { a, b, c, d, e, f } = this

  ;;   this.a = a * x
  ;;   this.b = b * y
  ;;   this.c = c * x
  ;;   this.d = d * y
  ;;   this.e = e * x - cx * x + cx
  ;;   this.f = f * y - cy * y + cy

  ;;   return this
  ;; }

;; (defn scale
;;   "Apply scale transformation to the matrix."
;;   ([m x] (scale m x x))
;;   ([m x y]
;;    (let [{:keys [a b c d e f]} m
;;          cx 0
;;          cy 0
;;          a' (* a x)
;;          b' (* b y)
;;          c' (* c x)
;;          d' (* d y)
;;          e' (+ cx (- (* e x)
;;                       (* cx x)))
;;          f' (+ cy (- (* f y)
;;                       (* cy y)))]
;;      (Matrix. a' b' c' d' e f))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m s] (multiply m (scale-matrix s)))
  ([m s c]
   (multiply m
             (translate-matrix c)
             (scale-matrix s)
             (translate-matrix (gpt/negate c)))))

(defn translate
  "Apply translate transformation to the matrix."
  [m pt]
  (let [pt (gpt/point pt)]
    (multiply m (translate-matrix pt))))

(defn ^boolean invertible?
  [{:keys [a b c d e f] :as m}]
  (let [det (- (* a d) (* c b))]
    (and (not (mth/nan? det))
         (mth/finite? e)
         (mth/finite? f))))

(defn invert
  [{:keys [a b c d e f] :as m}]
  (when (invertible? m)
    (let [det (- (* a d) (* c b))]
      (Matrix. (/ d det)
               (/ (- b) det)
               (/ (- c) det)
               (/ a det)
               (/ (- (* c f) (* d e)) det)
               (/ (- (* b e) (* a f)) det)))))

;; --- Transit Adapter

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler
   (fn [value]
     (map->Matrix value))))

