;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.worker.align
  "Workspace aligment indexes worker."
  (:require [beicon.core :as rx]
            [uxbox.util.kdtree :as kd]
            [uxbox.worker.impl :as impl]
            [uxbox.util.geom.point :as gpt]))

(defonce tree (kd/create))

(defmethod impl/handler :grid-init
  [{:keys [sender width height x-axis y-axis] :as opts}]
  (time
   (kd/setup! tree width height (or x-axis 10) (or y-axis 10)))
  (impl/reply! sender nil))

(defmethod impl/handler :grid-align
  [{:keys [sender point] :as message}]
  (let [point [(:x point) (:y point)]
        results (kd/nearest tree point 1)
        [[x y] d] (first results)
        result (gpt/point x y)]
    (impl/reply! sender {:point (gpt/point x y)})))
