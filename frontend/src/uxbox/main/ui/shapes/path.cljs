;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.path
  (:require [potok.core :as ptk]
            [cuerdas.core :as str :include-macros true]
            [uxbox.main.store :as st]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.mixins :as mx :include-macros true]))
;; --- Path Component

(declare path-shape)

(mx/defc path-component
  {:mixins [mx/static mx/reactive]}
  [{:keys [id] :as shape}]
  (let [modifiers (mx/react (common/modifiers-ref id))
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        shape (assoc shape :modifiers modifiers)]
    (letfn [(on-mouse-down [event]
              (common/on-mouse-down event shape selected))
            (on-double-click [event]
              (when selected?
                (st/emit! (uds/start-edition-mode id))))]
      [:g.shape {:class (when selected? "selected")
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       (path-shape shape)])))

;; --- Path Shape

(defn- render-path
  [{:keys [segments close?] :as shape}]
  (let [numsegs (count segments)]
    (loop [buffer []
           index 0]
      (cond
        (>= index numsegs)
        (if close?
          (str/join " " (conj buffer "Z"))
          (str/join " " buffer))

        (zero? index)
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "M~{x},~{y}"))]
          (recur buffer (inc index)))

        :else
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "L~{x},~{y}"))]
          (recur buffer (inc index)))))))

(mx/defc path-shape
  {:mixins [mx/static]}
  [{:keys [id modifiers rotation] :as shape}]
  (let [{:keys [resize displacement]} modifiers
        shape (cond-> shape
                displacement (geom/transform displacement)
                resize (geom/transform resize)
                (pos? rotation) (geom/rotate-shape))

        props {:id (str id)
               :d (render-path shape)}
        attrs (merge props (attrs/extract-style-attrs shape))]
    [:path attrs]))
