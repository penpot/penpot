;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.theme
  (:require
   [app.common.data :as d]
   [app.util.globals :as globals]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defonce ^:private color-scheme-media-query
  (.matchMedia globals/window "(prefers-color-scheme: dark)"))

(def ^:const default "dark")

(defn get-system-theme
  []
  (if ^boolean (.-matches color-scheme-media-query)
    "dark"
    "light"))

(defn- set-color-scheme
  [^string color]

  (let [node  (.querySelector js/document "body")
        class (if (= color "dark") "default" "light")]
    (.removeAttribute node "class")
    (.add ^js (.-classList ^js node) class)))

(defn use-initialize
  [{profile-theme :theme}]
  (let [system-theme* (mf/use-state get-system-theme)
        system-theme  (deref system-theme*)]

    (mf/with-effect []
      (let [s (->> (rx/from-event color-scheme-media-query "change")
                   (rx/map #(if (.-matches %) "dark" "light"))
                   (rx/subs! #(reset! system-theme* %)))]
        (fn []
          (rx/dispose! s))))

    (mf/with-effect [system-theme profile-theme]
      (set-color-scheme
       (cond
         (= profile-theme "system") system-theme
         (= profile-theme "default") "dark"
         :else (d/nilv profile-theme "dark"))))))
