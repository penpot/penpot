;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns app.util.geom.path
  (:require
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.util.geom.path-impl-simplify :as impl-simplify]))

(defn simplify
  ([points]
   (simplify points 0.1))
  ([points tolerance]
   (let [points (into-array points)]
     (into [] (impl-simplify/simplify points tolerance true)))))

;;
(def commands-regex #"(?i)[a-z][^a-z]*")

;; Matches numbers for path values allows values like... -.01, 10, +12.22
;; 0 and 1 are special because can refer to flags
(def num-regex #"([+-]?(([1-9]\d*(\.\d+)?)|(\.\d+)|0|1))")


(defn coord-n [size]
  (re-pattern (str "(?i)[a-z]\\s*"
                   (->> (range size)
                        (map #(identity num-regex))
                        (str/join "\\s+")))))


(defn parse-params [cmd-str num-params]
  (let [fix-starting-dot (fn [arg] (str/replace arg #"([^\d]|^)\." "$10."))]
    (->> (re-seq num-regex cmd-str)
         (map first)
         (map fix-starting-dot)
         (map d/read-string)
         (partition num-params))))

(defn command->param-list [{:keys [command params]}]
  (case command
    (:move-to :line-to :smooth-quadratic-bezier-curve-to)
    (let [{:keys [x y]} params] [x y])

    :close-path
    []

    (:line-to-horizontal :line-to-vertical)
    (let [{:keys [value]} params] [value])

    :curve-to
    (let [{:keys [c1x c1y c2x c2y x y]} params] [c1x c1y c2x c2y x y])

    (:smooth-curve-to :quadratic-bezier-curve-to)
    (let [{:keys [cx cy x y]} params] [cx cy x y])

    :elliptical-arc
    (let [{:keys [rx ry x-axis-rotation large-arc-flag sweep-flag x y]} params]
      [rx ry x-axis-rotation large-arc-flag sweep-flag x y])))

;; Path specification
;; https://www.w3.org/TR/SVG11/paths.html
(defmulti parse-command (comp str/upper first))

(defmethod parse-command "M" [cmd]
  (let [relative (str/starts-with? cmd "m")
        params (parse-params cmd 2)]
    (for [[x y] params]
      {:command :move-to
       :relative relative
       :params {:x x :y y}})))

(defmethod parse-command "Z" [cmd]
  [{:command :close-path}])

(defmethod parse-command "L" [cmd]
  (let [relative (str/starts-with? cmd "l")
        params (parse-params cmd 2)]
    (for [[x y] params]
      {:command :line-to
       :relative relative
       :params {:x x :y y}})))

(defmethod parse-command "H" [cmd]
  (let [relative (str/starts-with? cmd "h")
        params (parse-params cmd 1)]
    (for [[value] params]
      {:command :line-to-horizontal
       :relative relative
       :params {:value value}})))

(defmethod parse-command "V" [cmd]
  (let [relative (str/starts-with? cmd "v")
        params (parse-params cmd 1)]
    (for [[value] params]
      {:command :line-to-vertical
       :relative relative
       :params {:value value}})))

(defmethod parse-command "C" [cmd]
  (let [relative (str/starts-with? cmd "c")
        params (parse-params cmd 6)]
    (for [[c1x c1y c2x c2y x y] params]
      {:command :curve-to
       :relative relative
       :params {:c1x c1x
                :c1y c1y
                :c2x c2x
                :c2y c2y
                :x x
                :y y}})))

(defmethod parse-command "S" [cmd]
  (let [relative (str/starts-with? cmd "s")
        params (parse-params cmd 4)]
    (for [[cx cy x y] params]
      {:command :smooth-curve-to
       :relative relative
       :params {:cx cx
                :cy cy
                :x x
                :y y}})))

(defmethod parse-command "Q" [cmd]
  (let [relative (str/starts-with? cmd "s")
        params (parse-params cmd 4)]
    (for [[cx cy x y] params]
      {:command :quadratic-bezier-curve-to
       :relative relative
       :params {:cx cx
                :cy cy
                :x x
                :y y}})))

(defmethod parse-command "T" [cmd]
  (let [relative (str/starts-with? cmd "t")
        params (parse-params cmd (coord-n 2))]
    (for [[cx cy x y] params]
      {:command :smooth-quadratic-bezier-curve-to
       :relative relative
       :params {:x x
                :y y}})))

(defmethod parse-command "A" [cmd]
  (let [relative (str/starts-with? cmd "a")
        params (parse-params cmd 7)]
    (for [[rx ry x-axis-rotation large-arc-flag sweep-flag x y] params]
      {:command :elliptical-arc
       :relative relative
       :params {:rx rx
                :ry ry
                :x-axis-rotation x-axis-rotation
                :large-arc-flag large-arc-flag
                :sweep-flag sweep-flag
                :x x
                :y y}})))

(defn command->string [{:keys [command relative params] :as entry}]
  (let [command-str (case command
                      :move-to "M"
                      :close-path "Z"
                      :line-to "L"
                      :line-to-horizontal "H"
                      :line-to-vertical "V"
                      :curve-to "C"
                      :smooth-curve-to "S"
                      :quadratic-bezier-curve-to "Q"
                      :smooth-quadratic-bezier-curve-to "T"
                      :elliptical-arc "A")
        command-str (if relative (str/lower command-str) command-str)
        param-list (command->param-list entry)]
    (str/fmt "%s%s" command-str (str/join " " param-list))))

(defn path->content [string]
  (let [clean-string (-> string
                         (str/trim)
                         ;; Change "commas" for spaces
                         (str/replace #"," " ")
                         ;; Remove all consecutive spaces
                         (str/replace #"\s+" " "))
        commands (re-seq commands-regex clean-string)]
    (mapcat parse-command commands)))

(defn content->path [content]
  (->> content
       (map command->string)
       (str/join "")))

(defn make-curve-params
  ([point]
   (make-curve-params point point point))

  ([point handler] (make-curve-params point handler point))

  ([point h1 h2]
   {:x (:x point)
    :y (:y point)
    :c1x (:x h1)
    :c1y (:y h1)
    :c2x (:x h2)
    :c2y (:y h2)}))

(defn opposite-handler
  [point handler]
  (let [phv (gpt/to-vec point handler)
        opposite (gpt/add point (gpt/negate phv))]
    opposite))

(defn segments->content [segments]
  (let [initial (first segments)
        closed? (= (first segments) (last segments))
        lines (if closed?
                (take (- (count segments) 2) (rest segments))
                (rest segments))]

    (d/concat [{:command :move-to
                :params (select-keys initial [:x :y])}]
              (->> lines
                   (mapv #(hash-map :command :line-to
                                    :params (select-keys % [:x :y]))))

              (when closed?
                [{:command :close-path}]))))
