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
       (let [is-prev-val? (d/not-empty? @prev-modifiers)
             is-cur-val? (d/not-empty? modifiers)]

         (when (and (not is-prev-val?) is-cur-val?)
           (utils/start-transform! node shapes))

         (when is-cur-val?
           (utils/update-transform! node shapes transforms modifiers))

         (when (and is-prev-val? (not is-cur-val?))
           (utils/remove-transform! node @prev-shapes))

         (reset! prev-modifiers modifiers)
         (reset! prev-transforms transforms)
         (reset! prev-shapes shapes))))))
