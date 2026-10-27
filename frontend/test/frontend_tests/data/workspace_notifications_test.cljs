;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-notifications-test
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.notifications :as dwntf]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(defn- file-change-msg
  [extra]
  (merge {:type :file-change
          :profile-id (uuid/next)
          :file-id (uuid/next)
          :session-id (uuid/next)
          :revn 1
          :vern 0
          :changes []}
         extra))

(defn- ^:async watch-file-change
  [msg]
  (let [events (atom [])]
    (await (async/observe (ptk/watch (dwntf/handle-file-change msg) {} (rx/empty))
                          :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async repaired-file-change-applies-and-notifies
  (let [events (await (watch-file-change (file-change-msg {:repaired true})))
        types  (mapv ptk/type events)
        show   (d/seek #(= ::ntf/show (ptk/type %)) events)
        state  (ptk/update show {})]
    (t/is (= [::dch/commit ::ntf/show] types))
    (t/is (= :info (get-in state [:notification :level])))
    (t/is (nil? (get-in state [:notification :timeout]))
          "the toast stays until the user closes it")
    (t/is (= (i18n/tr "workspace.notifications.file-repaired")
             (get-in state [:notification :content])))))

(t/deftest ^:async ordinary-file-change-does-not-notify
  (let [events (await (watch-file-change (file-change-msg {})))]
    (t/is (= [::dch/commit] (mapv ptk/type events)))))
