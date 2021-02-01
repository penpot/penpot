;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.shortcuts
  (:require
   [app.main.data.colors :as mdc]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [potok.core :as ptk]
   [beicon.core :as rx]
   [app.config :as cfg])
  (:refer-clojure :exclude [meta]))

(def mac-command "\u2318")
(def mac-option  "\u2325")
(def mac-delete  "\u232B")
(def mac-shift   "\u21E7")
(def mac-control "\u2303")
(def mac-esc     "\u238B")

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

(defn bind-shortcuts [shortcuts bind-fn cb-fn]
  (doseq [[key {:keys [command disabled fn]}] shortcuts]
    (when-not disabled
      (if (vector? command)
        (doseq [cmd (seq command)]
          (bind-fn cmd (cb-fn key fn)))
        (bind-fn command (cb-fn key fn))))))

(defn meta [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-command
     "Ctrl+")
   key))

(defn shift [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-shift
     "Shift+")
   key))

(defn meta-shift [key]
  (-> key meta shift))

(defn supr []
  (if (cfg/check-platform? :macos)
    mac-delete
    "Supr"))

(defn esc []
  (if (cfg/check-platform? :macos)
    mac-esc
    "Escape"))

