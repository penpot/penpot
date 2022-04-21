;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame.dynamic-modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.workspace.viewport.utils :as utils]
   [rumext.alpha :as mf]))

(defn use-dynamic-modifiers
  [objects node modifiers]

  (let [transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           (when (some? modifiers)
             (d/mapm (fn [id {modifiers :modifiers}]
                       (let [center (gsh/center-shape (get objects id))]
                         (gsh/modifiers->transform center modifiers)))
                     modifiers))))

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           (->> (keys transforms)
                (mapv (d/getf objects)))))

        prev-shapes (mf/use-var nil)
        prev-modifiers (mf/use-var nil)
        prev-transforms (mf/use-var nil)]

    (mf/use-layout-effect
     (mf/deps transforms)
     (fn []
       (when (and (nil? @prev-transforms)
                  (some? transforms))
         (utils/start-transform! node shapes))

       (when (some? modifiers)
         (utils/update-transform! node shapes transforms modifiers))

       (when (and (some? @prev-modifiers)
                  (empty? modifiers))
         (utils/remove-transform! node @prev-shapes))

       (reset! prev-modifiers modifiers)
       (reset! prev-transforms transforms)
       (reset! prev-shapes shapes)))))
