;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.viewport-ref
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defonce viewport-ref (atom nil))
(defonce current-observer (atom nil))
(defonce viewport-brect (atom nil))

(defn init-observer
  [node on-change-bounds]

  (let [observer (js/ResizeObserver. on-change-bounds)]
    (when (some? @current-observer)
      (.disconnect @current-observer))

    (reset! current-observer observer)

    (when (some? node)
      (.observe observer node))))

(defn on-change-bounds
  [_]
  (when @viewport-ref
    (let [brect (dom/get-bounding-rect @viewport-ref)
          brect (gpt/point (d/parse-integer (:left brect))
                           (d/parse-integer (:top brect)))]
      (reset! viewport-brect brect))))

(defn create-viewport-ref
  []
  (let [ref (mf/use-ref nil)]
    [ref
     (mf/use-memo
      #(fn [node]
         (mf/set-ref-val! ref node)
         (reset! viewport-ref node)
         (init-observer node on-change-bounds)))]))

(defn point->viewport
  [pt]
  (let [zoom (dm/get-in @st/state [:workspace-local :zoom] 1)]
    (when (and (some? @viewport-ref)
               (some? @viewport-brect))
      (let [vbox     (.. ^js @viewport-ref -viewBox -baseVal)
            brect    @viewport-brect
            box      (gpt/point (.-x vbox) (.-y vbox))
            zoom     (gpt/point zoom)]

        (-> (gpt/subtract pt brect)
            (gpt/divide zoom)
            (gpt/add box))))))

(defn inside-viewport?
  [target]
  (dom/is-child? @viewport-ref target))
