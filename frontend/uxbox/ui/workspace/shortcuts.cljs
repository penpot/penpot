(ns uxbox.ui.workspace.shortcuts
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [goog.events :as events]
            [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler
           goog.ui.KeyboardShortcutHandler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:static +shortcuts+
  {:ctrl+g #(rs/emit! (dw/toggle-tool :grid))
   :ctrl+shift+f #(rs/emit! (dw/toggle-toolbox :draw))
   :ctrl+shift+i #(rs/emit! (dw/toggle-toolbox :icons))
   :ctrl+shift+l #(rs/emit! (dw/toggle-toolbox :layers))
   :esc #(rs/emit! (dw/deselect-all))
   :backspace #(rs/emit! (dw/remove-selected))
   :up #(rs/emit! (dw/move-selected :up))
   :down #(rs/emit! (dw/move-selected :down))
   :right #(rs/emit! (dw/move-selected :right))
   :left #(rs/emit! (dw/move-selected :left))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Watcher
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:static ^:private +bus+
  (rx/bus))

(defonce ^:static +stream+
  (rx/to-observable +bus+))

(defn- init-handler
  []
  (let [handler (KeyboardShortcutHandler. js/document)]
    ;; Register shortcuts.
    (doseq [item (keys +shortcuts+)]
      (let [identifier (name item)]
        (.registerShortcut handler identifier identifier)))

    ;; Initialize shortcut listener.
    (let [event KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
          callback #(rx/push! +bus+ (keyword (.-identifier %)))
          key (events/listen handler event callback)]
      (fn []
        (events/unlistenByKey key)
        (.clearKeyListener handler)))))

(define-once :subscriptions
  (rx/on-value +stream+ #(println "[debug]: shortcut:" %))
  (rx/on-value +stream+ (fn [event]
                          (when-let [handler (get +shortcuts+ event)]
                            (handler)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard Shortcuts Mixin
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -will-mount
  [own]
  (let [sub (init-handler)]
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
