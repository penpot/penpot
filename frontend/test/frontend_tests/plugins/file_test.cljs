;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.file-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.plugins.file :as file]
   [app.plugins.register :as r]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest file-version-created-at-returns-stored-date
  (let [created-at (js/Date.)
        version    (file/file-version-proxy
                    "00000000-0000-0000-0000-000000000000"
                    (random-uuid)
                    {}
                    {:id (random-uuid)
                     :label "Version"
                     :created-at created-at})]
    (t/is (identical? created-at (.-createdAt version)))))

(defn- make-version
  [file-id data]
  (file/file-version-proxy (str (uuid/next)) file-id {} data))

(defn- record-cmd!
  "Mock for `rp/cmd!` that records the calls and answers an observable with
  the shape each command expects: a list of team users, and `true` for the
  snapshot update (`update-file-snapshot` answers a boolean)."
  [calls]
  (fn [cmd params]
    (swap! calls conj [cmd params])
    (rx/of (if (= :get-team-users cmd) [] true))))

;; The snapshot RPC commands require the `file-id` (they use it to prune the
;; partitions of `file_change`); the plugin proxy is the other door to them and
;; sends it on rename, remove and pin.

(t/deftest ^:async renaming-a-version-sends-the-current-file-id
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])]

    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls))}

       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "user"})]
         (aset version "label" "renamed")
         (let [[cmd params] (last @calls)]
           (t/is (= :update-file-snapshot cmd))
           (t/is (= version-id (:id params)))
           (t/is (= file-id (:file-id params)))
           (t/is (= "renamed" (:label params)))))))))

(t/deftest ^:async removing-a-version-sends-the-current-file-id
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])]

    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls))}

       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "user"})]
         (await (.remove version))
         (let [[cmd params] (last @calls)]
           (t/is (= :delete-file-snapshot cmd))
           (t/is (= version-id (:id params)))
           (t/is (= file-id (:file-id params)))))))))

(t/deftest ^:async pinning-a-version-sends-the-current-file-id
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])]

    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls))}

       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "system"})]
         (await (.pin version))
         (t/is (= [[:get-team-users file-id]
                   [:update-file-snapshot file-id]]
                  (mapv (fn [[cmd params]] [cmd (:file-id params)]) @calls))))))))

(t/deftest ^:async file-version-proxy-pin-resolves-the-pinned-version
  ;; `pin` answers with a proxy, not with the boolean the snapshot command
  ;; returns: the autosave becomes a user version with a new label.
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        created-at (ct/now)
        calls      (atom [])]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at created-at
                                    :created-by "system"})
             pinned  (await (.pin version))]
         (t/is (file/file-version-proxy? pinned))
         (t/is (= [[:get-team-users file-id]
                   [:update-file-snapshot file-id]]
                  (mapv (fn [[cmd params]] [cmd (:file-id params)]) @calls)))
         (t/is (= (ct/format-inst created-at :localized-date)
                  (.-label pinned)))
         (t/is (false? (.-isAutosave pinned)))
         (t/is (identical? created-at (.-createdAt pinned))))))))
