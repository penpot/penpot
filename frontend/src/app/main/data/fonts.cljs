;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.fonts
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.media :as cm]
   [app.main.fonts :as fonts]
   [app.main.repo :as rp]
   [app.main.data.events :as ev]
   [app.main.data.users :as du]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defn prepare-font-variant
  [item]
  {:id (str (:font-style item) "-" (:font-weight item))
   :name (str (cm/font-weight->name (:font-weight item)) " "
              (str/capital (:font-style item)))
   :style (:font-style item)
   :weight (str (:font-weight item))
   ::fonts/woff1-file-id (:woff1-file-id item)
   ::fonts/woff2-file-id (:woff2-file-id item)
   ::fonts/ttf-file-id (:ttf-file-id item)
   ::fonts/otf-file-id (:otf-file-id item)})

(defn prepare-font
  [[id [item :as items]]]
  {:id id
   :name (:font-family item)
   :family (:font-family item)
   :variants (mapv prepare-font-variant items)})

(defn team-fonts-loaded
  [fonts]
  (ptk/reify ::team-fonts-loaded
    ptk/EffectEvent
    (effect [_ state stream]
      (let [fonts (->> (group-by :font-id fonts)
                       (mapv prepare-font))]
        (fonts/register! :custom fonts)))))

(defn load-team-fonts
  [team-id]
  (ptk/reify ::load-team-fonts
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :team-font-variants {:team-id team-id})
           (rx/map team-fonts-loaded)))))


(defn get-fonts
  [backend]
  (get @fonts/fonts backend []))
