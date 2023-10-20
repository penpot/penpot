;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg.path
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as upg]
   [app.common.math :as mth]
   [app.common.path.commands :as upc]
   [app.common.svg :as csvg]
   [cuerdas.core :as str]))

(def commands-regex #"(?i)[mzlhvcsqta][^mzlhvcsqta]*")
(def regex #"([+-]?(\d+(\.\d+)?|\.\d+)(e[+-]?\d+)?|[01])")


(defn extract-params
  [data params-pattern]
  (loop [result []
         pattern (seq params-pattern)
         current {}
         entries (re-seq regex data)]

    (if (and entries pattern)
      (let [[attr-name
             attr-type] (first pattern)
            match       (first entries)
            rval        (first match)
            val         (if (= attr-type :flag)
                          (d/parse-integer rval)
                          (-> rval csvg/fix-dot-number d/parse-double))
            current     (assoc current attr-name val)
            next-pattern (next pattern)]

        (if (some? next-pattern)
          (recur result
                 next-pattern
                 current
                 (next entries))
          (recur (conj result current)
                 (seq params-pattern)
                 {}
                 (next entries))))

      (if (seq current)
        (conj result current)
        result))))

;; Path specification
;; https://www.w3.org/TR/SVG11/paths.html
(defmulti parse-command
  (fn [cmd]
    (str/upper (subs cmd 0 1))))

(defmethod parse-command "M" [cmd]
  (let [relative (str/starts-with? cmd "m")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]

    (into [{:command :move-to
            :relative relative
            :params (first param-list)}]

          (for [params (rest param-list)]
            {:command :line-to
             :relative relative
             :params params}))))

(defmethod parse-command "Z" [_]
  [{:command :close-path}])

(defmethod parse-command "L" [cmd]
  (let [relative (str/starts-with? cmd "l")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :line-to
       :relative relative
       :params params})))

(defmethod parse-command "H" [cmd]
  (let [relative (str/starts-with? cmd "h")
        param-list (extract-params cmd [[:value :number]])]
    (for [params param-list]
      {:command :line-to-horizontal
       :relative relative
       :params params})))

(defmethod parse-command "V" [cmd]
  (let [relative (str/starts-with? cmd "v")
        param-list (extract-params cmd [[:value :number]])]
    (for [params param-list]
      {:command :line-to-vertical
       :relative relative
       :params params})))

(defmethod parse-command "C" [cmd]
  (let [relative (str/starts-with? cmd "c")
        param-list (extract-params cmd [[:c1x :number]
                                        [:c1y :number]
                                        [:c2x :number]
                                        [:c2y :number]
                                        [:x   :number]
                                        [:y   :number]])
        ]
    (for [params param-list]
      {:command :curve-to
       :relative relative
       :params params})))

(defmethod parse-command "S" [cmd]
  (let [relative (str/starts-with? cmd "s")
        param-list (extract-params cmd [[:cx :number]
                                        [:cy :number]
                                        [:x  :number]
                                        [:y  :number]])]
    (for [params param-list]
      {:command :smooth-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "Q" [cmd]
  (let [relative (str/starts-with? cmd "q")
        param-list (extract-params cmd [[:cx :number]
                                        [:cy :number]
                                        [:x  :number]
                                        [:y  :number]])]
    (for [params param-list]
      {:command :quadratic-bezier-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "T" [cmd]
  (let [relative (str/starts-with? cmd "t")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :smooth-quadratic-bezier-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "A" [cmd]
  (let [relative (str/starts-with? cmd "a")
        param-list (extract-params cmd [[:rx :number]
                                        [:ry :number]
                                        [:x-axis-rotation :number]
                                        [:large-arc-flag :flag]
                                        [:sweep-flag :flag]
                                        [:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :elliptical-arc
       :relative relative
       :params params})))

(defn smooth->curve
  [{:keys [params]} pos handler]
  (let [{c1x :x c1y :y} (upg/calculate-opposite-handler pos handler)]
    {:c1x c1x
     :c1y c1y
     :c2x (:cx params)
     :c2y (:cy params)}))

(defn quadratic->curve
  [sp ep cp]
  (let [cp1 (-> (gpt/to-vec sp cp)
                (gpt/scale (/ 2 3))
                (gpt/add sp))

        cp2 (-> (gpt/to-vec ep cp)
                (gpt/scale (/ 2 3))
                (gpt/add ep))]

    {:c1x (:x cp1)
     :c1y (:y cp1)
     :c2x (:x cp2)
     :c2y (:y cp2)}))

(defn- unit-vector-angle
  [ux uy vx vy]
  (let [sign (if (> 0 (- (* ux vy) (* uy vx))) -1.0 1.0)
        dot  (+ (* ux vx) (* uy vy))
        dot  (cond
               (> dot 1.0)   1.0
               (< dot -1.0) -1.0
               :else         dot)]
    (* sign (mth/acos dot))))

(defn- get-arc-center [x1 y1 x2 y2 fa fs rx ry sin-phi cos-phi]
  (let [x1p      (+ (* cos-phi (/ (- x1 x2) 2)) (* sin-phi (/ (- y1 y2) 2)))
        y1p      (+ (* (- sin-phi) (/ (- x1 x2) 2)) (* cos-phi (/ (- y1 y2) 2)))
        rx-sq    (* rx rx)
        ry-sq    (* ry ry)
        x1p-sq   (* x1p x1p)
        y1p-sq   (* y1p y1p)
        radicant (- (* rx-sq ry-sq)
                    (* rx-sq y1p-sq)
                    (* ry-sq x1p-sq))
        radicant (if (< radicant 0) 0 radicant)
        radicant (/ radicant (+ (* rx-sq y1p-sq) (* ry-sq x1p-sq)))
        radicant (* (mth/sqrt radicant) (if (= fa fs) -1 1))

        cxp      (* radicant (/ rx ry) y1p)
        cyp      (* radicant (/ (- ry) rx) x1p)
        cx       (+ (- (* cos-phi cxp)
                       (* sin-phi cyp))
                    (/ (+ x1 x2) 2))
        cy       (+ (* sin-phi cxp)
                    (* cos-phi cyp)
                    (/ (+ y1 y2) 2))

        v1x      (/ (- x1p cxp) rx)
        v1y      (/ (- y1p cyp) ry)
        v2x      (/ (- (- x1p) cxp) rx)
        v2y      (/ (- (- y1p) cyp) ry)
        theta1   (unit-vector-angle 1 0 v1x v1y)

        dtheta (unit-vector-angle v1x v1y v2x v2y)
        dtheta (if (and (= fs 0) (> dtheta 0)) (- dtheta (* mth/PI 2)) dtheta)
        dtheta (if (and (= fs 1) (< dtheta 0)) (+ dtheta (* mth/PI 2)) dtheta)]

    [cx cy theta1 dtheta]))

(defn approximate-unit-arc
  [theta1 dtheta]
  (let [alpha (* (/ 4 3) (mth/tan (/ dtheta 4)))
        x1 (mth/cos theta1)
        y1 (mth/sin theta1)
        x2 (mth/cos (+ theta1 dtheta))
        y2 (mth/sin (+ theta1 dtheta))]
    [x1 y1 (- x1 (* y1 alpha)) (+ y1 (* x1 alpha)) (+ x2 (* y2 alpha)) (- y2 (* x2 alpha)) x2 y2]))

(defn- process-curve
  [curve cc rx ry sin-phi cos-phi]
  (reduce (fn [curve i]
            (let [x  (nth curve i)
                  y  (nth curve (inc i))
                  x  (* x rx)
                  y  (* y ry)
                  xp (- (* cos-phi x) (* sin-phi y))
                  yp (+ (* sin-phi x) (* cos-phi y))]
              (-> curve
                  (assoc i (+ xp (nth cc 0)))
                  (assoc (inc i) (+ yp (nth cc 1))))))
          curve
          (range 0 (count curve) 2)))

(defn arc->beziers*
  [x1 y1 x2 y2 fa fs rx ry phi]
  (let [tau      (* mth/PI 2)
        phi-tau  (/ (* phi tau) 360)
        sin-phi  (mth/sin phi-tau)
        cos-phi  (mth/cos phi-tau)

        x1p      (+ (/ (* cos-phi (- x1 x2)) 2)
                    (/ (* sin-phi (- y1 y2)) 2))
        y1p      (+ (/ (* (- sin-phi) (- x1 x2)) 2)
                    (/ (* cos-phi (- y1 y2)) 2))]

    (if (or (= x1p 0)
            (= y1p 0)
            (= rx 0)
            (= ry 0))
      []
      (let [rx       (mth/abs rx)
            ry       (mth/abs ry)
            lambda   (+ (/ (* x1p x1p) (* rx rx))
                        (/ (* y1p y1p) (* ry ry)))
            rx       (if (> lambda 1) (* rx (mth/sqrt lambda)) rx)
            ry       (if (> lambda 1) (* ry (mth/sqrt lambda)) ry)

            cc       (get-arc-center x1 y1 x2 y2 fa fs rx ry sin-phi cos-phi)
            theta1   (nth cc 2)
            dtheta   (nth cc 3)
            segments (mth/max (mth/ceil (/ (mth/abs dtheta) (/ tau 4))) 1)
            dtheta   (/ dtheta segments)]

        (loop [i 0.0
               t (double theta1)
               r []]
          (if (< i segments)
            (let [curve (approximate-unit-arc t dtheta)
                  curve (process-curve curve cc rx ry sin-phi cos-phi)]
              (recur (inc i)
                     (+ t dtheta)
                     (conj r curve)))
            r))))))

(defn arc->beziers [from-p command]
  (let [to-command
        (fn [[_ _ c1x c1y c2x c2y x y]]
          {:command :curve-to
           :relative (:relative command)
           :params {:c1x c1x :c1y c1y
                    :c2x c2x :c2y c2y
                    :x   x   :y   y}})

        {from-x :x from-y :y} from-p
        {:keys [rx ry x-axis-rotation large-arc-flag sweep-flag x y]} (:params command)
        result (arc->beziers* from-x from-y x y large-arc-flag sweep-flag rx ry x-axis-rotation)]
    (mapv to-command result)))

(defn simplify-commands
  "Removes some commands and convert relative to absolute coordinates"
  [commands]
  (let [simplify-command
        ;; prev-pos   : previous position for the current path. Necessary for relative commands
        ;; prev-start : previous move-to necessary for Z commands
        ;; prev-cc    : previous command control point for cubic beziers
        ;; prev-qc    : previous command control point for quadratic curves
        (fn [[result prev-pos prev-start prev-cc prev-qc] [command _prev]]
          (let [command (assoc command :prev-pos prev-pos)

                command
                (cond-> command
                  (:relative command)
                  (-> (assoc :relative false)
                      (update :params (fn [params]
                                        (let [x (:x prev-pos)
                                              y (:y prev-pos)
                                              c (:command command)]
                                          (-> params
                                              (d/update-when :c1x + x)
                                              (d/update-when :c1y + y)

                                              (d/update-when :c2x + x)
                                              (d/update-when :c2y + y)

                                              (d/update-when :cx + x)
                                              (d/update-when :cy + y)

                                              (d/update-when :x + x)
                                              (d/update-when :y + y)

                                              (cond-> (= :line-to-horizontal c)
                                                (d/update-when :value + x))

                                              (cond-> (= :line-to-vertical c)
                                                (d/update-when :value + y))))))))

                params (:params command)
                orig-command command

                command
                (cond-> command
                  (= :line-to-horizontal (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (update :params (fn [params]
                                        (-> params
                                            (assoc :x (:value params))
                                            (assoc :y (:y prev-pos))))))

                  (= :line-to-vertical (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :y] (:value params))
                      (assoc-in [:params :x] (:x prev-pos)))

                  (= :smooth-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params dissoc :cx :cy)
                      (update :params merge (smooth->curve command prev-pos prev-cc)))

                  (= :quadratic-bezier-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params dissoc :cx :cy)
                      (update :params merge (quadratic->curve prev-pos (gpt/point params) (gpt/point (:cx params) (:cy params)))))

                  (= :smooth-quadratic-bezier-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params merge (quadratic->curve prev-pos (gpt/point params) (upg/calculate-opposite-handler prev-pos prev-qc)))))

                result (if (= :elliptical-arc (:command command))
                         (into result (arc->beziers prev-pos command))
                         (conj result command))

                next-cc (case (:command orig-command)
                          :smooth-curve-to
                          (gpt/point (get-in orig-command [:params :cx]) (get-in orig-command [:params :cy]))

                          :curve-to
                          (gpt/point (get-in orig-command [:params :c2x]) (get-in orig-command [:params :c2y]))

                          (:line-to-horizontal :line-to-vertical)
                          (gpt/point (get-in command [:params :x]) (get-in command [:params :y]))

                          (gpt/point (get-in orig-command [:params :x]) (get-in orig-command [:params :y])))

                next-qc (case (:command orig-command)
                          :quadratic-bezier-curve-to
                          (gpt/point (get-in orig-command [:params :cx]) (get-in orig-command [:params :cy]))

                          :smooth-quadratic-bezier-curve-to
                          (upg/calculate-opposite-handler prev-pos prev-qc)

                          (gpt/point (get-in orig-command [:params :x]) (get-in orig-command [:params :y])))

                next-pos (if (= :close-path (:command command))
                           prev-start
                           (upc/command->point prev-pos command))

                next-start (if (= :move-to (:command command)) next-pos prev-start)]

            [result next-pos next-start next-cc next-qc]))

        start (first commands)
        start (cond-> start
                (:relative start)
                (assoc :relative false))

        start-pos (gpt/point (:params start))]

    (->> (map vector (rest commands) commands)
         (reduce simplify-command [[start] start-pos start-pos start-pos start-pos])
         (first))))

(defn parse
  [path-str]
  (if (empty? path-str)
    path-str
    (let [commands (re-seq commands-regex path-str)]
      (-> (mapcat parse-command commands)
          (simplify-commands)))))
