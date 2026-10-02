;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.plus
  "Plus signs made of two open line subpaths"
  (:require
   [app.common.types.path :as path]
   [benches.render-wasm.builder :as sb :include-macros true]
   [benches.render-wasm.declarations :as decl :include-macros true]
   [benches.render-wasm.runtime.camera :as camera]))

(def ^:private default-params
  {:count 1000 :width 1920 :height 1080})

(def ^:private schema:params
  [:map {:closed true}
   [:count [:int {:min 1 :max 100000}]]
   [:width [:int {:min 100}]]
   [:height [:int {:min 100}]]])

(defn- plus-content
  [{:keys [width height]}]
  (fn [rng]
    (let [x (sb/rng-int rng 20 width)
          y (sb/rng-int rng 20 height)
          r (sb/rng-int rng 10 40)]
      (path/from-plain
       [{:command :move-to :params {:x (- x r) :y y}}
        {:command :line-to :params {:x (+ x r) :y y}}
        {:command :move-to :params {:x x :y (- y r)}}
        {:command :line-to :params {:x x :y (+ y r)}}]))))

(defn build
  [params]
  (let [params (merge default-params params)]
    (sb/scene {:seed (:seed params)
               :root {:x 0 :y 0 :width (:width params) :height (:height params)}
               :defaults {:path {:content (plus-content params)
                                 :fills []
                                 :strokes (sb/gen-vector (sb/gen-stroke 4))}}}
              (doseq [_ (range (:count params))]
                (sb/path)))))

(decl/defscene :plus
  {:version 1
   :description "Plus signs made of two open line subpaths"
   :params-schema schema:params}
  build)

(def ^:private base-view {:scale 1 :x 0 :y 0})

(decl/defcase :plus/load :plus
  {:params default-params :view base-view :context :fresh}
  (fn [rtx] (camera/load! rtx)))

(decl/defcase :plus/pan :plus
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :dx 140 :dy 60 :settle-ms 100}}
  (fn [rtx] (camera/pan! rtx)))

(decl/defcase :plus/zoom :plus
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :factor 1.4 :settle-ms 100}}
  (fn [rtx] (camera/zoom! rtx)))
