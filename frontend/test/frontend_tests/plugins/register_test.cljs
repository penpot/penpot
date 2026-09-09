;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.register-test
  (:require
   [app.main.repo :as rp]
   [app.plugins.register :as preg]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(defn- record-cmd-mock
  "Mock rp/cmd! that records calls in mock/rpc-calls and answers
  with (response-fn cmd params)."
  [response-fn]
  (mock/stub
   (fn [cmd params]
     (swap! mock/rpc-calls conj {:cmd cmd :params params})
     (response-fn cmd params))))

(defn- cmds
  []
  (mapv :cmd @mock/rpc-calls))

;; --- install-plugin! ---

(t/deftest install-success-releases-id
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock (fn [_ _] (rx/of {:ok true})))}
      (fn [done']
        (let [plugin {:plugin-id "reg-install-ok"}]
          (preg/install-plugin! plugin)
          (t/is (= [:add-profile-plugin] (cmds)))
          (t/is (= plugin (preg/get-plugin "reg-install-ok")))
          ;; id released: a second install issues a second RPC
          (preg/install-plugin! plugin)
          (t/is (= 2 (count @mock/rpc-calls)))
          (done')))
      done)))

(t/deftest install-while-in-flight-issues-no-second-rpc
  (t/async done
    (let [subjects (atom [])]
      (mock/with-mocks
        {rp/cmd! (record-cmd-mock
                  (fn [_ _]
                    (let [sb (rx/subject)]
                      (swap! subjects conj sb)
                      sb)))}
        (fn [done']
          (let [plugin {:plugin-id "reg-install-dedupe"}]
            (preg/install-plugin! plugin)
            (preg/install-plugin! plugin)
            (t/is (= 1 (count @mock/rpc-calls)) "second install while in flight is skipped")
            ;; complete the pending call; the id is released afterwards
            (rx/push! (first @subjects) {:ok true})
            (preg/install-plugin! plugin)
            (t/is (= 2 (count @mock/rpc-calls)))
            (done')))
        done))))

(t/deftest install-validation-error-cleans-local-only
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [_ _] (rx/throw (ex-info "rejected" {:type :validation :code :props-too-large}))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-install-validation"}]
          (preg/install-plugin! plugin)
          (t/is (= 1 (count @mock/rpc-calls)) "no rollback write after validation rejection")
          (t/is (nil? (preg/get-plugin "reg-install-validation")))
          ;; id released: the next install retries the RPC
          (preg/install-plugin! plugin)
          (t/is (= 2 (count @mock/rpc-calls)))
          (done')))
      done)))

(t/deftest install-validation-error-restores-previous-version
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [_ params]
                  (if (= "v2" (get-in params [:plugin :name]))
                    (rx/throw (ex-info "rejected" {:type :validation}))
                    (rx/of {:ok true}))))}
      (fn [done']
        (let [v1 {:plugin-id "reg-install-prev" :name "v1"}
              v2 {:plugin-id "reg-install-prev" :name "v2"}]
          (preg/install-plugin! v1)
          (preg/install-plugin! v2)
          (t/is (= 2 (count @mock/rpc-calls)))
          (t/is (= v1 (preg/get-plugin "reg-install-prev"))
                "the server-kept version is restored, not dropped")
          (done')))
      done)))

(t/deftest install-persistent-failure-terminates
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [_ _] (rx/throw (ex-info "boom" {:type :other}))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-install-hang"}]
          (preg/install-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin] (cmds))
                "one-shot rollback: no further calls")
          (t/is (nil? (preg/get-plugin "reg-install-hang")))
          (done')))
      done)))

(t/deftest install-non-validation-error-rolls-back-via-rpc
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [cmd _]
                  (if (= cmd :add-profile-plugin)
                    (rx/throw (ex-info "boom" {:type :other}))
                    (rx/of nil))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-install-rollback"}]
          (preg/install-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin] (cmds)))
          (t/is (nil? (preg/get-plugin "reg-install-rollback")))
          (done')))
      done)))

;; --- remove-plugin! ---

(t/deftest remove-success-releases-id
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock (fn [_ _] (rx/of {:ok true})))}
      (fn [done']
        (let [plugin {:plugin-id "reg-remove-ok"}]
          (preg/install-plugin! plugin)
          (preg/remove-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin] (cmds)))
          (t/is (nil? (preg/get-plugin "reg-remove-ok")))
          ;; id released: a second remove issues another RPC
          (preg/remove-plugin! plugin)
          (t/is (= 3 (count @mock/rpc-calls)))
          (done')))
      done)))

(t/deftest remove-while-in-flight-issues-no-second-rpc
  (t/async done
    (let [subjects (atom [])]
      (mock/with-mocks
        {rp/cmd! (record-cmd-mock
                  (fn [cmd _]
                    (if (= cmd :add-profile-plugin)
                      (rx/of {:ok true})
                      (let [sb (rx/subject)]
                        (swap! subjects conj sb)
                        sb))))}
        (fn [done']
          (let [plugin {:plugin-id "reg-remove-dedupe"}]
            (preg/install-plugin! plugin)
            (preg/remove-plugin! plugin)
            (preg/remove-plugin! plugin)
            (t/is (= 2 (count @mock/rpc-calls)) "second remove while in flight is skipped")
            ;; complete the pending call; the id is released afterwards
            (rx/push! (first @subjects) nil)
            (preg/remove-plugin! plugin)
            (t/is (= 3 (count @mock/rpc-calls)))
            (done')))
        done))))

(t/deftest remove-validation-error-restores-local-only
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [cmd _]
                  (if (= cmd :add-profile-plugin)
                    (rx/of {:ok true})
                    (rx/throw (ex-info "rejected" {:type :validation})))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-remove-validation"}]
          (preg/install-plugin! plugin)
          (preg/remove-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin] (cmds))
                "no reinstall write after validation rejection")
          (t/is (= plugin (preg/get-plugin "reg-remove-validation"))
                "local entry is restored")
          (done')))
      done)))

(t/deftest remove-non-validation-error-reinstalls-via-rpc
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [cmd _]
                  (if (= cmd :remove-profile-plugin)
                    (rx/throw (ex-info "boom" {:type :other}))
                    (rx/of {:ok true}))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-remove-rollback"}]
          (preg/install-plugin! plugin)
          (preg/remove-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin :add-profile-plugin] (cmds)))
          (t/is (= plugin (preg/get-plugin "reg-remove-rollback")))
          (done')))
      done)))

(t/deftest remove-persistent-failure-terminates
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [cmd _]
                  (if (= cmd :add-profile-plugin)
                    (rx/of {:ok true})
                    (rx/throw (ex-info "boom" {:type :other})))))}
      (fn [done']
        (let [plugin {:plugin-id "reg-remove-hang"}]
          (preg/install-plugin! plugin)
          (preg/remove-plugin! plugin)
          (t/is (= [:add-profile-plugin :remove-profile-plugin :add-profile-plugin] (cmds))
                "one-shot rollback: no further calls")
          (t/is (= plugin (preg/get-plugin "reg-remove-hang")))
          (done')))
      done)))

(t/deftest remove-validation-error-keeps-original-position
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [cmd _]
                  (if (= cmd :add-profile-plugin)
                    (rx/of {:ok true})
                    (rx/throw (ex-info "rejected" {:type :validation})))))}
      (fn [done']
        (let [own    #{"reg-idx-a" "reg-idx-b" "reg-idx-c"}
              plugin {:plugin-id "reg-idx-b"}]
          (preg/install-plugin! {:plugin-id "reg-idx-a"})
          (preg/install-plugin! plugin)
          (preg/install-plugin! {:plugin-id "reg-idx-c"})
          (preg/remove-plugin! plugin)
          ;; installs prepend, so the order is newest-first;
          ;; the failed removal must preserve it exactly
          (t/is (= ["reg-idx-c" "reg-idx-b" "reg-idx-a"]
                   (filterv own (mapv :plugin-id (preg/plugins-list)))))
          (done')))
      done)))
