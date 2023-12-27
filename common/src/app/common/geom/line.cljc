;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.line)

(defn line-value
  [[{px :x py :y} {vx :x vy :y}] {:keys [x y]}]
  (let [a vy
        b (- vx)
        c (+ (* (- vy) px) (* vx py))]
    (+ (* a x) (* b y) c)))

(defn is-inside-lines?
  [line-1 line-2 pos]
  (< (* (line-value line-1 pos) (line-value line-2 pos)) 0))
