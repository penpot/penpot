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

        on-close #(st/emit! dmsg/hide)

        toast-message   {:type (or (:type message) :info)
                         :links (:links message)
                         :on-close on-close
                         :content (:content message)}

        inline-message  {:actions (:actions message)
                         :links (:links message)
                         :content (:content message)}

        context-message {:type (or (:type message) :info)
                         :links (:links message)
                         :content (:content message)}

        is-context-msg (and (nil? (:timeout message)) (nil? (:actions message)))
        is-toast-msg   (or (= :toast (:notification-type message)) (some? (:timeout message)))
        is-inline-msg  (or (= :inline (:notification-type message)) (and (some? (:position message)) (= :floating (:position message))))]

    (when message
      (cond
        is-toast-msg
        [:& toast-notification toast-message]
        is-inline-msg
        [:& inline-notification inline-message]
        is-context-msg
        [:& context-notification context-message]
        :else
        [:& toast-notification toast-message]))))
