;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.shortcuts
  (:require
   ["mousetrap" :as mousetrap]
   [app.config :as cfg]
   [app.util.logging :as log])
  (:refer-clojure :exclude [meta]))

(log/set-level! :warn)

(def mac-command "\u2318")
(def mac-option  "\u2325")
(def mac-delete  "\u232B")
(def mac-shift   "\u21E7")
(def mac-control "\u2303")
(def mac-esc     "\u238B")
(def mac-enter   "\u23CE")

(def left-arrow  "\u2190")
(def up-arrow    "\u2191")
(def right-arrow "\u2192")
(def down-arrow  "\u2193")

(defn c-mod
  "Adds the control/command modifier to a shortcuts depending on the
  operating system for the user"
  [shortcut]
  (if (cfg/check-platform? :macos)
    (str "command+" shortcut)
    (str "ctrl+" shortcut)))

(defn a-mod
  "Adds the alt/option modifier to a shortcuts depending on the
  operating system for the user"
  [shortcut]
  (str "alt+" shortcut))

(defn ca-mod
  [shortcut]
  (c-mod (a-mod shortcut)))

(defn bind-shortcuts
  ([shortcuts-config]
   (bind-shortcuts
    shortcuts-config
    mousetrap/bind
    (fn [key cb]
      (fn [event]
        (log/debug :msg (str "Shortcut" key))
        (.preventDefault event)
        (cb event)))))

  ([shortcuts-config bind-fn cb-fn]
   (doseq [[key {:keys [command disabled fn type]}] shortcuts-config]
     (when-not disabled
       (if (vector? command)
         (doseq [cmd (seq command)]
           (bind-fn cmd (cb-fn key fn) type))
         (bind-fn command (cb-fn key fn) type))))))

(defn remove-shortcuts
    []
    (mousetrap/reset))

(defn meta [key]
  ;; If the key is "+" we need to surround with quotes
  ;; otherwise will not be very readable
  (let [key (if (and (not (cfg/check-platform? :macos))
                     (= key "+"))
              "\"+\""
              key)]
    (str
     (if (cfg/check-platform? :macos)
       mac-command
       "Ctrl+")
     key)))

(defn shift [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-shift
     "Shift+")
   key))

(defn alt [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-option
     "Alt+")
   key))

(defn meta-shift [key]
  (-> key meta shift))

(defn meta-alt [key]
  (-> key meta alt))

(defn supr []
  (if (cfg/check-platform? :macos)
    mac-delete
    "Supr"))

(defn esc []
  (if (cfg/check-platform? :macos)
    mac-esc
    "Escape"))

(defn enter []
  (if (cfg/check-platform? :macos)
    mac-enter
    "Enter"))
