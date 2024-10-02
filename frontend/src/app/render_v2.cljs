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
    (render-v2-cpp/set-canvas canvas)

    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (render-v2-rs/set-canvas canvas vbox zoom base-objects)))

(defn draw-canvas [vbox zoom base-objects]
  (cond
    ;; Rust
    (contains? cf/flags :render-v2-rs)
    (render-v2-rs/draw-canvas vbox zoom base-objects)))
