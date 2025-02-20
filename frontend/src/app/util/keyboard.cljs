;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.keyboard
  (:require
   [app.config :as cfg]
   [cuerdas.core :as str]))

(defrecord KeyboardEvent [type key shift ctrl alt meta mod editing native-event]
  Object
  (preventDefault [_]
    (.preventDefault native-event))

  (stopPropagation [_]
    (.stopPropagation native-event)))

(defn keyboard-event?
  [o]
  (instance? KeyboardEvent o))

(defn key-up-event?
  [^KeyboardEvent event]
  (= :up (.-type event)))

(defn key-down-event?
  [^KeyboardEvent event]
  (= :down (.-type event)))

(defn mod-event?
  [^KeyboardEvent event]
  (true? (.-mod event)))

(defn editing-event?
  [^KeyboardEvent event]
  (true? (.-editing event)))

(defn is-key?
  [^string key]
  (fn [^KeyboardEvent e]
    (= (.-key e) key)))

(defn is-key-ignore-case?
  [^string key]
  (let [key (str/upper key)]
    (fn [^KeyboardEvent e]
      (= (str/upper (.-key e)) key))))

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
(def equals? (is-key? "="))
(def plus? (is-key? "+"))
(def minus? (is-key? "-"))
(def underscore? (is-key? "_"))
(def up-arrow? (is-key? "ArrowUp"))
(def down-arrow? (is-key? "ArrowDown"))
(def left-arrow? (is-key? "ArrowLeft"))
(def right-arrow? (is-key? "ArrowRight"))
(def alt-key? (is-key? "Alt"))
(def shift-key? (is-key? "Shift"))
(def ctrl-key? (is-key? "Control"))
(def meta-key? (is-key? "Meta"))
(def comma? (is-key? ","))
(def backspace? (is-key? "Backspace"))
(def home? (is-key? "Home"))
(def tab? (is-key? "Tab"))
(def delete? (is-key? "Delete"))

