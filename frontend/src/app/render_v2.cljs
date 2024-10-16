;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-v2
  (:require
   [app.config :as cf]
   [app.render-v2.cpp :as render-v2-cpp]
   [app.render-v2.rs :as render-v2-rs]))

(defn is-enabled?
  []
  (or (contains? cf/flags :render-v2-cpp)
      (contains? cf/flags :render-v2-rs)))

(defn init
  []
  (cond
    ;; CPP
    (contains? cf/flags :render-v2-cpp)
    (render-v2-cpp/init)

    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (render-v2-rs/init)))

(defn set-canvas
  [canvas vbox zoom base-objects]
  (cond
    ;; CPP
    (contains? cf/flags :render-v2-cpp)
    (render-v2-cpp/set-canvas canvas vbox zoom base-objects)

    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (do
      (js/performance.mark "rs-set-canvas-start")
      (render-v2-rs/set-canvas canvas vbox zoom base-objects)
      (js/performance.mark "rs-set-canvas-end")
      (let [duration (.-duration (js/performance.measure "rs-set-canvas" "rs-set-canvas-start" "rs-set-canvas-end"))]
        (js/console.log "Rust set-canvas" duration)))))

(defn draw-canvas
  [vbox zoom base-objects]
  (cond
    ;; CPP
    (contains? cf/flags :render-v2-cpp)
    (render-v2-cpp/draw-canvas vbox zoom base-objects)

    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (do
      (js/performance.mark "rs-draw-canvas-start")
      (render-v2-rs/draw-canvas vbox zoom base-objects)
      (js/performance.mark "rs-draw-canvas-end")
      (let [duration (.-duration (js/performance.measure "rs-draw-canvas" "rs-draw-canvas-start" "rs-draw-canvas-end"))]
        (js/console.log "Rust draw-canvas" duration)))))

(defn set-objects [vbox zoom base-objects]
  (cond
    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (render-v2-rs/set-objects vbox zoom base-objects)

    (contains? cf/flags :render-v2-cpp)
    (render-v2-cpp/set-objects vbox zoom base-objects)))
