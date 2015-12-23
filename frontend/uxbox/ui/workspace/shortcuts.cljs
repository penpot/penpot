(ns uxbox.ui.workspace.shortcuts
  (:require [goog.events :as events]
            [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler
           goog.ui.KeyboardShortcutHandler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Watcher
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:static +shortcuts+
  #{:ctrl+g
    :ctrl+shift+f
    :ctrl+shift+l})

(defonce ^:static ^:private +bus+
  (rx/bus))

(defonce ^:static +stream+
  (rx/to-observable +bus+))

(defn- init-handler
  []
  (let [handler (KeyboardShortcutHandler. js/document)]
    ;; Register shortcuts.
    (doseq [item +shortcuts+]
      (let [identifier (name item)]
        (.registerShortcut handler identifier identifier)))

    ;; Initialize shortcut listener.
    (let [event KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
          callback #(rx/push! +bus+ (keyword (.-identifier %)))
          key (events/listen handler event callback)]
      (fn []
        (events/unlistenByKey key)
        (.clearKeyListener handler)))))

;; DEBUG
(rx/on-value +stream+ #(println "[debug]: shortcut:" %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti -handle-event identity)

(defmethod -handle-event :default [ev] nil)

(defmethod -handle-event :ctrl+shift+l
  [_]
  (rs/emit! (dw/toggle-toolbox :layers)))

(defmethod -handle-event :ctrl+shift+f
  [_]
  (rs/emit! (dw/toggle-toolbox :draw)))

(defmethod -handle-event :ctrl+g
  [_]
  (rs/emit! (dw/toggle-tool :grid)))

(rx/on-value +stream+ #(-handle-event %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Mixin
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -will-mount
  [own]
  (println "shortcut-will-mount")
  (let [sub (init-handler)]
    (assoc own ::subscription sub)))

(defn -will-unmount
  [own]
  (println "shortcut-will-unmount")
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
