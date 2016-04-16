;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.shortcuts
  (:require [goog.events :as events]
            [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.data.lightbox :as udl]
            [uxbox.data.workspace :as dw]
            [uxbox.data.shapes :as uds]
            [uxbox.data.history :as udh])
  (:import goog.events.EventType
           goog.events.KeyCodes
           goog.ui.KeyboardShortcutHandler
           goog.ui.KeyboardShortcutHandler))

(declare move-selected)

;; --- Shortcuts

(defonce ^:const +shortcuts+
  {:ctrl+g #(rs/emit! (dw/toggle-flag :grid))
   :ctrl+shift+f #(rs/emit! (dw/toggle-flag :drawtools))
   :ctrl+shift+i #(rs/emit! (dw/toggle-flag :icons))
   :ctrl+shift+l #(rs/emit! (dw/toggle-flag :layers))
   :ctrl+0 #(rs/emit! (dw/reset-zoom))
   :ctrl+r #(rs/emit! (dw/toggle-flag :ruler))
   :ctrl+d #(rs/emit! (uds/duplicate-selected))
   :ctrl+c #(rs/emit! (dw/copy-to-clipboard))
   :ctrl+v #(rs/emit! (dw/paste-from-clipboard))
   :ctrl+z #(rs/emit! (udh/backwards-to-previous-version))
   :ctrl+shift+z #(rs/emit! (udh/forward-to-next-version))
   :ctrl+shift+v #(udl/open! :clipboard)
   :esc #(rs/emit! (uds/deselect-all))
   :backspace #(rs/emit! (uds/delete-selected))
   :delete #(rs/emit! (uds/delete-selected))
   :ctrl+up #(rs/emit! (uds/move-selected-layer :up))
   :ctrl+down #(rs/emit! (uds/move-selected-layer :down))
   :ctrl+shift+up #(rs/emit! (uds/move-selected-layer :top))
   :ctrl+shift+down #(rs/emit! (uds/move-selected-layer :bottom))
   :shift+up #(move-selected :up :fast)
   :shift+down #(move-selected :down :fast)
   :shift+right #(move-selected :right :fast)
   :shift+left #(move-selected :left :fast)
   :up #(move-selected :up :std)
   :down #(move-selected :down :std)
   :right #(move-selected :right :std)
   :left #(move-selected :left :std)})

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

(defn- initialize
  []
  (let [stream (->> (rx/create watch-shortcuts)
                    (rx/pr-log "[debug]: shortcut:"))]
    (rx/on-value stream (fn [event]
                          (when-let [handler (get +shortcuts+ event)]
                            (handler))))))

;; --- Helpers

(defn- move-selected
  [dir speed]
  (case speed
    :std (rs/emit! (uds/move-selected dir 1))
    :fast (rs/emit! (uds/move-selected dir 20))))

;; --- Mixin

(defn- will-mount
  [own]
  (assoc own ::sub (initialize)))

(defn- will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(defn- transfer-state
  [oldown own]
  (assoc own ::sub (::sub oldown)))

(def shortcuts-mixin
  {:will-mount will-mount
   :will-unmount will-unmount
   :transfer-state transfer-state})
