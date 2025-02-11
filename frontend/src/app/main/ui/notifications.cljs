;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.notifications
  (:require
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.ds.notifications.toast :refer [toast*]]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.main.ui.notifications.inline-notification :refer [inline-notification]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:notification
  (l/derived :notification st/state))

(mf/defc current-notification
  []
  (let [notification (mf/deref ref:notification)
        on-close     (mf/use-fn #(st/emit! (ntf/hide)))
        context?     (and (nil? (:timeout notification))
                          (nil? (:actions notification)))
        inline?      (or (= :inline (:type notification))
                         (= :floating (:position notification)))
        toast?       (or (= :toast (:type notification))
                         (some? (:timeout notification)))
        content     (or (:content notification) "")]

    (when notification
      (cond
        toast?
        [:> toast*
         {:level (or (:level notification) :info)
          :type (:type notification)
          :on-close on-close} content]

        inline?
        [:& inline-notification
         {:accept (:accept notification)
          :cancel (:cancel notification)
          :links (:links notification)
          :content (:content notification)}]

        context?
        [:& context-notification
         {:level (or (:level notification) :info)
          :links (:links notification)
          :content (:content notification)}]

        :else
        [:> toast*
         {:level (or (:level notification) :info)
          :type (:type notification)
          :on-close on-close} content]))))
