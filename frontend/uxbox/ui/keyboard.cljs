(ns uxbox.ui.keyboard
  (:require [goog.events :as events]
            [beicon.core :as rx])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler
           goog.ui.KeyboardShortcutHandler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:static +shortcuts+
  #{:ctrl+g
    :esc
    :ctrl+shift+f
    :ctrl+shift+l})

(defonce ^:static +handler+
  (KeyboardShortcutHandler. js/document))

(defonce ^:static ^:private +bus+
  (rx/bus))

(defonce ^:static +stream+
  (rx/to-observable +bus+))

(defn init
  "Initialize the shortcuts handler."
  []
  (doseq [item +shortcuts+]
    (let [identifier (name item)]
      (.registerShortcut +handler+ identifier identifier)))
  (let [event KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED]
    (events/listen +handler+ event #(rx/push! +bus+ (keyword (.-identifier %))))))

(rx/on-value +stream+ #(println "[debug]: shortcut:" %))
