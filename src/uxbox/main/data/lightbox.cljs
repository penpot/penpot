;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.lightbox
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.util.rstore :as rs]))

;; --- Show Lightbox

(defrecord ShowLightbox [name params]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [data (merge {:name name} params)]
      (assoc state :lightbox data))))

(defn show-lightbox
  ([name]
   (ShowLightbox. name nil))
  ([name params]
   (ShowLightbox. name params)))

;; --- Hide Lightbox

(defrecord HideLightbox []
  rs/UpdateEvent
  (-apply-update [_ state]
    (dissoc state :lightbox)))

(defn hide-lightbox
  []
  (HideLightbox.))

;; --- Direct Call Api

(defn open!
  [& args]
  (rs/emit! (apply show-lightbox args)))

(defn close!
  [& args]
  (rs/emit! (apply hide-lightbox args)))
