;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.defer
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as ts]
   [goog.events :as events]
   [goog.functions :as gf]
   [goog.object :as gobj])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(defn deferred
  ([component] (deferred component ts/raf))
  ([component schedule]
   (mf/fnc deferred
     {::mf/wrap-props false}
     [props]
     (let [[render? set-render!] (mf/useState false)]
       (mf/use-effect
        (fn [] (schedule #(set-render! true))))
       (when render?
         (mf/create-element component props))))))

(defn throttle
  [component ms]
  (mf/fnc throttle
    {::mf/wrap-props false}
    [props]
    (let [[state set-state] (mf/useState props)
          set-state* (mf/use-memo #(gf/throttle set-state ms))]

      (mf/use-effect
       nil
       (fn []
         (set-state* props)))

      (mf/create-element component state))))
