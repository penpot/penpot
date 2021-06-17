;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.dropdown
  (:require
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [goog.object :as gobj]
   [rumext.alpha :as mf])
  (:import goog.events.EventType))

(mf/defc dropdown'
  {::mf/wrap-props false}
  [props]
  (let [children (gobj/get props "children")
        on-close (gobj/get props "on-close")
        ref      (gobj/get props "container")

        on-click
        (fn [event]
          (let [target (dom/get-target event)]
            (when-not (.-data-no-close ^js target)
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
          (let [keys [(events/listen js/document EventType.CLICK on-click)
                      (events/listen js/document EventType.CONTEXTMENU on-click)
                      (events/listen js/document EventType.KEYUP on-keyup)]]
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
