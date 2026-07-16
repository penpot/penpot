;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.viewport-ref
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.mouse :as mse]
   [rumext.v2 :as mf]))

(defonce viewport-ref (atom nil))
(defonce viewport-brect (atom nil))

(defn- init-observer
  [node]
  (let [on-change-bounds
        (fn [_]
          (let [brect (dom/get-bounding-rect node)
                brect (gpt/point (d/parse-integer (:left brect))
                                 (d/parse-integer (:top brect)))]
            (reset! viewport-brect brect)))

        observer
        (js/ResizeObserver. on-change-bounds)]

    (.observe observer node)
    observer))

(defn create-viewport-ref
  []
  (let [node-ref     (mf/use-ref nil)
        handler-ref  (mf/use-ref nil)
        observer-ref (mf/use-ref nil)
        callback     (mf/use-fn
                      (fn [node]
                        ;; Dispose all previous resources
                        (when-let [observer (mf/ref-val observer-ref)]
                          (.disconnect ^js observer)
                          (mf/set-ref-val! observer-ref nil))


                        (when-let [handler (mf/ref-val handler-ref)]
                          (when-let [node (mf/ref-val node-ref)]
                            (.removeEventListener ^js node "mouseleave" handler)
                            (mf/set-ref-val! handler-ref nil)))

                        ;; Reset the ref values to the current node (can be nil)
                        (mf/set-ref-val! node-ref node)
                        (reset! viewport-ref node)

                        (when (some? node)
                          (let [handler  (fn [] (st/emit! (mse/->BlurEvent)))
                                observer (init-observer node)]
                            (.addEventListener ^js node "mouseleave" handler)

                            (mf/set-ref-val! handler-ref handler)
                            (mf/set-ref-val! observer-ref observer)))))]
    [node-ref callback]))

(defn point->viewport
  [pt]
  (let [zoom          (d/nilv @refs/selected-zoom 1)
        viewport-node  @viewport-ref
        viewport-brect @viewport-brect]

    (when (and (some? viewport-brect)
               (some? viewport-node))
      (let [vbox (.. ^js viewport-node -viewBox -baseVal)
            box  (gpt/point (.-x vbox) (.-y vbox))
            zoom (gpt/point zoom)]

        (-> (gpt/subtract pt viewport-brect)
            (gpt/divide zoom)
            (gpt/add box))))))

(defn point->viewport-relative
  "Convert client coordinates to viewport-relative coordinates.
   Unlike point->viewport, this does NOT convert to canvas coordinates -
   it just subtracts the viewport's bounding rect offset."
  [pt]
  (when-let [brect @viewport-brect]
    (gpt/subtract pt brect)))

(defn inside-viewport?
  [target]
  (dom/is-child? @viewport-ref target))
