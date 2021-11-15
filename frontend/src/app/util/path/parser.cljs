;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.parser
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as upg]
   [app.common.path.commands :as upc]
   [app.util.path.arc-to-curve :refer [a2c]]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]))

;;
(def commands-regex #"(?i)[mzlhvcsqta][^mzlhvcsqta]*")

;; Matches numbers for path values allows values like... -.01, 10, +12.22
;; 0 and 1 are special because can refer to flags
(def num-regex #"[+-]?(\d+(\.\d+)?|\.\d+)(e[+-]?\d+)?")

(def flag-regex #"[01]")

(defn extract-params [cmd-str extract-commands]
  (loop [result []
         extract-idx 0
         current {}
         remain (-> cmd-str (subs 1) (str/trim))]

    (let [[param type] (nth extract-commands extract-idx)
          regex (case type
                  :flag     flag-regex
                  #_:number num-regex)
          match (re-find regex remain)]

      (if match
        (let [value (-> match first usvg/fix-dot-number d/read-string)
              remain (str/replace-first remain regex "")
              current (assoc current param value)
              extract-idx (inc extract-idx)
              [result current extract-idx]
              (if (>=  extract-idx (count extract-commands))
                [(conj result current) {} 0]
                [result current extract-idx])]
          (recur result
                 extract-idx
                 current
                 remain))
        (cond-> result
          (seq current) (conj current))))))

;; Path specification
;; https://www.w3.org/TR/SVG11/paths.html
(defmulti parse-command (comp str/upper first))

(defmethod parse-command "M" [cmd]
  (let [relative (str/starts-with? cmd "m")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]

    (d/concat [{:command :move-to
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
                                        [:x   :number]
                                        [:y   :number]])]
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
        result (a2c from-x from-y x y large-arc-flag sweep-flag rx ry x-axis-rotation)]
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
                      (d/update-in-when [:params :c1x] + (:x prev-pos))
                      (d/update-in-when [:params :c1y] + (:y prev-pos))

                      (d/update-in-when [:params :c2x] + (:x prev-pos))
                      (d/update-in-when [:params :c2y] + (:y prev-pos))

                      (d/update-in-when [:params :cx] + (:x prev-pos))
                      (d/update-in-when [:params :cy] + (:y prev-pos))

                      (d/update-in-when [:params :x] + (:x prev-pos))
                      (d/update-in-when [:params :y] + (:y prev-pos))

                      (cond->
                          (= :line-to-horizontal (:command command))
                        (d/update-in-when [:params :value] + (:x prev-pos))

                        (= :line-to-vertical (:command command))
                        (d/update-in-when [:params :value] + (:y prev-pos)))))

                params (:params command)
                orig-command command

                command
                (cond-> command
                  (= :line-to-horizontal (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :x] (:value params))
                      (assoc-in [:params :y] (:y prev-pos)))

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
                         (d/concat result (arc->beziers prev-pos command))
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


(defn parse-path [path-str]
  (let [clean-path-str
        (-> path-str
            (str/trim)
            ;; Change "commas" for spaces
            (str/replace #"," " ")
            ;; Remove all consecutive spaces
            (str/replace #"\s+" " "))
        commands (re-seq commands-regex clean-path-str)]
    (-> (mapcat parse-command commands)
        (simplify-commands))))

