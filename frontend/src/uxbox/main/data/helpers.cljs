;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.helpers)

(defn get-children
  "Retrieve all children ids recursively for a given shape"
  [shape-id objects]
  (let [shapes (get-in objects [shape-id :shapes])]
    (if shapes
      (concat
       shapes
       (mapcat get-children shapes))
      [])))
