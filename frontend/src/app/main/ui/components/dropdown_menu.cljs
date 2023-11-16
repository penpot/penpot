;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.dropdown-menu
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [goog.events :as events]
   [goog.object :as gobj]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc dropdown-menu-item*
  {::mf/wrap-props false}
  [props]
  (let [props (-> (obj/clone props)
                  (obj/set! "role" "menuitem"))]
    [:> :li props]))

(mf/defc dropdown-menu'
  {::mf/wrap-props false}
  [props]
  (let [children   (gobj/get props "children")
        on-close   (gobj/get props "on-close")
        ref        (gobj/get props "container")
        ids        (gobj/get props "ids")
        list-class (gobj/get props "list-class")
        ids (filter some? ids)
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

        on-key-down
        (fn [event]
          (let [first-id (dom/get-element (first ids))
                first-element (dom/get-element first-id)
                len (count ids)]

            (when (kbd/home? event)
              (when first-element
                (dom/focus! first-element)))

            (when (kbd/up-arrow? event)
              (let [actual-selected (dom/get-active)
                    actual-id (dom/get-attribute actual-selected "id")
                    actual-index (d/index-of ids actual-id)
                    previous-id (if (= 0 actual-index)
                                  (last ids)
                                  (nth ids (- actual-index 1)))]
                (dom/focus! (dom/get-element previous-id))))

            (when (kbd/down-arrow? event)
              (let [actual-selected (dom/get-active)
                    actual-id (dom/get-attribute actual-selected "id")
                    actual-index (d/index-of ids actual-id)
                    next-id (if (= (- len 1) actual-index)
                              (first ids)
                              (nth ids (+ 1 actual-index)))]
                (dom/focus! (dom/get-element next-id))))

            (when (kbd/tab? event)
              (on-close))))]

    (mf/with-effect []
      (let [keys [(events/listen globals/document EventType.CLICK on-click)
                  (events/listen globals/document EventType.CONTEXTMENU on-click)
                  (events/listen globals/document EventType.KEYUP on-keyup)
                  (events/listen globals/document EventType.KEYDOWN on-key-down)]]
        #(doseq [key keys]
           (events/unlistenByKey key))))

    [:ul {:class list-class :role "menu"} children]))

(mf/defc dropdown-menu
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")

  (when (gobj/get props "show")
    (mf/element dropdown-menu' props)))
