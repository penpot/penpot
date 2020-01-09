;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.shortcuts
  (:require [goog.events :as events]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as dl]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.undo :as du])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler))

(declare move-selected)

;; --- Shortcuts

(defonce +shortcuts+
  {
   ;; :shift+g #(st/emit! (dw/toggle-flag :grid))
   :ctrl+shift+m #(st/emit! (dw/toggle-layout-flag :sitemap))
   :ctrl+shift+f #(st/emit! (dw/toggle-layout-flag :drawtools))
   :ctrl+shift+i #(st/emit! (dw/toggle-layout-flag :icons))
   :ctrl+shift+l #(st/emit! (dw/toggle-layout-flag :layers))
   :ctrl+0 #(st/emit! (dw/reset-zoom))
   ;; :ctrl+r #(st/emit! (dw/toggle-flag :ruler))
   :ctrl+d #(st/emit! dw/duplicate-selected)
   ;; :ctrl+c #(st/emit! (dw/copy-to-clipboard))
   ;; :ctrl+v #(st/emit! (dw/paste-from-clipboard))
   ;; :ctrl+shift+v #(dl/open! :clipboard)
   ;; :ctrl+z #(st/emit! du/undo)
   ;; :ctrl+shift+z #(st/emit! du/redo)
   ;; :ctrl+y #(st/emit! du/redo)
   :ctrl+b #(st/emit! (dw/select-for-drawing :rect))
   :ctrl+e #(st/emit! (dw/select-for-drawing :circle))
   :ctrl+t #(st/emit! (dw/select-for-drawing :text))
   :esc #(st/emit! :interrupt dw/deselect-all)
   :delete #(st/emit! dw/delete-selected)
   :ctrl+up #(st/emit! (dw/order-selected-shapes :up))
   :ctrl+down #(st/emit! (dw/order-selected-shapes :down))
   :ctrl+shift+up #(st/emit! (dw/order-selected-shapes :top))
   :ctrl+shift+down #(st/emit! (dw/order-selected-shapes :bottom))
   :shift+up #(st/emit! (dw/move-selected :up true))
   :shift+down #(st/emit! (dw/move-selected :down true))
   :shift+right #(st/emit! (dw/move-selected :right true))
   :shift+left #(st/emit! (dw/move-selected :left true))
   :up #(st/emit! (dw/move-selected :up false))
   :down #(st/emit! (dw/move-selected :down false))
   :right #(st/emit! (dw/move-selected :right false))
   :left #(st/emit! (dw/move-selected :left false))
   })

;; --- Shortcuts Setup Functions

(defn- watch-shortcuts
  [sink]
  (let [handler (KeyboardShortcutHandler. js/document)]

    ;; Register shortcuts.
    (doseq [item (keys +shortcuts+)]
      (let [identifier (name item)]
        (.registerShortcut handler identifier identifier)))

    ;; Initialize shortcut listener.
    (let [event KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
             callback #(sink (keyword (.-identifier %)))
          key (events/listen handler event callback)]
      (fn []
        (events/unlistenByKey key)
        (.clearKeyListener handler)))))

(defn init
  []
  (let [stream (->> (rx/create watch-shortcuts)
                    (rx/pr-log "[debug]: shortcut:"))]
    (rx/on-value stream (fn [event]
                          (when-let [handler (get +shortcuts+ event)]
                            (handler))))))
