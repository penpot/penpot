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

(t/deftest mcp-has-default-management-permissions
  (t/is (preg/check-permission preg/mcp-plugin-id "manage:delete"))
  (t/is (preg/check-permission preg/mcp-plugin-id "manage:teams"))
  (t/is (preg/check-permission preg/mcp-plugin-id "content:write"))
  (t/is (not (preg/check-permission preg/mcp-plugin-id "unknown:permission"))))

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

(t/deftest install-validation-error-keeps-original-position
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [_ params]
                  (if (= "v2" (get-in params [:plugin :name]))
                    (rx/throw (ex-info "rejected" {:type :validation}))
                    (rx/of {:ok true}))))}
      (fn [done']
        (let [own #{"reg-pos-a" "reg-pos-b" "reg-pos-c"}
              v1  {:plugin-id "reg-pos-b" :name "v1"}
              v2  {:plugin-id "reg-pos-b" :name "v2"}]
          (preg/install-plugin! {:plugin-id "reg-pos-a"})
          (preg/install-plugin! v1)
          (preg/install-plugin! {:plugin-id "reg-pos-c"})
          (preg/install-plugin! v2)
          ;; installs prepend, so newest-first; the rejected update
          ;; must preserve order and restore v1
          (t/is (= ["reg-pos-c" "reg-pos-b" "reg-pos-a"]
                   (filterv own (mapv :plugin-id (preg/plugins-list)))))
          (t/is (= v1 (preg/get-plugin "reg-pos-b")))
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

(t/deftest install-non-validation-error-on-update-resaves-previous
  (t/async done
    (mock/with-mocks
      {rp/cmd! (record-cmd-mock
                (fn [_ params]
                  (if (= "v2" (get-in params [:plugin :name]))
                    (rx/throw (ex-info "busy" {:type :concurrency-limit}))
                    (rx/of {:ok true}))))}
      (fn [done']
        (let [own #{"reg-upd-a" "reg-upd-b" "reg-upd-c"}
              v1  {:plugin-id "reg-upd-b" :name "v1"}
              v2  {:plugin-id "reg-upd-b" :name "v2"}]
          (preg/install-plugin! {:plugin-id "reg-upd-a"})
          (preg/install-plugin! v1)
          (preg/install-plugin! {:plugin-id "reg-upd-c"})
          (reset! mock/rpc-calls [])
          (preg/install-plugin! v2)
          (t/is (= [:add-profile-plugin :add-profile-plugin] (cmds))
                "the compensating write re-saves v1, never removes it")
          (t/is (= v1 (get-in (second @mock/rpc-calls) [:params :plugin])))
          (t/is (= v1 (preg/get-plugin "reg-upd-b")))
          (t/is (= ["reg-upd-c" "reg-upd-b" "reg-upd-a"]
                   (filterv own (mapv :plugin-id (preg/plugins-list)))))
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

;; --- subscribe-registry! ---

(t/deftest registry-subscriber-sees-rollback
  (t/async done
    (let [subjects (atom [])
          seen     (atom [])
          listener (fn [] (swap! seen conj (some? (preg/get-plugin "reg-watch"))))]
      (mock/with-mocks
        {rp/cmd! (record-cmd-mock
                  (fn [_ _]
                    (let [sb (rx/subject)]
                      (swap! subjects conj sb)
                      sb)))}
        (fn [done']
          (preg/subscribe-registry! listener)
          (preg/install-plugin! {:plugin-id "reg-watch"})
          (rx/error! (first @subjects) (ex-info "rejected" {:type :validation}))
          (t/is (= [true false] @seen)
                "notified on the optimistic add and on the rollback")
          (preg/unsubscribe-registry! listener)
          (preg/install-plugin! {:plugin-id "reg-watch"})
          (t/is (= [true false] @seen) "no calls after unsubscribing")
          (done'))
        done))))

;; --- parse-manifest ---

(t/deftest parse-manifest-adds-allow-global-for-global-scope
  (let [manifest (preg/parse-manifest
                  "http://localhost:4400/manifest.json"
                  #js {:name "Global plugin"
                       :code "plugin.js"
                       :scope "global"
                       :permissions #js ["content:read"]})]
    (t/is (= "global" (:scope manifest)))
    (t/is (= #{"content:read" "allow:global"} (:permissions manifest)))))

(t/deftest parse-manifest-keeps-workspace-plugins-without-allow-global
  (let [manifest (preg/parse-manifest
                  "http://localhost:4400/manifest.json"
                  #js {:name "Workspace plugin"
                       :code "plugin.js"
                       :permissions #js ["content:read"]})]
    (t/is (nil? (:scope manifest)))
    (t/is (= #{"content:read"} (:permissions manifest)))))

;; --- global plugins restriction ---

(defn- global-not-allowed-error
  "The ex-data type thrown by `(f)`, or nil when it does not throw."
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (:type (ex-data cause)))))

(t/deftest global-plugins-are-allowed-for-mcp-and-localhost
  (t/is (preg/global-allowed? {:plugin-id preg/mcp-plugin-id
                               :host "https://design.penpot.app/plugins/mcp/"}))
  (doseq [host ["http://localhost:4400/" "http://127.0.0.1:4400/" "http://[::1]:4400/"
                "http://LOCALHOST:4400/" "http://LocalHost/" "http://localhost.:4400/"]]
    (t/is (preg/global-allowed? {:plugin-id "local" :host host}) host))
  (t/is (not (preg/global-allowed? {:plugin-id "remote" :host "https://plugins.example.com/"})))
  (t/is (not (preg/global-allowed? {:plugin-id "remote" :host "https://localhost.example.com/"}))))

(t/deftest parse-manifest-rejects-remote-global-scope
  (t/is (= ::preg/global-not-allowed
           (global-not-allowed-error
            #(preg/parse-manifest
              "https://plugins.example.com/manifest.json"
              #js {:name "Remote global plugin"
                   :code "plugin.js"
                   :scope "global"
                   :permissions #js ["content:read"]})))))

(t/deftest parse-manifest-rejects-remote-management-permissions
  (doseq [permission ["manage:projects" "manage:teams" "manage:delete" "allow:global"]]
    (t/is (= ::preg/global-not-allowed
             (global-not-allowed-error
              #(preg/parse-manifest
                "https://plugins.example.com/manifest.json"
                #js {:name "Remote management plugin"
                     :code "plugin.js"
                     :permissions #js ["content:read" permission]})))
          permission)))

(t/deftest parse-manifest-keeps-remote-workspace-plugins
  (let [manifest (preg/parse-manifest
                  "https://plugins.example.com/manifest.json"
                  #js {:name "Remote workspace plugin"
                       :code "plugin.js"
                       :permissions #js ["content:read"]})]
    (t/is (= #{"content:read"} (:permissions manifest)))))

(t/deftest ^:async stored-remote-plugins-lose-global-permissions
  (await
   (mock/with-mocks*
     {rp/cmd! (record-cmd-mock (fn [_ _] (rx/of {:ok true})))}
     (let [permissions #{"content:read" "allow:global" "manage:projects"}]
       (preg/install-plugin! {:plugin-id "reg-global-remote"
                              :host "https://plugins.example.com/"
                              :scope "global"
                              :permissions permissions})
       (preg/install-plugin! {:plugin-id "reg-global-local"
                              :host "http://localhost:4400/"
                              :scope "global"
                              :permissions permissions})
       (t/is (preg/has-permission? "reg-global-remote" "content:read"))
       (t/is (not (preg/has-permission? "reg-global-remote" "allow:global")))
       (t/is (not (preg/has-permission? "reg-global-remote" "manage:projects")))
       (t/is (preg/has-permission? "reg-global-local" "allow:global"))
       (t/is (preg/has-permission? "reg-global-local" "manage:projects"))))))
