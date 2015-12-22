(ns uxbox.ui.workspace.keyboard
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.data.workspace :as dw]))

(defmulti -handle-event identity)

(defmethod -handle-event :default
  [ev]
  (println "[warn]: shortcut" ev "not implemented"))

(defmethod -handle-event :ctrl+shift+l
  [_]
  (rs/emit! (dw/toggle-toolbox :layers)))

(defmethod -handle-event :ctrl+shift+f
  [_]
  (rs/emit! (dw/toggle-toolbox :draw)))

(defmethod -handle-event :ctrl+g
  [_]
  (rs/emit! (dw/toggle-tool :grid)))

(defn -will-mount
  [own]
  (let [sub (rx/on-value kbd/+stream+ #(-handle-event %))]
    (assoc own ::subscription sub)))

(defn -will-unmount
  [own]
  (let [sub (::subscription own)]
    (sub)
    (dissoc own ::subscription)))

(defn -transfer-state
  [old-own own]
  (assoc own ::subscription (::subscription old-own)))

(def mixin
  {:will-mount -will-mount
   :will-unmount -will-unmount
   :transfer-state -transfer-state})
