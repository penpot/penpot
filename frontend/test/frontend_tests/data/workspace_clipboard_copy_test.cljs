;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-clipboard-copy-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.data.workspace.clipboard :as dwcp]
   [app.main.repo :as rp]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- reject-unavailable
  "Stub for `clipboard/to-clipboard`: rejects the way it does on an
  insecure origin (issue #12648)."
  [_]
  (js/Promise.reject
   (js/Error. "Clipboard API is unavailable. (stub)")))

(t/deftest ^:async test-invitation-copy-rejection-skips-success
  (let [success (atom 0)
        errors  (atom 0)
        events  (atom [])]
    (await
     (mock/with-mocks*
       {rp/cmd! mock/rpc-cmd-mock
        clipboard/to-clipboard (mock/stub reject-unavailable)}
       (let [params (with-meta {:email "test@example.com"
                                :team-id (uuid/next)}
                      {:on-success (fn [& _] (swap! success inc))
                       :on-error   (fn [& _] (swap! errors inc))})]
         (await (async/observe
                 (ptk/watch (dtm/copy-invitation-link params)
                            {:router {:auth-verify-token {}}}
                            (rx/empty))
                 :on-next #(swap! events conj %)))
         (t/is (zero? @success)
               "on-success must not fire when the clipboard write rejects")
         (t/is (zero? @errors)
               "on-error must not fire: the clipboard failure is reported")
         (t/is (= 1 (count @events)))
         (t/is (= ::ntf/show (ptk/type (first @events))))
         (let [state (ptk/update (first @events) {})]
           (t/is (= :warning (get-in state [:notification :level])))
           (t/is (= :toast (get-in state [:notification :type])))
           (t/is (= (i18n/tr "errors.clipboard-api-unavailable")
                    (get-in state [:notification :content])))))))))

(t/deftest ^:async test-invitation-copy-resolution-fires-success
  (let [success (atom 0)
        errors  (atom 0)
        events  (atom [])]
    (await
     (mock/with-mocks*
       {rp/cmd! mock/rpc-cmd-mock
        clipboard/to-clipboard (mock/stub (fn [_] (js/Promise.resolve nil)))}
       (let [params (with-meta {:email "test@example.com"
                                :team-id (uuid/next)}
                      {:on-success (fn [& _] (swap! success inc))
                       :on-error   (fn [& _] (swap! errors inc))})]
         (await (async/observe
                 (ptk/watch (dtm/copy-invitation-link params)
                            {:router {:auth-verify-token {}}}
                            (rx/empty))
                 :on-next #(swap! events conj %)))
         (t/is (= 1 @success)
               "on-success fires exactly once when the write resolves")
         (t/is (zero? @errors))
         (t/is (empty? @events)
               "a successful copy must not emit a notification"))))))

(t/deftest ^:async test-copy-id-rejection-notifies
  (let [events (atom [])]
    (await
     (mock/with-mocks*
       {clipboard/to-clipboard (mock/stub reject-unavailable)}
       (await (async/observe
               (ptk/watch (dwcp/copy-id-to-clipboard (uuid/next))
                          {}
                          (rx/empty))
               :on-next #(swap! events conj %)))
       (t/is (= 1 (count @events)))
       (t/is (= ::ntf/show (ptk/type (first @events))))
       (let [state (ptk/update (first @events) {})]
         (t/is (= :warning (get-in state [:notification :level])))
         (t/is (= :toast (get-in state [:notification :type]))))))))
