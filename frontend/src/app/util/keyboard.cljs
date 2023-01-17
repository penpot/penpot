;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.keyboard
  (:require
   [app.config :as cfg]
   [cuerdas.core :as str]))

(defn is-key?
  [^string key]
  (fn [^js e]
    (= (.-key e) key)))

(defn is-key-ignore-case?
  [^string key]
  (fn [^js e]
    (= (str/upper (.-key e)) (str/upper key))))

(defn ^boolean alt?
  [^js event]
  (.-altKey event))

(defn ^boolean ctrl?
  [^js event]
  (.-ctrlKey event))

(defn ^boolean meta?
  [^js event]
  (.-metaKey event))

(defn ^boolean shift?
  [^js event]
  (.-shiftKey event))

(defn ^boolean mod?
  [^js event]
  (if (cfg/check-platform? :macos)
    (meta? event)
    (ctrl? event)))

(def esc? (is-key? "Escape"))
(def enter? (is-key? "Enter"))
(def space? (is-key? " "))
(def z? (is-key-ignore-case? "z"))
(def up-arrow? (is-key? "ArrowUp"))
(def down-arrow? (is-key? "ArrowDown"))
(def left-arrow? (is-key? "ArrowLeft"))
(def right-arrow? (is-key? "ArrowRight"))
(def alt-key? (is-key? "Alt"))
(def ctrl-key? (is-key? "Control"))
(def meta-key? (is-key? "Meta"))
(def comma? (is-key? ","))
(def backspace? (is-key? "Backspace"))
(def home? (is-key? "Home"))
(def tab? (is-key? "Tab"))

(defn editing? [e]
  (.-editing ^js e))

