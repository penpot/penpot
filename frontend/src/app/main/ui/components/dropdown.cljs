;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.dropdown
  (:require
   [app.config :as cfg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [goog.object :as gobj]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc dropdown'
  {::mf/wrap-props false}
  [props]
  (let [children (gobj/get props "children")
        on-close (gobj/get props "on-close")
        ref      (gobj/get props "container")

        on-click
        (fn [event]
          (let [target (dom/get-target event)

                ;; MacOS ctrl+click sends two events: context-menu and click.
                ;; In order to not have two handlings we ignore ctrl+click for this platform
                mac-ctrl-click? (and (cfg/check-platform? :macos) (kbd/ctrl? event))]
            (when (and (not mac-ctrl-click?)
                       (not (.-data-no-close ^js target)))
              (if ref
                (let [parent (mf/ref-val ref)]
                  (when-not (or (not parent) (.contains parent target))
                    (on-close)))
                (on-close)))))

        on-keyup
        (fn [event]
          (when (kbd/esc? event)
            (on-close)))

        on-mount
        (fn []
          (let [keys [(events/listen globals/document EventType.CLICK on-click)
                      (events/listen globals/document EventType.CONTEXTMENU on-click)
                      (events/listen globals/document EventType.KEYUP on-keyup)]]
            #(doseq [key keys]
               (events/unlistenByKey key))))]

    (mf/use-effect on-mount)
    children))

(mf/defc dropdown
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")

  (when (gobj/get props "show")
    (mf/element dropdown' props)))
