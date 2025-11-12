;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.dropdown-menu
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc dropdown-menu-item*
  [{:keys [can-focus] :rest props}]
  (let [can-focus (d/nilv can-focus true)
        tab-index (if can-focus "0" "-1")
        props     (mf/spread-props props {:role "menuitem" :tab-index tab-index})]
    [:> :li props]))

(mf/defc internal-dropdown-menu*
  {::mf/private true}
  [{:keys [on-close children class id]}]

  (assert (fn? on-close) "missing `on-close` prop")

  (let [on-click
        (mf/use-fn
         (mf/deps on-close)
         (fn [event]
           (let [target (dom/get-target event)
                 ;; MacOS ctrl+click sends two events: context-menu and click.
                 ;; In order to not have two handlings we ignore ctrl+click for this platform
                 mac-ctrl-click? (and (cfg/check-platform? :macos) (kbd/ctrl? event))]
             (when (and (not mac-ctrl-click?)
                        (not (.-data-no-close ^js target))
                        (fn? on-close))
               (on-close)))))

        container
        (mf/use-ref)

        on-keyup
        (fn [event]
          (when (kbd/esc? event)
            (on-close)))

        on-key-down
        (fn [event]
          (when-let [container (mf/ref-val container)]
            (let [entries (vec (dom/query-all container "[role=menuitem]"))]

              (cond
                (kbd/up-arrow? event)
                (let [selected (dom/get-active)
                      index    (d/index-of-pred entries #(identical? % selected))
                      target   (if (nil? index)
                                 (peek entries)
                                 (or (get entries (dec index)) (peek entries)))]

                  (dom/focus! target))

                (kbd/down-arrow? event)
                (let [selected (dom/get-active)
                      index    (d/index-of-pred entries #(identical? % selected))
                      target   (if (nil? index)
                                 (first entries)
                                 (or (get entries (inc index)) (first entries)))]
                  (dom/focus! target))

                (kbd/enter? event)
                (let [selected (dom/get-active)]
                  (dom/prevent-default event)
                  (dom/click! selected))

                (kbd/tab? event)
                (on-close)))))]

    (mf/with-effect [id]
      (when id
        (st/emit! (ptk/data-event :dropdown/open {:id id}))))

    (mf/with-effect [on-close id]
      (when id
        (let [stream (->> st/stream
                          (rx/filter (ptk/type? :dropdown/open))
                          (rx/map deref)
                          (rx/filter #(not= id (:id %)))
                          (rx/take 1))
              subs   (rx/subs! nil nil on-close stream)]
          (fn []
            (rx/dispose! subs)))))

    (mf/with-effect []
      (let [keys [(events/listen globals/document EventType.CLICK on-click)
                  (events/listen globals/document EventType.CONTEXTMENU on-click)
                  (events/listen globals/document EventType.KEYUP on-keyup)
                  (events/listen globals/document EventType.KEYDOWN on-key-down)]]
        #(doseq [key keys]
           (events/unlistenByKey key))))

    [:ul {:class class :role "menu" :ref container} children]))

(mf/defc dropdown-menu*
  [{:keys [show] :as props}]
  (when show
    [:> internal-dropdown-menu* props]))

