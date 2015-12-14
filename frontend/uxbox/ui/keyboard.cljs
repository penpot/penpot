(ns uxbox.ui.keyboard
  (:require [goog.events :as events])
  (:import [goog.events EventType KeyCodes]
           [goog.ui KeyboardShortcutHandler]))


(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))

;; (def workspace-event-keys
;;   ["DELETE" "ESC" "CTRL+C" "CTRL+V" "CTRL+B" "CTRL+E" "CTRL+L" "SHIFT+Q"
;;    "SHIFT+W" "SHIFT+E" "CTRL+SHIFT+I" "CTRL+SHIFT+F" "CTRL+SHIFT+C"
;;    "CTRL+SHIFT+L" "CTRL+G" "CTRL+UP" "CTRL+DOWN" "CTRL+SHIFT+UP"
;;    "CTRL+SHIFT+DOWN" "SHIFT+I" "SHIFT+0" "SHIFT+O"])

;; ;; Mixins

;; (defn keyboard-keypress
;;   "A mixin for capture keyboard events."
;;   [event-keys]
;;   (let [handler (KeyboardShortcutHandler. js/document)]
;;     (doseq [shortcut event-keys]
;;       (.registerShortcut handler shortcut shortcut))

;;     {:will-mount (fn [state]
;;                    (events/listen handler
;;                                   KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
;;                                   ws/on-workspace-keypress)
;;                    state)
;;      :will-unmount (fn [state]
;;                    (events/unlisten js/document
;;                                     EventType.KEYDOWN
;;                                     ws/on-workspace-keypress)
;;                      state)}))
