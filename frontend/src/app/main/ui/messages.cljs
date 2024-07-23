;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.messages
  (:require
   [app.main.data.messages :as dmsg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.main.ui.notifications.inline-notification :refer [inline-notification]]
   [app.main.ui.notifications.toast-notification :refer [toast-notification]]
   [rumext.v2 :as mf]))

(mf/defc notifications-hub
  []
  (let [message  (mf/deref refs/message)
        on-close (mf/use-fn #(st/emit! dmsg/hide))
        context? (and (nil? (:timeout message))
                      (nil? (:actions message)))
        inline?  (or (= :inline (:notification-type message))
                     (= :floating (:position message)))
        toast?   (or (= :toast (:notification-type message))
                     (some? (:timeout message)))]

    (when message
      (cond
        toast?
        [:& toast-notification
         {:type (or (:type message) :info)
          :links (:links message)
          :on-close on-close
          :content (:content message)}]

        inline?
        [:& inline-notification
         {:actions (:actions message)
          :links (:links message)
          :content (:content message)}]

        context?
        [:& context-notification
         {:type (or (:type message) :info)
          :links (:links message)
          :content (:content message)}]

        :else
        [:& toast-notification
         {:type (or (:type message) :info)
          :links (:links message)
          :on-close on-close
          :content (:content message)}]))))
