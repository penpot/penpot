(ns app.renderer
  (:require
   [app.config :as cf]
   [app.renderer.cpp :as renderer-cpp]
   [app.renderer.rs :as renderer-rs]))

(defn is-enabled?
  []
  (or (contains? cf/flags :renderer-v2-cpp)
      (contains? cf/flags :renderer-v2-rs)))

(defn init
  []
  (cond
    ;; CPP
    (contains? cf/flags :renderer-v2-cpp)
    (renderer-cpp/init)

    ;; Rust
    (contains? cf/flags :renderer-v2-rs)
    (renderer-rs/init)))

(defn set-canvas
  [canvas vbox base-objects]
  (cond
    ;; CPP
    (contains? cf/flags :renderer-v2-cpp)
    (renderer-cpp/set-canvas canvas)

    ;; Rust
    (contains? cf/flags :renderer-v2-rs)
    (renderer-rs/set-canvas canvas vbox base-objects)))
