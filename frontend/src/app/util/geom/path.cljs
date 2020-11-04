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

#_(let [path "M.343 15.974a.514.514 0 01-.317-.321c-.023-.07-.026-.23-.026-1.43 0-1.468-.001-1.445.09-1.586.02-.032 1.703-1.724 3.74-3.759a596.805 596.805 0 003.7-3.716c0-.009-.367-.384-.816-.833a29.9 29.9 0 01-.817-.833c0-.01.474-.49 1.054-1.07l1.053-1.053.948.946.947.947 1.417-1.413C12.366.806 12.765.418 12.856.357c.238-.161.52-.28.792-.334.17-.034.586-.03.76.008.801.173 1.41.794 1.57 1.603.03.15.03.569 0 .718a2.227 2.227 0 01-.334.793c-.061.09-.45.49-1.496 1.54L12.734 6.1l.947.948.947.947-1.053 1.054c-.58.58-1.061 1.054-1.07 1.054-.01 0-.384-.368-.833-.817-.45-.45-.824-.817-.834-.817-.009 0-1.68 1.666-3.716 3.701a493.093 493.093 0 01-3.759 3.74c-.14.091-.117.09-1.59.089-1.187 0-1.366-.004-1.43-.027zm6.024-4.633a592.723 592.723 0 003.663-3.68c0-.02-1.67-1.69-1.69-1.69-.01 0-1.666 1.648-3.68 3.663L.996 13.297v.834c0 .627.005.839.02.854.015.014.227.02.854.02h.833l3.664-3.664z"
      content (path->content path)
      new-path (content->path content)
      ]
  (prn "path" path)
  (.log js/console "?? 1" (clj->js content))
  (prn "?? 2" (= path new-path) new-path))
