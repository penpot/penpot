;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.fullscreen
  (:require
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [rumext.alpha :as mf]))

(def fullscreen-context
  (mf/create-context))

(mf/defc fullscreen-wrapper
  [{:keys [children] :as props}]
  (let [container (mf/use-ref)
        state     (mf/use-state (dom/fullscreen?))

        change
        (mf/use-callback
         (fn [_]
           (let [val (dom/fullscreen?)]
             (reset! state val))))

        manager
        (mf/use-memo
         (mf/deps @state)
         (fn []
           (specify! state
             cljs.core/IFn
             (-invoke
               ([it val]
                (if val
                  (wapi/request-fullscreen (mf/ref-val container))
                  (wapi/exit-fullscreen)))))))]

    ;; NOTE: the user interaction with F11 keyboard hot-key does not
    ;; emits the `fullscreenchange` event; that event is emitted only
    ;; when API is used. There are no way to detect the F11 behavior
    ;; in a uniform cross browser way.

    (mf/use-effect
     (fn []
       (.addEventListener js/document "fullscreenchange" change)
       (fn []
         (.removeEventListener js/document "fullscreenchange" change))))

    [:div.fullscreen-wrapper {:ref container :class (dom/classnames :fullscreen @state)}
     [:& (mf/provider fullscreen-context) {:value manager}
      children]]))

