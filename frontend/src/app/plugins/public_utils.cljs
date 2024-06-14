;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.public-utils
  "Utilities that will be exposed to plugins developers"
  (:require
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.plugins.utils :as u]))

(defn ^:export centerShapes
  [shapes]
  (let [shapes (->> shapes (map u/proxy->shape))]
    (-> (gsh/shapes->rect shapes)
        (grc/rect->center)
        (u/to-js))))
