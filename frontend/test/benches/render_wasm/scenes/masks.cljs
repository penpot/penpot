;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.masks
  "Fixed grid of masked groups with circle masks and ordered descendants."
  (:require
   [benches.render-wasm.builder :as sb :include-macros true]
   [benches.render-wasm.declarations :as decl :include-macros true]
   [benches.render-wasm.runtime.camera :as camera]))

(def ^:const ^:private default-params
  {:width 1920 :height 1080})

(def ^:const ^:private schema:params
  [:map {:closed true}
   [:width [:int {:min 1920}]]
   [:height [:int {:min 1080}]]])

(decl/defscene :masks
  {:version 1
   :description "Twenty-four circle masks with ordered striped descendants"
   :params-schema schema:params}
  (fn [params]
    (let [params (merge default-params params)]
      (sb/scene {:seed (:seed params)
                 :root {:x 0 :y 0 :width (:width params) :height (:height params)}}
                (doseq [i (range 24)]
                  (let [x (+ 25 (* 310 (mod i 6)))
                        y (+ 25 (* 255 (quot i 6)))]
                    (sb/group [:mask i] {:masked-group true}
                              (sb/circle {:x x :y y :width 180 :height 180
                                          :fills [{:fill-color "#ffffff"}]})
                              (doseq [j (range 8)]
                                (sb/rect {:x (+ x (* 28 j) -22)
                                          :y (- y 12)
                                          :width 25
                                          :height 205
                                          :fills [{:fill-color (if (even? j) "#1254b8" "#ff8b38")}]})))))))))

(def ^:const ^:private base-view {:scale 1 :x 0 :y 0})

(decl/defcase :masks/load :masks
  {:params default-params :view base-view :context :fresh}
  (fn [rtx] (camera/load! rtx)))

(decl/defcase :masks/pan :masks
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :dx 120 :dy 40 :settle-ms 100}}
  (fn [rtx] (camera/pan! rtx)))

(decl/defcase :masks/zoom :masks
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :factor 1.6 :settle-ms 100}}
  (fn [rtx] (camera/zoom! rtx)))
