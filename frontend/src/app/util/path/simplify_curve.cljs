;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.simplify-curve
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gshp]
   [app.util.path.path-impl-simplify :as impl-simplify]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [clojure.set :as set]
   [app.common.math :as mth]))

(defn simplify
  "Simplifies a drawing done with the pen tool"
  ([points]
   (simplify points 0.1))
  ([points tolerance]
   (let [points (into-array points)]
     (into [] (impl-simplify/simplify points tolerance true)))))
