(ns uxbox.ui.lightbox
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.util :as util]
            [uxbox.ui.keyboard :as k]
            [goog.events :as events])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce +current+ (atom nil))

(defn open!
  ([kind]
   (open! kind nil))
  ([kind params]
   (reset! +current+ (merge {:name kind} params))))

(defn close!
  []
  (reset! +current+ nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti render-lightbox :name)
(defmethod render-lightbox :default [_] nil)

(defn- on-esc-clicked
  [e]
  (when (k/esc? e)
    (close!)))

(defn- lightbox-will-mount
  [own]
  (let [key (events/listen js/document
                           EventType.KEYDOWN
                           on-esc-clicked)]
    (assoc own ::eventkey key)))

(defn- lightbox-will-umount
  [own]
  (let [key (::eventkey own)]
    (events/unlistenByKey key)
    (dissoc own ::eventkey)))

(defn- lightbox-transfer-state
  [old-own own]
  (assoc own ::eventkey (::eventkey old-own)))

(defn- lightbox-render
  [own]
  (let [params (rum/react +current+)]
    (html
     [:div.lightbox {:class (when (nil? params) "hide")}
      (render-lightbox params)])))

(def ^:static lightbox
  (util/component
   {:name "lightbox"
    :render lightbox-render
    :transfer-state lightbox-transfer-state
    :will-mount lightbox-will-mount
    :will-unmount lightbox-will-umount
    :mixins [rum/reactive]}))
