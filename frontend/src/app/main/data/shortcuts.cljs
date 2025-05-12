;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.shortcuts
  (:refer-clojure :exclude [meta reset!])
  (:require
   ["@penpot/mousetrap$default" :as mousetrap]
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.config :as cf]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
(def tab         "tab")

(defn c-mod
  "Adds the control/command modifier to a shortcuts depending on the
  operating system for the user"
  [shortcut]
  (if (cf/check-platform? :macos)
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

(defn meta
  [key]
  ;; If the key is "+" we need to surround with quotes
  ;; otherwise will not be very readable
  (let [key (if (and (not (cf/check-platform? :macos))
                     (= key "+"))
              "\"+\""
              key)]
    (str
     (if (cf/check-platform? :macos)
       mac-command
       "Ctrl+")
     key)))

(defn shift
  [key]
  (str
   (if (cf/check-platform? :macos)
     mac-shift
     "Shift+")
   key))

(defn alt
  [key]
  (str
   (if (cf/check-platform? :macos)
     mac-option
     "Alt+")
   key))

(defn meta-shift
  [key]
  (-> key meta shift))

(defn meta-alt
  [key]
  (-> key meta alt))

(defn alt-shift
  [key]
  (-> key alt shift))

(defn supr
  []
  (if (cf/check-platform? :macos)
    mac-delete
    "Del"))

(defn esc
  []
  (if (cf/check-platform? :macos)
    mac-esc
    "Escape"))

(defn enter
  []
  (if (cf/check-platform? :macos)
    mac-enter
    "Enter"))

(defn split-sc
  [sc]
  (let [sc (cond-> sc (str/includes? sc "++")
                   (str/replace "++" "+plus"))]
    (if (= (count sc) 1)
      [sc]
      (str/split sc #"\+| "))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: push

(def ^:private
  schema:shortcuts
  [:map-of :keyword
   [:map
    [:command [:or :string [:vector :any]]]
    [:fn {:optional true} fn?]
    [:tooltip {:optional true} :string]]])

(def ^:private check-shortcuts
  (sm/check-fn schema:shortcuts))

(defn- wrap-cb
  [key cb]
  (fn [event]
    (log/debug :msg (str "Shortcut" key))
    (when (aget event "preventDefault")
      (.preventDefault event))
    (cb event)))

(defn- bind!
  [shortcuts]
  (let [msbind (fn [command callback type]
                 (if type
                   (mousetrap/bind command callback type)
                   (mousetrap/bind command callback)))]
    (->> shortcuts
         (remove #(:disabled (second %)))
         (run! (fn [[key {:keys [command fn type]}]]
                 (let [callback (wrap-cb key fn)]
                   (if (vector? command)
                     (run! #(msbind % callback type) command)
                     (msbind command callback type))))))))

(defn- reset!
  ([]
   (mousetrap/reset))
  ([shortcuts]
   (mousetrap/reset)
   (bind! shortcuts)))

(def ^:private conj*
  (fnil conj (d/ordered-map)))

(defn push-shortcuts
  [key shortcuts]
  (assert (keyword? key) "expected a keyword for `key`")
  (let [shortcuts (check-shortcuts shortcuts)]
    (ptk/reify ::push-shortcuts
      ptk/UpdateEvent
      (update [_ state]
        (update state :shortcuts conj* [key shortcuts]))

      ptk/EffectEvent
      (effect [_ _ _]
        (reset! shortcuts)))))

(defn pop-shortcuts
  [key]
  (ptk/reify ::pop-shortcuts
    ptk/UpdateEvent
    (update [_ state]
      (update state :shortcuts (fn [shortcuts]
                                 (dissoc shortcuts key))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [[_key shortcuts] (last (:shortcuts state))]
        (reset! shortcuts)))))
