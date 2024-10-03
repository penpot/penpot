;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-v2.cpp
  (:require
   ["./cpp.js" :as render-v2]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defonce ^:dynamic internal-module nil)

(defn set-canvas
  [canvas vbox zoom base-objects]
  (js/console.log "setting canvas" canvas)
  (.setCanvas ^js internal-module canvas #js {:antialias false})
  (js/console.log "canvas set")
  (.drawCanvas ^js internal-module vbox zoom base-objects))

(defn draw-canvas
  [vbox zoom base-objects]
  (js/console.log "draw canvas" vbox zoom base-objects)
  (.drawCanvas ^js internal-module vbox zoom base-objects))

(defn on-init
  [module']
  (set! internal-module module')
  (js/console.log "internal-module" internal-module module')
  (js/console.log "add 2 + 2" (._add ^js module' 2 2)))

(defn init
  []
  (p/then (render-v2) #(on-init %)))
