;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.layout.popover
  "A panel that floats over the page and closes by itself. It only
  behaves: the caller places and draws the panel."
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.config :as cfg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(def ^:private schema:popover
  [:map
   [:is-open :boolean]
   [:on-close fn?]
   [:container {:optional true} [:maybe :any]]])

(defn- inside?
  "Whether `target` is in the element of the ref `node-ref`."
  [node-ref target]
  (let [node (some-> node-ref mf/ref-val)]
    (and (some? node) (.contains ^js node target))))

(defn- outside-click?
  "Whether the click `event` closes the popover: one out of the panel
  (`panel-ref`) and out of `container`, other than the second event of
  a ctrl+click on macOS, which also sends a context menu one."
  [event panel-ref container]
  (let [target (dom/get-target event)]
    (not (or (and (cfg/check-platform? :macos) (kbd/ctrl? event))
             (.-data-no-close ^js target)
             (inside? panel-ref target)
             (inside? container target)))))

(defn- give-focus-back
  "Focus `node` again, the element focused before the popover opened,
  unless the focus went somewhere else since: a dialog the popover
  opened keeps it."
  [node panel-ref]
  (let [active (dom/get-active)]
    (when (and (some? node)
               (.-isConnected ^js node)
               (or (nil? active)
                   (= active (.-body globals/document))
                   (inside? panel-ref active)))
      (dom/focus! node))))

(mf/defc popover-content*
  {::mf/private true}
  [{:keys [on-close container children]}]
  (let [panel-ref     (mf/use-ref nil)
        ;; read when an event comes: a new handler from a later render
        ;; must not set the popover up again and move the focus
        on-close-ref  (mf/use-ref on-close)
        container-ref (mf/use-ref container)]
    (mf/set-ref-val! on-close-ref on-close)
    (mf/set-ref-val! container-ref container)
    (mf/with-effect []
      (let [opener    (dom/get-active)
            close     #((mf/ref-val on-close-ref))
            listening (volatile! false)
            ;; the click that opened the popover is still on its way up:
            ;; it does not count
            timer     (tm/schedule #(vreset! listening true))

            on-click
            (fn [event]
              (when (and @listening
                         (outside-click? event panel-ref (mf/ref-val container-ref)))
                (close)))

            on-key-up
            (fn [event]
              (when (kbd/esc? event)
                (close)))

            keys [(events/listen globals/document EventType.CLICK on-click)
                  (events/listen globals/document EventType.CONTEXTMENU on-click)
                  (events/listen globals/document EventType.KEYUP on-key-up)]]
        (fn []
          (rx/dispose! timer)
          (run! events/unlistenByKey keys)
          (give-focus-back opener panel-ref))))

    [:div {:class (stl/css :popover) :ref panel-ref}
     children]))

(mf/defc popover*
  "A panel that floats over the page while `is-open`, for content a menu
  cannot hold (rows with a status, sections, a group that folds). A click
  out of it, and out of `container` (a ref, the element that opened it),
  or Escape calls `on-close`. On closing, the focus goes back where it
  was before it opened. It only behaves: it renders `children` in place
  and the caller places and draws the panel."
  {::mf/schema schema:popover}
  [{:keys [is-open on-close container children]}]
  (when ^boolean is-open
    [:> popover-content* {:on-close on-close
                          :container container
                          :children children}]))
