;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.paths
  "Curved paths"
  (:require
   [app.common.schema :as sm]
   [app.common.types.path :as path]
   [benches.render-wasm.builder :as sb :include-macros true]
   [benches.render-wasm.declarations :as decl :include-macros true]
   [benches.render-wasm.runtime.camera :as camera]))

(def ^:private default-params
  {:count 1000 :width 1920 :height 1080})

(def ^:private schema:params
  [:map {:closed true}
   [:count [:int {:min 1 :max 100000}]]
   [:width ::sm/positive-safe-number]
   [:height ::sm/positive-safe-number]])

(defn- curved-content
  [{:keys [width height]}]
  (fn [rng]
    (let [x (sb/rng-int rng 0 width)
          y (sb/rng-int rng 0 height)
          w (sb/rng-int rng 20 100)
          h (sb/rng-int rng 20 100)]
      (path/from-plain
       [{:command :move-to :params {:x x :y y}}
        {:command :curve-to
         :params {:c1x (+ x w) :c1y y
                  :c2x (+ x w) :c2y (+ y h)
                  :x (+ x w) :y (+ y h)}}
        {:command :line-to :params {:x x :y (+ y h)}}
        {:command :close-path}]))))

(defn build
  [params]
  (let [params (merge default-params params)]
    (sb/scene {:seed (:seed params)
               :root {:x 0 :y 0 :width (:width params) :height (:height params)}
               :defaults {:path {:content (curved-content params)
                                 :fills (sb/gen-vector (sb/gen-fill))
                                 :strokes (sb/gen-vector (sb/gen-stroke 3))}}}
              (doseq [_ (range (:count params))]
                (sb/path)))))

(decl/defscene :paths
  {:version 1
   :description "Curved paths"
   :params-schema schema:params}
  build)

(def ^:private base-view {:scale 1 :x 0 :y 0})

(decl/defcase :paths/load :paths
  {:params default-params :view base-view :context :fresh}
  (fn [rtx] (camera/load! rtx)))

(decl/defcase :paths/pan :paths
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :dx 160 :dy 40 :settle-ms 100}}
  (fn [rtx] (camera/pan! rtx)))

(decl/defcase :paths/zoom :paths
  {:params default-params :view base-view :context :reuse
   :operation {:steps 20 :factor 1.5 :settle-ms 100}}
  (fn [rtx] (camera/zoom! rtx)))
