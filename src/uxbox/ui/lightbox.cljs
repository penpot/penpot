(ns uxbox.ui.lightbox
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.data.lightbox :as udl]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as k]
            [goog.events :as events])
  (:import goog.events.EventType))

;; --- Lentes

(def ^:const ^:private lightbox-l
  (-> (l/key :lightbox)
      (l/focus-atom st/state)))

;; --- Lightbox (Component)

(defmulti render-lightbox :name)
(defmethod render-lightbox :default [_] nil)

(defn- on-esc-clicked
  [e]
  (when (k/esc? e)
    (udl/close!)))

(defn- lightbox-will-mount
  [own]
  (let [key (events/listen js/document
                           EventType.KEYDOWN
                           on-esc-clicked)]
    (assoc own ::key key)))

(defn- lightbox-will-umount
  [own]
  (events/unlistenByKey (::key own))
  (dissoc own ::key))

(defn- lightbox-transfer-state
  [old-own own]
  (assoc own ::key (::key old-own)))

(defn- lightbox-render
  [own]
  (let [data (rum/react lightbox-l)]
    (html
     [:div.lightbox
      {:class (when (nil? data) "hide")}
      (render-lightbox data)])))

(def lightbox
  (mx/component
   {:name "lightbox"
    :render lightbox-render
    :transfer-state lightbox-transfer-state
    :will-mount lightbox-will-mount
    :will-unmount lightbox-will-umount
    :mixins [rum/reactive mx/static]}))
