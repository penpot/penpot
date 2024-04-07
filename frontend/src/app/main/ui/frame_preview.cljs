;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.frame-preview
  (:require
   [app.common.data :as d]
   [rumext.v2 :as mf]))

(mf/defc frame-preview
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  []

  (let [iframe-ref (mf/use-ref nil)
        last-data* (mf/use-state nil)

        zoom-ref (mf/use-ref nil)
        zoom* (mf/use-state 1)
        zoom  @zoom*


        handle-load
        (mf/use-callback
         (fn [data width height]
           (reset! last-data* data)
           (let [iframe-dom (mf/ref-val iframe-ref)]
             (when iframe-dom
               (-> iframe-dom (aset "width" (+ width 64)))
               (-> iframe-dom (aset "height" (+ height 64)))
               (-> iframe-dom .-contentWindow .-document .open)
               (-> iframe-dom .-contentWindow .-document (.write data))
               (-> iframe-dom .-contentWindow .-document .close)))))

        load-ref
        (mf/use-callback
         (fn [iframe-dom]
           (.log js/console "load-ref" iframe-dom)
           (mf/set-ref-val! iframe-ref iframe-dom)
           (when (and iframe-dom @last-data*)
             (-> iframe-dom .-contentWindow .-document .open)
             (-> iframe-dom .-contentWindow .-document (.write @last-data*))
             (-> iframe-dom .-contentWindow .-document .close))))

        change-zoom
        (mf/use-callback
         (fn []
           (let [zoom-level (d/parse-integer (.-value (mf/ref-val zoom-ref)))]
             (reset! zoom* (/ zoom-level 100)))))]

    (mf/use-effect
     (fn []
       (aset js/window "load" handle-load)
       #(js-delete js/window "load")))

    [:div {:style {:display "flex" :width "100%" :height "100%" :flex-direction "column" :overflow "auto" :align-items "center"}}
     [:input {:id "zoom-input"
              :ref zoom-ref
              :type "range" :min 1 :max 400 :default-value 100
              :on-change change-zoom
              :style {:max-width "500px"}}]

     [:div {:style {:width "100%" :height "100%" :overflow "auto"}}
      [:iframe {:ref load-ref
                :frame-border "0"
                :scrolling "no"
                :style {:transform-origin "top left"
                        :transform (str "scale(" zoom ")")}}]]]))
