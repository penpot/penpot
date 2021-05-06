;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.format
  (:require
   [app.common.data :as d]
   [app.util.path.commands :as upc]
   [cuerdas.core :as str]
   [app.util.path.subpaths :as ups]))

(defn command->param-list [command]
  (let [params (:params command)]
    (case (:command command)
      (:move-to :line-to :smooth-quadratic-bezier-curve-to)
      (str (:x params) ","
           (:y params))

      :close-path
      ""

      (:line-to-horizontal :line-to-vertical)
      (str (:value params))

      :curve-to
      (str (:c1x params) ","
           (:c1y params) ","
           (:c2x params) ","
           (:c2y params) ","
           (:x params) ","
           (:y params))

      (:smooth-curve-to :quadratic-bezier-curve-to)
      (str (:cx params) ","
           (:cy params) ","
           (:x params) ","
           (:y params))

      :elliptical-arc
      (str (:rx params) ","
           (:ry params) ","
           (:x-axis-rotation params) ","
           (:large-arc-flag params) ","
           (:sweep-flag params) ","
           (:x params) ","
           (:y params)))))

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
    (str command-str param-list)))


(defn format-path [content]
  (with-out-str
    (loop [last-move nil
           current (first content)
           content (rest content)]

      (when (some? current)
        (let [point (upc/command->point current)
              current-move? (= :move-to (:command current))
              last-move (if current-move? point last-move)]
          (print (command->string current))

          (when (and (not current-move?) (= last-move point))
            (print "Z"))

          (recur last-move
                 (first content)
                 (rest content)))))))
