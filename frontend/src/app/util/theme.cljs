;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.theme
  (:require
   [app.common.data :as d]
   [app.util.globals :as globals]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defonce ^:private color-scheme-media-query
  (.matchMedia globals/window "(prefers-color-scheme: dark)"))

(defonce ^:private color-scheme-listeners*
  (atom #{}))

(def ^:const default "system")

(defn get-system-theme
  []
  (if ^boolean (.-matches color-scheme-media-query)
    "dark"
    "light"))

(defn- notify-color-scheme-listeners!
  []
  (doseq [f @color-scheme-listeners*]
    (f)))

(defn add-color-scheme-listener!
  "Registers `f` to run after each `body` color-scheme update in
   `use-initialize` (profile theme or OS preference). Returns a dispose fn."
  [f]
  (swap! color-scheme-listeners* conj f)
  (fn [] (swap! color-scheme-listeners* disj f)))

(defn- set-color-scheme
  [^string color]

  (let [node  (.querySelector js/document "body")
        class (if (= color "dark") "default" "light")]
    (.removeAttribute node "class")
    (.add ^js (.-classList ^js node) class)))

(defn resolve-theme
  "Resolves the profile's theme setting to the effective UI theme ('dark' or
  'light'). An unset preference or 'system' follows the system theme;
  the legacy 'default' preference means dark. Used by the app and plugins."
  [profile-theme system-theme]
  (let [preference (d/nilv profile-theme default)]
    (case preference
      "system" system-theme
      "default" "dark"
      preference)))

(defn use-theme
  "Return the effective theme and follow system preference changes."
  [{profile-theme :theme}]
  (let [system-theme* (mf/use-state get-system-theme)
        system-theme  (deref system-theme*)]

    (mf/with-effect []
      (let [s (->> (rx/from-event color-scheme-media-query "change")
                   (rx/map #(if (.-matches %) "dark" "light"))
                   (rx/subs! #(reset! system-theme* %)))]
        (fn []
          (rx/dispose! s))))

    (resolve-theme profile-theme system-theme)))

(defn use-initialize
  [profile]
  (let [color (use-theme profile)]
    (mf/with-effect [color]
      (set-color-scheme color)
      (notify-color-scheme-listeners!))))
