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
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.workspace-drawing :as udwd]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.undo :as udu]
            [uxbox.main.data.history :as udh]
            [uxbox.main.ui.workspace.sidebar.drawtools :as wsd])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler
           goog.ui.KeyboardShortcutHandler))

(declare move-selected)

;; --- Shortcuts

(defonce +shortcuts+
  {:shift+g #(st/emit! (udw/toggle-flag :grid))
   :ctrl+g #(st/emit! (uds/group-selected))
   :ctrl+shift+g #(st/emit! (uds/ungroup-selected))
   :ctrl+shift+m #(st/emit! (udw/toggle-flag :sitemap))
   :ctrl+shift+f #(st/emit! (udw/toggle-flag :drawtools))
   :ctrl+shift+i #(st/emit! (udw/toggle-flag :icons))
   :ctrl+shift+l #(st/emit! (udw/toggle-flag :layers))
   :ctrl+0 #(st/emit! (udw/reset-zoom))
   :ctrl+r #(st/emit! (udw/toggle-flag :ruler))
   :ctrl+d #(st/emit! (uds/duplicate-selected))
   :ctrl+c #(st/emit! (udw/copy-to-clipboard))
   :ctrl+v #(st/emit! (udw/paste-from-clipboard))
   :ctrl+shift+v #(udl/open! :clipboard)
   :ctrl+z #(st/emit! (udu/undo))
   :ctrl+shift+z #(st/emit! (udu/redo))
   :ctrl+y #(st/emit! (udu/redo))
   :ctrl+b #(st/emit! (udwd/select-for-drawing wsd/+draw-tool-rect+))
   :ctrl+e #(st/emit! (udwd/select-for-drawing wsd/+draw-tool-circle+))
   :ctrl+t #(st/emit! (udwd/select-for-drawing wsd/+draw-tool-text+))
   :esc #(st/emit! (udw/deselect-all))
   :delete #(st/emit! (udw/delete-selected))
   :ctrl+up #(st/emit! (udw/move-selected-layer :up))
   :ctrl+down #(st/emit! (udw/move-selected-layer :down))
   :ctrl+shift+up #(st/emit! (udw/move-selected-layer :top))
   :ctrl+shift+down #(st/emit! (udw/move-selected-layer :bottom))
   :shift+up #(st/emit! (udw/move-selected :up :fast))
   :shift+down #(st/emit! (udw/move-selected :down :fast))
   :shift+right #(st/emit! (udw/move-selected :right :fast))
   :shift+left #(st/emit! (udw/move-selected :left :fast))
   :up #(st/emit! (udw/move-selected :up :std))
   :down #(st/emit! (udw/move-selected :down :std))
   :right #(st/emit! (udw/move-selected :right :std))
   :left #(st/emit! (udw/move-selected :left :std))
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
