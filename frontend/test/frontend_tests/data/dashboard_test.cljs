;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.dashboard-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.team :as dtm]
   [app.main.data.websocket :as dws]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))
(defn- tr-args-for-team-org-toast
  "Runs handle-change-team-organization and returns recorded i18n/tr calls."
  [notification organization]
  (let [team-id     (uuid/next)
        current-query-string (str "screen=dashboard-recent&team-id=" team-id)
        state       {:current-team-id team-id
                     :teams {team-id {:id team-id :name "Team-Apple"}}}
        event       (dcm/handle-change-team-organization
                     {:team {:id team-id :organization organization}
                      :notification notification})
        tr-calls    (atom [])]
    (-> (mock/with-mocks*
          {cf/flags (conj cf/flags :admin-console)
           ;; plain variadic fn: mock/stub does not cover IFn variadic dispatch
           i18n/tr (fn [key & args]
                     (swap! tr-calls conj [key (vec args)])
                     (str key ":" (pr-str args)))
           rp/cmd! (mock/stub
                    (fn [cmd params]
                      (if (= [:check-nitrate-sso
                              {:team-id team-id   :query-string current-query-string}]
                             [cmd params])
                        (rx/of {:authorized true})
                        (rx/throw (ex-info "unexpected RPC" {:cmd cmd :params params})))))
           rt/get-current-query-string (mock/stub (constantly current-query-string))}
          (await (async/observe (ptk/watch event state nil)
                                :on-next (fn [ev]
                                           (t/is (contains? #{::ntf/show ::dtm/fetch-teams}
                                                            (ptk/type ev)))))))
        (.then (fn [_] @tr-calls))))) (t/deftest ^:async team-org-change-toast-names-team-when-joining-org
                                        (let [tr-calls (await (tr-args-for-team-org-toast
                                                               "dashboard.team-belong-organization"
                                                               {:id (uuid/next) :name "OrgB"}))]
                                          (t/is (= [["dashboard.team-belong-organization" ["Team-Apple" "OrgB"]]]
                                                   tr-calls))))

(t/deftest ^:async team-org-change-toast-names-team-when-leaving-org
  (let [tr-calls (await (tr-args-for-team-org-toast
                         "dashboard.team-no-longer-belong-organization"
                         {:name "OrgA"}))]
    (t/is (= [["dashboard.team-no-longer-belong-organization" ["Team-Apple" "OrgA"]]]
             tr-calls))))

(t/deftest ^:async team-org-change-keeps-restrictions-met-of-the-same-organization
  (let [team-id         (uuid/next)
        organization-id (uuid/next)
        state           {:teams {team-id {:id team-id
                                          :organization {:id organization-id
                                                         :name "Acme"
                                                         :restrictions-met {:send-invitations false}}}}}
        event           (dcm/handle-change-team-organization
                         {:team {:id team-id
                                 :organization {:id organization-id
                                                :name "Acme 2"
                                                :permissions {:send-invitations "owners"}}}
                          :notification nil})]
    (await (mock/with-mocks*
             {cf/flags (conj cf/flags :admin-console)}
             (let [organization (get-in (ptk/update event state) [:teams team-id :organization])]
               (t/is (= "Acme 2" (:name organization)))
               (t/is (= {:send-invitations false} (:restrictions-met organization))))))))

(t/deftest ^:async team-org-change-refetches-teams-for-the-current-profile
  (let [team-id (uuid/next)
        state   {:teams {team-id {:id team-id}}}
        event   (dcm/handle-change-team-organization
                 {:team {:id team-id :organization {:id (uuid/next) :name "Acme"}}
                  :notification nil})
        types   (atom [])]
    (await (mock/with-mocks*
             {cf/flags (conj cf/flags :admin-console)}
             (await (async/observe (ptk/watch event state nil)
                                   :on-next #(swap! types conj (ptk/type %))))))
    (t/is (= [::dtm/fetch-teams] @types))))

(t/deftest ^:async duplicated-project-has-a-file-count-and-is-unpinned
  (let [id       (uuid/next)
        copy-id  (uuid/next)
        team-id  (uuid/next)
        copied   {:id copy-id :team-id team-id :name "Copy"}
        state    {:projects {id {:id id :name "Source" :count 9 :is-pinned true}}}
        result   (atom state)]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [cmd _]
                             (->> (rx/of (case cmd
                                           :duplicate-project copied
                                           :get-project-files [{:id (uuid/next)} {:id (uuid/next)}]))
                                  (rx/observe-on :async))))}
       (await (async/observe (ptk/watch (dd/duplicate-project {:id id :name "Source"}) state (rx/empty))
                             :on-next #(swap! result (partial ptk/update %))))))
    (t/is (= (assoc copied :count 2 :is-pinned false) (get-in @result [:projects copy-id])))))

(t/deftest moving-current-team-into-sso-organization-redirects
  (t/async done
    (let [team-id      (uuid/next)
          organization {:id (uuid/next) :name "OrgA"}
          current-query-string (str "screen=dashboard-recent&team-id=" team-id)
          redirect-url "https://idp.example.com/authorize"
          state        {:current-team-id team-id}
          event        (dcm/handle-change-team-organization
                        {:team {:id team-id :organization organization}
                         :notification nil})]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd! (mock/stub
                  (fn [cmd params]
                    (if (= [:check-nitrate-sso
                            {:team-id team-id   :query-string current-query-string}]
                           [cmd params])
                      (rx/of {:authorized false :redirect-uri redirect-url})
                      (rx/throw (ex-info "unexpected RPC" {:cmd cmd :params params})))))
         rt/get-current-query-string (mock/stub (constantly current-query-string))}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [events]
                  (t/is (= [::rt/nav-raw] (mapv ptk/type events))))
                (fn [error]
                  (t/is false (str "unexpected error: " error))
                  (done'))
                (fn []
                  (done')))))
        done))))
(t/deftest organization-sso-activation-redirects-current-team
  (t/async done
    (let [team-id      (uuid/next)
          organization-id       (uuid/next)
          current-query-string (str "screen=workspace&team-id=" team-id)
          redirect-url "https://idp.example.com/authorize"
          state        {:current-team-id team-id
                        :teams {team-id {:id team-id
                                         :organization {:id organization-id}}}}
          event        (dcm/handle-organization-change-sso
                        {:organization-id organization-id})]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd! (mock/stub
                  (fn [cmd params]
                    (if (= [:check-nitrate-sso
                            {:team-id team-id   :query-string current-query-string}]
                           [cmd params])
                      (rx/of {:authorized false :redirect-uri redirect-url})
                      (rx/throw (ex-info "unexpected RPC" {:cmd cmd :params params})))))
         rt/get-current-query-string (mock/stub (constantly current-query-string))}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [events]
                  (t/is (= [::rt/nav-raw] (mapv ptk/type events))))
                (fn [error]
                  (t/is false (str "unexpected error: " error))
                  (done'))
                (fn []
                  (done')))))
        done))))

(defn- sent-messages
  "Runs the watch of `event` against `stream` with `dws/send` stubbed
  and resolves to the messages it sends over the websocket."
  [event state stream]
  (let [sent (atom [])]
    (-> (mock/with-mocks*
          {dws/send (mock/stub (fn [msg] (swap! sent conj msg) msg))}
          (await (async/observe (ptk/watch event state stream))))
        (.then (fn [_] @sent)))))

(t/deftest ^:async dashboard-initialize-subscribes-to-team
  (let [team-id (uuid/next)
        state   {:profile-id (uuid/next)
                 :teams {team-id {:id team-id :organization {:id (uuid/next)}}}}
        sent    (await (sent-messages (dd/initialize team-id) state (rx/empty)))]
    (t/is (= [{:type :subscribe-team :team-id team-id}] sent))))

(t/deftest ^:async dashboard-initialize-resubscribes-on-reconnect
  (let [team-id (uuid/next)
        state   {:profile-id (uuid/next)
                 :teams {team-id {:id team-id}}}
        stream  (rx/of (ptk/data-event ::dws/opened {})
                       (ptk/data-event ::dws/opened {}))
        sent    (await (sent-messages (dd/initialize team-id) state stream))]
    (t/is (= 3 (count sent)))
    (t/is (every? #(= {:type :subscribe-team :team-id team-id} %) sent))))

(t/deftest ^:async dashboard-initialize-accepts-team-organization-messages
  (let [team-id   (uuid/next)
        org-id    (uuid/next)
        state     {:profile-id (uuid/next)
                   :teams {team-id {:id team-id :organization {:id org-id}}}}
        message   (fn [topic]
                    (ptk/data-event ::dws/message
                                    {:type :organization-change-sso
                                     :topic topic
                                     :organization-id org-id}))
        stream    (rx/of (message org-id) (message (uuid/next)))
        processed (atom [])]
    (await (mock/with-mocks*
             {dws/send (mock/stub identity)}
             (await (async/observe (ptk/watch (dd/initialize team-id) state stream)
                                   :on-next #(swap! processed conj (ptk/type %))))))
    (t/is (= 1 (count (filter #{::dcm/handle-organization-change-sso} @processed))))))

;; Scenario: the user duplicates a file whose workspace still has a change
;; waiting to be saved. Proves: the copy is requested once the save is done,
;; and only then is the user told, with a warning that the copy misses what
;; could not be saved, never replaced by the success toast.
(t/deftest ^:async duplicating-a-file-waits-for-its-pending-changes
  (let [file-id   (uuid/next)
        commit-id (uuid/next)
        calls     (atom [])
        answer    (rx/subject)
        store     (ptk/store {:state {:files {}
                                      :persistence {:queue (conj #queue [] commit-id)
                                                    :index {commit-id {:id commit-id :file-id file-id}}}}})]
    (await
     (mock/with-mocks*
       {dps/force-persist-files-and-wait (mock/stub (fn [file-ids _]
                                                      (swap! calls conj [:wait file-ids])
                                                      (rx/empty)))
        rp/cmd!                          (mock/stub (fn [cmd _]
                                                      (swap! calls conj [cmd])
                                                      (->> answer (rx/take 1) (rx/observe-on :async))))
        i18n/tr                          (fn ([key] (str "translated:" key)) ([key & _] (str "translated:" key)))}
       (ptk/emit! store (dd/duplicate-files [{:id file-id :name "File"}]))
       (await (async/wait-for #(= 2 (count @calls)) "the copy is requested"))
       (t/is (= [[:wait #{file-id}] [:duplicate-file]] @calls))
       (t/is (nil? (:notification @store)) "nothing is told before the copy exists")
       (rx/push! answer {:id (uuid/next)})
       (await (async/wait-for #(some? (:notification @store)) "the user is told"))
       (await (async/settle))
       (t/is (= "translated:dashboard.duplicate-unsaved-changes"
                (get-in @store [:notification :content])))
       (t/is (= :warning (get-in @store [:notification :level])))))))

;; Scenario: the user duplicates a file with everything saved. Proves: the
;; success toast shows once the copy exists, not when it is requested.
(t/deftest ^:async duplicating-a-saved-file-tells-once-the-copy-exists
  (let [file-id (uuid/next)
        answer  (rx/subject)
        sent    (atom 0)
        store   (ptk/store {:state {:files {}}})]
    (await
     (mock/with-mocks*
       {dps/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        rp/cmd!                          (mock/stub (fn [_ _]
                                                      (swap! sent inc)
                                                      (->> answer (rx/take 1) (rx/observe-on :async))))
        i18n/tr                          (fn ([key] (str "translated:" key)) ([key & _] (str "translated:" key)))}
       (ptk/emit! store (dd/duplicate-files [{:id file-id :name "File"}]))
       (await (async/wait-for #(= 1 @sent) "the copy is requested"))
       (t/is (nil? (:notification @store)))
       (rx/push! answer {:id (uuid/next)})
       (await (async/wait-for #(some? (:notification @store)) "the user is told"))
       (t/is (= "translated:dashboard.success-duplicate-file"
                (get-in @store [:notification :content])))
       (t/is (= :success (get-in @store [:notification :level])))))))

;; Scenario: the server fails to copy the file. Proves: no success toast
;; claims a copy that does not exist.
(t/deftest ^:async a-failed-copy-tells-no-success
  (let [errors (atom [])
        store  (ptk/store {:state {:files {}}
                           :on-error #(swap! errors conj %)})]
    (await
     (mock/with-mocks*
       {dps/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        rp/cmd!                          (mock/stub (fn [_ _]
                                                      (->> (rx/throw (ex-info "copy failed" {:type :internal}))
                                                           (rx/observe-on :async))))
        i18n/tr                          (fn ([key] (str "translated:" key)) ([key & _] (str "translated:" key)))}
       (ptk/emit! store (dd/duplicate-files [{:id (uuid/next) :name "File"}]))
       (await (async/wait-for #(seq @errors) "the copy fails"))
       (await (async/settle))
       (t/is (nil? (:notification @store)))))))
