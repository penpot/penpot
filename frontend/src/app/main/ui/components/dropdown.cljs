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
   [app.util.timers :as tm]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc dropdown-content*
  [{:keys [children on-close container]}]
  (let [listening-ref (mf/use-ref nil)
        container-ref container

        on-click
        (fn [event]
          (when (mf/ref-val listening-ref)
            (let [target (dom/get-target event)

                  ;; MacOS ctrl+click sends two events: context-menu and click.
                  ;; In order to not have two handlings we ignore ctrl+click for this platform
                  mac-ctrl-click? (and (cfg/check-platform? :macos) (kbd/ctrl? event))]
              (when (and (not mac-ctrl-click?)
                         (not (.-data-no-close ^js target)))
                (if container-ref
                  (let [parent (mf/ref-val container-ref)]
                    (when-not (or (not parent) (.contains parent target))
                      (on-close)))
                  (on-close))))))

        on-keyup
        (fn [event]
          (when (kbd/esc? event)
            (on-close)))

        on-mount
        (fn []
          (let [keys [(events/listen globals/document EventType.CLICK on-click)
                      (events/listen globals/document EventType.CONTEXTMENU on-click)
                      (events/listen globals/document EventType.KEYUP on-keyup)]]
            (tm/schedule #(mf/set-ref-val! listening-ref true))
            #(run! events/unlistenByKey keys)))]

    (mf/use-effect on-mount)
    children))

(mf/defc dropdown
  {::mf/props :obj}
  [{:keys [on-close show children container]}]
  (assert (fn? on-close) "missing `on-close` prop")
  (assert (boolean? show) "missing `show` prop")

  (when ^boolean show
    [:> dropdown-content*
     {:on-close on-close
      :container container
      :children children}]))
