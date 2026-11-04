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
   [frontend-tests.helpers.async :as async]
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
  the shape each command expects: a list of team users, and `updated?` for
  the snapshot update (`update-file-snapshot` answers a boolean)."
  ([calls] (record-cmd! calls true))
  ([calls updated?]
   (fn [cmd params]
     (swap! calls conj [cmd params])
     (rx/of (if (= :get-team-users cmd) [] updated?)))))

(defn- deferred-cmd!
  "Mock for `rp/cmd!` that records the calls and answers a subject, so the
  test can observe the value the plugin exposes before the server answers."
  [calls response]
  (fn [cmd params]
    (swap! calls conj [cmd params])
    (->> response (rx/take 1) (rx/observe-on :async))))

(t/deftest ^:async file-version-proxy-sends-the-file-id
  ;; The snapshot RPC commands require the `file-id` (they need it for the
  ;; partition pruning on `file_change`); the plugin proxy is the other door to
  ;; them and has to send it on rename, remove and pin.
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])]

    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls))}

       (t/testing "rename sends the file id"
         (reset! calls [])
         (let [version (make-version file-id
                                     {:id version-id
                                      :label "Version"
                                      :created-at (ct/now)
                                      :created-by "user"})]
           (aset version "label" "renamed")
           (let [[cmd params] (last @calls)]
             (t/is (= :update-file-snapshot cmd))
             (t/is (= file-id (:file-id params)))
             (t/is (= "renamed" (:label params))))))

       (t/testing "remove sends the file id"
         (reset! calls [])
         (let [version (make-version file-id
                                     {:id version-id
                                      :label "Version"
                                      :created-at (ct/now)
                                      :created-by "user"})]
           (await (.remove version))
           (let [[cmd params] (last @calls)]
             (t/is (= :delete-file-snapshot cmd))
             (t/is (= file-id (:file-id params))))))))))

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

(t/deftest ^:async file-version-proxy-pin-rejects-when-the-update-fails
  ;; `update-file-snapshot` answers `false` when the row is gone (the GC can
  ;; remove an autosave). `pin` must reject instead of resolving with a
  ;; proxy that says the version is now a user one.
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (record-cmd! calls false))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "system"})]
         (try
           (await (.pin version))
           (t/is false "pin must reject when the update answers false")
           (catch :default cause
             (t/is (string? cause)))))))))

(t/deftest ^:async file-version-proxy-rename-reads-the-new-label-at-once
  ;; The plugin reads the new label immediately; the edit is confirmed by the
  ;; server afterwards. It also marks the version as a user one, so an
  ;; autosave stops reporting itself as autosave.
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])
        response   (rx/subject)]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (deferred-cmd! calls response))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "system"})]
         (aset version "label" "renamed")
         (t/is (= "renamed" (.-label version))
               "the new label is visible before the server answers")
         (t/is (false? (.-isAutosave version))
               "a renamed version is a user version")
         (rx/push! response true)
         (await (async/settle))
         (t/is (= "renamed" (.-label version))
               "the confirmed label stays")
         (t/is (= [[:update-file-snapshot file-id]]
                  (mapv (fn [[cmd params]] [cmd (:file-id params)]) @calls))))))))

(t/deftest ^:async file-version-proxy-rename-rolls-back-when-the-update-fails
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])
        response   (rx/subject)]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (deferred-cmd! calls response))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "system"})]
         (aset version "label" "renamed")
         (t/is (= "renamed" (.-label version)))
         (rx/push! response false)
         (await (async/wait-for #(= "Version" (.-label version))
                                "a refused rename restores the old label"))
         (t/is (= "Version" (.-label version)))
         (t/is (true? (.-isAutosave version))
               "rolling back also restores the autosave kind")
         (t/is (= [[:update-file-snapshot file-id]]
                  (mapv (fn [[cmd params]] [cmd (:file-id params)]) @calls))))))))

(t/deftest ^:async file-version-proxy-rename-rolls-back-on-a-transport-error
  (let [file-id    (uuid/next)
        version-id (uuid/next)
        calls      (atom [])
        response   (rx/subject)]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (deferred-cmd! calls response))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "user"})]
         (aset version "label" "renamed")
         (t/is (= "renamed" (.-label version)))
         (rx/error! response (ex-info "offline" {:type :offline}))
         (await (async/wait-for #(= "Version" (.-label version))
                                "a failed request restores the old label"))
         (t/is (= "Version" (.-label version))))))))

(t/deftest ^:async file-version-proxy-remove-rejects-when-the-delete-fails
  (let [file-id    (uuid/next)
        version-id (uuid/next)]
    (await
     (mock/with-mocks*
       {r/check-permission (constantly true)
        rp/cmd!            (mock/stub (fn [_ _] (rx/of false)))}
       (let [version (make-version file-id
                                   {:id version-id
                                    :label "Version"
                                    :created-at (ct/now)
                                    :created-by "user"})]
         (try
           (await (.remove version))
           (t/is false "remove must reject when the command answers false")
           (catch :default cause
             (t/is (string? cause)))))))))
